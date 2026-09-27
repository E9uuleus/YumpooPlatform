import { randomUUID } from 'node:crypto'
import { app, BrowserWindow, dialog, ipcMain, Menu, screen, Tray, type IpcMainInvokeEvent, type NativeImage } from 'electron'
import type { DesktopTimerCommand, DesktopTimerState, TimerMenuAction, TimerMenuState, TimerWindowMode, TimerOrbLayout, TimerPreferencesChange } from '@yumpoo/preload-contract'
import { isTrustedAuthIpcSender } from './auth-ipc'
import { createWindowOptions } from './window-policy'
import { installSecurityGuards } from './security-guards'
import { applicationIcon } from './application-icon'
import { TimerQuickMenu } from './timer-menu'
import { TimerPreferenceStore, validDisplay, validDockSide, validOrbSize } from './timer-preferences'
import { compactVisual, dockShape, hitTest, ORB_SIZES, orbDetails, orbShape, panelVisual, placeDock, placeOrb, placePanel, snapDock, type Rect } from './timer-geometry'

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
export function validTimerId(value: unknown): value is string { return typeof value === 'string' && uuid.test(value) }
const validMode = (value: unknown): value is TimerWindowMode => value === 'compact' || value === 'picker' || value === 'settings'
const validTitle = (value: unknown): value is string => typeof value === 'string' && value.length <= 1000
const validOffset = (value: unknown) => value === undefined || (Number.isSafeInteger(value) && Math.abs(value as number) <= 7 * 86_400_000)
const expanded = (mode: TimerWindowMode) => mode !== 'compact'

export function validTimerState(value: unknown): value is DesktopTimerState {
  if (!value || typeof value !== 'object') return false
  const s = value as DesktopTimerState
  return (s.accountId === null || validTimerId(s.accountId)) && Number.isSafeInteger(s.rowVersion) && s.rowVersion >= 0
    && typeof s.connected === 'boolean' && typeof s.busy === 'boolean'
    && Number.isSafeInteger(s.savedAt) && s.savedAt >= 0 && s.savedAt <= Date.now() + 10000
    && (s.running === null || (!!s.running && validTimerId(s.running.sessionId)
      && (s.running.workItemId === null || validTimerId(s.running.workItemId)) && validTitle(s.running.title)
      && typeof s.running.startedAt === 'string' && Number.isFinite(Date.parse(s.running.startedAt))))
    && (s.recent === null || (!!s.recent && validTimerId(s.recent.workItemId) && validTitle(s.recent.title)))
    && (s.accountId !== null || (s.running === null && s.recent === null))
    && validOffset(s.clockOffsetMs)
}

function validPreferencesChange(value: unknown): value is TimerPreferencesChange {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const c = value as Record<string, unknown>
  return Object.keys(c).every(key => key === 'display' || key === 'orbSize' || key === 'dockSide')
    && (c.display === undefined || validDisplay(c.display))
    && (c.orbSize === undefined || validOrbSize(c.orbSize))
    && (c.dockSide === undefined || validDockSide(c.dockSide))
}

const sameLayout = (a: TimerOrbLayout, b: TimerOrbLayout) => a.size === b.size && a.side === b.side && a.detailWidth === b.detailWidth
  && a.canvas?.left === b.canvas?.left && a.canvas?.top === b.canvas?.top && a.canvas?.width === b.canvas?.width && a.canvas?.height === b.canvas?.height
  && a.dock?.side === b.dock?.side && a.dock?.expanded === b.dock?.expanded
const sameRect = (a: Rect, b: Rect) => a.x === b.x && a.y === b.y && a.width === b.width && a.height === b.height

export class TimerWindowController {
  private window: BrowserWindow | null = null
  private tray: Tray | undefined
  private inboxCount: number | undefined
  private installed = false
  private contextMenu: Menu | undefined
  private readonly menu: TimerQuickMenu
  private menuWarmed = false
  private mode: TimerWindowMode = 'picker'
  private pinned: boolean
  private startupShown = false
  private hoverTimer: ReturnType<typeof setInterval> | undefined
  private reframeTimer: ReturnType<typeof setTimeout> | undefined
  private blurTimer: ReturnType<typeof setTimeout> | undefined
  private hovered = false
  private dragging = false
  private suppressHover = false
  private dragTimer: ReturnType<typeof setInterval> | undefined
  private dragGuard: ReturnType<typeof setTimeout> | undefined
  private orb: TimerOrbLayout
  private shape: Rect[] = []
  private state: DesktopTimerState | undefined
  private stateAt = 0
  private pendingCommand: string | undefined
  private commandTimeout: ReturnType<typeof setTimeout> | undefined
  private approvedExit = false
  private exitRequest: string | undefined
  private exitTimeout: ReturnType<typeof setTimeout> | undefined

