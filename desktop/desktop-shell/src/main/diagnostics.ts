import type { DiagnosticsProbe, DiagnosticsReport, DiagnosticsStartupFailure } from '@yumpoo/preload-contract'

/** Resolves only for a trusted certificate; validation failures reject with the TLS error code. */
export interface TlsInspection {
  readonly subject?: string
  readonly issuer?: string
  readonly validTo?: string
}

export interface ProbeResponse {
  readonly status: number
  readonly date: string | null
  readonly json?: unknown
}

export interface DiagnosticsDependencies {
  readonly origin: string | null
  readonly versions: DiagnosticsReport['versions']
  readonly packaged: boolean
  readonly safeMode: boolean
  readonly crashLoop: boolean
  readonly consecutiveFailures: number
  readonly startupFailure: DiagnosticsStartupFailure | null
  /** Self-check renders the page without touching the network. */
  readonly offline: boolean
  readonly timeoutMs?: number
  lookup(hostname: string): Promise<number>
  resolveProxy(url: string): Promise<string>
  inspectTls(hostname: string, port: number): Promise<TlsInspection>
  fetch(url: string, readJson: boolean): Promise<ProbeResponse>
  isEncryptionAvailable(): boolean
  userDataWritable(): boolean
  gpuFeatureStatus(): Record<string, string>
  appMetrics(): Array<{ type: string; memory: { workingSetSize: number; privateBytes?: number } }>
  now(): Date
}

const DEFAULT_TIMEOUT_MS = 8_000
const DAY_MS = 24 * 60 * 60 * 1000

export async function collectDiagnostics(dependencies: DiagnosticsDependencies): Promise<DiagnosticsReport> {
  const origin = dependencies.origin ? new URL(dependencies.origin) : null
  const skipped = dependencies.offline || !origin
  const timeout = dependencies.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const notRun: DiagnosticsProbe = { ok: false, error: dependencies.offline ? 'SKIPPED_OFFLINE' : 'NO_ORIGIN' }
  const network: DiagnosticsReport['network'] = skipped || !origin
    ? { skipped: true, proxy: null, dns: notRun, tls: notRun, health: notRun, version: notRun }
    : await probeNetwork(dependencies, origin, timeout)
  return {
    schemaVersion: 1,
    generatedAt: dependencies.now().toISOString(),
    versions: dependencies.versions,
    mode: {
      packaged: dependencies.packaged,
      safeMode: dependencies.safeMode,
      crashLoop: dependencies.crashLoop,
      consecutiveFailures: dependencies.consecutiveFailures,
      startupFailure: dependencies.startupFailure,
    },
    origin: origin?.origin ?? null,
    network,
    local: {
      safeStorageAvailable: safely(() => dependencies.isEncryptionAvailable(), false),
      userDataWritable: safely(() => dependencies.userDataWritable(), false),
      gpu: safely(() => sanitizeGpu(dependencies.gpuFeatureStatus()), {}),
      processes: safely(() => dependencies.appMetrics().map(metric => ({
        type: String(metric.type).slice(0, 32),
        workingSetKb: Math.round(metric.memory.workingSetSize),
        ...(typeof metric.memory.privateBytes === 'number' ? { privateKb: Math.round(metric.memory.privateBytes) } : {}),
      })), []),
    },
    update: { state: 'unavailable' },
  }
}

