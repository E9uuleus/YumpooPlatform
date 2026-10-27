import { app, BrowserWindow, ipcMain, screen, type IpcMainInvokeEvent } from 'electron'
import type { TimerMenuAction, TimerMenuState } from '@yumpoo/preload-contract'
import path from 'node:path'
import { MENU_PAGE_URL, LOCAL_PAGES_PARTITION, prepareLocalPagesSession } from './diagnostics-window'
import { createWindowOptions } from './window-policy'
import { MENU, placeMenu } from './timer-geometry'

const ACTIONS = new Set<TimerMenuAction>(['diagnostics', 'open-main', 'open-inbox', 'find', 'settings', 'toggle', 'show-orb', 'show-dock', 'hide', 'toggle-pin', 'preferences', 'exit', 'close', 'check-update', 'download-update', 'restart-update'])
const KEEP_OPEN = new Set<TimerMenuAction>(['toggle', 'show-orb', 'show-dock', 'hide', 'toggle-pin', 'check-update', 'download-update'])
export const validMenuAction = (value: unknown): value is TimerMenuAction => typeof value === 'string' && ACTIONS.has(value as TimerMenuAction)

/** A prewarmed popup that renders the shared quick panel for the tray and the compact timer surfaces. */
export class TimerQuickMenu {
  private window: BrowserWindow | null = null
  private ready = false
  private focused = false
  private hiddenAt = 0
  private openedAt = 0
  private opener: BrowserWindow | null = null
  private watch: ReturnType<typeof setInterval> | undefined
  private installed = false
  private stopped = false
  private failed = false
  private state: TimerMenuState | undefined
  private pending: 'tray' | 'pointer' | undefined
  private loading: ReturnType<typeof setTimeout> | undefined
  private focusTimer: ReturnType<typeof setTimeout> | undefined

  constructor(private readonly preload: string, private readonly onAction: (action: TimerMenuAction) => void,
    private readonly onFallback: (kind: 'tray' | 'pointer') => void = () => undefined) {}

  install(): void {
    if (this.installed) return
    this.installed = true
    ipcMain.handle('yumpoo:menu:get-state', event => {
      if (!this.isSender(event)) throw new Error('UNTRUSTED_IPC_SENDER')
      if (!this.state) throw new Error('MENU_STATE_UNAVAILABLE')
      return this.state
    })
    ipcMain.handle('yumpoo:menu:ready', event => {
      if (!this.isSender(event)) throw new Error('UNTRUSTED_IPC_SENDER')
      if (this.stopped) return
      clearTimeout(this.loading)
      this.ready = true
      const pending = this.pending
      this.pending = undefined
      if (pending && this.state) this.open(pending, this.state)
    })
    ipcMain.handle('yumpoo:timer:menu-action', (event, action: unknown) => {
      if (!this.isSender(event)) throw new Error('UNTRUSTED_IPC_SENDER')
      if (!validMenuAction(action)) throw new Error('INVALID_TIMER_MENU_ACTION')
      if (this.stopped) return
      if (!KEEP_OPEN.has(action)) this.hide()
      this.onAction(action)
    })
  }

  isSender(event: IpcMainInvokeEvent): boolean {
    const window = this.window
    return !!window && !window.isDestroyed() && event.sender === window.webContents
      && event.senderFrame === window.webContents.mainFrame && event.senderFrame?.url === MENU_PAGE_URL
  }

  isVisible(): boolean { return !!this.window && !this.window.isDestroyed() && this.window.isVisible() }

  stop(): void {
    if (this.stopped) return
    this.stopped = true
    this.pending = undefined
    clearTimeout(this.loading); clearTimeout(this.focusTimer); clearInterval(this.watch)
    if (this.window && !this.window.isDestroyed()) this.window.destroy()
  }

