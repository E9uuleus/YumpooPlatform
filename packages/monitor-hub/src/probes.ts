import type { Config, ReleaseEvent, Verification } from './model.js'
import type { Alert, Hub } from './hub.js'
import { writeFile, rename } from 'node:fs/promises'
import { parse as parseYaml } from 'yaml'

type Sample = { metric: Record<string, string>; value: [number, string] }
const metricNames = ['ALERTS','probe_success','up','yumpoo_ops_sample_timestamp_seconds','yumpoo_ops_io_timestamp_seconds','yumpoo_ops_alert_evaluation_timestamp_seconds']
export const timestampQuery = metricNames.map(name => `label_replace(timestamp({__name__="${name}"}), "monitor_metric_name", "${name}", "", "")`).join(' or ')
export class Probes {
  samples: Sample[] = []
  sampledAt = 0
  discovery = new Map<string, boolean>()
  get discoverySuccess() { return this.config.services.every(service => this.discovery.get(service.id)) }
  freshSince = new Map<string, number>()
  constructor(public config: Config, public send: typeof fetch = fetch) {}
  async json(url: string) {
    const response = await this.send(url, { signal: AbortSignal.timeout(8000), redirect: 'error' })
    if (!response.ok) throw new Error('Probe HTTP failure')
    const text = await response.text()
    if (Buffer.byteLength(text) > 256_000) throw new Error('Probe response too large')
    return JSON.parse(text)
  }
  async poll(hub: Hub, now = Date.now()) {
    try {
      // Carry the original sample timestamp, not the instant query evaluation timestamp.
      const [values, timestamps] = await Promise.all([
        this.json(`${this.config.prometheusUrl}/api/v1/query?query=${encodeURIComponent(`{__name__=~"${metricNames.join('|')}"}`)}`),
        this.json(`${this.config.prometheusUrl}/api/v1/query?query=${encodeURIComponent(timestampQuery)}`),
      ])
      if (values.status !== 'success' || timestamps.status !== 'success') throw new Error('Prometheus query failure')
      const key = (metric: Record<string,string>) => JSON.stringify([metric.__name__ ?? metric.monitor_metric_name, Object.entries(metric).filter(([k]) => k !== '__name__' && k !== 'monitor_metric_name').sort()])
      const times = new Map<string, number>((timestamps.data.result as Sample[]).map(s => [key(s.metric), Number(s.value[1])]))
      if (now - this.sampledAt >= 90_000) this.freshSince.clear()
      this.samples = (values.data.result as Sample[]).map(s => ({ ...s, value: [times.get(key(s.metric)) ?? 0, s.value[1]] }))
      this.sampledAt = now
      const jobs = new Set(this.samples.filter(s => s.metric.__name__ === 'up').map(s => s.metric.job!))
      for (const job of jobs) {
        const up = this.samples.filter(s => s.metric.__name__ === 'up' && s.metric.job === job)
        if (up.every(s => s.value[1] === '1' && now - s.value[0] * 1000 < 90_000)) { if (!this.freshSince.has(job)) this.freshSince.set(job, now) }
        else this.freshSince.delete(job)
      }
      for (const job of this.freshSince.keys()) if (!jobs.has(job)) this.freshSince.delete(job)
    } catch { this.samples = []; this.sampledAt = 0; this.freshSince.clear() }
    for (const service of this.config.services) {
      const groups = service.checks.map(check => this.samples.filter(s => s.metric.__name__ === 'probe_success' && s.metric.check === check))
      const samples = groups.flat()
      const fresh = groups.every(group => group.length > 0) && samples.every(s => now - s.value[0] * 1000 < 90_000)
      const failed = samples.some(s => now - s.value[0] * 1000 < 90_000 && s.value[1] === '0')
      const state = failed ? 'down' : !fresh || !this.discovery.get(service.id) ? 'unknown' : 'healthy'
      hub.observe({ service: service.id, state, at: fresh ? Math.min(...samples.map(s => s.value[0] * 1000)) : now }, now)
    }
  }
  fresh(alert: Alert, now = Date.now()) {
    if (now - this.sampledAt > 90_000) return false
    const source = alert.labels.alertname?.startsWith('MonitoringTarget') ? alert.labels.job : alert.labels.source_job
    if (!source) return false
    const since = this.freshSince.get(source)
    if (since === undefined || now - since < 60_000) return false
    if (this.samples.some(s => s.metric.__name__ === 'ALERTS' && s.metric.alertstate === 'firing' && Object.entries(alert.labels).every(([key, value]) => s.metric[key] === value))) return false
    const samples = this.samples.filter(s => s.metric.__name__ === 'up' && s.metric.job === source)
    if (!samples.length || !samples.every(s => s.value[1] === '1' && now - s.value[0] * 1000 < 90_000)) return false
    if (source === 'external') return this.samples.some(s => s.metric.__name__ === 'probe_success' && s.metric.instance === alert.labels.instance && s.value[1] === '1' && now - s.value[0] * 1000 < 90_000)
    if (source === 'application') return ['yumpoo_ops_sample_timestamp_seconds','yumpoo_ops_io_timestamp_seconds','yumpoo_ops_alert_evaluation_timestamp_seconds'].every(name => this.samples.some(s => s.metric.__name__ === name && now - Number(s.value[1]) * 1000 < 90_000))
    return true
  }
  async discover() {
    const targets: {targets:string[];labels:Record<string,string>}[] = []
    try {
      const response = await this.send(`${this.config.siteUrl}/`, { signal: AbortSignal.timeout(8000), redirect: 'error' })
      if (!response.ok) throw new Error('Home unavailable')
      const html = await response.text()
      if (Buffer.byteLength(html) > 512_000) throw new Error('Home too large')
      const assets = [...html.matchAll(/(?:src|href)=["'](\/assets\/[^"'<>]+\.(?:js|css))["']/gu)].map(m => m[1]!)
      if (!assets.length) throw new Error('Missing application assets')
      targets.push({ targets: [...new Set(assets)].slice(0, 12).map(p => new URL(p, this.config.siteUrl).href), labels: { check:'assets', module:'asset', service:'server' } })
      this.discovery.set('server', true)
    } catch { this.discovery.set('server', false) }
    try {
      const manifest = await this.desktopManifest()
      targets.push({ targets: [`${this.config.siteUrl}/desktop/stable/${encodeURIComponent(manifest.installer.name)}`], labels: { check:'installer', module:'installer', service:'desktop' } })
      this.discovery.set('desktop', true)
    } catch { this.discovery.set('desktop', false) }
    try {
      if (this.config.discoveryFile) {
        await writeFile(`${this.config.discoveryFile}.tmp`, JSON.stringify(targets), { mode: 0o644 })
        await rename(`${this.config.discoveryFile}.tmp`, this.config.discoveryFile)
      }
    } catch { this.discovery.clear() }
  }
  async desktopManifest() {
    const manifest = await this.json(`${this.config.siteUrl}/desktop/stable/release.json`)
    if (!validManifest(manifest)) throw new Error('Invalid manifest')
    const response = await this.send(`${this.config.siteUrl}/desktop/stable/latest.yml`, { signal: AbortSignal.timeout(8000), redirect: 'error' })
    if (!response.ok) throw new Error('Missing updater manifest')
    const text = await response.text()
    if (Buffer.byteLength(text) > 32_000) throw new Error('Updater manifest too large')
    const latest = parseYaml(text, { maxAliasCount: 0 })
    const entry = latest?.files?.find((file: {url?:string}) => file.url === manifest.installer.name)
    if (latest?.version !== manifest.version || latest.path !== manifest.installer.name || !entry || entry.size !== manifest.installer.bytes
      || typeof entry.sha512 !== 'string' || !/^[A-Za-z0-9+/]{86}==$/u.test(entry.sha512) || latest.sha512 !== entry.sha512) throw new Error('Inconsistent updater manifest')
    return manifest
  }
  async verify(event: ReleaseEvent): Promise<Verification> {
    const base = this.config.siteUrl
    if (event.payload.target === 'desktop') {
      const manifest = await this.desktopManifest()
      const installer = await this.send(`${base}/desktop/stable/${encodeURIComponent(manifest.installer.name)}`, { method: 'HEAD', signal: AbortSignal.timeout(8000), redirect: 'error' })
      return { healthy: installer.ok && Number(installer.headers.get('content-length')) === manifest.installer.bytes, tag: manifest.tag, commit: manifest.sourceCommit, notes: Array.isArray(manifest.notes) ? manifest.notes.filter((n: unknown) => typeof n === 'string') : [] }
    }
    const [health, version, home] = await Promise.all([
      this.json(`${base}/healthz`), this.json(`${base}/version.json`),
      this.send(`${base}/`, { signal: AbortSignal.timeout(8000), redirect: 'error' }),
    ])
    return { healthy: health.status === 'UP' && home.ok && typeof version.release?.tag === 'string' && typeof version.build?.commit === 'string', tag: version.release?.tag, commit: version.build?.commit }
  }
}

function validManifest(manifest: {schemaVersion?: number; tag?: string; sourceCommit?: string; version?: string; installer?:{name?:string;bytes?:number;sha256?:string}}): boolean {
  return manifest.schemaVersion === 1 && /^desktop-v\d+\.\d+\.\d+$/u.test(manifest.tag ?? '') && /^[a-f0-9]{40}$/u.test(manifest.sourceCommit ?? '')
    && !!manifest.installer && /^[\w. -]+\.exe$/u.test(manifest.installer.name ?? '') && !manifest.installer.name!.includes('..')
    && Number.isSafeInteger(manifest.installer.bytes) && manifest.installer.bytes! > 0 && /^[a-f0-9]{64}$/u.test(manifest.installer.sha256 ?? '')
    && manifest.tag === `desktop-v${manifest.version}`
}