async function probeNetwork(dependencies: DiagnosticsDependencies, origin: URL, timeout: number): Promise<DiagnosticsReport['network']> {
  const port = origin.port ? Number(origin.port) : 443
  type Network = DiagnosticsReport['network']
  const [proxy, dns, tls, health, version] = await Promise.all([
    withTimeout(dependencies.resolveProxy(origin.origin), timeout).then(value => value.slice(0, 200)).catch(() => null),
    probe(timeout, async () => ({ addresses: await dependencies.lookup(origin.hostname) })) as Promise<Network['dns']>,
    probe(timeout, async () => {
      const inspection = await dependencies.inspectTls(origin.hostname, port)
      const validTo = inspection.validTo ? new Date(inspection.validTo) : undefined
      return {
        ...(inspection.subject ? { subject: inspection.subject.slice(0, 200) } : {}),
        ...(inspection.issuer ? { issuer: inspection.issuer.slice(0, 200) } : {}),
        ...(validTo && Number.isFinite(validTo.getTime())
          ? { validTo: validTo.toISOString(), daysRemaining: Math.floor((validTo.getTime() - dependencies.now().getTime()) / DAY_MS) }
          : {}),
      }
    }) as Promise<Network['tls']>,
    probe(timeout, async () => {
      const response = await dependencies.fetch(new URL('/healthz', origin).href, false)
      if (response.status !== 200) throw Object.assign(new Error('HTTP'), { code: `HTTP_${response.status}`, details: { status: response.status } })
      return { status: response.status }
    }) as Promise<Network['health']>,
    probe(timeout, async () => {
      const started = dependencies.now().getTime()
      const response = await dependencies.fetch(new URL('/version.json', origin).href, true)
      const received = dependencies.now().getTime()
      const serverTime = response.date ? Date.parse(response.date) : Number.NaN
      const version = serverVersion(response.json)
      const details = {
        status: response.status,
        ...(version ? { serverVersion: version } : {}),
        // The Date header has one-second resolution; the request midpoint approximates when it was stamped.
        ...(Number.isFinite(serverTime) ? { clockSkewMs: Math.round(serverTime - (started + received) / 2) } : {}),
      }
      if (response.status !== 200) throw Object.assign(new Error('HTTP'), { code: `HTTP_${response.status}`, details })
      return details
    }) as Promise<Network['version']>,
  ])
  return { skipped: false, proxy, dns, tls, health, version }
}

/** Failures keep whatever details the probe gathered before failing, such as the HTTP status. */
async function probe(timeout: number, run: () => Promise<object>): Promise<DiagnosticsProbe> {
  const started = Date.now()
  try {
    const details = await withTimeout(run(), timeout)
    return { ok: true, durationMs: Date.now() - started, ...details }
  } catch (error) {
    const failure = error as { code?: unknown; details?: object }
    return { ok: false, error: errorCode(failure.code ?? (error instanceof Error ? error.message : 'FAILED')), durationMs: Date.now() - started, ...failure.details }
  }
}

function withTimeout<T>(promise: Promise<T>, timeout: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(Object.assign(new Error('TIMEOUT'), { code: 'TIMEOUT' })), timeout)
    promise.then(value => { clearTimeout(timer); resolve(value) }, error => { clearTimeout(timer); reject(error) })
  })
}

/** Error codes are reduced to identifier characters so no host-supplied text reaches the report. */
function errorCode(value: unknown): string {
  const code = String(value).toUpperCase().replace(/[^A-Z0-9_]+/gu, '_').replace(/^_+|_+$/gu, '')
  return code.slice(0, 64) || 'FAILED'
}

/** The product version is the release tag; builds without one fall back to the abbreviated commit. */
function serverVersion(json: unknown): string | undefined {
  const info = json as { release?: { tag?: unknown }; build?: { commit?: unknown } } | null
  if (typeof info?.release?.tag === 'string' && /^server-v[\d.]{1,32}$/u.test(info.release.tag)) return info.release.tag
  return typeof info?.build?.commit === 'string' && /^[0-9a-f]{40}$/u.test(info.build.commit) ? info.build.commit.slice(0, 12) : undefined
}

function sanitizeGpu(status: Record<string, string>): Record<string, string> {
  return Object.fromEntries(Object.entries(status).slice(0, 40)
    .map(([key, value]) => [key.slice(0, 64), String(value).slice(0, 64)]))
}

function safely<T>(read: () => T, fallback: T): T {
  try {
    return read()
  } catch {
    return fallback
  }
}
