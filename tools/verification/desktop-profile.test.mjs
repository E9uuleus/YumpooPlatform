import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { directoryPiece, isolateProfile, registryPiece, waitForProcessTree } from './desktop-profile.mjs'

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

test('waits for the whole uninstaller process tree, including children of an exited parent', async () => {
  const snapshots = [
    [{ pid: 10, parentPid: 1 }, { pid: 11, parentPid: 10 }, { pid: 99, parentPid: 1 }],
    [{ pid: 11, parentPid: 10 }, { pid: 12, parentPid: 11 }, { pid: 99, parentPid: 1 }],
    [{ pid: 12, parentPid: 11 }, { pid: 99, parentPid: 1 }],
    [{ pid: 99, parentPid: 1 }, { pid: 10, parentPid: 1 }],
  ]
  let calls = 0
  await waitForProcessTree(10, { timeoutMs: 10_000, intervalMs: 0, list: () => snapshots[Math.min(calls++, snapshots.length - 1)] })
  assert.equal(calls, 4, 'a reused root PID must not be waited on')
})

test('fails when the uninstaller process tree outlives the timeout', async () => {
  await assert.rejects(waitForProcessTree(10, { timeoutMs: 0, intervalMs: 0, list: () => [{ pid: 11, parentPid: 10 }] }), /11/u)
})
