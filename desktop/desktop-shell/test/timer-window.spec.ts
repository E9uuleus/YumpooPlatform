import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import type { BrowserWindow } from 'electron'
import type { DesktopTimerState, TimerMenuAction } from '@yumpoo/preload-contract'

type MenuItem = { label?: string; type?: string; enabled?: boolean; checked?: boolean; click?: () => void }
const mocks = vi.hoisted(() => ({
  handlers: new Map<string, (...args: unknown[]) => unknown>(), appEvents: new Map<string, (...args: unknown[]) => void>(),
  windows: [] as Array<{ options: unknown; instance: unknown }>, trayEvents: new Map<string, () => void>(),
  menu: [] as MenuItem[], quit: vi.fn(), tooltip: vi.fn(), popup: vi.fn(), nativePopup: vi.fn(),
  area: { x: 0, y: 0, width: 1280, height: 800 },
  cursor: { x: -9999, y: -9999 },
  menuOpen: vi.fn((): boolean => false), menuPrewarm: vi.fn(), menuUpdate: vi.fn(), menuSender: vi.fn((): boolean => false), menuHide: vi.fn(),
  menuAction: undefined as ((action: TimerMenuAction) => void) | undefined,
  quickToggle: vi.fn((): boolean => false), railHost: undefined as { timer: () => boolean } | undefined,
}))
vi.mock('electron', () => ({
  app: { isPackaged: false, quit: mocks.quit, on: (name: string, fn: (...args: unknown[]) => void) => mocks.appEvents.set(name, fn) },
  ipcMain: { handle: (name: string, fn: (...args: unknown[]) => unknown) => mocks.handlers.set(name, fn) },
  dialog: { showMessageBox: vi.fn(async () => ({ response: 0 })) },
  nativeImage: { createFromPath: vi.fn(() => ({ isEmpty: () => false })) },
  Menu: { buildFromTemplate: (items: MenuItem[]) => { mocks.menu = items; return Object.assign(items, { popup: mocks.nativePopup }) } },
  Tray: class {
    setImage = vi.fn()
    setToolTip = mocks.tooltip
    setContextMenu = vi.fn()
    popUpContextMenu = mocks.popup
    destroy = vi.fn()
    on(name: string, fn: () => void) { mocks.trayEvents.set(name, fn) }
  },
  screen: { getPrimaryDisplay: () => ({ workArea: mocks.area }), getDisplayMatching: () => ({ workArea: mocks.area }), getCursorScreenPoint: () => mocks.cursor },
  BrowserWindow: class {
    events = new Map<string, (...args: unknown[]) => void>()
    contentEvents = new Map<string, (...args: unknown[]) => void>()
    webContents = { mainFrame: { url: 'https://yumpoo.example/timer' }, send: vi.fn(), on: (name: string, fn: (...args: unknown[]) => void) => this.contentEvents.set(name, fn), once: vi.fn(), removeListener: vi.fn() }
    isDestroyed = () => false
    isVisible = vi.fn(() => true)
    bounds = { x: 0, y: 0, width: 176, height: 176 }
    getBounds = vi.fn(() => ({ ...this.bounds }))
    setBounds = vi.fn((bounds: typeof this.bounds) => { this.bounds = { ...bounds } })
    setShape = vi.fn()
    moveTop = vi.fn()
    showInactive = vi.fn()
    show = vi.fn()
    focus = vi.fn()
    isMinimized = vi.fn(() => false)
    restore = vi.fn()
    hide = vi.fn()
    setAlwaysOnTop = vi.fn()
    setPosition = vi.fn((x: number, y: number) => { this.bounds = { ...this.bounds, x, y } })
    loadURL = vi.fn(async () => undefined)
    constructor(options: unknown) { this.bounds = { ...this.bounds, ...options as object }; mocks.windows.push({ options, instance: this }) }
    on(name: string, fn: (...args: unknown[]) => void) { this.events.set(name, fn) }
    once(name: string, fn: (...args: unknown[]) => void) { this.events.set(name, fn) }
  },
}))
vi.mock('../src/main/security-guards', () => ({ installSecurityGuards: vi.fn() }))
vi.mock('../src/main/timer-menu', () => ({
  TimerQuickMenu: class {
    install = vi.fn()
    prewarm = mocks.menuPrewarm
    open = mocks.menuOpen
    update = mocks.menuUpdate
    isSender = mocks.menuSender
    hide = mocks.menuHide
    constructor(_origin: string, _preload: string, onAction: (action: TimerMenuAction) => void) { mocks.menuAction = onAction }
  },
}))
vi.mock('../src/main/quick-panel', () => ({
  QuickPanelController: class {
    applyLevel = vi.fn()
    install = vi.fn()
    prewarm = vi.fn()
    destroy = vi.fn()
    railProject = vi.fn(() => null)
    isSender = vi.fn(() => false)
    toggle = mocks.quickToggle
  },
}))
vi.mock('../src/main/rail-window', () => ({
  RailController: class {
    install = vi.fn()
    sync = vi.fn()
    destroy = vi.fn()
    isSender = vi.fn(() => false)
    constructor(_origin: string, _preload: string, _timer: unknown, _prefs: unknown, host: { timer: () => boolean }) { mocks.railHost = host }
  },
}))
import { TimerWindowController } from '../src/main/timer-window'
import { TimerPreferenceStore } from '../src/main/timer-preferences'

