import { app, BrowserWindow, ipcMain, screen, type IpcMainInvokeEvent } from 'electron'
import type { QuickPanelContext, RailProject, TimerDockSide } from '@yumpoo/preload-contract'
import { isTrustedAuthIpcSender } from './auth-ipc'
import { createWindowOptions } from './window-policy'
import { installSecurityGuards } from './security-guards'
import { loadSurface } from './surface-load'
import { clampRect, placeBeside, placeMenu, QUICK_PANEL, QUICK_PANEL_MAX, resizeFrom, taskbarEdge, type Rect } from './timer-geometry'
import type { TimerPreferenceStore } from './timer-preferences'

const CLOSE_MS = 180
/** Pages the panel may open in the main window: the settings page, or one day of My Day. */
const MAIN_PATH = /^\/(?:settings(?:\?section=rail)?|workspace\/[a-z0-9._@-]{1,64}\/my-time\/day\/\d{4}-\d{2}-\d{2})$/

export type QuickPanelRequest = { source: 'tray' | 'shortcut' } | { source: 'rail'; projectId: string; anchor: Rect; side: TimerDockSide }

export interface QuickPanelHost {
  level(): { pinned: boolean; aboveTaskbar: boolean }
  openMain(path?: string): void
  /** The panel opened, closed or switched project; the rail stays out while the panel is open for one of its projects. */
  changed(): void
}

/** The tray's quick time panel: a prewarmed popup like TimerQuickMenu that renders `/timer/quick`. */
export class QuickPanelController {
  private window: BrowserWindow | null = null
  private ready = false
  private focused = false
  private hiddenAt = 0
  private openedAt = 0
  private opener: BrowserWindow | null = null
  private watch: ReturnType<typeof setInterval> | undefined
  private closeTimer: ReturnType<typeof setTimeout> | undefined
  private projectId: string | null = null
  private source: QuickPanelRequest['source'] = 'tray'
  private installed = false
  private resizeTimer: ReturnType<typeof setInterval> | undefined
  private resizeGuard: ReturnType<typeof setTimeout> | undefined

  constructor(private readonly origin: string, private readonly preload: string, private readonly prefs: TimerPreferenceStore, private readonly host: QuickPanelHost) {}

  install(): void {
    if (this.installed) return
    this.installed = true
    ipcMain.handle('yumpoo:quick:close', event => {
      if (!this.isSender(event)) throw new Error('UNTRUSTED_IPC_SENDER')
      this.hide()
    })
    ipcMain.handle('yumpoo:quick:open-main', (event, path: unknown) => {
      if (!this.isSender(event)) throw new Error('UNTRUSTED_IPC_SENDER')
      if (path !== undefined && (typeof path !== 'string' || !MAIN_PATH.test(path))) throw new Error('INVALID_QUICK_PATH')
      this.hide()
      this.host.openMain(path)
    })
    ipcMain.handle('yumpoo:quick:pin', (event, pinned: unknown) => {
      if (!this.isSender(event)) throw new Error('UNTRUSTED_IPC_SENDER')
      if (typeof pinned !== 'boolean') throw new Error('INVALID_QUICK_PIN')
      this.setPinned(pinned)
    })
    ipcMain.handle('yumpoo:quick:resize', (event, active: unknown) => {
      if (!this.isSender(event)) throw new Error('UNTRUSTED_IPC_SENDER')
      if (typeof active !== 'boolean') throw new Error('INVALID_QUICK_RESIZE')
      if (active) { if (!this.pinned()) this.setPinned(true); this.followCursor() }
      else this.endResize()
    })
    app.on('will-quit', () => { clearInterval(this.watch); clearTimeout(this.closeTimer); this.endResize() })
  }

  isSender(event: IpcMainInvokeEvent): boolean { return isTrustedAuthIpcSender(event, this.window, this.origin) }

  /** Visible and not closing. */
  isVisible(): boolean { return !!this.window && !this.window.isDestroyed() && this.window.isVisible() && !this.closeTimer }

