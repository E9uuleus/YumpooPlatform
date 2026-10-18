import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { setTimeout as delay } from 'node:timers/promises'
import { directoryPiece, isolateProfile, registryPiece, spawnAndWaitForTree } from './desktop-profile.mjs'

function sandbox(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-profile-test-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const userData = path.join(root, 'Yumpoo Desktop')
  fs.mkdirSync(userData)
  fs.writeFileSync(path.join(userData, 'timer-preferences.json'), 'own')
  const backupRoot = path.join(root, 'backups')
  fs.mkdirSync(backupRoot)
  return { userData, backupRoot }
}

function fakeRegistry({ present = true, failOn } = {}) {
  const calls = []
  const step = name => {
    calls.push(name)
    if (name === failOn) throw new Error(`${name} failed`)
  }
  return {
    calls,
    exists: () => present,
    export: (_key, file) => { step('export'); fs.writeFileSync(file, 'saved') },
    remove: () => { step('remove'); present = false },
    import: () => { step('import'); present = true },
  }
}

test('restores userData moved before a later backup step fails', async t => {
  const { userData, backupRoot } = sandbox(t)
  const registry = fakeRegistry({ failOn: 'export' })
  let ran = false
  await assert.rejects(isolateProfile([directoryPiece(userData, 'userData'), registryPiece('HKCU\\x', 'protocol.reg', registry)],
    async () => { ran = true }, { backupRoot }), /export failed/u)
  assert.equal(ran, false)
  assert.equal(fs.readFileSync(path.join(userData, 'timer-preferences.json'), 'utf8'), 'own')
  assert.deepEqual(registry.calls, ['export'], 'a key that was never exported must not be deleted or imported')
  assert.deepEqual(fs.readdirSync(backupRoot), [])
})

test('clears what the run left and restores every piece after a failing run', async t => {
  const { userData, backupRoot } = sandbox(t)
  const registry = fakeRegistry()
  await assert.rejects(isolateProfile([directoryPiece(userData, 'userData'), registryPiece('HKCU\\x', 'protocol.reg', registry)], async () => {
    assert.equal(fs.existsSync(userData), false)
    fs.mkdirSync(path.join(userData, 'auth'), { recursive: true })
    throw new Error('installer failed')
  }, { backupRoot }), /installer failed/u)
  assert.deepEqual(fs.readdirSync(userData), ['timer-preferences.json'])
  assert.deepEqual(registry.calls, ['export', 'remove', 'import'])
  assert.deepEqual(fs.readdirSync(backupRoot), [])
})

test('keeps the backup and fails when a piece cannot be restored', async t => {
  const { userData, backupRoot } = sandbox(t)
  const registry = fakeRegistry({ failOn: 'import' })
  const logged = []
  t.mock.method(console, 'error', message => logged.push(message))
  await assert.rejects(isolateProfile([directoryPiece(userData, 'userData'), registryPiece('HKCU\\x', 'protocol.reg', registry)],
    async () => 'passed', { backupRoot }), error => {
    assert(error instanceof AggregateError)
    assert.match(error.message, /备份保留在/u)
    return true
  })
  const [backup] = fs.readdirSync(backupRoot)
  assert(backup, 'the backup directory must survive a failed restore')
  assert.equal(fs.readFileSync(path.join(backupRoot, backup, 'protocol.reg'), 'utf8'), 'saved')
  assert.equal(fs.readFileSync(path.join(userData, 'timer-preferences.json'), 'utf8'), 'own')
  assert.deepEqual(logged, [])
})

test('leaves a directory it never moved untouched and removes run leftovers when none existed', async t => {
  const { userData, backupRoot } = sandbox(t)
  const missing = path.join(path.dirname(userData), 'updater')
  const failing = () => ({ isolate() { throw new Error('locked') }, restore() {} })
  await assert.rejects(isolateProfile([failing, directoryPiece(userData, 'userData')], async () => {}, { backupRoot }), /locked/u)
  assert.deepEqual(fs.readdirSync(userData), ['timer-preferences.json'])

  await isolateProfile([directoryPiece(missing, 'updater')], async () => fs.mkdirSync(missing), { backupRoot })
  assert.equal(fs.existsSync(missing), false)
})

