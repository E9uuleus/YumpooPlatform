import type { WebContents } from 'electron'
import { describe, expect, it, vi } from 'vitest'
import { installSecurityGuards } from '../src/main/security-guards'

describe('Electron 导航与权限门禁', () => {
  it('拒绝新窗口、权限、WebView 和跨源导航', () => {
    const listeners = new Map<string, (...args: never[]) => void>()
    let windowHandler: (() => { action: 'deny' }) | undefined
    let permissionHandler:
      | ((_webContents: unknown, _permission: unknown, callback: (allowed: boolean) => void) => void)
      | undefined

    const webContents = {
      setWindowOpenHandler(handler: () => { action: 'deny' }) {
        windowHandler = handler
      },
      on(eventName: string, listener: (...args: never[]) => void) {
        listeners.set(eventName, listener)
      },
      session: {
        setPermissionRequestHandler(handler: typeof permissionHandler) {
          permissionHandler = handler
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

    const webviewEvent = { preventDefault: vi.fn() }
    listeners.get('will-attach-webview')?.(webviewEvent as never)
    expect(webviewEvent.preventDefault).toHaveBeenCalledOnce()

    const permissionCallback = vi.fn()
    permissionHandler?.(undefined, undefined, permissionCallback)
    expect(permissionCallback).toHaveBeenCalledWith(false)
  })
})
