import { app, net, safeStorage, session } from 'electron'
import { lookup } from 'node:dns/promises'
import { rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import tls from 'node:tls'
import type { DiagnosticsDependencies, ProbeResponse, TlsInspection } from './diagnostics'

const MAX_JSON_BYTES = 64 * 1024

export type RuntimeDiagnosticsOptions = Pick<DiagnosticsDependencies,
  'origin' | 'safeMode' | 'crashLoop' | 'consecutiveFailures' | 'startupFailure' | 'offline' | 'updateState'>

/** Probes use Chromium's network stack (system proxy, Windows certificate store) and never send cookies. */
export function runtimeDiagnostics(options: RuntimeDiagnosticsOptions): DiagnosticsDependencies {
  return {
    ...options,
    packaged: app.isPackaged,
    versions: {
      shell: app.getVersion(),
      electron: process.versions.electron,
      chrome: process.versions.chrome,
      os: `${os.type()} ${os.release()}`,
      arch: process.arch,
    },
    lookup: async hostname => (await lookup(hostname, { all: true })).length,
    resolveProxy: url => session.defaultSession.resolveProxy(url),
    inspectTls,
    fetch: probeFetch,
    isEncryptionAvailable: () => safeStorage.isEncryptionAvailable(),
    userDataWritable,
    gpuFeatureStatus: () => ({ ...app.getGPUFeatureStatus() } as unknown as Record<string, string>),
    appMetrics: () => app.getAppMetrics(),
    now: () => new Date(),
  }
}

async function probeFetch(url: string, readJson: boolean): Promise<ProbeResponse> {
  const response = await net.fetch(url, { credentials: 'omit', cache: 'no-store', redirect: 'manual' })
  let json: unknown
  if (readJson && response.ok) {
    const body = await response.arrayBuffer()
    if (body.byteLength <= MAX_JSON_BYTES) {
      try { json = JSON.parse(Buffer.from(body).toString('utf8')) } catch { json = undefined }
    }
  } else {
    await response.body?.cancel()
  }
  return { status: response.status, date: response.headers.get('date'), json }
}

/**
 * Node's own TLS client reports the certificate Chromium does not expose. It validates normally against
 * the Windows store plus the bundled roots, so a corporate root CA is not a false failure; an untrusted or
 * expired certificate fails the handshake and its error code becomes the probe result.
 */
function inspectTls(hostname: string, port: number): Promise<TlsInspection> {
  return new Promise((resolve, reject) => {
    const socket = tls.connect({
      host: hostname,
      port,
      servername: hostname,
      ca: [...tls.getCACertificates('system'), ...tls.getCACertificates('bundled')],
    })
    socket.once('secureConnect', () => {
      const certificate = socket.getPeerCertificate()
      const name = (value: unknown) => (typeof value === 'string' ? value : Array.isArray(value) ? value.join(',') : undefined)
      const subject = name(certificate.subject?.CN)
      const issuer = name(certificate.issuer?.O ?? certificate.issuer?.CN)
      resolve({
        ...(subject ? { subject } : {}),
        ...(issuer ? { issuer } : {}),
        ...(certificate.valid_to ? { validTo: certificate.valid_to } : {}),
      })
      socket.end()
    })
    socket.setTimeout(10_000, () => socket.destroy(Object.assign(new Error('TIMEOUT'), { code: 'TIMEOUT' })))
    socket.once('error', reject)
  })
}

function userDataWritable(): boolean {
  const probe = path.join(app.getPath('userData'), `.write-probe-${process.pid}`)
  try {
    writeFileSync(probe, 'ok')
    return true
  } catch {
    return false
  } finally {
    rmSync(probe, { force: true })
  }
}