test('runs cleanup before restoring and returns the run result', async t => {
  const { userData, backupRoot } = sandbox(t)
  const order = []
  const result = await isolateProfile([directoryPiece(userData, 'userData')], async () => 'ok', {
    backupRoot,
    async cleanUp() { order.push(fs.existsSync(path.join(userData, 'timer-preferences.json')) ? 'restored' : 'isolated') },
  })
  assert.equal(result, 'ok')
  assert.deepEqual(order, ['isolated'])
  assert.equal(fs.readFileSync(path.join(userData, 'timer-preferences.json'), 'utf8'), 'own')
})

test('keeps everything in the backup when cleanup fails, so a late uninstaller cannot delete restored data', async t => {
  const { userData, backupRoot } = sandbox(t)
  const registry = fakeRegistry()
  const logged = []
  t.mock.method(console, 'error', message => logged.push(message))
  await assert.rejects(isolateProfile([directoryPiece(userData, 'userData'), registryPiece('HKCU\\x', 'protocol.reg', registry)], async () => {
    fs.mkdirSync(path.join(userData, 'auth'), { recursive: true })
  }, { backupRoot, async cleanUp() { throw new Error('静默卸载未在 120 秒内完成') } }), error => {
    assert.match(error.message, /未恢复本机配置/u)
    assert.match(error.message, /120 秒/u)
    return true
  })
  const [backup] = fs.readdirSync(backupRoot)
  assert(backup, 'the backup directory must survive a failed cleanup')
  assert.deepEqual(logged, [], 'a passing run has no failure to log')
  assert.deepEqual(registry.calls, ['export', 'remove'], 'the protocol key must not be re-imported')
  // A still-running uninstaller deletes what it finds in userData; the person's own copy is not there.
  fs.rmSync(userData, { recursive: true, force: true })
  assert.equal(fs.readFileSync(path.join(backupRoot, backup, 'userData', 'timer-preferences.json'), 'utf8'), 'own')
  assert.equal(fs.readFileSync(path.join(backupRoot, backup, 'protocol.reg'), 'utf8'), 'saved')
})

function processFixture(t, source) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "yumpoo-process-test-'中文-"))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const script = path.join(root, 'process fixture.cjs')
  fs.writeFileSync(script, source)
  return { root, argumentLine: `"${script}"` }
}

const windowsOnly = { skip: process.platform !== 'win32' && '需要 Windows PowerShell 的进程树等待语义' }

test('waits for detached descendants after both parents exit, without waiting on unrelated processes', windowsOnly, async t => {
  const { root, argumentLine } = processFixture(t, `
    const { spawn } = require('node:child_process')
    const fs = require('node:fs')
    const path = require('node:path')
    const generation = Number(process.argv[2] || 0)
    if (generation < 2) {
      const child = spawn(process.execPath, [__filename, String(generation + 1)], { detached: true, windowsHide: true, stdio: 'ignore' })
      child.unref()
    } else {
      setTimeout(() => fs.writeFileSync(path.join(__dirname, 'finished'), 'grandchild'), 800)
    }
  `)
  const unrelated = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { windowsHide: true, stdio: 'ignore' })
  t.after(async () => {
    if (unrelated.exitCode === null) {
      const exited = once(unrelated, 'exit')
      unrelated.kill()
      await exited
    }
  })
  await once(unrelated, 'spawn')
  assert.equal(await spawnAndWaitForTree(process.execPath, argumentLine, 15_000), 0)
  assert.equal(fs.readFileSync(path.join(root, 'finished'), 'utf8'), 'grandchild')
  assert.equal(unrelated.exitCode, null, 'only descendants belong to the wait')
})

test('preserves native arguments and a failing process exit code', windowsOnly, async t => {
  const { root, argumentLine } = processFixture(t, `
    require('node:fs').writeFileSync(require('node:path').join(__dirname, 'arguments.json'), JSON.stringify(process.argv.slice(2)))
    process.exit(23)
  `)
  const args = ['space value', "O'Brien", 'literal $value & | ;']
  const code = await spawnAndWaitForTree(process.execPath, `${argumentLine} ${args.map(value => `"${value}"`).join(' ')}`, 15_000)
  assert.equal(code, 23)
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(root, 'arguments.json'), 'utf8')), args)
})

test('rejects when the process-tree wait exceeds its deadline', windowsOnly, async () => {
  try {
    await assert.rejects(spawnAndWaitForTree(process.execPath, '-e "setTimeout(() => {}, 3000)"', 1000), /1000ms/u)
  } finally {
    // A timed-out launcher may leave its child running; this harmless fixture exits on its own.
    await delay(3500)
  }
})
