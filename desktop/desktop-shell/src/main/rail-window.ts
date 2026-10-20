import { app, BrowserWindow, ipcMain, screen, type IpcMainInvokeEvent } from 'electron'
import type { RailState, TimerDockSide } from '@yumpoo/preload-contract'
import { isTrustedAuthIpcSender } from './auth-ipc'
import { createWindowOptions } from './window-policy'
import { installSecurityGuards } from './security-guards'
import { applicationIcon } from './application-icon'
import { validRailProjects, type DesktopPreferenceStore } from './desktop-preferences'
import type { TimerPreferenceStore } from './timer-preferences'
import { hitTest, placeRail, railShape, snapDock, type Rect } from './timer-geometry'

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const SLIDE_MS = 300
const TUCK_DELAY_MS = 450

export interface RailHost {
  signedIn(): boolean
  /** Whether the top slot shows the timer. */
  timer(): boolean
  pinned(): boolean
  /** Project whose quick panel is open from the rail; the rail stays out meanwhile. */
  quickProject(): string | null
  openProject(projectId: string, anchor: Rect, side: TimerDockSide): void
  openSettings(): void
  /** Right-clicks open the timer quick menu at the pointer, as on the orb. */
  openMenu(): void
}

/**
 * The project rail: a narrow, transparent window against a screen edge. Auto-hide only changes the native shape and a
 * CSS slide; the window keeps its bounds. Side and vertical position are the timer's `dockSide` and `dockY`.
 */
export class RailController {
  private window: BrowserWindow | null = null
  private shape: Rect[] = []
  private rail: Rect = { x: 0, y: 0, width: 0, height: 0 }
  private tucked = true
  private hovered = false
  private dragging = false
  private ready = false
  private hoverTimer: ReturnType<typeof setInterval> | undefined
  private tuckTimer: ReturnType<typeof setTimeout> | undefined
  private shapeTimer: ReturnType<typeof setTimeout> | undefined
  private reframeTimer: ReturnType<typeof setTimeout> | undefined
  private installed = false

  constructor(private readonly origin: string, private readonly preload: string, private readonly timerPrefs: TimerPreferenceStore,
    private readonly prefs: DesktopPreferenceStore, private readonly host: RailHost) {}

  install(): void {
    if (this.installed) return
    this.installed = true
    const trusted = (event: IpcMainInvokeEvent) => { if (!this.isSender(event)) throw new Error('UNTRUSTED_IPC_SENDER') }
    ipcMain.handle('yumpoo:rail:get-state', event => { trusted(event); return this.state() })
    ipcMain.handle('yumpoo:rail:open', (event, projectId: unknown, top: unknown) => {
      trusted(event)
      if (typeof projectId !== 'string' || !uuid.test(projectId) || typeof top !== 'number' || !Number.isFinite(top)) throw new Error('INVALID_RAIL_PROJECT')
      const bounds = this.window!.getBounds()
      this.host.openProject(projectId, { x: bounds.x + this.rail.x, y: bounds.y + Math.round(top), width: this.rail.width, height: 0 }, this.timerPrefs.get().dockSide)
    })
    ipcMain.handle('yumpoo:rail:projects', (event, projects: unknown) => {
      trusted(event)
      if (!validRailProjects(projects)) throw new Error('INVALID_RAIL_PROJECTS')
      this.prefs.update({ railProjects: projects })
    })
    ipcMain.handle('yumpoo:rail:settings', event => { trusted(event); this.host.openSettings() })
    app.on('will-quit', () => this.clearTimers())
  }

  isSender(event: IpcMainInvokeEvent): boolean { return isTrustedAuthIpcSender(event, this.window, this.origin) }

  /** Shows, hides or re-lays out the rail after any change to sign-in, preferences, the timer slot or the quick panel. */
  sync(): void {
    const prefs = this.prefs.get()
    if (!this.host.signedIn() || !prefs.railEnabled) { this.window?.hide(); return }
    if (!this.window || this.window.isDestroyed()) { this.create(); return }
    const window = this.window
    if (!this.dragging) {
      const placed = this.place(screen.getDisplayMatching(window.getBounds()).workArea)
      const bounds = window.getBounds()
      if (placed.x !== bounds.x || placed.y !== bounds.y || placed.width !== bounds.width || placed.height !== bounds.height) window.setBounds(placed)
    }
    if (this.tucked && (prefs.railMode === 'always' || this.host.quickProject())) this.setTucked(false)
    else this.applyShape()
    this.applyLevel()
    this.publish()
    if (this.ready && !window.isVisible()) window.showInactive()
  }

  /** Signing out or switching account reloads the page from scratch. */
  destroy(): void {
    const window = this.window
    if (window && !window.isDestroyed()) window.destroy()
  }

  private applyLevel(): void {
    const window = this.window
    if (!window || window.isDestroyed()) return
    if (this.prefs.get().aboveTaskbar && this.host.pinned()) window.setAlwaysOnTop(true, 'screen-saver')
    else window.setAlwaysOnTop(this.host.pinned())
  }