  prewarm(): void {
    if (this.stopped) return
    if (this.window && !this.window.isDestroyed()) return
    prepareLocalPagesSession(path.join(__dirname, '..', 'diagnostics'))
    const options = createWindowOptions(this.preload, app.isPackaged)
    const window = new BrowserWindow({ ...options,
      webPreferences: { ...options.webPreferences, partition: LOCAL_PAGES_PARTITION, spellcheck: false },
      width: MENU.width, height: MENU.height, minWidth: MENU.width, minHeight: MENU.height,
      frame: false, transparent: true, backgroundColor: '#00000000', hasShadow: false, thickFrame: false,
      resizable: false, movable: false, minimizable: false, maximizable: false, fullscreenable: false,
      skipTaskbar: true, type: 'toolbar', alwaysOnTop: true, title: 'YumpooPlatform · 快捷面板',
    })
    this.window = window
    this.ready = false
    this.failed = false
    window.setAlwaysOnTop(true, 'pop-up-menu')
    window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
    window.webContents.on('will-navigate', event => event.preventDefault())
    window.webContents.on('will-redirect', event => event.preventDefault())
    window.webContents.on('will-attach-webview', event => event.preventDefault())
    window.on('page-title-updated', event => event.preventDefault())
    window.on('focus', () => { this.focused = true })
    window.on('blur', () => { if (this.focused) this.hide() })
    window.on('closed', () => { if (this.window === window) { this.window = null; this.ready = false; clearInterval(this.watch) } })
    const failed = () => {
      if (this.stopped || this.window !== window) return
      this.failed = true
      this.ready = false
      clearTimeout(this.loading)
      const pending = this.pending
      this.pending = undefined
      if (!window.isDestroyed()) window.destroy()
      if (pending) this.onFallback(pending)
    }
    window.webContents.on('render-process-gone', failed)
    this.loading = setTimeout(failed, 10_000)
    void window.loadURL(MENU_PAGE_URL).catch((error: unknown) => {
      if (!this.stopped) console.error('[YUMPOO_TIMER_MENU_LOAD_FAILED]', error instanceof Error ? error.message : '')
      failed()
    })
  }

  /** Queue the first open until the packaged renderer is ready; only a local failure uses the native fallback. */
  open(kind: 'tray' | 'pointer', state: TimerMenuState): boolean {
    if (this.stopped) return true
    this.state = state
    const window = this.window
    if (!window || window.isDestroyed() || !this.ready) {
      const failed = this.failed
      this.prewarm()
      if (failed) return false
      this.pending = this.pending === kind ? undefined : kind
      return true
    }
    if (kind === 'tray' && (window.isVisible() || Date.now() - this.hiddenAt < 250)) { this.hide(); return true }
    const cursor = screen.getCursorScreenPoint()
    window.setBounds(placeMenu(kind, cursor, screen.getDisplayNearestPoint(cursor)))
    window.webContents.send('yumpoo:timer:menu-state', state)
    const focused = BrowserWindow.getFocusedWindow()
    this.opener = focused && focused !== window ? focused : null
    this.focused = false
    this.openedAt = Date.now()
    window.show()
    window.focus()
    // A right-click on a native drag region is still being handled by the opener, which can take activation back.
    this.focusTimer = setTimeout(() => { if (!this.stopped && this.isVisible() && !window.isFocused()) window.focus() }, 0)
    clearInterval(this.watch)
    this.watch = setInterval(() => this.dismissWhenOutside(), 120)
    return true
  }

  /**
   * Blur alone is not enough: when the panel never became the focused window, a click elsewhere only moves focus
   * away from the window that opened it, so the panel also closes once focus leaves both.
   */
  private dismissWhenOutside(): void {
    const window = this.window
    if (!window || window.isDestroyed() || !window.isVisible()) { clearInterval(this.watch); return }
    const focused = BrowserWindow.getFocusedWindow()
    if (focused === window) { this.focused = true; return }
    if (this.focused || (Date.now() - this.openedAt > 300 && focused !== this.opener)) this.hide()
  }

  update(state: TimerMenuState): void {
    if (this.stopped) return
    this.state = state
    if (this.isVisible()) this.window!.webContents.send('yumpoo:timer:menu-state', state)
  }

  hide(): void {
    this.pending = undefined
    clearTimeout(this.focusTimer)
    if (!this.isVisible()) return
    clearInterval(this.watch)
    this.focused = false
    this.opener = null
    this.hiddenAt = Date.now()
    this.window!.hide()
  }
}
