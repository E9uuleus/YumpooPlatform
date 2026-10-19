import type { DiagnosticsAction, DiagnosticsActionResult, DiagnosticsReport } from '@yumpoo/preload-contract'
import { BrowserWindow, session, type IpcMain, type IpcMainInvokeEvent, type NativeImage, type Protocol } from 'electron'
import { readFile } from 'node:fs/promises'
import path from 'node:path'

export const DIAGNOSTICS_REPORT_CHANNEL = 'yumpoo:diagnostics:report'
export const DIAGNOSTICS_ACTION_CHANNEL = 'yumpoo:diagnostics:action'
export const DIAGNOSTICS_SCHEME = 'yumpoo-diagnostics'
const DIAGNOSTICS_ORIGIN = `${DIAGNOSTICS_SCHEME}://app`
export const DIAGNOSTICS_PAGE_URL = `${DIAGNOSTICS_ORIGIN}/diagnostics.html`
/** The launch screen shown over the main window until the web app has loaded; it has no preload and no script. */
export const LAUNCH_PAGE_URL = `${DIAGNOSTICS_ORIGIN}/launch.html`
/** Shared by the diagnostics window and the launch screen: both show only packaged pages. */
export const LOCAL_PAGES_PARTITION = 'yumpoo-diagnostics'
const DIAGNOSTICS_ASSETS: ReadonlyMap<string, string> = new Map([
  ['/diagnostics.html', 'text/html; charset=utf-8'],
  ['/diagnostics.css', 'text/css; charset=utf-8'],
  ['/page.js', 'text/javascript; charset=utf-8'],
  ['/launch.html', 'text/html; charset=utf-8'],
  ['/launch.css', 'text/css; charset=utf-8'],
  ['/mark.png', 'image/png'],
])
const DIAGNOSTICS_ACTIONS: ReadonlySet<string> = new Set<DiagnosticsAction>([
  'retry', 'restart-normal', 'open-web', 'reset-preferences', 'sign-out', 'register-protocol', 'open-logs', 'export',
])

export type DiagnosticsActionHandlers = Record<DiagnosticsAction, (window: BrowserWindow) => Promise<DiagnosticsActionResult>>

/**
 * The page is served from a privileged custom scheme rather than file:, which the hardened fuses deny
 * inside app.asar; a standard secure scheme also gives the page a real origin for its CSP. Must run
 * before the app is ready.
 */
export function registerDiagnosticsScheme(protocol: Pick<Protocol, 'registerSchemesAsPrivileged'>): void {
  protocol.registerSchemesAsPrivileged([{ scheme: DIAGNOSTICS_SCHEME, privileges: { standard: true, secure: true } }])
}

/** Maps a request to one of the packaged local-page files; anything else is not found. */
export function resolveDiagnosticsAsset(requestUrl: string): { file: string; contentType: string } | undefined {
  let url: URL
  try {
    url = new URL(requestUrl)
  } catch {
    return undefined
  }
  const contentType = DIAGNOSTICS_ASSETS.get(url.pathname)
  // WHATWG URL reports a "null" origin for custom schemes, so protocol and host are compared instead.
  return url.protocol === `${DIAGNOSTICS_SCHEME}:` && url.host === 'app' && !url.username && !url.password && contentType
    ? { file: url.pathname.slice(1), contentType } : undefined
}

export function isDiagnosticsAction(value: unknown): value is DiagnosticsAction {
  return typeof value === 'string' && DIAGNOSTICS_ACTIONS.has(value)
}

/** Only the packaged diagnostics page in its own window's main frame may call the diagnostics channels. */
export function isTrustedDiagnosticsSender(event: Pick<IpcMainInvokeEvent, 'sender' | 'senderFrame'>, window: BrowserWindow | null): boolean {
  return !!window && !window.isDestroyed()
    && event.sender === window.webContents
    && event.senderFrame === window.webContents.mainFrame
    && event.senderFrame?.url === DIAGNOSTICS_PAGE_URL
}

let localPagesPrepared = false

/**
 * Locks down the local-pages session once: every permission and download refused, only the packaged files
 * reachable, served by the privileged scheme registered on this partition alone.
 */
