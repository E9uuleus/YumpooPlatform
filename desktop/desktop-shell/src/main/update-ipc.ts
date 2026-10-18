import type { DesktopUpdateState } from '@yumpoo/preload-contract'
import type { BrowserWindow, IpcMain, Session } from 'electron'
import { isTrustedAuthIpcSender } from './auth-ipc'

export const UPDATE_STATE_CHANNEL = 'yumpoo:update:state'
export const CLIENT_PROTOCOL_VERSION = '1'

export interface UpdateIpcOptions {
  readonly ipcMain: Pick<IpcMain, 'handle'>
  readonly getMainWindow: () => BrowserWindow | null
  readonly allowedOrigin: string
  getState(): DesktopUpdateState
  check(): void
  /** Starts the normal quit (with its timer confirmation); the verified update installs on the way out. */
  restart(): void
}

/** Only the main window's top frame on the configured origin may read or drive update state. */
export function installUpdateIpc(options: UpdateIpcOptions): void {
  const trusted = (event: Parameters<typeof isTrustedAuthIpcSender>[0]) => {
    if (!isTrustedAuthIpcSender(event, options.getMainWindow(), options.allowedOrigin)) throw new Error('UNTRUSTED_IPC_SENDER')
  }
  options.ipcMain.handle('yumpoo:update:get-state', event => {
    trusted(event)
    return options.getState()
  })
  options.ipcMain.handle('yumpoo:update:check', event => {
    trusted(event)
    options.check()
  })
  options.ipcMain.handle('yumpoo:update:restart', event => {
    trusted(event)
    if (options.getState().phase !== 'ready') throw new Error('UPDATE_NOT_READY')
    options.restart()
  })
}

/**
 * Every request the shell makes to its own origin identifies the client, so the server can report version
 * distribution and later refuse versions below its minimum. Other origins never see these headers.
 */
export function installClientHeaders(session: Pick<Session, 'webRequest'>, origin: string, version: string): void {
  const { protocol, hostname } = new URL(origin)
  // Match patterns cannot express a port, so the filter narrows by host and the exact origin is checked here.
  session.webRequest.onBeforeSendHeaders({ urls: [`${protocol}//${hostname}/*`] }, (details, callback) => {
    let sameOrigin = false
    try {
      sameOrigin = new URL(details.url).origin === origin
    } catch {
      sameOrigin = false
    }
    callback({
      requestHeaders: sameOrigin
        ? {
            ...details.requestHeaders,
            'X-Client-Type': 'ELECTRON',
            'X-Client-Version': version,
            'X-Client-Protocol-Version': CLIENT_PROTOCOL_VERSION,
          }
        : details.requestHeaders,
    })
  })
}
