import type { DesktopUpdateState } from '@yumpoo/preload-contract'
import type { BrowserWindow, IpcMainInvokeEvent, Session } from 'electron'
import { describe, expect, it, vi } from 'vitest'
import { installClientHeaders, installUpdateIpc } from '../src/main/update-ipc'

const origin = 'https://yumpoo.example.com'

function fixture(phase: DesktopUpdateState['phase']) {
  const frame = { url: `${origin}/workspace` }
  const webContents = { mainFrame: frame }
  const mainWindow = { isDestroyed: () => false, webContents } as unknown as BrowserWindow
  const handlers = new Map<string, (event: IpcMainInvokeEvent) => unknown>()
  const check = vi.fn()
  const restart = vi.fn()
  installUpdateIpc({
    ipcMain: { handle: (channel: string, listener: never) => { handlers.set(channel, listener) } } as never,
    getMainWindow: () => mainWindow,
    allowedOrigin: origin,
    getState: () => ({ phase, currentVersion: '1.0.0', channel: 'stable' }),
    check,
    restart,
  })
  const trusted = { sender: webContents, senderFrame: frame } as unknown as IpcMainInvokeEvent
  const foreign = { sender: webContents, senderFrame: { url: 'https://evil.example.com/' } } as unknown as IpcMainInvokeEvent
  return { handlers, check, restart, trusted, foreign }
}

describe('更新 IPC', () => {
  it('只信任主窗口主框架', () => {
    const { handlers, check, trusted, foreign } = fixture('idle')
    expect(handlers.get('yumpoo:update:get-state')?.(trusted)).toMatchObject({ phase: 'idle', currentVersion: '1.0.0' })
    handlers.get('yumpoo:update:check')?.(trusted)
    expect(check).toHaveBeenCalledOnce()
    expect(() => handlers.get('yumpoo:update:get-state')?.(foreign)).toThrow('UNTRUSTED_IPC_SENDER')
    expect(() => handlers.get('yumpoo:update:check')?.(foreign)).toThrow('UNTRUSTED_IPC_SENDER')
  })

  it('只有就绪时才能重启以更新', () => {
    const idle = fixture('idle')
    expect(() => idle.handlers.get('yumpoo:update:restart')?.(idle.trusted)).toThrow('UPDATE_NOT_READY')
    expect(idle.restart).not.toHaveBeenCalled()
    const ready = fixture('ready')
    ready.handlers.get('yumpoo:update:restart')?.(ready.trusted)
    expect(ready.restart).toHaveBeenCalledOnce()
  })
})

describe('客户端版本请求头', () => {
  function capture(configuredOrigin: string) {
    let filter: { urls: string[] } | undefined
    let listener: ((details: { url: string; requestHeaders: Record<string, string> }, callback: (response: unknown) => void) => void) | undefined
    const session = { webRequest: { onBeforeSendHeaders: (f: typeof filter, l: typeof listener) => { filter = f; listener = l } } } as unknown as Session
    installClientHeaders(session, configuredOrigin, '1.2.3')
    const send = (url: string) => {
      let response: { requestHeaders: Record<string, string> } | undefined
      listener?.({ url, requestHeaders: { Accept: 'application/json' } }, value => { response = value as typeof response })
      return response?.requestHeaders
    }
    return { filter, send }
  }

  it('只给本 origin 的请求加客户端类型、版本和协议版本', () => {
    const { filter, send } = capture(origin)
    expect(filter).toEqual({ urls: ['https://yumpoo.example.com/*'] })
    expect(send(`${origin}/api/v1/auth/me`)).toEqual({
      Accept: 'application/json', 'X-Client-Type': 'ELECTRON', 'X-Client-Version': '1.2.3', 'X-Client-Protocol-Version': '1',
    })
  })

  it('带端口的 origin 也按完整 origin 判断', () => {
    const { filter, send } = capture('http://127.0.0.1:18173')
    expect(filter).toEqual({ urls: ['http://127.0.0.1/*'] })
    expect(send('http://127.0.0.1:18173/api/v1/auth/me')).toHaveProperty('X-Client-Version', '1.2.3')
    expect(send('http://127.0.0.1:9999/other')).toEqual({ Accept: 'application/json' })
  })
})