  constructor(private readonly main: () => BrowserWindow | null, private readonly origin: string, private readonly preload: string,
    private readonly prefs = new TimerPreferenceStore()) {
    this.pinned = prefs.get().pinned
    this.orb = { size: ORB_SIZES[prefs.get().orbSize], side: null, detailWidth: 0 }
    this.menu = new TimerQuickMenu(origin, preload, action => this.menuAction(action))
  }

  private trusted(event: IpcMainInvokeEvent, mainOnly = false): void {
    if (!isTrustedAuthIpcSender(event, this.main(), this.origin) && (mainOnly || !isTrustedAuthIpcSender(event, this.window, this.origin))) throw new Error('UNTRUSTED_IPC_SENDER')
  }

  install(): void {
    if (this.installed) return
    this.installed = true
    this.tray = new Tray(applicationIcon())
    this.tray.on('click', () => this.showMain())
    this.tray.on('double-click', () => this.showMain())
    this.tray.on('right-click', () => {
      this.updateTray()
      if (!this.menu.open('tray', this.menuState())) this.tray?.popUpContextMenu(this.contextMenu)
    })
    this.menu.install()
    this.updateTray()
    ipcMain.handle('yumpoo:timer:show', async (event, mode: unknown = 'picker', activate: unknown = true) => {
      this.trusted(event)
      if (!validMode(mode) || typeof activate !== 'boolean') throw new Error('INVALID_TIMER_WINDOW')
      await this.show(mode, activate)
    })
    ipcMain.handle('yumpoo:timer:hide', event => { this.trusted(event); this.window?.hide(); this.updateTray() })
    ipcMain.handle('yumpoo:timer:window-state', event => {
      this.trusted(event)
      return { mode: this.mode, pinned: this.pinned, surface: event.sender === this.main()?.webContents ? 'main' : 'timer', savedAt: this.state?.savedAt ?? 0,
        orb: { ...this.orb }, hovered: this.hovered, preferences: this.prefs.preferences() }
    })
    ipcMain.handle('yumpoo:timer:orb-layout', (event, change: unknown) => {
      if (!isTrustedAuthIpcSender(event, this.window, this.origin)) throw new Error('UNTRUSTED_IPC_SENDER')
      const c = change as Record<string, unknown> | null
      // Older web builds may still send size or titleVisible; those are ignored rather than rejected.
      if (!c || typeof c !== 'object' || Array.isArray(c) || Object.keys(c).some(key => key !== 'details' && key !== 'size' && key !== 'titleVisible')
        || (c.details !== undefined && typeof c.details !== 'boolean')) throw new Error('INVALID_TIMER_ORB')
      if (this.mode === 'compact' && typeof c.details === 'boolean') this.setDetails(c.details)
      return { ...this.orb }
    })
    ipcMain.handle('yumpoo:timer:drag', (event, active: unknown) => {
      if (!isTrustedAuthIpcSender(event, this.window, this.origin)) throw new Error('UNTRUSTED_IPC_SENDER')
      if (typeof active !== 'boolean') throw new Error('INVALID_TIMER_DRAG')
      if (active) this.followCursor()
      else this.endDrag()
    })
    ipcMain.handle('yumpoo:timer:mode', (event, mode: unknown) => {
      this.trusted(event)
      if (!validMode(mode)) throw new Error('INVALID_TIMER_MODE')
      this.resize(mode)
    })
    ipcMain.handle('yumpoo:timer:preferences', (event, change: unknown) => {
      if (!this.menu.isSender(event)) this.trusted(event)
      if (!validPreferencesChange(change)) throw new Error('INVALID_TIMER_PREFERENCES')
      this.applyPreferences(change)
      return this.prefs.preferences()
    })
    ipcMain.handle('yumpoo:timer:pin', (event, pinned: unknown) => {
      this.trusted(event)
      if (typeof pinned !== 'boolean') throw new Error('INVALID_TIMER_PIN')
      this.setPinned(pinned)
    })
    ipcMain.handle('yumpoo:timer:state', (event, state: unknown) => {
      this.trusted(event, true)
      if (!validTimerState(state)) throw new Error('INVALID_TIMER_STATE')
      if (this.state?.accountId === state.accountId && state.rowVersion < this.state.rowVersion) return
      const saved = state.accountId && state.savedAt > (this.state?.savedAt ?? 0) && Date.now() - state.savedAt < 2600 && !state.running
      this.state = state
      this.stateAt = Date.now()
      if (!this.menuWarmed) { this.menuWarmed = true; this.menu.prewarm() }
      if (!state.accountId) this.window?.hide()
      this.updateTray()
      if (state.accountId && !this.startupShown) {
        this.startupShown = true
        void this.show('compact', false)
      } else if (saved && !this.window?.isVisible()) void this.show('compact', false)
    })
    ipcMain.handle('yumpoo:timer:command-complete', async (event, id: unknown, success: unknown) => {
      this.trusted(event, true)
      if (!validTimerId(id) || typeof success !== 'boolean' || id !== this.pendingCommand) throw new Error('INVALID_TIMER_COMMAND')
      clearTimeout(this.commandTimeout)
      this.pendingCommand = undefined
      this.updateTray()
      if (!success) await this.commandFailed()
    })
    ipcMain.handle('yumpoo:timer:refresh', event => {
      this.trusted(event)
      for (const target of [this.main(), this.window]) {
        if (target && !target.isDestroyed() && target.webContents !== event.sender) target.webContents.send('yumpoo:timer:changed')
      }
    })
    ipcMain.handle('yumpoo:timer:open-item', async (event, projectId: unknown, itemId: unknown) => {
      this.trusted(event)
      if (!validTimerId(projectId) || !validTimerId(itemId)) throw new Error('INVALID_TIMER_ITEM')
      const main = this.main()
      if (!main) return
      await main.loadURL(new URL(`/projects/${projectId}/overview?workItemId=${itemId}`, this.origin).href)
      this.showMain()
    })
    ipcMain.handle('yumpoo:timer:exit-received', (event, requestId: unknown) => {
      this.trusted(event, true)
      if (!validTimerId(requestId) || requestId !== this.exitRequest) throw new Error('INVALID_TIMER_EXIT')
      clearTimeout(this.exitTimeout)
    })
    ipcMain.handle('yumpoo:timer:exit-complete', (event, requestId: unknown, allow: unknown) => {
      this.trusted(event, true)
      if (!validTimerId(requestId) || typeof allow !== 'boolean' || requestId !== this.exitRequest) throw new Error('INVALID_TIMER_EXIT')
      clearTimeout(this.exitTimeout); this.exitRequest = undefined
      if (allow) { this.approvedExit = true; app.quit() }
      else this.updateTray()
    })
    app.on('before-quit', event => {
      if (!this.approvedExit && this.main()) { event.preventDefault(); this.requestExit() }
    })
    app.on('will-quit', () => {
      clearInterval(this.hoverTimer)
      clearTimeout(this.reframeTimer)
      clearTimeout(this.blurTimer)
      clearTimeout(this.exitTimeout); clearTimeout(this.commandTimeout)
      clearInterval(this.dragTimer); clearTimeout(this.dragGuard)
      this.tray?.destroy()
    })
  }