  /** The rail project the open panel shows, so the rail does not tuck away beneath it. */
  railProject(): string | null { return !this.pinned() && this.isVisible() && this.source === 'rail' ? this.projectId : null }

  private pinned(): boolean { return this.prefs.get().quickPinned }

  applyLevel(): void {
    const window = this.window
    if (!window || window.isDestroyed()) return
    if (!this.pinned()) window.setAlwaysOnTop(true, 'pop-up-menu')
    else {
      const { pinned, aboveTaskbar } = this.host.level()
      if (aboveTaskbar && pinned) window.setAlwaysOnTop(true, 'screen-saver')
      else window.setAlwaysOnTop(pinned)
    }
  }

  setPinned(value: boolean): void {
    const window = this.window
    this.prefs.update({ quickPinned: value, ...(value && window ? { quickBounds: window.getBounds() } : {}) })
    this.applyLevel()
    window?.webContents.send('yumpoo:quick:pinned', value)
    this.host.changed()
    clearInterval(this.watch)
    if (!value && window?.isVisible()) {
      this.focused = window.isFocused()
      this.watch = setInterval(() => this.dismissWhenOutside(), 120)
    }
  }

  private saveBounds(): void {
    if (this.pinned() && this.window && !this.window.isDestroyed()) this.prefs.update({ quickBounds: this.window.getBounds() })
  }

  private followCursor(): void {
    const window = this.window
    if (!window || window.isDestroyed() || this.resizeTimer) return
    const start = window.getBounds(), cursor = screen.getCursorScreenPoint()
    this.resizeTimer = setInterval(() => {
      if (window.isDestroyed()) { this.endResize(); return }
      const point = screen.getCursorScreenPoint()
      window.setBounds(resizeFrom(start, point.x - cursor.x, point.y - cursor.y, screen.getDisplayMatching(start).workArea))
    }, 16)
    this.resizeGuard = setTimeout(() => this.endResize(), 60_000)
  }

  private endResize(): void {
    clearInterval(this.resizeTimer); clearTimeout(this.resizeGuard)
    this.resizeTimer = undefined
    this.saveBounds()
  }

  prewarm(): void {
    if (this.window && !this.window.isDestroyed()) return
    const size = this.prefs.get().quickBounds ?? QUICK_PANEL
    const window = new BrowserWindow({ ...createWindowOptions(this.preload, app.isPackaged),
      width: size.width, height: size.height, minWidth: QUICK_PANEL.width, minHeight: QUICK_PANEL.height,
      maxWidth: QUICK_PANEL_MAX.width, maxHeight: QUICK_PANEL_MAX.height,
      frame: false, transparent: true, backgroundColor: '#00000000', hasShadow: false, thickFrame: false,
      resizable: false, movable: true, minimizable: false, maximizable: false, fullscreenable: false,
      skipTaskbar: true, type: 'toolbar', alwaysOnTop: true, title: 'YumpooPlatform · 快捷工时',
    })
    this.window = window
    this.ready = false
    this.applyLevel()
    installSecurityGuards(window.webContents, this.origin)
    window.on('page-title-updated', event => event.preventDefault())
    window.on('focus', () => { this.focused = true })
    window.on('blur', () => { if (this.focused && !this.pinned()) this.hide() })
    window.on('will-move', () => { if (!this.pinned()) this.setPinned(true) })
    window.on('moved', () => this.saveBounds())
    window.on('closed', () => {
      if (this.window !== window) return
      this.endResize()
      this.window = null; this.ready = false; clearInterval(this.watch); clearTimeout(this.closeTimer); this.closeTimer = undefined
      this.host.changed()
    })
    window.webContents.on('did-finish-load', () => { if (this.window === window) this.ready = true })
    window.webContents.on('render-process-gone', () => { if (this.window === window) { this.ready = false; window.destroy() } })
    loadSurface(window, new URL('/timer/quick', this.origin).href).catch((error: unknown) => {
      console.error('[YUMPOO_QUICK_PANEL_LOAD_FAILED]', error instanceof Error ? error.message : '')
      if (!window.isDestroyed()) window.destroy()
    })
  }

