import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { exactBreakingChangeException, readBreakingChangeExceptions } from './breaking-change-policy.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')

test('historical exceptions authorize only the original exact pair', () => {
  const exceptions = readBreakingChangeExceptions(root)
  for (const entry of exceptions) {
    assert.equal(exactBreakingChangeException(exceptions, entry.oldSha256, entry.newSha256), entry)
    const evolved = createHash('sha256').update(`${entry.newSha256}:optional-field`).digest('hex')
    assert.equal(exactBreakingChangeException(exceptions, entry.oldSha256, evolved), undefined)
    assert.equal(exactBreakingChangeException(exceptions, evolved, entry.newSha256), undefined)
  }
})

test('malformed exceptions, duplicate grants and missing decisions fail closed', context => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-openapi-policy-'))
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }))
  const entry = readBreakingChangeExceptions(root)[0]
  fs.mkdirSync(path.join(directory, 'tools/openapi'), { recursive: true })
  fs.mkdirSync(path.dirname(path.join(directory, entry.agentNote)), { recursive: true })
  fs.writeFileSync(path.join(directory, entry.agentNote), '# Decision\n\nStatus: implemented\n')
  const check = exceptions => {
    fs.writeFileSync(path.join(directory, 'tools/openapi/breaking-change-exceptions.json'), JSON.stringify({ schemaVersion: 1, exceptions }))
    return () => readBreakingChangeExceptions(directory)
  }
  assert.doesNotThrow(check([entry]))
  for (const invalid of [{ ...entry, reason: '' }, { ...entry, oldSha256: '*' },
    { ...entry, agentNote: '../../external.md' }, { ...entry, newSha256: entry.oldSha256 }]) {
    assert.throws(check([invalid]))
  }
  assert.throws(check([entry, { ...entry, id: 'same-pair' }]))
  fs.unlinkSync(path.join(directory, entry.agentNote))
  assert.throws(check([entry]))
})