  attachMain(window: BrowserWindow): void {
    window.setIcon(applicationIcon())
    window.webContents.setBackgroundThrottling(false)
    window.on('close', event => { if (!this.approvedExit) { event.preventDefault(); window.hide() } })
  }

  showMain(): void {
    const main = this.main()
    if (!main || main.isDestroyed()) return
    if (main.isMinimized()) main.restore()
    main.show(); main.focus()
  }

  inboxTray(): Tray | undefined { return this.tray }

  isInboxPreferencesSender(event: IpcMainInvokeEvent): boolean { return this.menu.isSender(event) }

  setInboxBadge(count: number | undefined, image: NativeImage): void {
    this.inboxCount = count
    this.tray?.setImage(image)
    this.updateTray()
  }

  private ready(): boolean {
    const state = this.state
    return !!state?.accountId && state.connected && Date.now() - this.stateAt < 15000
  }

  private commandsEnabled(): boolean {
    return this.ready() && !this.state?.busy && !this.pendingCommand && !this.exitRequest
  }

  private menuState(): TimerMenuState {
    const state = this.state
    return {
      signedIn: !!state?.accountId, ready: this.ready(), enabled: this.commandsEnabled(),
      running: state?.running ? { title: state.running.title, startedAt: state.running.startedAt } : null,
      recent: state?.recent ? { title: state.recent.title } : null,
      clockOffsetMs: state?.clockOffsetMs ?? 0, savedAt: state?.savedAt ?? 0,
      visible: !!this.window && !this.window.isDestroyed() && this.window.isVisible(),
      display: this.prefs.get().display, pinned: this.pinned,
      ...(this.inboxCount === undefined ? {} : { inbox: { unreadCount: this.inboxCount } }),
    }
  }

