import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { loadDistribution, parseDistribution } from '../src/main/distribution'

describe('桌面分发配置', () => {
  it('仓库内生产配置通过校验', () => {
    const production = JSON.parse(readFileSync(path.join(__dirname, '..', 'distribution', 'production.json'), 'utf8'))
    expect(parseDistribution(production).origin).toMatch(/^https:\/\/[^/]+$/u)
  })

  it.each([
    null,
    [],
    { schemaVersion: 2, origin: 'https://yumpoo.example.com' },
    { schemaVersion: 1 },
    { schemaVersion: 1, origin: 'https://yumpoo.example.com', extra: true },
    { schemaVersion: 1, origin: 'http://yumpoo.example.com' },
    { schemaVersion: 1, origin: 'https://yumpoo.example.com/' },
    { schemaVersion: 1, origin: 'https://yumpoo.example.com/app' },
    { schemaVersion: 1, origin: 'https://yumpoo.example.com?x=1' },
    { schemaVersion: 1, origin: 'https://user:secret@yumpoo.example.com' },
    { schemaVersion: 1, origin: 'not a url' },
  ])('拒绝无效配置：%j', (value) => {
    expect(() => parseDistribution(value)).toThrow()
  })

  const fixture = JSON.parse(readFileSync(path.join(__dirname, 'fixtures', 'signed-update.json'), 'utf8')) as { publicKey: string; signed: { keyId: string } }
  const update = { feed: 'https://yumpoo.example.com/desktop/stable/', channel: 'stable', publicKeys: [{ keyId: fixture.signed.keyId, publicKey: fixture.publicKey }] }

  it('接受带更新源和派生 keyId 的更新配置', () => {
    expect(parseDistribution({ schemaVersion: 1, origin: 'https://yumpoo.example.com', update }).update).toEqual(update)
    expect(parseDistribution({ schemaVersion: 1, origin: 'https://yumpoo.example.com', update: { ...update, feed: 'http://127.0.0.1:18200/feed/' } }).update?.feed)
      .toBe('http://127.0.0.1:18200/feed/')
  })

  it.each([
    { ...update, feed: 'http://yumpoo.example.com/desktop/stable/' },
    { ...update, feed: 'https://yumpoo.example.com/desktop/stable' },
    { ...update, feed: 'https://yumpoo.example.com/desktop/?x=1' },
    { ...update, feed: 'https://user:pw@yumpoo.example.com/desktop/' },
    { ...update, channel: 'Stable' },
    { ...update, publicKeys: [] },
    { ...update, publicKeys: [{ keyId: 'ed25519-0000000000000000', publicKey: fixture.publicKey }] },
    { ...update, publicKeys: [{ keyId: fixture.signed.keyId, publicKey: 'AAAA' }] },
    { ...update, publicKeys: [update.publicKeys[0], update.publicKeys[0]] },
    { ...update, extra: true },
  ])('拒绝无效更新配置：%j', (value) => {
    expect(() => parseDistribution({ schemaVersion: 1, origin: 'https://yumpoo.example.com', update: value })).toThrow()
  })

  it('读取构建目录中的配置，缺失时给出明确错误', () => {
    const directory = mkdtempSync(path.join(os.tmpdir(), 'yumpoo-distribution-'))
    try {
      expect(() => loadDistribution(directory)).toThrow('缺少分发配置')
      writeFileSync(path.join(directory, 'distribution.json'), JSON.stringify({ schemaVersion: 1, origin: 'https://yumpoo.example.com' }))
      expect(loadDistribution(directory)).toEqual({ schemaVersion: 1, origin: 'https://yumpoo.example.com' })
    } finally {
      rmSync(directory, { recursive: true, force: true })
    }
  })
})
