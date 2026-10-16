import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import { spawn } from 'node:child_process'
import { setTimeout as sleep } from 'node:timers/promises'
import { pathToFileURL } from 'node:url'
import { createGithub, run } from './github.mjs'
import { prepareRelease } from './promote-release.mjs'
import { packageName, releaseWorkflowPath, rollbackWorkflowPath, validateStatus } from './policy.mjs'

export const deploymentRoot = '/C:/ProgramData/Yumpoo/deployment'
export const deploymentUser = 'yumpoo-deploy'
export const triggerCommand = 'schtasks.exe /Run /TN YumpooDeploy'
export const packagePartBytes = 2 * 1024 * 1024
export const maxPackageParts = 512
export const uploadConcurrency = 4
export const sftpConnectionTimeoutMs = 5 * 60_000
export const packageUploadTimeoutMs = 40 * 60_000

export function packagePartCount(bytes) {
  assert(Number.isSafeInteger(bytes) && bytes > 0 && bytes <= packagePartBytes * maxPackageParts, 'Package must contain 1 byte to 1 GiB')
  return Math.ceil(bytes / packagePartBytes)
}

function validateRequestId(requestId) {
  assert(/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u.test(requestId), 'Invalid deployment request ID')
}

export function packagePartName(index) {
  assert(Number.isInteger(index) && index >= 0 && index < maxPackageParts, 'Invalid package part index')
  return `package-${String(index).padStart(4, '0')}.part`
}

export function makeRequest(provenance, requestId = randomUUID()) {
  validateRequestId(requestId)
  return {
    schemaVersion: 2, mode: 'deploy', requestId, tag: provenance.tag, sourceCommit: provenance.sourceCommit, packageSha256: provenance.packageSha256,
    packagePartCount: packagePartCount(provenance.packageBytes),
  }
}

// Code-only rollback carries no package: the server switches back to the retained previous release directory.
export function makeRollbackRequest(provenance, requestId = randomUUID()) {
  validateRequestId(requestId)
  return { schemaVersion: 2, mode: 'rollback', requestId, tag: provenance.tag, sourceCommit: provenance.sourceCommit }
}

export function sftpQuote(value) {
  assert(typeof value === 'string' && !/[\r\n\0]/u.test(value), 'Invalid SFTP path')
  return `"${value.replaceAll('\\', '\\\\').replaceAll('"', '\\"')}"`
}

export function commitUploadBatch(directory, request) {
  validateRequestId(request.requestId)
  const inbox = `${deploymentRoot}/inbox/${request.requestId}`
  return [
    `put ${sftpQuote(path.join(directory, 'provenance.json'))} ${sftpQuote(`${inbox}/provenance.json`)}`,
    `put ${sftpQuote(path.join(directory, 'request.json'))} ${sftpQuote(`${inbox}/request.json.uploading`)}`,
    `rename ${sftpQuote(`${inbox}/request.json.uploading`)} ${sftpQuote(`${inbox}/request.json`)}`,
    '',
  ].join('\n')
}

export async function splitPackage(file, directory, signal) {
  assert((await fs.promises.lstat(file)).isFile(), 'Package must be a regular file')
  const source = await fs.promises.open(file, 'r')
  try {
    const bytes = (await source.stat()).size
    const count = packagePartCount(bytes)
    const parts = []
    const buffer = Buffer.allocUnsafe(packagePartBytes)
    for (let index = 0; index < count; index += 1) {
      signal?.throwIfAborted()
      const length = Math.min(packagePartBytes, bytes - index * packagePartBytes)
      let read = 0
      while (read < length) {
        const chunk = await source.read(buffer, read, length - read, index * packagePartBytes + read)
        assert(chunk.bytesRead > 0, 'Package changed while splitting')
        read += chunk.bytesRead
      }
      const name = packagePartName(index)
      const destination = path.join(directory, name)
      await fs.promises.writeFile(destination, buffer.subarray(0, length), { mode: 0o600, flag: 'wx', signal })
      parts.push({ name, file: destination, bytes: length })
    }
    assert.equal((await source.stat()).size, bytes, 'Package changed while splitting')
    return { parts, bytes }
  } finally {
    await source.close()
  }
}