  private updateTray(): void {
    const state = this.state
    const signedIn = !!state?.accountId
    const ready = this.ready()
    const enabled = this.commandsEnabled()
    const title = state?.running ? `计时中 · ${state.running.title}` : '计时已暂停'
    this.tray?.setToolTip(`YumpooPlatform${this.inboxCount === undefined ? '' : `\n${this.inboxCount} 条未读`}${state?.running ? `\n${title}` : ''}`.slice(0, 120))
    const running = state?.running
    const recent = state?.recent
    const visible = !!this.window?.isVisible()
    const display = this.prefs.get().display
    this.contextMenu = Menu.buildFromTemplate([
      { label: 'YumpooPlatform', enabled: false },
      { label: signedIn ? (ready ? title : '等待连接') : '尚未登录', enabled: false },
      { type: 'separator' },
      ...(running ? [{ label: '暂停并保存计时', enabled, click: () => this.toggleTimer() }]
        : recent ? [{ label: `继续 · ${recent.title.slice(0, 50)}`, enabled, click: () => this.toggleTimer() }] : []),
      { label: '查找工作项…', click: () => this.menuAction('find') },
      { label: '打开主界面', click: () => this.showMain() },
      ...(this.inboxCount === undefined ? [] : [{ label: `收件箱 · ${this.inboxCount} 条未读`, click: () => this.menuAction('open-inbox') }]),
      { type: 'separator' },
      { label: '悬浮球', type: 'radio', checked: visible && display === 'orb', enabled: signedIn, click: () => this.menuAction('show-orb') },
      { label: '侧边栏', type: 'radio', checked: visible && display === 'dock', enabled: signedIn, click: () => this.menuAction('show-dock') },
      { label: '隐藏', type: 'radio', checked: !visible, enabled: signedIn, click: () => this.menuAction('hide') },
      { label: '窗口置顶', type: 'checkbox', checked: this.pinned, enabled: signedIn, click: () => this.setPinned(!this.pinned) },
      { type: 'separator' },
      { label: '退出 YumpooPlatform…', click: () => this.requestExit() },
    ])
    if (process.platform === 'linux') this.tray?.setContextMenu(this.contextMenu)
    this.menu.update(this.menuState())
  }

  private menuAction(action: TimerMenuAction): void {
    const signedIn = !!this.state?.accountId
    switch (action) {
      case 'open-main': this.showMain(); break
      case 'open-inbox':
        this.showMain()
        this.main()?.webContents.send('yumpoo:inbox:open', null)
        break
      case 'find':
      case 'settings':
        if (signedIn) void this.show(action === 'find' ? 'picker' : 'settings')
        else this.showMain()
        break
      case 'toggle': this.toggleTimer(); break
      case 'show-orb':
      case 'show-dock':
        if (!signedIn) { this.showMain(); break }
        this.applyPreferences({ display: action === 'show-orb' ? 'orb' : 'dock' })
        void this.show('compact', false)
        break
      case 'hide': this.window?.hide(); break
      case 'toggle-pin': if (signedIn) this.setPinned(!this.pinned); break
      case 'exit': this.requestExit(); break
      case 'close': break
    }
    this.updateTray()
  }

  private openSurfaceMenu(): void {
    this.updateTray()
    if (!this.menu.open('pointer', this.menuState())) this.contextMenu?.popup(this.window ? { window: this.window } : {})
  }