  private place(area: Rect): Rect {
    const prefs = this.prefs.get(), timer = this.timerPrefs.get()
    const placed = placeRail(timer.dockSide, timer.dockY, prefs.railSize, prefs.railProjects?.items.length ?? 0, this.host.timer(), area)
    this.rail = placed.rail
    return placed.bounds
  }

  private state(): RailState {
    const prefs = this.prefs.get()
    return { side: this.timerPrefs.get().dockSide, size: prefs.railSize, mode: prefs.railMode, tucked: this.tucked,
      timer: this.host.timer(), projects: prefs.railProjects, active: this.host.quickProject() }
  }

  private publish(): void {
    if (this.window && !this.window.isDestroyed()) this.window.webContents.send('yumpoo:rail:state', this.state())
  }

  private applyShape(): void {
    const window = this.window
    if (!window || window.isDestroyed()) return
    this.shape = railShape(window.getBounds(), this.timerPrefs.get().dockSide, this.tucked)
    if (process.platform === 'win32' || process.platform === 'linux') window.setShape(this.shape)
  }

  /** Sliding out grows the shape first; tucking slides in first and shrinks the shape once the slide has finished. */
  private setTucked(tucked: boolean): void {
    this.tucked = tucked
    clearTimeout(this.shapeTimer)
    if (tucked) this.shapeTimer = setTimeout(() => this.applyShape(), SLIDE_MS)
    else this.applyShape()
    this.publish()
  }

  private canTuck(): boolean {
    return this.prefs.get().railMode === 'autohide' && !this.host.quickProject() && !this.dragging
  }

  private updateHover(): void {
    const window = this.window
    if (!window || window.isDestroyed() || !window.isVisible() || this.dragging) return
    const bounds = window.getBounds(), cursor = screen.getCursorScreenPoint()
    const hovered = hitTest(this.shape, cursor.x - bounds.x, cursor.y - bounds.y)
    // The taskbar takes the top of the screen-saver level whenever it is used; reaching for the rail brings the rail back above it.
    if (hovered && !this.hovered && this.prefs.get().aboveTaskbar && this.host.pinned()) window.moveTop()
    this.hovered = hovered
    if (hovered) {
      clearTimeout(this.tuckTimer)
      this.tuckTimer = undefined
      if (this.tucked) this.setTucked(false)
    } else if (!this.tucked && !this.tuckTimer && this.canTuck()) {
      this.tuckTimer = setTimeout(() => {
        this.tuckTimer = undefined
        if (!this.hovered && this.canTuck()) this.setTucked(true)
      }, TUCK_DELAY_MS)
    }
  }

  /** After a native drag the rail snaps to the nearer edge and keeps its vertical position as a fraction of the work area. */
  private endDrag(): void {
    const window = this.window
    if (!window || window.isDestroyed()) return
    const bounds = window.getBounds()
    const snapped = snapDock(bounds, this.timerPrefs.get().dockSide, screen.getDisplayMatching(bounds).workArea)
    this.dragging = false
    this.timerPrefs.update({ dockSide: snapped.side, dockY: snapped.dockY })
    this.sync()
  }

  private clearTimers(): void {
    clearInterval(this.hoverTimer); clearTimeout(this.tuckTimer); clearTimeout(this.shapeTimer); clearTimeout(this.reframeTimer)
  }

  private create(): void {
    this.tucked = this.prefs.get().railMode === 'autohide'
    this.ready = false
    this.hovered = false
    this.dragging = false
    const bounds = this.place(screen.getPrimaryDisplay().workArea)
    const window = new BrowserWindow({ ...createWindowOptions(this.preload, app.isPackaged), ...bounds,
      minWidth: 0, minHeight: 0,
      frame: false, resizable: false, minimizable: false, maximizable: false, fullscreenable: false, skipTaskbar: true,
      transparent: true, backgroundColor: '#00000000', hasShadow: false, thickFrame: false,
      alwaysOnTop: this.host.pinned(), title: 'YumpooPlatform · 项目侧边栏', icon: applicationIcon(),
    })
    this.window = window
    this.applyShape()
    this.applyLevel()
    clearInterval(this.hoverTimer)
    this.hoverTimer = setInterval(() => this.updateHover(), 100)
    window.on('page-title-updated', event => event.preventDefault())
    window.on('will-move', () => {
      this.dragging = true
      clearTimeout(this.tuckTimer)
      this.tuckTimer = undefined
    })
    if (process.platform === 'win32') window.on('moved', () => this.endDrag())
    else window.on('move', () => {
      clearTimeout(this.reframeTimer)
      this.reframeTimer = setTimeout(() => { if (this.dragging) this.endDrag() }, 120)
    })
    window.on('system-context-menu', event => { event.preventDefault(); this.host.openMenu() })
    window.webContents.on('context-menu', () => this.host.openMenu())
    installSecurityGuards(window.webContents, this.origin)
    window.on('closed', () => {
      if (this.window !== window) return
      this.clearTimers()
      this.window = null
    })
    window.once('ready-to-show', () => { if (this.window === window) { this.ready = true; this.sync() } })
    window.loadURL(new URL('/desktop/rail', this.origin).href).catch(() => {
      console.error('[YUMPOO_RAIL_WINDOW_LOAD_FAILED]')
      if (!window.isDestroyed()) window.destroy()
    })
  }
}
