import { afterEach, describe, expect, it, vi } from 'vitest'
import { beginAuthentication, consumeReturnPath, resetAuthenticationNavigation, saveRememberLogin } from './navigation'

describe('认证返回路径', () => {
  afterEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    resetAuthenticationNavigation()
    vi.restoreAllMocks()
  })

  it('保存并一次性恢复包含查询和锚点的站内地址', () => {
    const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => undefined)
    vi.spyOn(Date, 'now').mockReturnValue(1_000)
    beginAuthentication('/admin/identity/members?page=2#detail')
    expect(assign).toHaveBeenCalledWith('/api/v1/auth/wecom/authorize?remember=true')
    expect(consumeReturnPath(1_001)).toBe('/admin/identity/members?page=2#detail')
    expect(consumeReturnPath(1_001)).toBe('/')
  })

  it('授权地址带上本机记住的免登录选择，页面从 bfcache 恢复后允许再次发起', () => {
    const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => undefined)
    saveRememberLogin(false)
    beginAuthentication()
    beginAuthentication()
    window.dispatchEvent(Object.assign(new Event('pageshow'), { persisted: true }))
    beginAuthentication()
    expect(assign.mock.calls).toEqual([
      ['/api/v1/auth/wecom/authorize?remember=false'], ['/api/v1/auth/wecom/authorize?remember=false'],
    ])
  })

  it('并发认证只导航一次且不覆盖首个返回地址', () => {
    const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => undefined)
    beginAuthentication('/first')
    beginAuthentication('/second')
    expect(assign).toHaveBeenCalledTimes(1)
    expect(consumeReturnPath()).toBe('/first')
  })

  it.each(['/api/v1/private', '/%61pi/v1/private', '/status/unavailable', '/forbidden', '//evil.example/path'])('拒绝非法返回地址 %s', (path) => {
    vi.spyOn(window.location, 'assign').mockImplementation(() => undefined)
    beginAuthentication(path)
    expect(consumeReturnPath()).toBe('/')
  })

  it('拒绝过期和存储异常，并在读取前删除记录', () => {
    sessionStorage.setItem('yumpoo.auth.return-path.v1', JSON.stringify({ path: '/safe', expiresAt: 99 }))
    expect(consumeReturnPath(100)).toBe('/')
    expect(sessionStorage.length).toBe(0)
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError') })
    expect(consumeReturnPath()).toBe('/')
  })
})