  private toggleTimer(): void {
    const state = this.state
    if (state?.running) this.dispatchCommand({ requestId: randomUUID(), action: 'stop', sessionId: state.running.sessionId })
    else if (state?.recent) this.dispatchCommand({ requestId: randomUUID(), action: 'start', workItemId: state.recent.workItemId })
    else if (state?.accountId) void this.show('picker')
    else this.showMain()
  }

  private setPinned(pinned: boolean): void {
    this.pinned = pinned
    this.prefs.update({ pinned })
    this.window?.setAlwaysOnTop(pinned)
    this.window?.webContents.send('yumpoo:timer:mode-changed', this.mode)
    this.updateTray()
  }

  private applyPreferences(change: TimerPreferencesChange): void {
    const before = this.prefs.get()
    const after = this.prefs.update(change)
    if (before.display === after.display && before.orbSize === after.orbSize && before.dockSide === after.dockSide) return
    const window = this.window
    if (window && !window.isDestroyed()) {
      if (this.mode === 'compact') {
        const bounds = window.getBounds()
        const area = screen.getDisplayMatching(bounds).workArea
        const visual = compactVisual(bounds, this.orb)
        const size = ORB_SIZES[after.orbSize]
        const anchor = this.orb.dock
          ? { x: this.orb.dock.side === 'right' ? area.x + area.width - size - 24 : area.x + 24, bottom: visual.y + (visual.height + size) / 2 }
          : { x: visual.x, bottom: visual.y + visual.height }
        const next = this.placeCompact(anchor, area)
        if (!sameRect(next, bounds)) window.setBounds(next)
        this.applyShape()
      } else this.orb = { ...this.orb, size: ORB_SIZES[after.orbSize] }
      window.webContents.send('yumpoo:timer:mode-changed', this.mode)
    }
    this.updateTray()
  }

  private dispatchCommand(command: DesktopTimerCommand): void {
    if (this.pendingCommand || this.exitRequest || this.state?.busy || !this.state?.connected || Date.now() - this.stateAt >= 15000) return
    const main = this.main()
    if (!main || main.isDestroyed()) return
    this.pendingCommand = command.requestId
    this.updateTray()
    main.webContents.send('yumpoo:timer:command', command)
    this.commandTimeout = setTimeout(() => {
      if (this.pendingCommand !== command.requestId) return
      this.pendingCommand = undefined
      this.updateTray()
      void this.commandFailed()
    }, 15000)
  }

  private async commandFailed(): Promise<void> {
    await this.show('picker')
    this.window?.webContents.send('yumpoo:timer:command-failed')
  }

  private requestExit(): void {
    if (this.exitRequest) return
    const main = this.main()
    if (!main || main.isDestroyed()) return
    const requestId = randomUUID()
    this.exitRequest = requestId
    this.showMain()
    this.updateTray()
    main.webContents.send('yumpoo:timer:exit-request', requestId)
    this.exitTimeout = setTimeout(() => {
      if (this.exitRequest !== requestId) return
      void dialog.showMessageBox(main, { type: 'warning', message: '应用未能确认计时状态', detail: '可取消退出后重试，或明确选择继续计时并退出。计时不会自动停止。', buttons: ['取消退出', '继续计时并退出'], defaultId: 0, cancelId: 0 }).then(result => {
        if (this.exitRequest !== requestId) return
        this.exitRequest = undefined
        if (result.response === 1) { this.approvedExit = true; app.quit() }
        else this.updateTray()
      })
    }, 30000)
  }

  /** Lays out the compact surface for the saved display style; an orb keeps its circle's bottom-left near `anchor`. */
  private placeCompact(anchor: { x: number; bottom: number } | undefined, area: Rect): Rect {
    const prefs = this.prefs.get()
    const size = ORB_SIZES[prefs.orbSize]
    const placed = prefs.display === 'dock'
      ? placeDock(prefs.dockSide, prefs.dockY, area)
      : placeOrb(size, anchor?.x ?? area.x + area.width - size - 24, anchor?.bottom ?? area.y + area.height - 24, area)
    this.orb = placed.layout
    return placed.bounds
  }

