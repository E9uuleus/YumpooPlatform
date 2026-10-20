import type { App, WebContents } from 'electron'
import { describe, expect, it, vi } from 'vitest'
import {
  installAppSecurityGuards,
  installSecurityGuards,
} from '../src/main/security-guards'

describe('Electron 导航与权限门禁', () => {
  it('拒绝新窗口、WebView 和跨源导航，权限只对受信来源放行剪贴板写入，下载只放行附件内容', () => {
    const listeners = new Map<string, (...args: never[]) => void>()
    let windowHandler: (() => { action: 'deny' }) | undefined
    let permissionHandler:
      | ((_webContents: unknown, permission: string, callback: (allowed: boolean) => void, details: { requestingUrl: string }) => void)
      | undefined
    let permissionCheckHandler: ((_webContents: unknown, permission: string, requestingOrigin: string) => boolean) | undefined
    const sessionListeners = new Map<string, (...args: never[]) => void>()

    const webContents = {
      setWindowOpenHandler(handler: () => { action: 'deny' }) {
        windowHandler = handler
      },
      on(eventName: string, listener: (...args: never[]) => void) {
        listeners.set(eventName, listener)
      },
      session: {
        setPermissionCheckHandler(handler: typeof permissionCheckHandler) {
          permissionCheckHandler = handler
        },
        setPermissionRequestHandler(handler: typeof permissionHandler) {
          permissionHandler = handler
        },
        on(eventName: string, listener: (...args: never[]) => void) {
          sessionListeners.set(eventName, listener)
        },
      },
    } as unknown as WebContents

    installSecurityGuards(webContents, 'https://yumpoo.example.com')

    expect(windowHandler?.()).toEqual({ action: 'deny' })

    const navigationEvent = { preventDefault: vi.fn() }
    listeners.get('will-navigate')?.(
      navigationEvent as never,
      'https://attacker.example.com' as never,
    )
    expect(navigationEvent.preventDefault).toHaveBeenCalledOnce()

    const sameOriginEvent = { preventDefault: vi.fn() }
    listeners.get('will-navigate')?.(
      sameOriginEvent as never,
      'https://yumpoo.example.com/work' as never,
    )
    expect(sameOriginEvent.preventDefault).not.toHaveBeenCalled()

    const redirectEvent = { preventDefault: vi.fn() }
    listeners.get('will-redirect')?.(
      redirectEvent as never,
      'https://attacker.example.com' as never,
    )
    expect(redirectEvent.preventDefault).toHaveBeenCalledOnce()

    const webviewEvent = { preventDefault: vi.fn() }
    listeners.get('will-attach-webview')?.(webviewEvent as never)
    expect(webviewEvent.preventDefault).toHaveBeenCalledOnce()

    const permissionCallback = vi.fn()
    permissionHandler?.(undefined, 'clipboard-sanitized-write', permissionCallback, { requestingUrl: 'https://yumpoo.example.com/work' })
    permissionHandler?.(undefined, 'clipboard-sanitized-write', permissionCallback, { requestingUrl: 'https://attacker.example.com/' })
    permissionHandler?.(undefined, 'clipboard-read', permissionCallback, { requestingUrl: 'https://yumpoo.example.com/work' })
    expect(permissionCallback.mock.calls).toEqual([[true], [false], [false]])
    expect(permissionCheckHandler?.(undefined, 'clipboard-sanitized-write', 'https://yumpoo.example.com/')).toBe(true)
    expect(permissionCheckHandler?.(undefined, 'clipboard-sanitized-write', 'https://attacker.example.com')).toBe(false)
    expect(permissionCheckHandler?.(undefined, 'media', 'https://yumpoo.example.com')).toBe(false)

    const downloadEvent = { preventDefault: vi.fn() }
    sessionListeners.get('will-download')?.(downloadEvent as never)
    expect(downloadEvent.preventDefault).toHaveBeenCalledOnce()
  })

  it('证书错误全局失败关闭', () => {
    let certificateErrorHandler: ((...args: never[]) => void) | undefined
    const application = {
      on(eventName: string, listener: (...args: never[]) => void) {
        if (eventName === 'certificate-error') {
          certificateErrorHandler = listener
        }
      },
    } as unknown as App
    installAppSecurityGuards(application)

    const event = { preventDefault: vi.fn() }
    const callback = vi.fn()
    certificateErrorHandler?.(
      event as never,
      undefined as never,
      'https://yumpoo.example.com' as never,
      'certificate-error' as never,
      undefined as never,
      callback as never,
    )
    expect(event.preventDefault).toHaveBeenCalledOnce()
    expect(callback).toHaveBeenCalledWith(false)
  })
})
