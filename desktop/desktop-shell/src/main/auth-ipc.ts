import type { DesktopAuthController } from './desktop-auth'
import type { BrowserWindow, IpcMain, IpcMainInvokeEvent } from 'electron'

export const AUTH_IS_ENABLED_CHANNEL = 'yumpoo:auth:is-enabled'
export const AUTH_START_CHANNEL = 'yumpoo:auth:start'
export const AUTH_STATUS_CHANNEL = 'yumpoo:auth:status'

export interface AuthIpcOptions {
  readonly ipcMain: Pick<IpcMain, 'handle'>
  readonly getMainWindow: () => BrowserWindow | null
  readonly allowedOrigin: string
  readonly controller: DesktopAuthController
}

export function isTrustedAuthIpcSender(
  event: IpcMainInvokeEvent,
  mainWindow: BrowserWindow | null,
  allowedOrigin: string,
): boolean {
  if (
    !mainWindow ||
    mainWindow.isDestroyed() ||
    event.sender !== mainWindow.webContents ||
    event.senderFrame !== mainWindow.webContents.mainFrame
  ) {
    return false
  }

  try {
    return new URL(event.senderFrame.url).origin === allowedOrigin
  } catch {
    return false
  }
}

export function installAuthIpc(options: AuthIpcOptions): void {
  function assertTrusted(event: IpcMainInvokeEvent): void {
    if (
      !isTrustedAuthIpcSender(
        event,
        options.getMainWindow(),
        options.allowedOrigin,
      )
    ) {
      throw new Error('UNTRUSTED_IPC_SENDER')
    }
  }

  options.ipcMain.handle(AUTH_IS_ENABLED_CHANNEL, (event) => {
    assertTrusted(event)
    return options.controller.isEnabled()
  })
  options.ipcMain.handle(AUTH_START_CHANNEL, async (event) => {
    assertTrusted(event)
    await options.controller.start()
  })
}
