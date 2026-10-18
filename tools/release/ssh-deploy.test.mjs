import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { spawn } from 'node:child_process'
import {
  deploy, waitForDeployment, makeRequest, makeRollbackRequest, submitRollbackRequest, commitUploadBatch, sftpQuote, deploymentRoot, deploymentUser, triggerCommand,
  packagePartBytes, maxPackageParts, packagePartCount, packagePartName, splitPackage, uploadPackageParts,
  makeDesktopRequest, desktopPartName, uploadDesktopFiles,
  runSftpProcess, createSshTransport, sshChildEnvironment, sftpConnectionTimeoutMs, packageUploadTimeoutMs,
} from './ssh-deploy.mjs'
import { packageName, validateStatus } from './policy.mjs'

const requestId = '7169f9c0-745b-442f-a927-55271a906f3a'
const provenance = { tag: 'server-v1.0.0', sourceCommit: 'a'.repeat(40), packageSha256: 'b'.repeat(64), packageBytes: 1 }
const request = makeRequest(provenance, requestId)
// The server status keeps its own schema version, independent of the request schema.
const status = (state, phase = 'verify') => ({
  schemaVersion: 1, requestId: request.requestId, tag: request.tag, sourceCommit: request.sourceCommit, state, phase, updatedAt: '2026-10-15T00:00:00Z',
})

test('deploy requests use schema 2 deploy mode; rollback requests carry no package fields', () => {
  assert.deepEqual({ schemaVersion: request.schemaVersion, mode: request.mode }, { schemaVersion: 2, mode: 'deploy' })
  assert.deepEqual(makeRollbackRequest(provenance, requestId), { schemaVersion: 2, mode: 'rollback', requestId, tag: provenance.tag, sourceCommit: provenance.sourceCommit })
  assert.throws(() => makeRollbackRequest(provenance, 'not-a-uuid'))
})

test('rollback submission uploads only provenance and an atomically committed request', async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-rollback-test-'))
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }))
  fs.writeFileSync(path.join(directory, 'provenance.json'), JSON.stringify(provenance))
  const batches = []
  const rollbackRequest = makeRollbackRequest(provenance, requestId)
  await submitRollbackRequest({ directory, request: rollbackRequest, sftp: async batch => { batches.push(batch) } })
  assert.equal(batches.length, 1)
  const lines = batches[0].trimEnd().split('\n')
  assert(lines[0].startsWith('mkdir ') && lines[0].endsWith(`/inbox/${requestId}"`))
  assert.deepEqual(lines.slice(1), commitUploadBatch(directory, rollbackRequest).trimEnd().split('\n'))
  assert(!batches[0].includes('.part') && !batches[0].includes(packageName))
  assert.equal(JSON.parse(fs.readFileSync(path.join(directory, 'request.json'), 'utf8')).mode, 'rollback')
  await assert.rejects(submitRollbackRequest({ directory, request, sftp: async () => {} }), /Only rollback/u)
})

function clock() {
  let elapsed = 0
  return { now: () => elapsed, delay: async milliseconds => { elapsed += milliseconds }, log: () => {}, timeoutMs: 120_000, pollMs: 10_000 }
}

test('metadata commit only submits fixed files and publishes the request atomically last', () => {
  const batch = commitUploadBatch('/tmp/source with spaces', request)
  const lines = batch.trimEnd().split('\n')
  assert.equal(lines.length, 3)
  assert(lines[0].endsWith(`/${requestId}/provenance.json"`))
  assert(lines[1].endsWith(`/${requestId}/request.json.uploading"`))
  assert(lines[2].startsWith('rename '))
  assert(lines[2].endsWith(`/${requestId}/request.json"`))
  assert.equal(deploymentUser, 'yumpoo-deploy')
  assert.equal(triggerCommand, 'schtasks.exe /Run /TN YumpooDeploy')
  assert(!batch.includes('.ps1'))
  assert.throws(() => makeRequest(provenance, '../../other-directory'))
  assert.throws(() => sftpQuote('/tmp/file\n!local-command'))
  assert.throws(() => sftpQuote('/tmp/file\0'))
  assert.equal(sftpQuote('/tmp/a"b'), '"/tmp/a\\"b"')
})

function uploadFixture(t, bytes) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-parts-test-'))
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }))
  const data = Buffer.alloc(bytes).fill(Buffer.from([0, 1, 37, 128, 255, 96, 22]))
  const receipt = { ...provenance, packageBytes: bytes, packageSha256: createHash('sha256').update(data).digest('hex') }
  fs.writeFileSync(path.join(directory, packageName), data)
  fs.writeFileSync(path.join(directory, 'provenance.json'), JSON.stringify(receipt))
  return { directory, data, provenance: receipt, request: makeRequest(receipt, requestId) }
}

