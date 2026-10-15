import assert from 'node:assert/strict'
import test from 'node:test'
import { deploy, waitForDeployment, makeRequest, uploadBatch, sftpQuote, deploymentRoot, deploymentUser, triggerCommand } from './ssh-deploy.mjs'
import { validateStatus } from './policy.mjs'

const requestId = '7169f9c0-745b-442f-a927-55271a906f3a'
const provenance = { tag: 'server-v1.0.0', sourceCommit: 'a'.repeat(40), packageSha256: 'b'.repeat(64) }
const request = makeRequest(provenance, requestId)
const status = (state, phase = 'verify') => ({ ...request, state, phase, updatedAt: '2026-10-15T00:00:00Z' })

function clock() {
  let elapsed = 0
  return { now: () => elapsed, delay: async milliseconds => { elapsed += milliseconds }, log: () => {}, timeoutMs: 120_000, pollMs: 10_000 }
}

test('upload only submits fixed files and publishes the request atomically last', () => {
  const batch = uploadBatch('/tmp/source with spaces', request)
  const lines = batch.trimEnd().split('\n')
  assert.equal(lines.length, 5)
  assert.match(lines[0], new RegExp(`^mkdir "${deploymentRoot}/inbox/${requestId}"$`, 'u'))
  assert(lines[1].endsWith(`/${requestId}/package.zip"`))
  assert(lines[2].endsWith(`/${requestId}/provenance.json"`))
  assert(lines[3].endsWith(`/${requestId}/request.json.uploading"`))
  assert(lines[4].startsWith('rename '))
  assert(lines[4].endsWith(`/${requestId}/request.json"`))
  assert.equal(deploymentUser, 'yumpoo-deploy')
  assert.equal(triggerCommand, 'schtasks.exe /Run /TN YumpooDeploy')
  assert(!batch.includes('.ps1'))
  assert.throws(() => makeRequest(provenance, '../../other-directory'))
  assert.throws(() => sftpQuote('/tmp/file\n!local-command'))
  assert.throws(() => sftpQuote('/tmp/file\0'))
  assert.equal(sftpQuote('/tmp/a"b'), '"/tmp/a\\"b"')
})

test('SSH trigger disconnect reconnects to the same independently running request', async () => {
  const states = [null, status('running', 'backup'), new Error('SSH transport disconnected'), null, status('succeeded', 'healthy')]
  let triggers = 0
  let uploads = 0
  const transport = {
    async upload(_directory, input) { uploads += 1; assert.deepEqual(input, request) },
    async trigger() { triggers += 1; throw new Error('Connection closed after task launch') },
    async readStatus(input) {
      assert.equal(input.requestId, requestId)
      const next = states.shift()
      if (next instanceof Error) throw next
      return next
    },
  }
  const result = await deploy({ transport, directory: '/tmp/source', provenance, requestId, ...clock() })
  assert.equal(result.state, 'succeeded')
  assert.equal(uploads, 1, 'Polling must not resubmit or mutate the package')
  assert.equal(triggers, 1, 'An observed task is never restarted after disconnect')
})

test('missing trigger acknowledgment retries only the fixed task trigger until the status appears', async () => {
  let polls = 0
  let triggers = 0
  const result = await waitForDeployment({
    request, ...clock(),
    transport: {
      async trigger() { triggers += 1; if (triggers === 1) throw new Error('No connection') },
      async readStatus() { polls += 1; return polls >= 6 ? status('succeeded', 'already_current') : null },
    },
  })
  assert.equal(result.phase, 'already_current')
  assert.equal(triggers, 2)
})

test('a previously completed request is read without triggering another deployment', async () => {
  let triggers = 0
  const result = await waitForDeployment({
    request, ...clock(), transport: {
      async trigger() { triggers += 1 },
      async readStatus() { return status('succeeded', 'already_current') },
    },
  })
  assert.equal(result.state, 'succeeded')
  assert.equal(triggers, 0)
})

test('rollback, failure and recovery-required are not reported as successful deployment', async () => {
  for (const state of ['failed', 'rolled_back', 'recovery_required']) {
    await assert.rejects(waitForDeployment({
      request, ...clock(), transport: { async readStatus() { return status(state, 'rollback') } },
    }), new RegExp(`ended with ${state}`, 'u'))
  }
})

test('status substitution, malformed JSON and untrusted phase text fail closed', async () => {
  for (const changes of [
    { requestId: 'another-request' }, { tag: 'server-v2' }, { sourceCommit: 'c'.repeat(40) },
    { state: 'cancelled' }, { schemaVersion: 2 }, { phase: '\n::error::unsafe' }, { updatedAt: 'unknown' },
  ]) assert.throws(() => validateStatus({ ...status('running'), ...changes }, request))
  await assert.rejects(waitForDeployment({
    request, ...clock(), transport: { async readStatus() { throw new SyntaxError('Partial JSON') } },
  }), /invalid deployment status JSON/u)
})

test('client timeout does not cancel or kill the detached server task', async () => {
  let triggers = 0
  await assert.rejects(waitForDeployment({
    request, ...clock(), timeoutMs: 25_000,
    transport: { async trigger() { triggers += 1 }, async readStatus() { return status('running', 'migrate') } },
  }), /server task continues independently/u)
  assert.equal(triggers, 0)
})
