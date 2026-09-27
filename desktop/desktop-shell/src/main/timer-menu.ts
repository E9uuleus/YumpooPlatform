import { app, BrowserWindow, ipcMain, screen, type IpcMainInvokeEvent } from 'electron'
import type { TimerMenuAction, TimerMenuState } from '@yumpoo/preload-contract'
import { isTrustedAuthIpcSender } from './auth-ipc'
import { createWindowOptions } from './window-policy'
import { installSecurityGuards } from './security-guards'
import { MENU, placeMenu } from './timer-geometry'

const ACTIONS = new Set<TimerMenuAction>(['open-main', 'open-inbox', 'find', 'settings', 'toggle', 'show-orb', 'show-dock', 'hide', 'toggle-pin', 'exit', 'close'])
const KEEP_OPEN = new Set<TimerMenuAction>(['toggle', 'show-orb', 'show-dock', 'hide', 'toggle-pin'])
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

  constructor(private readonly origin: string, private readonly preload: string, private readonly onAction: (action: TimerMenuAction) => void) {}

  install(): void {
    if (this.installed) return
    this.installed = true
    ipcMain.handle('yumpoo:timer:menu-action', (event, action: unknown) => {
      if (!this.isSender(event)) throw new Error('UNTRUSTED_IPC_SENDER')
      if (!validMenuAction(action)) throw new Error('INVALID_TIMER_MENU_ACTION')
      if (!KEEP_OPEN.has(action)) this.hide()
      this.onAction(action)
    })
  }

  isSender(event: IpcMainInvokeEvent): boolean { return isTrustedAuthIpcSender(event, this.window, this.origin) }

  isVisible(): boolean { return !!this.window && !this.window.isDestroyed() && this.window.isVisible() }

  prewarm(): void {
    if (this.window && !this.window.isDestroyed()) return
    const window = new BrowserWindow({ ...createWindowOptions(this.preload, app.isPackaged),
      width: MENU.width, height: MENU.height, minWidth: MENU.width, minHeight: MENU.height,
      frame: false, transparent: true, backgroundColor: '#00000000', hasShadow: false, thickFrame: false,
      resizable: false, movable: false, minimizable: false, maximizable: false, fullscreenable: false,
      skipTaskbar: true, type: 'toolbar', alwaysOnTop: true, title: 'YumpooPlatform · 快捷面板',
    })
    this.window = window
    this.ready = false
    window.setAlwaysOnTop(true, 'pop-up-menu')
    installSecurityGuards(window.webContents, this.origin)
    window.on('page-title-updated', event => event.preventDefault())
    window.on('focus', () => { this.focused = true })
    window.on('blur', () => { if (this.focused) this.hide() })
    window.on('closed', () => { if (this.window === window) { this.window = null; this.ready = false; clearInterval(this.watch) } })
    window.webContents.on('did-finish-load', () => { if (this.window === window) this.ready = true })
    window.webContents.on('render-process-gone', () => { if (this.window === window) { this.ready = false; window.destroy() } })
    window.loadURL(new URL('/timer/menu', this.origin).href).catch(() => {
      console.error('[YUMPOO_TIMER_MENU_LOAD_FAILED]')
      if (!window.isDestroyed()) window.destroy()
    })
  }

  /** Returns false while the panel is not loaded so the caller can fall back to the native menu. */
  open(kind: 'tray' | 'pointer', state: TimerMenuState): boolean {
    const window = this.window
    if (!window || window.isDestroyed() || !this.ready) { this.prewarm(); return false }
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
    setTimeout(() => { if (this.isVisible() && !window.isFocused()) window.focus() }, 0)
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
    if (this.isVisible()) this.window!.webContents.send('yumpoo:timer:menu-state', state)
  }

  hide(): void {
    if (!this.isVisible()) return
    clearInterval(this.watch)
    this.focused = false
    this.opener = null
    this.hiddenAt = Date.now()
    this.window!.hide()
  }
}