function partUpload(batch) {
  const match = /^put "((?:\\.|[^"])*)" "([^"\n]*\/package-(\d{4})\.part)"\n$/u.exec(batch)
  if (!match) return null
  return { local: match[1].replace(/\\(.)/gu, '$1'), remote: match[2], index: Number(match[3]) }
}

test('package limits and names enforce two-MiB pieces and at most 512 parts', () => {
  assert.equal(packagePartCount(1), 1)
  assert.equal(packagePartCount(packagePartBytes), 1)
  assert.equal(packagePartCount(packagePartBytes + 1), 2)
  assert.equal(packagePartCount(1024 ** 3), maxPackageParts)
  for (const bytes of [0, -1, 0.5, Infinity, 1024 ** 3 + 1]) assert.throws(() => packagePartCount(bytes))
  assert.equal(packagePartName(0), 'package-0000.part')
  assert.equal(packagePartName(511), 'package-0511.part')
  for (const index of [-1, 0.5, 512]) assert.throws(() => packagePartName(index))
  assert.equal(sftpConnectionTimeoutMs, 300_000)
  assert.equal(packageUploadTimeoutMs, 2_400_000)
})

test('parallel upload reassembles identical bytes, never exceeds four connections, and commits last', async t => {
  const fixture = uploadFixture(t, 5 * packagePartBytes + 17)
  const uploaded = new Map()
  const calls = []
  const logs = []
  let active = 0
  let maximum = 0
  let committed = false
  await uploadPackageParts({
    ...fixture, log: message => logs.push(message),
    async sftp(batch, options) {
      calls.push(batch)
      assert(options.timeoutMs > 0 && options.timeoutMs <= 300_000)
      const part = partUpload(batch)
      if (part) {
        active += 1
        maximum = Math.max(maximum, active)
        assert.equal(part.remote, `${deploymentRoot}/inbox/${requestId}/${packagePartName(part.index)}`)
        const bytes = fs.readFileSync(part.local)
        await new Promise(resolve => setTimeout(resolve, 10 + (3 - part.index % 4) * 5))
        uploaded.set(part.index, bytes)
        active -= 1
      } else if (batch.includes('provenance.json')) {
        assert.equal(uploaded.size, 6)
        assert.equal(active, 0)
        assert.equal(batch, commitUploadBatch(fixture.directory, fixture.request))
        assert.equal(JSON.parse(fs.readFileSync(path.join(fixture.directory, 'request.json'))).packagePartCount, 6)
        committed = true
      } else assert.equal(batch, `mkdir "${deploymentRoot}/inbox/${requestId}"\n`)
    },
  })
  assert.equal(maximum, 4)
  assert.equal(committed, true)
  const pieces = [...uploaded].sort(([left], [right]) => left - right).map(([, value]) => value)
  assert(pieces.slice(0, -1).every(piece => piece.length === packagePartBytes))
  assert.equal(pieces.at(-1).length, 17)
  assert.deepEqual(Buffer.concat(pieces), fixture.data)
  assert.equal(createHash('sha256').update(Buffer.concat(pieces)).digest('hex'), fixture.provenance.packageSha256)
  assert.equal(logs.length, 6)
  assert.equal(logs.at(-1), `Uploaded 6/6 package parts (${fixture.data.length}/${fixture.data.length} bytes)`)
  assert(calls.at(-1).endsWith(`/${requestId}/request.json"\n`))
  assert(!fs.readdirSync(fixture.directory).some(name => name.startsWith('package-parts-')))
})

test('an exact part boundary has no empty final part', async t => {
  const fixture = uploadFixture(t, packagePartBytes)
  const directory = fs.mkdtempSync(path.join(fixture.directory, 'split-'))
  const result = await splitPackage(path.join(fixture.directory, packageName), directory)
  assert.equal(result.parts.length, 1)
  assert.equal(result.parts[0].bytes, packagePartBytes)
  assert.deepEqual(fs.readFileSync(result.parts[0].file), fixture.data)
})

test('one failed part cancels active transfers, waits for their exit, and never submits metadata', async t => {
  const fixture = uploadFixture(t, 6 * packagePartBytes + 1)
  const launched = []
  let active = 0
  let closed = 0
  let metadata = false
  await assert.rejects(uploadPackageParts({
    ...fixture, log: () => {},
    async sftp(batch, { signal }) {
      const part = partUpload(batch)
      if (!part) { if (batch.includes('provenance.json')) metadata = true; return }
      launched.push(part.index)
      active += 1
      try {
        if (part.index === 1) {
          await new Promise(resolve => setImmediate(resolve))
          throw new Error('Simulated part failure')
        }
        await new Promise((_resolve, reject) => {
          signal.addEventListener('abort', () => setTimeout(() => reject(new Error('Transfer cancelled after child exit')), 10), { once: true })
        })
      } finally { active -= 1; closed += 1 }
    },
  }), /Simulated part failure/u)
  assert.deepEqual(launched, [0, 1, 2, 3])
  assert.equal(active, 0)
  assert.equal(closed, 4)
  assert.equal(metadata, false)
  assert.equal(fs.existsSync(path.join(fixture.directory, 'request.json')), false)
})

test('the overall upload deadline cancels outstanding connections without publishing a request', async t => {
  const fixture = uploadFixture(t, 10)
  let aborted = false
  const batches = []
  await assert.rejects(uploadPackageParts({
    ...fixture, timeoutMs: 100, log: () => {},
    async sftp(batch, { signal, timeoutMs }) {
      batches.push(batch)
      assert(timeoutMs <= 100)
      if (!partUpload(batch)) return
      await new Promise((_resolve, reject) => {
        signal.addEventListener('abort', () => { aborted = true; reject(new Error('Cancelled')) }, { once: true })
      })
    },
  }), /overall time limit/u)
  assert.equal(aborted, true)
  assert(!batches.some(batch => batch.includes('provenance.json') || batch.includes('request.json')))
})

test('desktop requests list each feed file with its digest and part count; parts are named per file', () => {
  const files = [{ name: 'YumpooDesktop-Setup-1.1.0-x64.exe', bytes: 2 * packagePartBytes + 1, sha256: 'c'.repeat(64) }, { name: 'latest.yml', bytes: 10, sha256: 'd'.repeat(64) }]
  const desktop = makeDesktopRequest({ tag: 'desktop-v1.1.0', sourceCommit: 'a'.repeat(40) }, files, requestId)
  assert.deepEqual(desktop, {
    schemaVersion: 2, mode: 'desktop-publish', requestId, tag: 'desktop-v1.1.0', sourceCommit: 'a'.repeat(40),
    files: [{ ...files[0], partCount: 3 }, { ...files[1], partCount: 1 }],
  })
  assert.equal(desktopPartName(0, 0), 'desktop-0-0000.part')
  assert.equal(desktopPartName(7, 511), 'desktop-7-0511.part')
  for (const [file, index] of [[8, 0], [-1, 0], [0, 512]]) assert.throws(() => desktopPartName(file, index))
  for (const name of ['../latest.yml', 'a b.exe', '']) {
    assert.throws(() => makeDesktopRequest({ tag: 'desktop-v1.1.0', sourceCommit: 'a'.repeat(40) }, [{ ...files[1], name }], requestId))
  }
})

test('desktop upload sends every file in distinct parts that reassemble exactly, then commits the request last', async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-desktop-parts-test-'))
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }))
  const contents = { 'YumpooDesktop-Setup-1.1.0-x64.exe': Buffer.alloc(packagePartBytes + 5, 7), 'latest.yml': Buffer.from('version: 1.1.0\n') }
  for (const [name, bytes] of Object.entries(contents)) fs.writeFileSync(path.join(directory, name), bytes)
  fs.writeFileSync(path.join(directory, 'provenance.json'), '{}')
  const desktop = makeDesktopRequest({ tag: 'desktop-v1.1.0', sourceCommit: 'a'.repeat(40) },
    Object.entries(contents).map(([name, bytes]) => ({ name, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') })), requestId)
  const received = new Map()
  let committed = false
  await uploadDesktopFiles({
    directory, request: desktop, log: () => {},
    async sftp(batch) {
      const part = /^put "((?:\\.|[^"\\])*)" "[^"\n]*\/(desktop-(\d)-(\d{4})\.part)"\n$/u.exec(batch)
      if (part) {
        assert(!committed)
        received.set(part[2], fs.readFileSync(part[1].replace(/\\(.)/gu, '$1')))
      } else if (batch.includes('provenance.json')) {
        assert.equal(batch, commitUploadBatch(directory, desktop))
        committed = true
      }
    },
  })
  assert(committed)
  assert.deepEqual([...received.keys()].sort(), ['desktop-0-0000.part', 'desktop-0-0001.part', 'desktop-1-0000.part'])
  assert.deepEqual(Buffer.concat([received.get('desktop-0-0000.part'), received.get('desktop-0-0001.part')]), contents['YumpooDesktop-Setup-1.1.0-x64.exe'])
  assert.deepEqual(received.get('desktop-1-0000.part'), contents['latest.yml'])
  await assert.rejects(uploadDesktopFiles({ directory, request, sftp: async () => {} }), /Only desktop requests/u)
  let connected = false
  await assert.rejects(uploadDesktopFiles({
    directory, request: { ...desktop, requestId: 'f'.repeat(8) + '-745b-442f-a927-55271a906f3a', files: [{ ...desktop.files[0], partCount: 1 }] },
    async sftp() { connected = true },
  }), /part count/u)
  assert.equal(connected, false)
})

