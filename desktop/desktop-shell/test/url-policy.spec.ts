import { describe, expect, it } from 'vitest'
import { resolveWebAppUrl } from '../src/main/url-policy'

describe('YUMPOO_WEB_URL 策略', () => {
  it('开发模式默认使用回环地址', () => {
    expect(resolveWebAppUrl({ isPackaged: false }).href).toBe(
      'http://127.0.0.1:18173/',
    )
  })

  it.each([
    'http://192.168.1.20:18173',
    'https://example.com',
    'file:///tmp/index.html',
  ])('开发模式拒绝非回环 HTTP 地址：%s', (configuredUrl) => {
    expect(() => resolveWebAppUrl({ configuredUrl, isPackaged: false })).toThrow()
  })

  it('生产模式要求 HTTPS 且拒绝凭据', () => {
    expect(
      resolveWebAppUrl({
        configuredUrl: 'https://yumpoo.example.com/app',
        isPackaged: true,
      }).href,
    ).toBe('https://yumpoo.example.com/app')
    expect(() => resolveWebAppUrl({ isPackaged: true })).toThrow()
    expect(() =>
      resolveWebAppUrl({
        configuredUrl: 'http://yumpoo.example.com',
        isPackaged: true,
      }),
    ).toThrow()
    expect(() =>
      resolveWebAppUrl({
        configuredUrl: 'https://user:secret@yumpoo.example.com',
        isPackaged: true,
      }),
    ).toThrow()
  })
})
