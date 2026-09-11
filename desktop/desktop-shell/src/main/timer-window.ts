import { randomUUID } from 'node:crypto'
import { app, BrowserWindow, dialog, ipcMain, Menu, screen, Tray, type IpcMainInvokeEvent, type Rectangle } from 'electron'
import type { DesktopTimerCommand, DesktopTimerState, TimerWindowMode, TimerOrbChange, TimerOrbLayout } from '@yumpoo/preload-contract'
import { isTrustedAuthIpcSender } from './auth-ipc'
import { createWindowOptions } from './window-policy'
import { installSecurityGuards } from './security-guards'
import { applicationIcon } from './application-icon'

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
export function validTimerId(value: unknown): value is string { return typeof value === 'string' && uuid.test(value) }
const validMode = (value: unknown): value is TimerWindowMode => value === 'compact' || value === 'picker'
const validTitle = (value: unknown): value is string => typeof value === 'string' && value.length <= 1000

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
}

export class TimerWindowController {
  private window: BrowserWindow | null = null
  private tray: Tray | undefined
  private installed = false
  private contextMenu: Menu | undefined
  private mode: TimerWindowMode = 'picker'
  private pinned = true
  private startupShown = false
  private hoverTimer: ReturnType<typeof setInterval> | undefined
  private reframeTimer: ReturnType<typeof setTimeout> | undefined
  private blurTimer: ReturnType<typeof setTimeout> | undefined
  private hovered = false
  private orb: TimerOrbLayout = { size: 176, side: null, detailWidth: 0 }
  private state: DesktopTimerState | undefined
  private stateAt = 0
  private pendingCommand: string | undefined
  private commandTimeout: ReturnType<typeof setTimeout> | undefined
  private approvedExit = false
  private exitRequest: string | undefined
  private exitTimeout: ReturnType<typeof setTimeout> | undefined

  constructor(private readonly main: () => BrowserWindow | null, private readonly origin: string, private readonly preload: string) {}

  private trusted(event: IpcMainInvokeEvent, mainOnly = false): void {
    if (!isTrustedAuthIpcSender(event, this.main(), this.origin) && (mainOnly || !isTrustedAuthIpcSender(event, this.window, this.origin))) throw new Error('UNTRUSTED_IPC_SENDER')
  }