test('a mismatched packagePartCount fails before opening any SFTP connection', async t => {
  const fixture = uploadFixture(t, 10)
  let connected = false
  await assert.rejects(uploadPackageParts({
    ...fixture, request: { ...fixture.request, packagePartCount: 2 },
    async sftp() { connected = true },
  }), /part count/u)
  assert.equal(connected, false)
})

test('asynchronous SFTP closes stdin and excludes credentials from child environment and errors', async () => {
  const env = { ...process.env, GH_TOKEN: 'test-github-secret', WINDOWS_DEPLOY_SSH_KEY: 'test-private-key', GITHUB_TOKEN: 'other-token', ACTIONS_RUNTIME_TOKEN: 'runtime-token' }
  const cleaned = sshChildEnvironment(env)
  for (const name of ['GH_TOKEN', 'WINDOWS_DEPLOY_SSH_KEY', 'GITHUB_TOKEN', 'ACTIONS_RUNTIME_TOKEN']) assert.equal(cleaned[name], undefined)
  const execute = source => (_command, _args, options) => {
    assert.equal(options.shell, false)
    assert.equal(options.windowsHide, true)
    assert.equal(options.detached, process.platform !== 'win32')
    assert.deepEqual(options.env, cleaned)
    return spawn(process.execPath, ['-e', source], options)
  }
  await runSftpProcess([], { input: 'complete batch\n', env, timeoutMs: 3_000 }, execute("let data=''; process.stdin.setEncoding('utf8'); process.stdin.on('data', s=>data+=s); process.stdin.on('end',()=>{if(data!=='complete batch\\n'||process.env.GH_TOKEN||process.env.WINDOWS_DEPLOY_SSH_KEY)process.exit(9)});"))
  await assert.rejects(runSftpProcess([], { input: '', env, timeoutMs: 3_000 }, execute("console.error('test-github-secret test-private-key'); process.exit(4)")), error => {
    assert.match(error.message, /SFTP connection failed/u)
    assert(!error.message.includes('test-github-secret') && !error.message.includes('test-private-key'))
    return true
  })
})