export function sshChildEnvironment(env) {
  const names = new Set(['PATH', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'PATHEXT', 'TEMP', 'TMP', 'TMPDIR', 'HOME', 'USERPROFILE', 'LANG', 'LC_ALL', 'LC_CTYPE', 'TZ'])
  return Object.fromEntries(Object.entries(env).filter(([name]) => names.has(name.toUpperCase())))
}

export function runSftpProcess(args, { input, env, timeoutMs, signal }, spawnProcess = spawn) {
  assert(Number.isFinite(timeoutMs) && timeoutMs > 0 && timeoutMs <= sftpConnectionTimeoutMs, 'Invalid SFTP connection timeout')
  return new Promise((resolve, reject) => {
    if (signal?.aborted) { reject(new Error('SFTP upload cancelled')); return }
    let child
    try {
      child = spawnProcess('sftp', args, {
        env: sshChildEnvironment(env), shell: false, windowsHide: true,
        detached: process.platform !== 'win32', stdio: ['pipe', 'ignore', 'pipe'],
      })
    } catch {
      reject(new Error('Unable to start SFTP connection'))
      return
    }
    let failure
    let closed = false
    const stop = message => {
      if (closed) return
      failure ||= new Error(message)
      // SFTP starts an SSH child; the Linux runner must terminate both connections together.
      if (process.platform !== 'win32' && child.pid) {
        try { process.kill(-child.pid, 'SIGKILL'); return } catch { }
      }
      child.kill('SIGKILL')
    }
    const abort = () => stop('SFTP upload cancelled')
    const timer = setTimeout(() => stop('SFTP connection timed out'), timeoutMs)
    signal?.addEventListener('abort', abort, { once: true })
    child.stderr.resume()
    child.once('error', () => { failure ||= new Error('Unable to start SFTP connection') })
    child.stdin.on('error', () => stop('SFTP connection closed while sending commands'))
    child.once('close', code => {
      closed = true
      clearTimeout(timer)
      signal?.removeEventListener('abort', abort)
      if (failure) reject(failure)
      else if (code !== 0) reject(new Error(`SFTP connection failed (exit ${code ?? 'unknown'})`))
      else resolve()
    })
    if (signal?.aborted) abort()
    else child.stdin.end(input)
  })
}

export async function uploadPackageParts({ directory, request, sftp, log = console.log, timeoutMs = packageUploadTimeoutMs }) {
  validateRequestId(request.requestId)
  assert(Number.isInteger(request.packagePartCount) && request.packagePartCount >= 1 && request.packagePartCount <= maxPackageParts, 'Invalid packagePartCount')
  assert(Number.isFinite(timeoutMs) && timeoutMs > 0 && timeoutMs <= packageUploadTimeoutMs, 'Invalid overall upload timeout')
  const controller = new AbortController()
  const deadline = Date.now() + timeoutMs
  const timer = setTimeout(() => controller.abort(new Error('Package upload exceeded its overall time limit')), timeoutMs)
  let partsDirectory
  const connect = batch => {
    const remaining = deadline - Date.now()
    if (remaining <= 0 && !controller.signal.aborted) controller.abort(new Error('Package upload exceeded its overall time limit'))
    controller.signal.throwIfAborted()
    return sftp(batch, { signal: controller.signal, timeoutMs: Math.min(sftpConnectionTimeoutMs, remaining) })
  }
  try {
    partsDirectory = await fs.promises.mkdtemp(path.join(directory, 'package-parts-'))
    const { parts, bytes } = await splitPackage(path.join(directory, packageName), partsDirectory, controller.signal)
    assert.equal(parts.length, request.packagePartCount, 'Request part count does not match the package')
    const inbox = `${deploymentRoot}/inbox/${request.requestId}`
    await connect(`mkdir ${sftpQuote(inbox)}\n`)
    let next = 0
    let completed = 0
    let completedBytes = 0
    let failure
    const worker = async () => {
      while (!controller.signal.aborted) {
        const part = parts[next++]
        if (!part) return
        try {
          await connect(`put ${sftpQuote(part.file)} ${sftpQuote(`${inbox}/${part.name}`)}\n`)
          completed += 1
          completedBytes += part.bytes
          log(`Uploaded ${completed}/${parts.length} package parts (${completedBytes}/${bytes} bytes)`)
        } catch (error) {
          failure ||= controller.signal.aborted ? controller.signal.reason : error
          controller.abort(failure)
          return
        }
      }
    }
    await Promise.allSettled(Array.from({ length: Math.min(uploadConcurrency, parts.length) }, worker))
    if (failure) throw failure
    controller.signal.throwIfAborted()
    assert.equal(completed, parts.length, 'Package upload did not complete')
    await fs.promises.writeFile(path.join(directory, 'request.json'), `${JSON.stringify(request, null, 2)}\n`, { mode: 0o600, flag: 'wx', signal: controller.signal })
    await connect(commitUploadBatch(directory, request))
  } catch (error) {
    throw controller.signal.aborted ? controller.signal.reason : error
  } finally {
    clearTimeout(timer)
    if (partsDirectory) await fs.promises.rm(partsDirectory, { recursive: true, force: true })
  }
}