  private resize(mode: TimerWindowMode): void {
    if (expanded(mode)) this.endDrag()
    const previous = this.mode
    this.mode = mode
    if (!this.window || this.window.isDestroyed() || previous === mode) return
    if (expanded(previous) && expanded(mode)) { this.window.webContents.send('yumpoo:timer:mode-changed', mode); return }
    const bounds = this.window.getBounds()
    const area = screen.getDisplayMatching(bounds).workArea
    if (mode === 'compact') {
      const visual = panelVisual(bounds), size = ORB_SIZES[this.prefs.get().orbSize]
      this.window.setBounds(this.placeCompact({ x: visual.x + visual.width - size, bottom: visual.y + visual.height }, area))
    } else {
      this.window.setBounds(placePanel(compactVisual(bounds, this.orb), area, this.orb.dock?.side))
      this.orb = { size: this.orb.size, side: null, detailWidth: 0 }
    }
    this.applyShape()
    this.window.webContents.send('yumpoo:timer:mode-changed', mode)
  }

  private setDetails(open: boolean): void {
    const next = this.orb.dock ? { ...this.orb, dock: { ...this.orb.dock, expanded: open } } : orbDetails(this.orb, open)
    const changed = !sameLayout(next, this.orb)
    this.orb = next
    if (changed) this.applyShape()
  }

  /** Re-fits the transparent canvas after a drag; a dock also snaps to the nearest edge and remembers where it was left. */
  private reframe(force = false): void {
    const window = this.window
    if (!window || window.isDestroyed() || this.mode !== 'compact' || !this.orb.canvas || (this.dragging && !force)) return
    const bounds = window.getBounds(), area = screen.getDisplayMatching(bounds).workArea
    let next: { bounds: Rect; layout: TimerOrbLayout }
    if (this.orb.dock) {
      const snapped = snapDock(bounds, this.orb.dock.side, area)
      this.prefs.update({ dockSide: snapped.side, dockY: snapped.dockY })
      next = placeDock(snapped.side, snapped.dockY, area, this.orb.dock.expanded)
    } else {
      const visual = compactVisual(bounds, this.orb)
      next = placeOrb(ORB_SIZES[this.prefs.get().orbSize], visual.x, visual.y + visual.height, area)
      next.layout = orbDetails(next.layout, this.orb.side !== null)
    }
    if (!sameRect(next.bounds, bounds)) window.setBounds(next.bounds)
    if (sameLayout(next.layout, this.orb)) return
    this.orb = next.layout
    this.applyShape()
    window.webContents.send('yumpoo:timer:mode-changed', 'compact')
  }

  /** Dragging hides the quick panel and keeps the capsule, or a closed card, from opening until the pointer has left once. */
  private beginDrag(): void {
    const window = this.window
    if (this.dragging || !window || window.isDestroyed() || this.mode !== 'compact') return
    this.dragging = true
    this.menu.hide()
    // The orb travels alone: an open capsule is clipped away at once, while an open dock card is carried along.
    if (!this.orb.dock) this.setDetails(false)
    if (!this.orb.dock?.expanded) {
      this.suppressHover = true
      if (this.hovered) { this.hovered = false; window.webContents.send('yumpoo:timer:orb-hover', false) }
    }
    window.webContents.send('yumpoo:timer:dragging', true)
  }

  private endDrag(): void {
    clearInterval(this.dragTimer); clearTimeout(this.dragGuard)
    this.dragTimer = undefined
    if (!this.dragging) return
    this.reframe(true)
    this.dragging = false
    if (this.window && !this.window.isDestroyed()) this.window.webContents.send('yumpoo:timer:dragging', false)
  }

  /** Buttons cannot be native drag regions, so a drag that starts on one moves the window with the cursor instead. */
  private followCursor(): void {
    const window = this.window
    if (!window || window.isDestroyed() || this.mode !== 'compact' || this.dragging) return
    const bounds = window.getBounds(), cursor = screen.getCursorScreenPoint()
    const grab = { x: cursor.x - bounds.x, y: cursor.y - bounds.y }
    this.beginDrag()
    this.dragTimer = setInterval(() => {
      if (window.isDestroyed()) { this.endDrag(); return }
      const point = screen.getCursorScreenPoint()
      window.setPosition(point.x - grab.x, point.y - grab.y)
    }, 16)
    this.dragGuard = setTimeout(() => this.endDrag(), 60_000)
  }

