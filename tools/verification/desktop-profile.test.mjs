import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { directoryPiece, isolateProfile, registryPiece } from './desktop-profile.mjs'

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
