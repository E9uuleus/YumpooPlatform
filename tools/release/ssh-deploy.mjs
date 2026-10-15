import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import { setTimeout as sleep } from 'node:timers/promises'
import { pathToFileURL } from 'node:url'
import { createGithub, run } from './github.mjs'
import { prepareRelease } from './promote-release.mjs'
import { packageName, validateStatus } from './policy.mjs'

export const deploymentRoot = '/C:/ProgramData/Yumpoo/deployment'
export const deploymentUser = 'yumpoo-deploy'
export const triggerCommand = 'schtasks.exe /Run /TN YumpooDeploy'

export function makeRequest(provenance, requestId = randomUUID()) {
  assert(/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u.test(requestId), 'Invalid deployment request ID')
  return {
    schemaVersion: 1, requestId, tag: provenance.tag, sourceCommit: provenance.sourceCommit, packageSha256: provenance.packageSha256,
  }
}

export function sftpQuote(value) {
  assert(typeof value === 'string' && !/[\r\n\0]/u.test(value), 'Invalid SFTP path')
  return `"${value.replaceAll('\\', '\\\\').replaceAll('"', '\\"')}"`
}

export function uploadBatch(directory, request) {
  makeRequest(request, request.requestId)
  const inbox = `${deploymentRoot}/inbox/${request.requestId}`
  return [
    `mkdir ${sftpQuote(inbox)}`,
    `put ${sftpQuote(path.join(directory, packageName))} ${sftpQuote(`${inbox}/package.zip`)}`,
    `put ${sftpQuote(path.join(directory, 'provenance.json'))} ${sftpQuote(`${inbox}/provenance.json`)}`,
    `put ${sftpQuote(path.join(directory, 'request.json'))} ${sftpQuote(`${inbox}/request.json.uploading`)}`,
    `rename ${sftpQuote(`${inbox}/request.json.uploading`)} ${sftpQuote(`${inbox}/request.json`)}`,
    '',
  ].join('\n')
}

export function createSshTransport(env, directory) {
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
  const childEnv = { ...env }
  for (const name of ['GH_TOKEN', 'WINDOWS_DEPLOY_SSH_KEY', 'WINDOWS_DEPLOY_KNOWN_HOSTS']) delete childEnv[name]
  const sftp = batch => run('sftp', [...options, '-P', String(port), '-b', '-', target], { input: batch, env: childEnv, timeout: 600_000 })
  return {
    async upload(sourceDirectory, request) {
      fs.writeFileSync(path.join(sourceDirectory, 'request.json'), `${JSON.stringify(request, null, 2)}\n`, { mode: 0o600, flag: 'wx' })
      sftp(uploadBatch(sourceDirectory, request))
    },
    async trigger() {
      run('ssh', [...options, '-p', String(port), target, triggerCommand], { env: childEnv, timeout: 45_000 })
    },
    async readStatus(request) {
      const destination = path.join(directory, 'status-download.json')
      fs.rmSync(destination, { force: true })
      try {
        sftp(`get ${sftpQuote(`${deploymentRoot}/status/${request.requestId}.json`)} ${sftpQuote(destination)}\n`)
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

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-deploy-'))
  try {
    const release = await prepareRelease({
      github: createGithub(), tag: process.env.RELEASE_TAG, env: process.env, directory, promote: false,
    })
    const transport = createSshTransport(process.env, directory)
    const result = await deploy({ transport, directory: release.directory, provenance: release.provenance })
    if (process.env.GITHUB_STEP_SUMMARY) {
      fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `Deployed **${result.tag}** (${result.sourceCommit}). Request: ${result.requestId}. Phase: ${result.phase}.\n`)
    }
  } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  } finally {
    fs.rmSync(directory, { recursive: true, force: true })
  }
}
