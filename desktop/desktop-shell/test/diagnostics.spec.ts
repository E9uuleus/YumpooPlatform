import { describe, expect, it, vi } from 'vitest'
import { collectDiagnostics, type DiagnosticsDependencies } from '../src/main/diagnostics'

const NOW = new Date('2026-10-17T06:00:00.000Z')

function dependencies(overrides: Partial<DiagnosticsDependencies> = {}): DiagnosticsDependencies {
  return {
    origin: 'https://yumpoo.example.com',
    versions: { shell: '1.0.0', electron: '43.7.9', chrome: '150.0', os: 'Windows_NT 10.0.26200', arch: 'x64' },
    packaged: true,
    safeMode: false,
    crashLoop: false,
    consecutiveFailures: 0,
    startupFailure: null,
    offline: false,
    lookup: async () => 2,
    resolveProxy: async () => 'DIRECT',
    inspectTls: async () => ({ subject: 'yumpoo.example.com', issuer: 'Example CA', validTo: 'Dec 31 00:00:00 2026 GMT' }),
    fetch: async url => url.endsWith('/version.json')
      ? { status: 200, date: 'Sat, 17 Oct 2026 06:00:30 GMT', json: { release: { tag: 'server-v2026.10.16.1' }, build: { commit: 'a'.repeat(40) } } }
      : { status: 200, date: null },
    isEncryptionAvailable: () => true,
    userDataWritable: () => true,
    gpuFeatureStatus: () => ({ gpu_compositing: 'enabled' }),
    appMetrics: () => [{ type: 'Browser', memory: { workingSetSize: 81_000.4, privateBytes: 60_000 } }, { type: 'Tab', memory: { workingSetSize: 120_000 } }],
    now: () => NOW,
    ...overrides,
  }
}

describe('诊断报告', () => {
  it('汇总网络、本机与版本信息', async () => {
    const report = await collectDiagnostics(dependencies())
    expect(report.network.skipped).toBe(false)
    expect(report.network.proxy).toBe('DIRECT')
    expect(report.network.dns).toMatchObject({ ok: true, addresses: 2 })
    expect(report.network.tls).toMatchObject({ ok: true, subject: 'yumpoo.example.com', issuer: 'Example CA', daysRemaining: 74 })
    expect(report.network.health).toMatchObject({ ok: true, status: 200 })
    expect(report.network.version).toMatchObject({ ok: true, status: 200, serverVersion: 'server-v2026.10.16.1', clockSkewMs: 30_000 })
    expect(report.local).toEqual({
      safeStorageAvailable: true,
      userDataWritable: true,
      gpu: { gpu_compositing: 'enabled' },
      processes: [{ type: 'Browser', workingSetKb: 81_000, privateKb: 60_000 }, { type: 'Tab', workingSetKb: 120_000 }],
    })
    expect(report.update).toEqual({ state: 'unavailable' })
  })

  it('离线自检和缺少分发配置时不访问网络', async () => {
    const lookup = vi.fn(async () => 1)
    const offline = await collectDiagnostics(dependencies({ offline: true, lookup }))
    expect(offline.network).toMatchObject({ skipped: true, dns: { ok: false, error: 'SKIPPED_OFFLINE' } })
    const missing = await collectDiagnostics(dependencies({ origin: null, lookup, startupFailure: 'DISTRIBUTION_INVALID' }))
    expect(missing.origin).toBeNull()
    expect(missing.network.health.error).toBe('NO_ORIGIN')
    expect(missing.mode.startupFailure).toBe('DISTRIBUTION_INVALID')
    expect(lookup).not.toHaveBeenCalled()
  })

  it('失败只留下规范化的错误码', async () => {
    const report = await collectDiagnostics(dependencies({
      lookup: async () => { throw Object.assign(new Error('getaddrinfo ENOTFOUND yumpoo.example.com'), { code: 'ENOTFOUND' }) },
      inspectTls: async () => { throw Object.assign(new Error('certificate has expired'), { code: 'CERT_HAS_EXPIRED' }) },
      fetch: async url => (url.endsWith('/healthz') ? { status: 503, date: null } : { status: 200, date: null, json: { release: { tag: '<script>' } } }),
    }))
    expect(report.network.dns).toMatchObject({ ok: false, error: 'ENOTFOUND' })
    expect(report.network.tls).toMatchObject({ ok: false, error: 'CERT_HAS_EXPIRED' })
    expect(report.network.tls.subject).toBeUndefined()
    expect(report.network.health).toMatchObject({ ok: false, error: 'HTTP_503', status: 503 })
    expect(report.network.version.serverVersion).toBeUndefined()
    expect(JSON.stringify(report)).not.toContain('getaddrinfo')
  })

  it('探测超时按 TIMEOUT 记录，本机读取异常不影响报告', async () => {
    const report = await collectDiagnostics(dependencies({
      timeoutMs: 20,
      fetch: () => new Promise(() => undefined),
      appMetrics: () => { throw new Error('unavailable') },
      isEncryptionAvailable: () => { throw new Error('not ready') },
    }))
    expect(report.network.health).toMatchObject({ ok: false, error: 'TIMEOUT' })
    expect(report.local.processes).toEqual([])
    expect(report.local.safeStorageAvailable).toBe(false)
  })
})