test('connection timeout and cancellation await the actual child close event', async () => {
  for (const mode of ['timeout', 'abort']) {
    const controller = new AbortController()
    let closed = false
    const pending = runSftpProcess([], {
      input: '', env: process.env, timeoutMs: mode === 'timeout' ? 100 : 3_000, signal: controller.signal,
    }, (_command, _args, options) => {
      const child = spawn(process.execPath, ['-e', 'setInterval(()=>{},1000)'], options)
      child.once('close', () => { closed = true })
      return child
    })
    const timer = mode === 'abort' ? setTimeout(() => controller.abort(), 100) : null
    await assert.rejects(pending, mode === 'timeout' ? /timed out/u : /cancelled/u)
    clearTimeout(timer)
    assert.equal(closed, true)
  }
})

test('transport retains fixed host verification and receives only safe child environment', async t => {
  const fixture = uploadFixture(t, 10)
  const env = {
    ...process.env, GH_TOKEN: 'test-github-secret', WINDOWS_DEPLOY_HOST: 'deploy.example.com',
    WINDOWS_DEPLOY_SSH_KEY: '-----BEGIN PRIVATE KEY-----\ntest-private-key\n-----END PRIVATE KEY-----',
    WINDOWS_DEPLOY_KNOWN_HOSTS: 'deploy.example.com ssh-ed25519 test-host-key',
  }
  let calls = 0
  const transport = createSshTransport(env, fixture.directory, {
    log: () => {},
    async executeSftp(args, options) {
      calls += 1
      assert(args.includes('StrictHostKeyChecking=yes'))
      assert(args.includes('IdentitiesOnly=yes'))
      assert.equal(args.at(-1), 'yumpoo-deploy@deploy.example.com')
      assert(!JSON.stringify(args).includes('test-private-key'))
      assert.equal(options.env.GH_TOKEN, undefined)
      assert.equal(options.env.WINDOWS_DEPLOY_SSH_KEY, undefined)
      assert(options.timeoutMs > 0 && options.timeoutMs <= 300_000)
    },
  })
  await transport.upload(fixture.directory, fixture.request)
  assert.equal(calls, 3)
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