type Mini = { moveTop: ReturnType<typeof vi.fn>; focus: ReturnType<typeof vi.fn>; isVisible: ReturnType<typeof vi.fn>; webContents: { mainFrame: { url: string }; send: ReturnType<typeof vi.fn> }; events: Map<string, (event?: unknown) => void>; contentEvents: Map<string, (event?: unknown) => void>; hide: ReturnType<typeof vi.fn>; show: ReturnType<typeof vi.fn>; showInactive: ReturnType<typeof vi.fn>; setBounds: ReturnType<typeof vi.fn>; setShape: ReturnType<typeof vi.fn>; setAlwaysOnTop: ReturnType<typeof vi.fn> }
const mini = () => mocks.windows[0]!.instance as Mini
const shaped = process.platform === 'win32' || process.platform === 'linux'
function setup(store = new TimerPreferenceStore()) {
  const events = new Map<string, (event: { preventDefault: () => void }) => void>()
  const main = { isDestroyed: () => false, isMinimized: () => false, show: vi.fn(), focus: vi.fn(), hide: vi.fn(), setIcon: vi.fn(),
    webContents: { mainFrame: { url: 'https://yumpoo.example/work' }, send: vi.fn(), setBackgroundThrottling: vi.fn() },
    on: (name: string, fn: (event: { preventDefault: () => void }) => void) => events.set(name, fn),
  }
  const controller = new TimerWindowController(() => main as unknown as BrowserWindow, 'https://yumpoo.example', '/preload.js', store)
  controller.install(); controller.attachMain(main as unknown as BrowserWindow)
  const event = { sender: main.webContents, senderFrame: main.webContents.mainFrame }
  return { main, event, events, store, controller }
}
function state(running = false): DesktopTimerState {
  return { accountId: crypto.randomUUID(), rowVersion: 1, connected: true, busy: false, savedAt: 0,
    running: running ? { sessionId: crypto.randomUUID(), workItemId: crypto.randomUUID(), title: '测试工作', startedAt: new Date().toISOString() } : null,
    recent: { workItemId: crypto.randomUUID(), title: '最近工作' } }
}
const invoke = (channel: string, ...args: unknown[]) => mocks.handlers.get(`yumpoo:timer:${channel}`)?.(...args)
const menu = (label: string) => mocks.menu.find(item => item.label === label)!
const source = () => ({ sender: mini().webContents, senderFrame: mini().webContents.mainFrame })
const lastShape = () => mini().setShape.mock.calls.at(-1)![0] as Array<{ x: number; y: number; width: number; height: number }>
async function drop() { mini().events.get('moved')?.(); mini().events.get('move')?.(); await vi.advanceTimersByTimeAsync(130) }
beforeEach(() => {
  mocks.handlers.clear(); mocks.appEvents.clear(); mocks.trayEvents.clear(); mocks.windows.length = 0
  mocks.area = { x: 0, y: 0, width: 1280, height: 800 }; mocks.cursor = { x: -9999, y: -9999 }
  vi.clearAllMocks(); mocks.quickToggle.mockReturnValue(false); mocks.menuOpen.mockReturnValue(false); mocks.menuSender.mockReturnValue(false); vi.useFakeTimers()
})
afterEach(() => { mocks.appEvents.get('will-quit')?.(); vi.useRealTimers() })