export async function submitRollbackRequest({ directory, request, sftp }) {
  validateRequestId(request.requestId)
  assert.equal(request.mode, 'rollback', 'Only rollback requests skip the package upload')
  await fs.promises.writeFile(path.join(directory, 'request.json'), `${JSON.stringify(request, null, 2)}\n`, { mode: 0o600, flag: 'wx' })
  await sftp(`mkdir ${sftpQuote(`${deploymentRoot}/inbox/${request.requestId}`)}\n${commitUploadBatch(directory, request)}`)
}

export function createSshTransport(env, directory, { executeSftp = runSftpProcess, log = console.log } = {}) {
  const host = env.WINDOWS_DEPLOY_HOST
  assert(typeof host === 'string' && /^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/u.test(host), 'WINDOWS_DEPLOY_HOST must be an IPv4 address or DNS name')
  const port = Number(env.WINDOWS_DEPLOY_PORT || '22')
  assert(Number.isSafeInteger(port) && port > 0 && port <= 65535, 'Invalid WINDOWS_DEPLOY_PORT')
  assert(env.WINDOWS_DEPLOY_SSH_KEY?.includes('PRIVATE KEY'), 'WINDOWS_DEPLOY_SSH_KEY is required')
  assert(env.WINDOWS_DEPLOY_KNOWN_HOSTS?.trim(), 'WINDOWS_DEPLOY_KNOWN_HOSTS is required; provision the verified host key out of band')
  const keyFile = path.join(directory, 'ssh-key')
  const hostsFile = path.join(directory, 'known_hosts')
  fs.writeFileSync(keyFile, `${env.WINDOWS_DEPLOY_SSH_KEY.trim()}\n`, { mode: 0o600, flag: 'wx' })
  fs.writeFileSync(hostsFile, `${env.WINDOWS_DEPLOY_KNOWN_HOSTS.trim()}\n`, { mode: 0o600, flag: 'wx' })
  const options = [
    '-F', '/dev/null', '-i', keyFile,
    '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes', '-o', 'IdentityAgent=none',
    '-o', 'StrictHostKeyChecking=yes', '-o', `UserKnownHostsFile=${hostsFile}`, '-o', 'GlobalKnownHostsFile=/dev/null',
    '-o', 'ConnectTimeout=15', '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=2',
  ]
  const target = `${deploymentUser}@${host}`
  const childEnv = sshChildEnvironment(env)
  const sftp = (batch, connection = {}) => executeSftp([...options, '-P', String(port), '-b', '-', target], {
    input: batch, env: childEnv, timeoutMs: 45_000, ...connection,
  })
  return {
    async upload(sourceDirectory, request) {
      await uploadPackageParts({ directory: sourceDirectory, request, sftp, log })
    },
    async submitRollback(sourceDirectory, request) {
      await submitRollbackRequest({ directory: sourceDirectory, request, sftp })
    },
    async trigger() {
      run('ssh', [...options, '-p', String(port), target, triggerCommand], { env: childEnv, timeout: 45_000 })
    },
    async readStatus(request) {
      const destination = path.join(directory, 'status-download.json')
      fs.rmSync(destination, { force: true })
      try {
        await sftp(`get ${sftpQuote(`${deploymentRoot}/status/${request.requestId}.json`)} ${sftpQuote(destination)}\n`)
      } catch {
        return null
      }
      return JSON.parse(fs.readFileSync(destination, 'utf8').replace(/^\uFEFF/u, ''))
    },
  }
}