  private applyShape(): void {
    this.shape = this.mode !== 'compact' || !this.orb.canvas ? [] : this.orb.dock ? dockShape(this.orb) : orbShape(this.orb)
    if (process.platform === 'win32' || process.platform === 'linux') this.window?.setShape(this.shape)
  }

  private updateHover(): void {
    const window = this.window
    if (this.dragging) return
    let hovered = false
    if (window && !window.isDestroyed() && window.isVisible() && this.mode === 'compact') {
      const bounds = window.getBounds(), cursor = screen.getCursorScreenPoint()
      hovered = hitTest(this.shape, cursor.x - bounds.x, cursor.y - bounds.y)
    }
    if (this.suppressHover) { if (!hovered) this.suppressHover = false; hovered = false }
    if (hovered === this.hovered) return
    this.hovered = hovered
    window?.webContents.send('yumpoo:timer:orb-hover', hovered)
  }

  private async show(mode: TimerWindowMode = 'picker', activate = true): Promise<void> {
    const reveal = (window: BrowserWindow) => {
      if (window.isMinimized()) window.restore()
      if (activate) { window.show(); window.focus() }
      else window.showInactive()
      this.updateTray()
    }
    if (this.window && !this.window.isDestroyed()) {
      this.resize(mode)
      if (activate || !this.window.isVisible()) reveal(this.window)
      return
    }
    this.mode = mode
    const area = screen.getPrimaryDisplay().workArea
    const compact = this.placeCompact(undefined, area)
    const bounds = mode === 'compact' ? compact : placePanel(compactVisual(compact, this.orb), area, this.orb.dock?.side)
    if (expanded(mode)) this.orb = { size: this.orb.size, side: null, detailWidth: 0 }
    const window = new BrowserWindow({ ...createWindowOptions(this.preload, app.isPackaged), ...bounds,
      minWidth: 0, minHeight: 0,
      frame: false, resizable: false, maximizable: false, fullscreenable: false, skipTaskbar: true,
      transparent: true, backgroundColor: '#00000000', hasShadow: false, thickFrame: false,
      alwaysOnTop: this.pinned, title: 'YumpooPlatform · 计时器', icon: applicationIcon(),
    })
    this.window = window
    this.hovered = false
    clearInterval(this.hoverTimer)
    this.hoverTimer = setInterval(() => this.updateHover(), 100)
    this.applyShape()
    window.on('page-title-updated', event => event.preventDefault())
    window.on('blur', () => {
      clearTimeout(this.blurTimer)
      this.blurTimer = setTimeout(() => {
        if (!window.isDestroyed() && window.isVisible() && !this.pinned && this.mode === 'compact') window.moveTop()
      }, 0)
    })
    // Windows announces manual moves and reports their end once; elsewhere `move` streams during the drag, so it is debounced.
    window.on('will-move', () => this.beginDrag())
    if (process.platform === 'win32') window.on('moved', () => { if (this.dragging) this.endDrag(); else this.reframe() })
    else window.on('move', () => {
      clearTimeout(this.reframeTimer)
      this.reframeTimer = setTimeout(() => { if (this.dragging && !this.dragTimer) this.endDrag(); else this.reframe() }, 120)
    })
    // Right-clicking a drag region raises the system window menu; the shared quick panel replaces it.
    window.on('system-context-menu', event => { event.preventDefault(); this.openSurfaceMenu() })
    window.webContents.on('context-menu', () => { if (this.mode === 'compact') this.openSurfaceMenu() })
    installSecurityGuards(window.webContents, this.origin)
    window.on('close', event => { if (!this.approvedExit) { event.preventDefault(); window.hide(); this.updateTray() } })
    window.on('closed', () => {
      clearInterval(this.hoverTimer); clearTimeout(this.reframeTimer); clearTimeout(this.blurTimer); clearInterval(this.dragTimer); clearTimeout(this.dragGuard)
      this.window = null; this.dragging = false; this.updateTray()
    })
    window.once('ready-to-show', () => reveal(window))
    try { await window.loadURL(new URL(`/timer?mode=${mode}`, this.origin).href) }
    catch {
      window.destroy()
      this.showMain()
      console.error('[YUMPOO_TIMER_WINDOW_LOAD_FAILED]')
    }
  }
}