  /** Signing out or switching account reloads the page from scratch the next time it is prewarmed. */
  destroy(): void {
    const window = this.window
    if (window && !window.isDestroyed()) window.destroy()
  }

  /**
   * Opens at the taskbar corner, or beside the rail for one project. A second trigger for the same target within
   * 250ms of closing closes it, because the click that triggers it has already blurred the panel. Returns false
   * while the page is not loaded so the caller can show the main window instead.
   */
  toggle(request: QuickPanelRequest, projects: RailProject[]): boolean {
    const window = this.window
    if (!window || window.isDestroyed() || !this.ready) { this.prewarm(); return false }
    const projectId = request.source === 'rail' ? request.projectId : null
    const pinned = this.pinned()
    if (pinned && this.isVisible()) {
      if (request.source === 'rail' && projectId !== this.projectId) {
        this.projectId = projectId
        this.source = request.source
        window.webContents.send('yumpoo:quick:open', { projectId, projects, origin: request.side, pinned: true } satisfies QuickPanelContext)
      }
      window.show(); window.focus()
      return true
    }
    if (!pinned && (this.isVisible() || Date.now() - this.hiddenAt < 250) && projectId === this.projectId) { this.hide(); return true }
    const cursor = screen.getCursorScreenPoint()
    const saved = this.prefs.get().quickBounds
    const bounds = saved ?? window.getBounds()
    const display = pinned ? screen.getDisplayMatching(bounds)
      : request.source === 'rail' ? screen.getDisplayMatching(request.anchor) : screen.getDisplayNearestPoint(cursor)
    window.setBounds(pinned ? clampRect(bounds, display.workArea)
      : request.source === 'rail' ? placeBeside(request.anchor, request.side, display.workArea, saved ?? QUICK_PANEL)
      : placeMenu(request.source === 'tray' ? 'tray' : 'corner', cursor, display, saved ?? QUICK_PANEL))
    clearTimeout(this.closeTimer)
    this.closeTimer = undefined
    this.projectId = projectId
    this.source = request.source
    const context: QuickPanelContext = { projectId, projects, pinned, origin: !pinned && request.source === 'rail' ? request.side : taskbarEdge(display) }
    window.webContents.send('yumpoo:quick:open', context)
    const focused = BrowserWindow.getFocusedWindow()
    this.opener = focused && focused !== window ? focused : null
    this.focused = false
    this.openedAt = Date.now()
    window.show()
    window.focus()
    setTimeout(() => { if (this.isVisible() && !window.isFocused()) window.focus() }, 0)
    clearInterval(this.watch)
    if (!pinned) this.watch = setInterval(() => this.dismissWhenOutside(), 120)
    this.host.changed()
    return true
  }

  /** Same rule as TimerQuickMenu: close once focus has left both the panel and the window that opened it. */
  private dismissWhenOutside(): void {
    const window = this.window
    if (!window || window.isDestroyed() || !this.isVisible()) { clearInterval(this.watch); return }
    const focused = BrowserWindow.getFocusedWindow()
    if (focused === window) { this.focused = true; return }
    if (this.focused || (Date.now() - this.openedAt > 300 && focused !== this.opener)) this.hide()
  }

  hide(): void {
    if (!this.isVisible()) return
    const window = this.window!
    clearInterval(this.watch)
    this.focused = false
    this.opener = null
    this.hiddenAt = Date.now()
    window.webContents.send('yumpoo:quick:closing')
    this.closeTimer = setTimeout(() => {
      this.closeTimer = undefined
      if (!window.isDestroyed()) window.hide()
      this.host.changed()
    }, CLOSE_MS)
    this.host.changed()
  }
}