export async function waitForDeployment({ transport, request, timeoutMs = 40 * 60_000, pollMs = 10_000, now = Date.now, delay = sleep, log = console.log }) {
  const deadline = now() + timeoutMs
  let nextTrigger = 0
  let observed = false
  let lastPhase
  while (now() < deadline) {
    let status
    try {
      status = await transport.readStatus(request)
    } catch (error) {
      if (error instanceof SyntaxError) throw new Error('Server returned invalid deployment status JSON')
      log('Deployment status connection interrupted; reconnecting to the existing request')
    }
    if (status) {
      validateStatus(status, request)
      observed = true
      if (lastPhase !== `${status.state}/${status.phase}`) {
        lastPhase = `${status.state}/${status.phase}`
        log(`Deployment ${request.requestId}: ${lastPhase}`)
      }
      if (status.state === 'succeeded') return status
      if (status.state !== 'running') throw new Error(`Deployment ${request.requestId} ended with ${status.state} (${status.phase})`)
    }
    if (!observed && now() >= nextTrigger) {
      nextTrigger = now() + 30_000
      try {
        await transport.trigger()
      } catch {
        log('Task trigger connection interrupted; checking the existing request before retrying the fixed task trigger')
      }
    }
    await delay(pollMs)
  }
  throw new Error(`Timed out waiting for deployment ${request.requestId}; the server task continues independently. Inspect its status before retrying`)
}

export async function deploy({ transport, directory, provenance, requestId, ...pollOptions }) {
  const request = makeRequest(provenance, requestId)
  console.log(`Submitting deployment request ${request.requestId} for ${request.tag} (${request.sourceCommit})`)
  await transport.upload(directory, request)
  return waitForDeployment({ transport, request, ...pollOptions })
}

export async function rollback({ transport, directory, provenance, requestId, ...pollOptions }) {
  const request = makeRollbackRequest(provenance, requestId)
  console.log(`Submitting rollback request ${request.requestId} to ${request.tag} (${request.sourceCommit})`)
  await transport.submitRollback(directory, request)
  return waitForDeployment({ transport, request, ...pollOptions })
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-deploy-'))
  try {
    const mode = process.env.DEPLOY_MODE || 'deploy'
    assert(['deploy', 'rollback'].includes(mode), 'Invalid DEPLOY_MODE')
    const release = await prepareRelease({
      github: createGithub(), tag: process.env.RELEASE_TAG, env: process.env, directory,
      workflowPath: mode === 'rollback' ? rollbackWorkflowPath : releaseWorkflowPath,
    })
    const transport = createSshTransport(process.env, directory)
    const result = await (mode === 'rollback' ? rollback : deploy)({ transport, directory: release.directory, provenance: release.provenance })
    if (process.env.GITHUB_STEP_SUMMARY) {
      fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${mode === 'rollback' ? 'Rolled back to' : 'Deployed'} **${result.tag}** (${result.sourceCommit}). Request: ${result.requestId}. Phase: ${result.phase}.\n`)
    }
  } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  } finally {
    fs.rmSync(directory, { recursive: true, force: true })
  }
}
