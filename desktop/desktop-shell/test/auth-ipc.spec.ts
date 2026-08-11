import type { BrowserWindow, IpcMainInvokeEvent } from 'electron'
import { describe, expect, it, vi } from 'vitest'
import {
  AUTH_IS_ENABLED_CHANNEL,
  AUTH_START_CHANNEL,
  installAuthIpc,
  isTrustedAuthIpcSender,
} from '../src/main/auth-ipc'
import type { DesktopAuthController } from '../src/main/desktop-auth'

function trustedFixture(origin = 'https://yumpoo.example.com') {
  const frame = { url: `${origin}/work` }
  const webContents = { mainFrame: frame }
  const mainWindow = {
    isDestroyed: () => false,
    webContents,
  } as unknown as BrowserWindow
  const event = {
    sender: webContents,
    senderFrame: frame,
  } as unknown as IpcMainInvokeEvent
  return { event, frame, mainWindow, webContents }
}

describe('Electron 认证 IPC 门禁', () => {
  it('要求当前窗口、main frame 和精确 SPA origin', () => {
    const fixture = trustedFixture()
    expect(
      isTrustedAuthIpcSender(
        fixture.event,
        fixture.mainWindow,
        'https://yumpoo.example.com',
      ),
    ).toBe(true)
    expect(
      isTrustedAuthIpcSender(
        { ...fixture.event, senderFrame: { url: fixture.frame.url } } as never,
        fixture.mainWindow,
        'https://yumpoo.example.com',
      ),
    ).toBe(false)
    expect(
      isTrustedAuthIpcSender(
        { ...fixture.event, senderFrame: { url: 'https://attacker.example' } } as never,
        fixture.mainWindow,
        'https://yumpoo.example.com',
      ),
    ).toBe(false)
  })

  it('固定注册 isEnabled/start 并拒绝伪造 sender', async () => {
    const handlers = new Map<string, (event: IpcMainInvokeEvent) => unknown>()
    const controller = {
      isEnabled: vi.fn(() => true),
      start: vi.fn(async () => undefined),
    } as unknown as DesktopAuthController
    const fixture = trustedFixture()
    installAuthIpc({
      ipcMain: {
        handle(channel, handler) {
          handlers.set(channel, handler)
        },
      },
      getMainWindow: () => fixture.mainWindow,
      allowedOrigin: 'https://yumpoo.example.com',
      controller,
    })

    expect(handlers.get(AUTH_IS_ENABLED_CHANNEL)?.(fixture.event)).toBe(true)
    await handlers.get(AUTH_START_CHANNEL)?.(fixture.event)
    expect(controller.start).toHaveBeenCalledOnce()

    const forgedEvent = {
      ...fixture.event,
      sender: {},
    } as unknown as IpcMainInvokeEvent
    expect(() => handlers.get(AUTH_IS_ENABLED_CHANNEL)?.(forgedEvent)).toThrow(
      'UNTRUSTED_IPC_SENDER',
    )
  })
})
