import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { runSelfCheck, selfCheckReportPath } from '../src/main/self-check'

const absolute = path.resolve('self-check.json')

function dependencies(overrides: Partial<Parameters<typeof runSelfCheck>[0]> = {}) {
  return {
    isPackaged: true,
    version: '1.0.0',
    electronVersion: '43.7.9',
    loadDistribution: () => ({ schemaVersion: 1 as const, origin: 'https://yumpoo.example.com' }),
    isDefaultProtocolClient: (protocol: string) => protocol === 'yumpoo',
    isEncryptionAvailable: () => true,
    ...overrides,
  }
}

describe('安装自检', () => {
  it('只接受绝对报告路径', () => {
    expect(selfCheckReportPath(['YumpooDesktop.exe', `--self-check=${absolute}`])).toBe(absolute)
    expect(selfCheckReportPath(['YumpooDesktop.exe', '--self-check=report.json'])).toBeUndefined()
    expect(selfCheckReportPath(['YumpooDesktop.exe', '--self-check='])).toBeUndefined()
    expect(selfCheckReportPath(['YumpooDesktop.exe'])).toBeUndefined()
  })

  it('完整安装通过', () => {
    expect(runSelfCheck(dependencies())).toEqual({
      schemaVersion: 1, status: 'PASS', version: '1.0.0', electronVersion: '43.7.9', packaged: true,
      distributionOrigin: 'https://yumpoo.example.com', protocolRegistered: true, safeStorageAvailable: true, failures: [],
    })
  })

  it('逐项报告缺失', () => {
    const report = runSelfCheck(dependencies({
      isPackaged: false,
      loadDistribution: () => { throw new Error('missing') },
      isDefaultProtocolClient: () => false,
      isEncryptionAvailable: () => false,
    }))
    expect(report.status).toBe('FAIL')
    expect(report.failures).toEqual(['NOT_PACKAGED', 'DISTRIBUTION_INVALID', 'PROTOCOL_NOT_REGISTERED'])
    expect(report.distributionOrigin).toBeNull()
    expect(report.safeStorageAvailable).toBe(false)
  })
})
