import { describe, expect, it, vi } from 'vitest'
import {
  parseDesktopAuthCallback,
  ProtocolLaunchDispatcher,
  registerYumpooProtocolClient,
} from '../src/main/protocol-client'

const CODE = 'c'.repeat(43)
const STATE = 's'.repeat(43)
const CALLBACK = `yumpoo://auth/callback?code=${CODE}&state=${STATE}`

describe('yumpoo 协议策略', () => {
  it('只接受唯一、严格的认证回调', () => {
    expect(parseDesktopAuthCallback(CALLBACK)).toEqual({ code: CODE, state: STATE })
    for (const candidate of [
      `https://auth/callback?code=${CODE}&state=${STATE}`,
      `yumpoo://user@auth/callback?code=${CODE}&state=${STATE}`,
      `yumpoo://auth:123/callback?code=${CODE}&state=${STATE}`,
      `yumpoo://auth/other?code=${CODE}&state=${STATE}`,
      `${CALLBACK}#fragment`,
      `${CALLBACK}&next=https://attacker.example`,
      `${CALLBACK}&state=${STATE}`,
      `yumpoo://auth/callback?code=short&state=${STATE}`,
      `yumpoo://auth/callback?code=${CODE}&state=short`,
      `yumpoo://auth/callback?code=${CODE}`,
    ]) {
      expect(parseDesktopAuthCallback(candidate), candidate).toBeUndefined()
    }
  })

  it('在处理器就绪前排队冷启动参数，并拒绝歧义参数', () => {
    const dispatcher = new ProtocolLaunchDispatcher()
    const handler = vi.fn()
    expect(dispatcher.dispatch(['electron.exe', '.', CALLBACK])).toBe(true)
    expect(handler).not.toHaveBeenCalled()
    dispatcher.setHandler(handler)
    expect(handler).toHaveBeenCalledWith({ code: CODE, state: STATE })

    expect(dispatcher.dispatch([CALLBACK, CALLBACK])).toBe(false)
    expect(handler).toHaveBeenCalledOnce()
    expect(dispatcher.dispatch(['yumpoo://auth/not-callback'])).toBe(false)
  })

  it('packaged 与开发模式使用各自的协议注册参数', () => {
    const setAsDefaultProtocolClient = vi.fn(() => true)
    const application = { setAsDefaultProtocolClient }
    expect(
      registerYumpooProtocolClient(application, {
        isPackaged: true,
        executablePath: 'C:\\Yumpoo\\Yumpoo.exe',
        processArguments: ['C:\\Yumpoo\\Yumpoo.exe'],
      }),
    ).toBe(true)
    expect(setAsDefaultProtocolClient).toHaveBeenLastCalledWith('yumpoo')

    registerYumpooProtocolClient(application, {
      isPackaged: false,
      executablePath: 'C:\\Electron\\electron.exe',
      processArguments: ['C:\\Electron\\electron.exe', '.'],
    })
    expect(setAsDefaultProtocolClient).toHaveBeenLastCalledWith(
      'yumpoo',
      'C:\\Electron\\electron.exe',
      [expect.stringMatching(/desktop-shell$/)],
    )
  })
})