export function prepareLocalPagesSession(pageDirectory: string): void {
  if (localPagesPrepared) return
  localPagesPrepared = true
  const localSession = session.fromPartition(LOCAL_PAGES_PARTITION)
  localSession.setPermissionCheckHandler(() => false)
  localSession.setPermissionRequestHandler((_contents, _permission, callback) => callback(false))
  localSession.on('will-download', event => event.preventDefault())
  // Only the local-pages scheme is reachable from this session; every other request is refused.
  localSession.webRequest.onBeforeRequest((details, callback) => {
    callback({ cancel: !details.url.startsWith(`${DIAGNOSTICS_ORIGIN}/`) && !details.url.startsWith('devtools:') })
  })
  localSession.protocol.handle(DIAGNOSTICS_SCHEME, async request => {
    const asset = resolveDiagnosticsAsset(request.url)
    if (!asset) return new Response(null, { status: 404 })
    const body = await readFile(path.join(pageDirectory, asset.file))
    return new Response(body, { headers: { 'content-type': asset.contentType, 'cache-control': 'no-store' } })
  })
}

export interface DiagnosticsWindowOptions {
  /** Directory holding the packaged local pages (diagnostics.html, page.js, launch.html, …). */
  readonly pageDirectory: string
  readonly preloadPath: string
  readonly icon: () => NativeImage
  readonly isPackaged: boolean
  readonly collect: () => Promise<DiagnosticsReport>
  readonly actions: DiagnosticsActionHandlers
  readonly onAction?: (action: DiagnosticsAction, result: DiagnosticsActionResult | Error) => void
  readonly onClosed?: () => void
}

/**
 * A local window that works without the remote web app: it loads only the packaged page, runs in its own
 * in-memory session with every permission, download and navigation refused, and talks to main through a
 * dedicated minimal preload.
 */
export class DiagnosticsWindow {
  private window: BrowserWindow | null = null

  constructor(private readonly options: DiagnosticsWindowOptions) {}

  install(ipcMain: Pick<IpcMain, 'handle'>): void {
    ipcMain.handle(DIAGNOSTICS_REPORT_CHANNEL, event => {
      this.assertTrusted(event)
      return this.options.collect()
    })
    ipcMain.handle(DIAGNOSTICS_ACTION_CHANNEL, async (event, action: unknown) => {
      this.assertTrusted(event)
      if (!isDiagnosticsAction(action) || !this.window) throw new Error('INVALID_DIAGNOSTICS_ACTION')
      try {
        const result = await this.options.actions[action](this.window)
        this.options.onAction?.(action, result)
        return result
      } catch (error) {
        this.options.onAction?.(action, error instanceof Error ? error : new Error('FAILED'))
        return { completed: false, message: '操作失败，详情已写入日志。' }
      }
    })
  }

  /** Opens or focuses the window; resolves once the page finished loading. */
  async open(show = true): Promise<BrowserWindow> {
    if (this.window && !this.window.isDestroyed()) {
      if (show) {
        if (this.window.isMinimized()) this.window.restore()
        this.window.show()
        this.window.focus()
      }
      return this.window
    }
    prepareLocalPagesSession(this.options.pageDirectory)
    const window = new BrowserWindow({
      width: 760,
      height: 720,
      minWidth: 560,
      minHeight: 480,
      title: 'Yumpoo Desktop 诊断与修复',
      icon: this.options.icon(),
      autoHideMenuBar: true,
      backgroundColor: '#f4f6f8',
      show: false,
      webPreferences: {
        preload: this.options.preloadPath,
        contextIsolation: true,
        sandbox: true,
        nodeIntegration: false,
        webSecurity: true,
        webviewTag: false,
        allowRunningInsecureContent: false,
        devTools: !this.options.isPackaged,
        partition: LOCAL_PAGES_PARTITION,
        spellcheck: false,
      },
    })
    this.window = window
    window.removeMenu()
    const contents = window.webContents
    contents.setWindowOpenHandler(() => ({ action: 'deny' }))
    contents.on('will-navigate', event => event.preventDefault())
    contents.on('will-redirect', event => event.preventDefault())
    contents.on('will-attach-webview', event => event.preventDefault())
    window.on('closed', () => {
      if (this.window === window) this.window = null
      this.options.onClosed?.()
    })
    if (show) window.once('ready-to-show', () => window.show())
    await window.loadURL(DIAGNOSTICS_PAGE_URL)
    return window
  }

  close(): void {
    if (this.window && !this.window.isDestroyed()) this.window.close()
  }

  private assertTrusted(event: IpcMainInvokeEvent): void {
    if (!isTrustedDiagnosticsSender(event, this.window)) throw new Error('UNTRUSTED_IPC_SENDER')
  }
}
