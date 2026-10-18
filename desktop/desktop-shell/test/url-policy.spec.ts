import { describe, expect, it } from 'vitest'
import { resolveWebAppUrl } from '../src/main/url-policy'

describe('Web URL 策略', () => {
  it('开发模式默认使用回环地址', () => {
    expect(resolveWebAppUrl({ isPackaged: false }).href).toBe(
      'http://127.0.0.1:18173/',
    )
  })

  it.each([
    'http://192.168.1.20:18173',
    'https://example.com',
    'file:///tmp/index.html',
    'http://user:secret@127.0.0.1:18173',
  ])('开发模式拒绝非回环 HTTP 地址：%s', (configuredUrl) => {
    expect(() => resolveWebAppUrl({ configuredUrl, isPackaged: false })).toThrow()
  })

  it('打包版只使用分发配置并忽略 YUMPOO_WEB_URL', () => {
    expect(
      resolveWebAppUrl({
        configuredUrl: 'https://attacker.example.com',
        distributionOrigin: 'https://yumpoo.example.com',
        isPackaged: true,
      }).href,
    ).toBe('https://yumpoo.example.com/')
  })

  it('打包版缺少分发配置或不是 HTTPS 时拒绝启动', () => {
    expect(() => resolveWebAppUrl({ configuredUrl: 'https://yumpoo.example.com', isPackaged: true })).toThrow('分发配置')
    expect(() => resolveWebAppUrl({ distributionOrigin: 'http://yumpoo.example.com', isPackaged: true })).toThrow()
  })
})