describe('desktop timer and tray', () => {
  it('将更新状态推送到菜单，并在两个菜单中执行更新动作', () => {
    const { controller } = setup()
    const actions = { check: vi.fn(), download: vi.fn(), restart: vi.fn() }
    const update = { phase: 'idle' as const, currentVersion: '1.1.2', channel: null }
    controller.setUpdate(update, actions)
    expect(mocks.menuUpdate).toHaveBeenLastCalledWith(expect.objectContaining({ update }))
    menu('检查更新').click!(); mocks.menuAction!('check-update')
    expect(actions.check).toHaveBeenCalledTimes(2)
    controller.setUpdate({ ...update, phase: 'available', version: '1.1.3' }, actions)
    menu('下载更新 1.1.3').click!(); mocks.menuAction!('download-update')
    expect(actions.download).toHaveBeenCalledTimes(2)
    controller.setUpdate({ ...update, phase: 'ready', version: '1.1.3' }, actions)
    menu('重启以更新到 1.1.3').click!(); mocks.menuAction!('restart-update')
    expect(actions.restart).toHaveBeenCalledTimes(2)
    expect(mocks.tooltip).toHaveBeenLastCalledWith('YumpooPlatform\n新版本 1.1.3 已就绪')
    controller.setUpdate({ ...update, phase: 'error' }, actions)
    expect(menu('检查更新')).toBeDefined()
    for (const phase of ['disabled', 'checking', 'downloading', 'verifying'] as const) {
      controller.setUpdate({ ...update, phase }, actions)
      expect(mocks.menu.some(item => item.label?.includes('更新'))).toBe(false)
    }
  })
  it('keeps inbox badges in timer tooltips and exposes an inbox action in both menus', () => {
    const { controller, main } = setup()
    controller.setInboxBadge(7, {} as Parameters<typeof controller.setInboxBadge>[1])
    expect(mocks.tooltip).toHaveBeenLastCalledWith('YumpooPlatform\n7 条未读')
    expect(mocks.menuUpdate).toHaveBeenLastCalledWith(expect.objectContaining({ inbox: { unreadCount: 7 } }))
    menu('收件箱 · 7 条未读').click!()
    expect(main.webContents.send).toHaveBeenLastCalledWith('yumpoo:inbox:open', null)
    mocks.menuAction!('open-inbox')
    expect(main.show).toHaveBeenCalledTimes(2)
    controller.setInboxBadge(0, {} as Parameters<typeof controller.setInboxBadge>[1])
    expect(mocks.tooltip).toHaveBeenLastCalledWith('YumpooPlatform\n0 条未读')
    controller.setInboxBadge(undefined, {} as Parameters<typeof controller.setInboxBadge>[1])
    expect(mocks.tooltip).toHaveBeenLastCalledWith('YumpooPlatform')
    expect(mocks.menuUpdate.mock.calls.at(-1)![0]).not.toHaveProperty('inbox')
    expect(mocks.menu.some(item => item.label?.startsWith('收件箱'))).toBe(false)
  })
  it('rejects forged windows, subframes and malformed IPC payloads', async () => {
    const { event } = setup()
    await expect(invoke('show', { ...event, sender: {} })).rejects.toThrow('UNTRUSTED')
    await expect(invoke('show', { ...event, senderFrame: { url: event.senderFrame.url } })).rejects.toThrow('UNTRUSTED')
    await expect(invoke('show', event, 'https://attacker.example')).rejects.toThrow('INVALID_TIMER_WINDOW')
    expect(() => invoke('state', event, { accountId: 'fake' })).toThrow('INVALID_TIMER_STATE')
    expect(() => invoke('state', event, { ...state(), savedAt: Date.now() + 20000 })).toThrow('INVALID_TIMER_STATE')
    expect(() => invoke('state', event, { ...state(), clockOffsetMs: 1.5 })).toThrow('INVALID_TIMER_STATE')
    expect(() => invoke('state', event, { ...state(), clockOffsetMs: 8 * 86_400_000 })).toThrow('INVALID_TIMER_STATE')
    expect(mocks.windows).toHaveLength(0)
  })
  it('opens a small frameless orb and aligns the work panel with its bottom-right corner', async () => {
    const { event } = setup()
    await invoke('show', event, 'compact', false)
    expect(mocks.windows[0]?.options).toMatchObject({ frame: false, transparent: true, resizable: false, skipTaskbar: true, x: 966, y: 702, width: 314, height: 84, alwaysOnTop: true, show: false,
      webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false, partition: 'persist:yumpoo-authenticated' } })
    mini().events.get('ready-to-show')?.()
    expect(mini().showInactive).toHaveBeenCalledOnce()
    if (shaped) {
      const shape = lastShape()
      expect(shape).toHaveLength(84)
      expect(shape[0]!.x).toBeGreaterThan(216 + 30)
      expect(shape[42]).toMatchObject({ x: 216, width: 84 })
    }
    await invoke('show', event, 'picker')
    expect(mocks.windows).toHaveLength(1)
    expect(mini().setBounds).toHaveBeenLastCalledWith({ x: 884, y: 284, width: 384, height: 504 })
    expect(mini().show).toHaveBeenCalledOnce()
    if (shaped) expect(mini().setShape).toHaveBeenLastCalledWith([])
    invoke('mode', source(), 'compact')
    expect(mini().setBounds).toHaveBeenLastCalledWith({ x: 966, y: 702, width: 314, height: 84 })
    invoke('pin', event, false)
    expect(mini().setAlwaysOnTop).toHaveBeenCalledWith(false)
  })
  it('switches between the picker and settings views without moving the panel', async () => {
    const { event } = setup()
    await invoke('show', event, 'settings')
    expect(mocks.windows[0]?.options).toMatchObject({ x: 884, y: 284, width: 384, height: 504 })
    expect(mini().webContents.send).not.toHaveBeenCalled()
    invoke('mode', source(), 'picker')
    expect(mini().setBounds).not.toHaveBeenCalled()
    expect(mini().webContents.send).toHaveBeenLastCalledWith('yumpoo:timer:mode-changed', 'picker')
    expect(invoke('window-state', source())).toMatchObject({ mode: 'picker', preferences: { display: 'orb', orbSize: 'medium', dockSide: 'right' } })
  })
  it('opens the capsule toward free space by changing only the native shape', async () => {
    const { event } = setup()
    await invoke('show', event, 'compact')
    expect(invoke('orb-layout', source(), { details: true })).toMatchObject({ size: 64, side: 'left', detailWidth: 216 })
    if (shaped) expect(lastShape()[42]).toMatchObject({ x: 0, width: 300 })
    const shapes = mini().setShape.mock.calls.length
    invoke('orb-layout', source(), { details: true })
    expect(mini().setShape.mock.calls).toHaveLength(shapes)
    invoke('orb-layout', source(), { details: false })
    expect(mini().setBounds).not.toHaveBeenCalled()
    if (shaped) expect(lastShape()[42]).toMatchObject({ x: 216, width: 84 })
  })
  it('accepts orb layout changes only from the trusted mini renderer and ignores legacy geometry keys', async () => {
    const { event } = setup()
    await invoke('show', event, 'compact')
    expect(() => invoke('orb-layout', event, { details: true })).toThrow('UNTRUSTED')
    expect(() => invoke('orb-layout', { ...source(), senderFrame: {} }, { details: true })).toThrow('UNTRUSTED')
    for (const change of [null, [], { details: 'yes' }, { x: 100 }]) {
      expect(() => invoke('orb-layout', source(), change)).toThrow('INVALID_TIMER_ORB')
    }
    expect(invoke('orb-layout', source(), { size: 280, titleVisible: true })).toMatchObject({ size: 64, side: null })
    expect(mini().setBounds).not.toHaveBeenCalled()
    invoke('mode', source(), 'picker')
    expect(invoke('orb-layout', source(), { details: true })).toEqual({ size: 64, side: null, detailWidth: 0 })
  })
  it('reframes after a native drag across the screen, then opens right-side details without another move', async () => {
    const { event } = setup()
    await invoke('show', event, 'compact')
    mini().setBounds({ x: -200, y: 300, width: 314, height: 84 })
    await drop()
    expect(invoke('window-state', source())).toMatchObject({ orb: { canvas: { left: 26, width: 316 } } })
    expect(mini().setBounds).toHaveBeenLastCalledWith({ x: 0, y: 300, width: 316, height: 84 })
    expect(mini().webContents.send).toHaveBeenLastCalledWith('yumpoo:timer:mode-changed', 'compact')
    const moves = mini().setBounds.mock.calls.length
    expect(invoke('orb-layout', source(), { details: true })).toMatchObject({ side: 'right', detailWidth: 216 })
    expect(mini().setBounds.mock.calls).toHaveLength(moves)
  })
  it('keeps the reserved canvas and visible capsule within a small monitor with a negative origin', async () => {
    mocks.area = { x: -400, y: -100, width: 400, height: 700 }
    const { event } = setup()
    await invoke('show', event, 'compact')
    expect(mocks.windows[0]?.options).toMatchObject({ x: -314, y: 502, width: 314, height: 84 })
    expect(invoke('orb-layout', source(), { details: true })).toMatchObject({ size: 64, side: 'left', detailWidth: 216 })
    invoke('preferences', event, { orbSize: 'large' })
    expect(invoke('window-state', source())).toMatchObject({ orb: { size: 80, side: null, canvas: { left: 226 } } })
    expect(mini().setBounds).toHaveBeenLastCalledWith({ x: -316, y: 486, width: 316, height: 100 })
  })
  it('persists preferences, resizes the orb in place and validates every source', async () => {
    const { event, store } = setup()
    await invoke('show', event, 'compact')
    expect(invoke('preferences', event, { orbSize: 'large' })).toEqual({ display: 'orb', orbSize: 'large', dockSide: 'right' })
    expect(mini().setBounds).toHaveBeenLastCalledWith({ x: 964, y: 686, width: 316, height: 100 })
    expect(mini().webContents.send).toHaveBeenLastCalledWith('yumpoo:timer:mode-changed', 'compact')
    expect(store.get().orbSize).toBe('large')
    for (const change of [null, [], { display: 'sidebar' }, { orbSize: 'huge' }, { dockSide: 'top' }, { pinned: false }]) {
      expect(() => invoke('preferences', event, change)).toThrow('INVALID_TIMER_PREFERENCES')
    }
    expect(() => invoke('preferences', { ...event, sender: {} }, { display: 'dock' })).toThrow('UNTRUSTED')
    mocks.menuSender.mockReturnValue(true)
    expect(invoke('preferences', { sender: {}, senderFrame: {} }, { display: 'dock' })).toMatchObject({ display: 'dock' })
    invoke('pin', event, false)
    expect(store.get()).toMatchObject({ display: 'dock', pinned: false })
    mocks.handlers.clear()
    const restarted = setup(store)
    expect(invoke('window-state', restarted.event)).toMatchObject({ pinned: false, preferences: { display: 'dock', orbSize: 'large' } })
  })
  it('hands the compact surface to the rail while the display style is the rail', async () => {
    const store = new TimerPreferenceStore()
    store.update({ display: 'dock' })
    const { event } = setup(store)
    invoke('state', event, state())
    await vi.advanceTimersByTimeAsync(0)
    expect(mocks.windows).toHaveLength(0)
    expect(mocks.railHost?.timer()).toBe(true)
    await invoke('show', event, 'picker')
    expect(mocks.windows).toHaveLength(1)
    invoke('mode', source(), 'compact')
    expect(mini().hide).toHaveBeenCalledOnce()
    mocks.menuAction?.('hide')
    expect(mocks.railHost?.timer()).toBe(false)
    expect(mocks.menuUpdate).toHaveBeenLastCalledWith(expect.objectContaining({ display: 'dock', visible: false }))
    mocks.menuAction?.('show-dock')
    expect(mocks.railHost?.timer()).toBe(true)
  })

  it('closes the quick panel and keeps a closed capsule shut while the orb is dragged natively', async () => {
    const { event } = setup()
    await invoke('show', event, 'compact')
    const sends = () => mini().webContents.send.mock.calls.map(call => call.slice(0, 2))
    mocks.cursor = { x: 1224, y: 744 }
    await vi.advanceTimersByTimeAsync(100)
    expect(sends().at(-1)).toEqual(['yumpoo:timer:orb-hover', true])
    mini().events.get('will-move')?.()
    mini().events.get('will-move')?.()
    expect(mocks.menuHide).toHaveBeenCalledOnce()
    expect(sends().slice(-2)).toEqual([['yumpoo:timer:orb-hover', false], ['yumpoo:timer:dragging', true]])
    await vi.advanceTimersByTimeAsync(300)
    expect(sends().at(-1)).toEqual(['yumpoo:timer:dragging', true])
    mini().setBounds({ x: 600, y: 300, width: 314, height: 84 })
    mocks.cursor = { x: 858, y: 342 }
    await drop()
    expect(sends()).toContainEqual(['yumpoo:timer:dragging', false])
    expect(mini().setBounds).toHaveBeenLastCalledWith({ x: 600, y: 300, width: 516, height: 84 })
    const count = sends().length
    await vi.advanceTimersByTimeAsync(300)
    expect(sends()).toHaveLength(count)
    mocks.cursor = { x: 50, y: 50 }
    await vi.advanceTimersByTimeAsync(100)
    mocks.cursor = { x: 858, y: 342 }
    await vi.advanceTimersByTimeAsync(100)
    expect(sends().at(-1)).toEqual(['yumpoo:timer:orb-hover', true])
  })
  it('drags the orb alone by collapsing an open capsule and keeping it shut until the pointer leaves', async () => {
    const { event } = setup()
    await invoke('show', event, 'compact')
    mocks.cursor = { x: 1224, y: 744 }
    await vi.advanceTimersByTimeAsync(100)
    expect(invoke('orb-layout', source(), { details: true })).toMatchObject({ side: 'left', detailWidth: 216 })
    mini().events.get('will-move')?.()
    if (shaped) expect(lastShape()[42]).toMatchObject({ x: 216, width: 84 })
    expect(invoke('window-state', source())).toMatchObject({ orb: { side: null, detailWidth: 0 } })
    expect(mini().webContents.send).toHaveBeenCalledWith('yumpoo:timer:orb-hover', false)
    expect(mini().webContents.send).toHaveBeenLastCalledWith('yumpoo:timer:dragging', true)
    await drop()
    const sends = mini().webContents.send.mock.calls.length
    await vi.advanceTimersByTimeAsync(300)
    expect(mini().webContents.send.mock.calls).toHaveLength(sends)
    expect(invoke('window-state', source())).toMatchObject({ orb: { side: null }, hovered: false })
  })
  it('moves the orb with the cursor for a drag that starts on a button', async () => {
    const { event } = setup()
    await invoke('show', event, 'compact')
    expect(() => invoke('drag', event, true)).toThrow('UNTRUSTED')
    expect(() => invoke('drag', source(), 'yes')).toThrow('INVALID_TIMER_DRAG')
    mocks.cursor = { x: 1224, y: 744 }
    await vi.advanceTimersByTimeAsync(100)
    invoke('drag', source(), true)
    expect(mini().webContents.send).toHaveBeenLastCalledWith('yumpoo:timer:dragging', true)
    mocks.cursor = { x: 400, y: 300 }
    await vi.advanceTimersByTimeAsync(20)
    expect(mini().setPosition).toHaveBeenLastCalledWith(142, 258)
    invoke('drag', source(), false)
    expect(mini().webContents.send).toHaveBeenLastCalledWith('yumpoo:timer:dragging', false)
    const moves = mini().setPosition.mock.calls.length
    await vi.advanceTimersByTimeAsync(100)
    expect(mini().setPosition.mock.calls).toHaveLength(moves)
  })

  it('moves a visible orb into the rail from the quick panel', async () => {
    const { event, store } = setup()
    invoke('state', event, state())
    await vi.advanceTimersByTimeAsync(0)
    mocks.menuAction?.('show-dock')
    expect(store.get().display).toBe('dock')
    expect(mini().hide).toHaveBeenCalledOnce()
    expect(mocks.railHost?.timer()).toBe(true)
    mocks.menuAction?.('hide')
    expect(mocks.railHost?.timer()).toBe(false)
    expect(mini().hide).toHaveBeenCalledOnce()
    mocks.menuAction?.('toggle-pin')
    expect(mini().setAlwaysOnTop).toHaveBeenLastCalledWith(false)
    expect(mocks.menuUpdate).toHaveBeenLastCalledWith(expect.objectContaining({ signedIn: true, display: 'dock', visible: false, pinned: false }))
  })

  it('toggles the quick time panel on tray click and opens the shared quick menu on right-click', () => {
    const { main, event } = setup()
    expect(mocks.trayEvents.has('double-click')).toBe(false)
    mocks.trayEvents.get('click')?.()
    expect(mocks.quickToggle).not.toHaveBeenCalled()
    expect(main.show).toHaveBeenCalledOnce()
    expect(main.setIcon).toHaveBeenCalledOnce()
    expect(mocks.windows).toHaveLength(0)
    mocks.trayEvents.get('right-click')?.()
    expect(mocks.menuOpen).toHaveBeenLastCalledWith('tray', expect.objectContaining({ signedIn: false, display: 'orb' }))
    expect(mocks.popup).toHaveBeenCalledWith(mocks.menu)
    expect(mocks.tooltip).toHaveBeenLastCalledWith('YumpooPlatform')
    mocks.menuOpen.mockReturnValue(true)
    mocks.trayEvents.get('right-click')?.()
    expect(mocks.popup).toHaveBeenCalledOnce()
    menu('查找工作项…').click?.()
    expect(main.show).toHaveBeenCalledTimes(2)
    invoke('state', event, { ...state(), clockOffsetMs: 1500 })
    expect(mocks.menuPrewarm).toHaveBeenCalledOnce()
    invoke('state', event, { ...state(), clockOffsetMs: 1500 })
    expect(mocks.menuPrewarm).toHaveBeenCalledOnce()
    expect(mocks.menuUpdate).toHaveBeenLastCalledWith(expect.objectContaining({ signedIn: true, clockOffsetMs: 1500, recent: { title: '最近工作' } }))
    mocks.trayEvents.get('click')?.()
    expect(mocks.quickToggle).toHaveBeenLastCalledWith({ source: 'tray' }, [])
    expect(main.show).toHaveBeenCalledTimes(3)
    mocks.quickToggle.mockReturnValue(true)
    mocks.trayEvents.get('click')?.()
    expect(main.show).toHaveBeenCalledTimes(3)
    expect(menu('窗口置顶').checked).toBe(true)
    menu('窗口置顶').click?.()
    expect(menu('窗口置顶').checked).toBe(false)
    expect(invoke('window-state', event)).toMatchObject({ pinned: false })
  })
  it('replaces the system window menu on the compact surface with the quick panel', async () => {
    const { event } = setup()
    await invoke('show', event, 'compact')
    const preventDefault = vi.fn()
    mini().events.get('system-context-menu')?.({ preventDefault })
    expect(preventDefault).toHaveBeenCalledOnce()
    expect(mocks.menuOpen).toHaveBeenLastCalledWith('pointer', expect.any(Object))
    expect(mocks.nativePopup).toHaveBeenCalledOnce()
    mocks.menuOpen.mockReturnValue(true)
    mini().contentEvents.get('context-menu')?.()
    expect(mocks.menuOpen).toHaveBeenCalledTimes(2)
    expect(mocks.nativePopup).toHaveBeenCalledOnce()
    invoke('mode', source(), 'picker')
    mini().contentEvents.get('context-menu')?.()
    expect(mocks.menuOpen).toHaveBeenCalledTimes(2)
  })
  it('starts compactly after authentication and carries authoritative save feedback without repeated reveals', () => {
    const { event } = setup()
    const active = state(true)
    invoke('state', event, active)
    expect(mocks.windows).toHaveLength(1)
    const savedAt = Date.now()
    const stopped = { ...active, rowVersion: 2, running: null, savedAt }
    invoke('state', event, stopped)
    expect(mocks.windows).toHaveLength(1)
    expect(mocks.windows[0]?.options).toMatchObject({ width: 314, height: 84 })
    expect(invoke('window-state', event)).toMatchObject({ mode: 'compact', savedAt })
    invoke('state', event, stopped)
    expect(mini().setBounds).not.toHaveBeenCalled()
  })
  it('detects hover across the native drag surface and keeps it while crossing into the capsule', async () => {
    const { event } = setup()
    await invoke('show', event, 'compact')
    mocks.cursor = { x: 1224, y: 744 }
    await vi.advanceTimersByTimeAsync(100)
    expect(mini().webContents.send).toHaveBeenLastCalledWith('yumpoo:timer:orb-hover', true)
    invoke('orb-layout', source(), { details: true })
    mocks.cursor = { x: 1080, y: 744 }
    const before = mini().webContents.send.mock.calls.length
    await vi.advanceTimersByTimeAsync(200)
    expect(mini().webContents.send.mock.calls).toHaveLength(before)
    mocks.cursor = { x: 50, y: 50 }
    await vi.advanceTimersByTimeAsync(100)
    expect(mini().webContents.send).toHaveBeenLastCalledWith('yumpoo:timer:orb-hover', false)
  })
  it('closes main and mini to the tray without pausing or asking to exit', async () => {
    const { events, main, event } = setup()
    events.get('close')?.({ preventDefault: vi.fn() })
    expect(main.hide).toHaveBeenCalledOnce()
    expect(main.webContents.send).not.toHaveBeenCalled()
    expect(main.webContents.setBackgroundThrottling).toHaveBeenCalledWith(false)
    await invoke('show', event)
    mini().events.get('close')?.({ preventDefault: vi.fn() })
    expect(mini().hide).toHaveBeenCalledOnce()
    expect(mocks.quit).not.toHaveBeenCalled()
    menu('打开主界面').click?.()
    expect(main.show).toHaveBeenCalledOnce()
  })
  it('keeps an unpinned compact window above the desktop after blur without focusing or making it always-on-top', async () => {
    const { event } = setup()
    await invoke('show', event, 'compact', false)
    invoke('pin', event, false)
    mini().events.get('blur')?.(); await vi.advanceTimersByTimeAsync(1)
    expect(mini().moveTop).toHaveBeenCalledOnce()
    expect(mini().focus).not.toHaveBeenCalled()
    expect(mini().setAlwaysOnTop).toHaveBeenLastCalledWith(false)
    expect(mini().hide).not.toHaveBeenCalled()
    mini().events.get('blur')?.()
    mini().isVisible.mockReturnValue(false)
    await vi.advanceTimersByTimeAsync(1)
    expect(mini().moveTop).toHaveBeenCalledOnce()
    mini().isVisible.mockReturnValue(true)
    invoke('pin', event, true)
    mini().events.get('blur')?.(); await vi.advanceTimersByTimeAsync(1)
    expect(mini().moveTop).toHaveBeenCalledOnce()
  })
  it('routes a single tray pause to the background main renderer and requires its acknowledgement', async () => {
    const { main, event } = setup()
    const active = state(true)
    invoke('state', event, active)
    const pause = menu('暂停并保存计时')
    pause.click?.(); pause.click?.()
    expect(main.webContents.send).toHaveBeenCalledOnce()
    const command = main.webContents.send.mock.calls[0]![1]
    expect(command).toMatchObject({ action: 'stop', sessionId: active.running!.sessionId })
    expect(menu('暂停并保存计时').enabled).toBe(false)
    await expect(invoke('command-complete', event, crypto.randomUUID(), true)).rejects.toThrow('INVALID_TIMER_COMMAND')
    await invoke('command-complete', event, command.requestId, true)
    expect(menu('暂停并保存计时').enabled).toBe(true)
    expect(main.show).not.toHaveBeenCalled()
  })
  it('lets the quick panel toggle only through the shell-owned session identity', async () => {
    const { main, event } = setup()
    const active = state(true)
    invoke('state', event, active)
    mocks.menuAction?.('toggle')
    expect(main.webContents.send).toHaveBeenLastCalledWith('yumpoo:timer:command', expect.objectContaining({ action: 'stop', sessionId: active.running!.sessionId }))
    expect(mocks.menuUpdate).toHaveBeenLastCalledWith(expect.objectContaining({ enabled: false }))
    mocks.menuAction?.('settings')
    await vi.advanceTimersByTimeAsync(0)
    expect(invoke('window-state', event)).toMatchObject({ mode: 'settings' })
    mocks.menuAction?.('exit')
    expect(main.webContents.send).toHaveBeenLastCalledWith('yumpoo:timer:exit-request', expect.any(String))
  })
  it('offers recent resume, disables disconnected commands and keeps state owner restricted', async () => {
    const { event, main } = setup()
    const idle = state()
    invoke('state', event, idle)
    menu('继续 · 最近工作').click?.()
    expect(main.webContents.send.mock.calls[0]![1]).toMatchObject({ action: 'start', workItemId: idle.recent!.workItemId })
    const id = main.webContents.send.mock.calls[0]![1].requestId
    await invoke('command-complete', event, id, true)
    invoke('state', event, { ...idle, connected: false })
    expect(menu('继续 · 最近工作').enabled).toBe(false)
    await invoke('show', event)
    const miniEvent = source()
    expect(() => invoke('state', miniEvent, idle)).toThrow('UNTRUSTED')
    expect(invoke('window-state', miniEvent)).toMatchObject({ surface: 'timer' })
  })
  it('keeps the orb and timer menu entries away while the person has switched the timer off', async () => {
    const { event, main } = setup()
    invoke('state', event, { ...state(), timerEnabled: false })
    expect(mocks.windows).toHaveLength(0)
    expect(mocks.menu.map(item => item.label)).not.toContain('查找工作项…')
    expect(mocks.menu.map(item => item.label)).not.toContain('悬浮球')
    expect(mocks.menu.some(item => item.label?.startsWith('继续'))).toBe(false)
    expect(mocks.menuUpdate.mock.calls.at(-1)![0]).toMatchObject({ timerEnabled: false })
    await invoke('show', event)
    expect(mocks.windows).toHaveLength(0)
    expect(main.show).toHaveBeenCalled()
    expect(() => invoke('state', event, { ...state(), timerEnabled: 'no' })).toThrow('INVALID_TIMER_STATE')
    invoke('state', event, { ...state(), rowVersion: 2, timerEnabled: true })
    expect(menu('查找工作项…')).toBeDefined()
  })
  it('hides an existing running orb on opt-out and does not resume on re-enable', async () => {
    const { event, main } = setup()
    const active = state(true)
    invoke('state', event, active)
    await vi.advanceTimersByTimeAsync(0)
    mini().events.get('ready-to-show')?.()
    const stopped = { ...active, rowVersion: 2, running: null, timerEnabled: false, savedAt: Date.now() }
    invoke('state', event, stopped)
    expect(mini().hide).toHaveBeenCalled()
    expect(mocks.menu.some(item => item.label?.startsWith('继续'))).toBe(false)
    expect(mocks.menu.map(item => item.label)).not.toContain('暂停并保存计时')
    invoke('state', event, { ...stopped, rowVersion: 3, timerEnabled: true })
    expect(menu('继续 · 最近工作')).toBeDefined()
    expect(main.webContents.send).not.toHaveBeenCalledWith('yumpoo:timer:command', expect.anything())
  })
  it('never replays an unacknowledged tray command after a timeout', async () => {
    const { event, main } = setup()
    invoke('state', event, state(true))
    menu('暂停并保存计时').click?.()
    await vi.advanceTimersByTimeAsync(16000)
    expect(main.webContents.send).toHaveBeenCalledOnce()
    expect(mini().webContents.send).toHaveBeenCalledWith('yumpoo:timer:command-failed')
  })
  it('deduplicates explicit exit and quits only after affirmative acknowledgement', async () => {
    const { main, event } = setup()
    menu('退出 YumpooPlatform…').click?.()
    mocks.appEvents.get('before-quit')?.({ preventDefault: vi.fn() })
    expect(main.webContents.send).toHaveBeenCalledOnce()
    const id = main.webContents.send.mock.calls[0]![1] as string
    expect(() => invoke('exit-complete', event, crypto.randomUUID(), true)).toThrow('INVALID_TIMER_EXIT')
    invoke('exit-received', event, id)
    await vi.advanceTimersByTimeAsync(40000)
    expect(mocks.quit).not.toHaveBeenCalled()
    invoke('exit-complete', event, id, false)
    menu('退出 YumpooPlatform…').click?.()
    invoke('exit-complete', event, main.webContents.send.mock.calls[1]![1], true)
    expect(mocks.quit).toHaveBeenCalledOnce()
  })
  it('reports a cancelled exit, whether the page declines or the person cancels the fallback dialog', async () => {
    const { controller, event, main } = setup()
    const cancelled = vi.fn()
    controller.onExitCancelled(cancelled)
    menu('退出 YumpooPlatform…').click?.()
    invoke('exit-complete', event, main.webContents.send.mock.calls[0]![1], false)
    expect(cancelled).toHaveBeenCalledOnce()
    menu('退出 YumpooPlatform…').click?.()
    await vi.advanceTimersByTimeAsync(31000)
    expect(cancelled).toHaveBeenCalledTimes(2)
    menu('退出 YumpooPlatform…').click?.()
    invoke('exit-complete', event, main.webContents.send.mock.calls[2]![1], true)
    expect(cancelled).toHaveBeenCalledTimes(2)
    expect(mocks.quit).toHaveBeenCalledOnce()
  })
})