  install(): void {
    if (this.installed) return
    this.installed = true
    this.tray = new Tray(applicationIcon())
    this.tray.on('click', () => this.showMain())
    this.tray.on('double-click', () => this.showMain())
    this.tray.on('right-click', () => { this.updateTray(); this.tray?.popUpContextMenu(this.contextMenu) })
    this.updateTray()
    ipcMain.handle('yumpoo:timer:show', async (event, mode: unknown = 'picker', activate: unknown = true) => {
      this.trusted(event)
      if (!validMode(mode) || typeof activate !== 'boolean') throw new Error('INVALID_TIMER_WINDOW')
      await this.show(mode, activate)
    })
    ipcMain.handle('yumpoo:timer:hide', event => { this.trusted(event); this.window?.hide(); this.updateTray() })
    ipcMain.handle('yumpoo:timer:window-state', event => {
      this.trusted(event)
      return { mode: this.mode, pinned: this.pinned, surface: event.sender === this.main()?.webContents ? 'main' : 'timer', savedAt: this.state?.savedAt ?? 0, orb: { ...this.orb }, hovered: this.hovered }
    })
    ipcMain.handle('yumpoo:timer:orb-layout', (event, change: unknown) => {
      if (!isTrustedAuthIpcSender(event, this.window, this.origin)) throw new Error('UNTRUSTED_IPC_SENDER')
      const c = change as TimerOrbChange | null
      if (!c || typeof c !== 'object' || Array.isArray(c) || Object.keys(c).some(key => key !== 'size' && key !== 'details' && key !== 'titleVisible')
        || (c.titleVisible !== undefined && typeof c.titleVisible !== 'boolean')
        || (c.size !== undefined && (!Number.isInteger(c.size) || c.size < 128 || c.size > 280))
        || (c.details !== undefined && typeof c.details !== 'boolean')) throw new Error('INVALID_TIMER_ORB')
      if (this.mode === 'compact') this.layoutOrb(c)
      return { ...this.orb }
    })
    ipcMain.handle('yumpoo:timer:mode', (event, mode: unknown) => {
      this.trusted(event)
      if (!validMode(mode)) throw new Error('INVALID_TIMER_MODE')
      this.resize(mode)
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

  private updateTray(): void {
    const state = this.state
    const ready = !!state?.accountId && state.connected && Date.now() - this.stateAt < 15000
    const enabled = ready && !state?.busy && !this.pendingCommand && !this.exitRequest
    const title = state?.running ? `计时中 · ${state.running.title}` : '计时已暂停'
    this.tray?.setToolTip(`YumpooPlatform${state?.running ? `\n${title}` : ''}`.slice(0, 120))
    const running = state?.running
    const recent = state?.recent
    this.contextMenu = Menu.buildFromTemplate([
      { label: 'YumpooPlatform', enabled: false },
      { label: '打开 YumpooPlatform', click: () => this.showMain() },
      { type: 'separator' },
      { label: state?.accountId ? (ready ? title : '等待连接') : '尚未登录', enabled: false },
      { label: '查找工作项…', click: () => { if (state?.accountId) void this.show('picker'); else this.showMain() } },
      ...(running ? [{ label: '暂停并保存计时', enabled, click: () => this.dispatchCommand({ requestId: randomUUID(), action: 'stop', sessionId: running.sessionId }) }]
        : recent ? [{ label: `继续 · ${recent.title.slice(0, 50)}`, enabled, click: () => this.dispatchCommand({ requestId: randomUUID(), action: 'start', workItemId: recent.workItemId }) }] : []),
      { label: this.window?.isVisible() ? '隐藏计时器' : '显示悬浮球', click: () => {
        if (!state?.accountId) { this.showMain(); return }
        if (this.window?.isVisible()) { this.window.hide(); this.updateTray() }
        else void this.show('compact')
      } },
      { label: '计时器置顶', type: 'checkbox', checked: this.pinned, enabled: !!state?.accountId, click: () => this.setPinned(!this.pinned) },
      { type: 'separator' },
      { label: '退出 YumpooPlatform…', click: () => this.requestExit() },
    ])
    if (process.platform === 'linux') this.tray?.setContextMenu(this.contextMenu)
  }

  private setPinned(pinned: boolean): void {
    this.pinned = pinned
    this.window?.setAlwaysOnTop(pinned)
    this.window?.webContents.send('yumpoo:timer:mode-changed', this.mode)
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

  private placeOrb(requestedSize: number, circleX: number, circleBottom: number, area: Rectangle): Rectangle {
    const size = Math.min(requestedSize, Math.max(128, area.width - 176), area.height - 40)
    const x = Math.max(area.x, Math.min(circleX, area.x + area.width - size))
    const y = Math.max(area.y + 40, Math.min(circleBottom - size, area.y + area.height - size))
    const left = Math.min(224, x - area.x), right = Math.min(224, area.x + area.width - x - size)
    const canvas = { left, top: 40, width: left + size + right, height: size + 40 }
    this.orb = { size, side: null, detailWidth: 0, canvas }
    return { x: x - left, y: y - 40, width: canvas.width, height: canvas.height }
  }

  private resize(mode: TimerWindowMode): void {
    const previous = this.mode
    this.mode = mode
    if (!this.window || this.window.isDestroyed() || previous === mode) return
    const bounds = this.window.getBounds()
    const area = screen.getDisplayMatching(bounds).workArea
    const right = previous === 'compact' ? bounds.x + (this.orb.canvas?.left ?? 0) + this.orb.size : bounds.x + bounds.width
    if (mode === 'compact') {
      this.window.setBounds(this.placeOrb(this.orb.size, right - this.orb.size, bounds.y + bounds.height, area))
    } else {
      const width = Math.min(392, area.width), height = Math.min(520, area.height)
      this.window.setBounds({ width, height,
        x: Math.max(area.x, Math.min(right - width, area.x + area.width - width)),
        y: Math.max(area.y, Math.min(bounds.y + bounds.height - height, area.y + area.height - height)),
      })
      this.orb = { size: this.orb.size, side: null, detailWidth: 0 }
    }
    this.applyShape(mode)
    this.window.webContents.send('yumpoo:timer:mode-changed', mode)
  }

  private layoutOrb(change: TimerOrbChange, reframe = false): boolean {
    if (!this.window || this.window.isDestroyed()) return false
    const previous = this.orb
    const bounds = this.window.getBounds()
    const area = screen.getDisplayMatching(bounds).workArea
    if (reframe || (change.size !== undefined && change.size !== previous.size)) {
      const left = bounds.x + (previous.canvas?.left ?? 0)
      const bottom = bounds.y + (previous.canvas?.top ?? 0) + previous.size
      const next = this.placeOrb(change.size ?? previous.size, left, bottom, area)
      if (next.x !== bounds.x || next.y !== bounds.y || next.width !== bounds.width || next.height !== bounds.height) this.window.setBounds(next)
    }
    const canvas = this.orb.canvas!
    const left = canvas.left, right = canvas.width - canvas.left - this.orb.size
    const details = change.details ?? previous.side !== null
    const side = details ? (left >= right ? 'left' : 'right') : null
    const detailWidth = side ? Math.max(left, right) : 0
    const titleVisible = change.titleVisible ?? previous.titleVisible ?? false
    this.orb = { ...this.orb, side: detailWidth > 0 ? side : null, detailWidth, titleVisible }
    const changed = previous.size !== this.orb.size || previous.side !== this.orb.side || previous.detailWidth !== detailWidth
      || !!previous.titleVisible !== titleVisible || previous.canvas?.left !== canvas.left || previous.canvas?.width !== canvas.width || previous.canvas?.height !== canvas.height
    if (changed) this.applyShape('compact')
    return changed
  }

  private applyShape(mode: TimerWindowMode): void {
    if (process.platform !== 'win32' && process.platform !== 'linux') return
    const header = this.orb.canvas?.top ?? 0
    const rects = mode === 'picker' ? [] : Array.from({ length: this.orb.size }, (_, y) => {
      const radius = this.orb.size / 2
      const half = Math.sqrt(Math.max(0, radius ** 2 - (y + .5 - radius) ** 2))
      const x = Math.floor(radius - half) + (this.orb.canvas?.left ?? 0)
      return { x, y: y + header, width: Math.max(1, Math.ceil(half * 2)), height: 1 }
    })
    if (mode === 'compact' && this.orb.titleVisible) rects.push({ x: (this.orb.canvas?.left ?? 0) + 8, y: 4, width: this.orb.size - 16, height: 30 })
    if (mode === 'compact' && this.orb.side) {
      const height = Math.min(112, this.orb.size - 24)
      rects.push({ x: (this.orb.canvas?.left ?? 0) + (this.orb.side === 'left' ? -this.orb.detailWidth : this.orb.size - 12),
        y: header + Math.floor((this.orb.size - height) / 2), width: this.orb.detailWidth + 12, height })
    }
    this.window?.setShape(rects)
  }

  private updateHover(): void {
    const window = this.window
    let hovered = false
    if (window && !window.isDestroyed() && window.isVisible() && this.mode === 'compact') {
      const bounds = window.getBounds(), cursor = screen.getCursorScreenPoint()
      const x = cursor.x - bounds.x, y = cursor.y - bounds.y - (this.orb.canvas?.top ?? 0)
      const radius = this.orb.size / 2, center = radius + (this.orb.canvas?.left ?? 0)
      const detailTop = (this.orb.size - Math.min(112, this.orb.size - 24)) / 2
      hovered = (x - center) ** 2 + (y - radius) ** 2 <= radius ** 2
        || (!!this.orb.side && x >= center - radius - (this.orb.side === 'left' ? this.orb.detailWidth : 0) && x <= center + radius + (this.orb.side === 'right' ? this.orb.detailWidth : 0) && y >= detailTop && y <= this.orb.size - detailTop)
    }
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
    const width = Math.min(mode === 'compact' ? this.orb.size : 392, area.width), height = Math.min(mode === 'compact' ? this.orb.size : 520, area.height)
    const bounds = mode === 'compact'
      ? this.placeOrb(this.orb.size, area.x + area.width - width - 24, area.y + area.height - 24, area)
      : { width, height, x: Math.max(area.x, area.x + area.width - width - 24), y: Math.max(area.y, area.y + area.height - height - 24) }
    const window = new BrowserWindow({ ...createWindowOptions(this.preload, app.isPackaged), ...bounds,
      minWidth: Math.min(128, width), minHeight: Math.min(128, height),
      frame: false, resizable: false, maximizable: false, fullscreenable: false, skipTaskbar: true,
      transparent: true, backgroundColor: '#00000000', hasShadow: false, thickFrame: false,
      alwaysOnTop: this.pinned, title: 'YumpooPlatform · 计时器', icon: applicationIcon(),
    })
    this.window = window
    this.hovered = false
    clearInterval(this.hoverTimer)
    this.hoverTimer = setInterval(() => this.updateHover(), 100)
    this.applyShape(mode)
    window.on('page-title-updated', event => event.preventDefault())
    window.on('blur', () => {
      clearTimeout(this.blurTimer)
      this.blurTimer = setTimeout(() => {
        if (!window.isDestroyed() && window.isVisible() && !this.pinned && this.mode === 'compact') window.moveTop()
      }, 0)
    })
    window.on('move', () => {
      clearTimeout(this.reframeTimer)
      this.reframeTimer = setTimeout(() => {
        if (this.mode === 'compact' && this.layoutOrb({}, true)) window.webContents.send('yumpoo:timer:mode-changed', 'compact')
      }, 120)
    })
    installSecurityGuards(window.webContents, this.origin)
    window.on('close', event => { if (!this.approvedExit) { event.preventDefault(); window.hide(); this.updateTray() } })
    window.on('closed', () => { clearInterval(this.hoverTimer); clearTimeout(this.reframeTimer); clearTimeout(this.blurTimer); this.window = null; this.updateTray() })
    window.once('ready-to-show', () => reveal(window))
    try { await window.loadURL(new URL(`/timer?mode=${mode}`, this.origin).href) }
    catch {
      window.destroy()
      this.showMain()
      console.error('[YUMPOO_TIMER_WINDOW_LOAD_FAILED]')
    }
  }
}
