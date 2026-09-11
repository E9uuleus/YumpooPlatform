import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import type { BrowserWindow } from 'electron'
import type { DesktopTimerState } from '@yumpoo/preload-contract'

type MenuItem = { label?: string; enabled?: boolean; checked?: boolean; click?: () => void }
const mocks = vi.hoisted(() => ({
  handlers: new Map<string, (...args: unknown[]) => unknown>(), appEvents: new Map<string, (...args: unknown[]) => void>(),
  windows: [] as Array<{ options: unknown; instance: unknown }>, trayEvents: new Map<string, () => void>(),
  menu: [] as MenuItem[], quit: vi.fn(), tooltip: vi.fn(), popup: vi.fn(),
  area: { x: 0, y: 0, width: 1280, height: 800 },
  cursor: { x: -9999, y: -9999 },
}))
vi.mock('electron', () => ({
  app: { isPackaged: false, quit: mocks.quit, on: (name: string, fn: (...args: unknown[]) => void) => mocks.appEvents.set(name, fn) },
  ipcMain: { handle: (name: string, fn: (...args: unknown[]) => unknown) => mocks.handlers.set(name, fn) },
  dialog: { showMessageBox: vi.fn(async () => ({ response: 0 })) },
  nativeImage: { createFromBitmap: vi.fn(() => ({})) },
  Menu: { buildFromTemplate: (items: MenuItem[]) => { mocks.menu = items; return items } },
  Tray: class {
    setToolTip = mocks.tooltip
    setContextMenu = vi.fn()
    popUpContextMenu = mocks.popup
    destroy = vi.fn()
    on(name: string, fn: () => void) { mocks.trayEvents.set(name, fn) }
  },
  screen: { getPrimaryDisplay: () => ({ workArea: mocks.area }), getDisplayMatching: () => ({ workArea: mocks.area }), getCursorScreenPoint: () => mocks.cursor },
  BrowserWindow: class {
    events = new Map<string, (...args: unknown[]) => void>()
    webContents = { mainFrame: { url: 'https://yumpoo.example/timer' }, send: vi.fn() }
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
    loadURL = vi.fn(async () => undefined)
    constructor(options: unknown) { this.bounds = { ...this.bounds, ...options as object }; mocks.windows.push({ options, instance: this }) }
    on(name: string, fn: (...args: unknown[]) => void) { this.events.set(name, fn) }
    once(name: string, fn: (...args: unknown[]) => void) { this.events.set(name, fn) }
  },
}))
vi.mock('../src/main/security-guards', () => ({ installSecurityGuards: vi.fn() }))
import { TimerWindowController } from '../src/main/timer-window'

type Mini = { moveTop: ReturnType<typeof vi.fn>; focus: ReturnType<typeof vi.fn>; isVisible: ReturnType<typeof vi.fn>; webContents: { mainFrame: { url: string }; send: ReturnType<typeof vi.fn> }; events: Map<string, (event?: unknown) => void>; hide: ReturnType<typeof vi.fn>; show: ReturnType<typeof vi.fn>; showInactive: ReturnType<typeof vi.fn>; setBounds: ReturnType<typeof vi.fn>; setShape: ReturnType<typeof vi.fn>; setAlwaysOnTop: ReturnType<typeof vi.fn> }
const mini = () => mocks.windows[0]!.instance as Mini
function setup() {
  const events = new Map<string, (event: { preventDefault: () => void }) => void>()
  const main = { isDestroyed: () => false, isMinimized: () => false, show: vi.fn(), focus: vi.fn(), hide: vi.fn(), setIcon: vi.fn(),
    webContents: { mainFrame: { url: 'https://yumpoo.example/work' }, send: vi.fn(), setBackgroundThrottling: vi.fn() },
    on: (name: string, fn: (event: { preventDefault: () => void }) => void) => events.set(name, fn),
  }
  const controller = new TimerWindowController(() => main as unknown as BrowserWindow, 'https://yumpoo.example', '/preload.js')
  controller.install(); controller.attachMain(main as unknown as BrowserWindow)
  const event = { sender: main.webContents, senderFrame: main.webContents.mainFrame }
  return { main, event, events }
}
function state(running = false): DesktopTimerState {
  return { accountId: crypto.randomUUID(), rowVersion: 1, connected: true, busy: false, savedAt: 0,
    running: running ? { sessionId: crypto.randomUUID(), workItemId: crypto.randomUUID(), title: '测试工作', startedAt: new Date().toISOString() } : null,
    recent: { workItemId: crypto.randomUUID(), title: '最近工作' } }
}
const invoke = (channel: string, ...args: unknown[]) => mocks.handlers.get(`yumpoo:timer:${channel}`)?.(...args)
const menu = (label: string) => mocks.menu.find(item => item.label === label)!
beforeEach(() => { mocks.handlers.clear(); mocks.appEvents.clear(); mocks.trayEvents.clear(); mocks.windows.length = 0; mocks.area = { x: 0, y: 0, width: 1280, height: 800 }; mocks.cursor = { x: -9999, y: -9999 }; vi.clearAllMocks(); vi.useFakeTimers() })
afterEach(() => { mocks.appEvents.get('will-quit')?.(); vi.useRealTimers() })

describe('desktop timer and tray', () => {
  it('rejects forged windows, subframes and malformed IPC payloads', async () => {
    const { event } = setup()
    await expect(invoke('show', { ...event, sender: {} })).rejects.toThrow('UNTRUSTED')
    await expect(invoke('show', { ...event, senderFrame: { url: event.senderFrame.url } })).rejects.toThrow('UNTRUSTED')
    await expect(invoke('show', event, 'https://attacker.example')).rejects.toThrow('INVALID_TIMER_WINDOW')
    expect(() => invoke('state', event, { accountId: 'fake' })).toThrow('INVALID_TIMER_STATE')
    expect(() => invoke('state', event, { ...state(), savedAt: Date.now() + 20000 })).toThrow('INVALID_TIMER_STATE')
    expect(mocks.windows).toHaveLength(0)
  })
  it('reuses an isolated frameless window and constrains expansion to the display', async () => {
    const { event } = setup()
    await invoke('show', event, 'compact', false)
    expect(mocks.windows[0]?.options).toMatchObject({ frame: false, transparent: true, resizable: false, skipTaskbar: true, width: 424, height: 216, alwaysOnTop: true, show: false,
      webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false, partition: 'yumpoo-authenticated' } })
    mini().events.get('ready-to-show')?.()
    expect(mini().showInactive).toHaveBeenCalledOnce()
    if (process.platform !== 'darwin') {
      const shape = mini().setShape.mock.calls[0]![0] as Array<{ x: number; width: number }>
      expect(shape).toHaveLength(176)
      expect(shape[0]!.x).toBeGreaterThan(70)
      expect(shape[88]!.width).toBe(176)
    }
    mini().setBounds({ x: 776, y: 660, width: 424, height: 216 })
    await invoke('show', event, 'picker')
    expect(mocks.windows).toHaveLength(1)
    expect(mini().setBounds).toHaveBeenCalledWith({ x: 784, y: 280, width: 392, height: 520 })
    expect(mini().show).toHaveBeenCalledOnce()
    if (process.platform !== 'darwin') expect(mini().setShape).toHaveBeenLastCalledWith([])
    invoke('pin', event, false)
    expect(mini().setAlwaysOnTop).toHaveBeenCalledWith(false)
  })
  it('keeps native bounds fixed for details and only resizes the canvas for an explicit size change', async () => {
    const { event } = setup()
    await invoke('show', event, 'compact')
    const source = { sender: mini().webContents, senderFrame: mini().webContents.mainFrame }
    expect(invoke('orb-layout', source, { details: true })).toMatchObject({ size: 176, side: 'left', detailWidth: 224 })
    invoke('orb-layout', source, { details: false })
    invoke('orb-layout', source, { details: true })
    expect(mini().setBounds).not.toHaveBeenCalled()
    expect(invoke('orb-layout', source, { size: 256 })).toMatchObject({ size: 256, side: 'left', detailWidth: 224 })
    expect(mini().setBounds).toHaveBeenLastCalledWith({ x: 800, y: 480, width: 480, height: 296 })
    if (process.platform !== 'darwin') {
      const shape = mini().setShape.mock.calls.at(-1)![0] as Array<{ x: number; width: number }>
      expect(shape[128]).toMatchObject({ x: 224, width: 256 })
      expect(shape.at(-1)).toMatchObject({ x: 0, width: 236 })
    }
    invoke('mode', source, 'picker')
    invoke('mode', source, 'compact')
    expect(invoke('window-state', source)).toMatchObject({ orb: { size: 256, side: null, detailWidth: 0, canvas: { left: 224, top: 40 } } })
  })
  it('accepts orb layout changes only from the trusted mini renderer with bounded dimensions', async () => {
    const { event } = setup()
    await invoke('show', event, 'compact')
    const source = { sender: mini().webContents, senderFrame: mini().webContents.mainFrame }
    expect(() => invoke('orb-layout', event, { size: 180 })).toThrow('UNTRUSTED')
    expect(() => invoke('orb-layout', { ...source, senderFrame: {} }, { size: 180 })).toThrow('UNTRUSTED')
    for (const change of [null, [], { size: NaN }, { size: 127 }, { size: 281 }, { size: 176.5 }, { details: 'yes' }, { x: 100 }]) {
      expect(() => invoke('orb-layout', source, change)).toThrow('INVALID_TIMER_ORB')
    }
    invoke('mode', source, 'picker')
    expect(invoke('orb-layout', source, { size: 200 })).toEqual({ size: 176, side: null, detailWidth: 0 })
  })
  it('reframes after dragging across the screen, then opens right-side details without another window move', async () => {
    const { event } = setup()
    await invoke('show', event, 'compact')
    const source = { sender: mini().webContents, senderFrame: mini().webContents.mainFrame }
    mini().setBounds({ x: -200, y: 300, width: 424, height: 216 })
    mini().events.get('move')?.()
    await vi.advanceTimersByTimeAsync(130)
    expect(invoke('window-state', source)).toMatchObject({ orb: { canvas: { left: 24, width: 424 } } })
    expect(mini().setBounds).toHaveBeenLastCalledWith({ x: 0, y: 300, width: 424, height: 216 })
    const moves = mini().setBounds.mock.calls.length
    expect(invoke('orb-layout', source, { details: true })).toMatchObject({ side: 'right', detailWidth: 224 })
    expect(mini().setBounds.mock.calls).toHaveLength(moves)
  })
  it('keeps the reserved canvas and visible details within a small monitor with a negative origin', async () => {
    mocks.area = { x: -400, y: -100, width: 400, height: 700 }
    const { event } = setup()
    await invoke('show', event, 'compact')
    const source = { sender: mini().webContents, senderFrame: mini().webContents.mainFrame }
    expect(invoke('orb-layout', source, { details: true })).toMatchObject({ size: 176, side: 'left', detailWidth: 200 })
    expect(mini().setBounds).not.toHaveBeenCalled()
    expect(invoke('orb-layout', source, { size: 280 })).toMatchObject({ size: 224, side: 'left', detailWidth: 176 })
    expect(mini().setBounds).toHaveBeenLastCalledWith({ x: -400, y: 312, width: 400, height: 264 })
  })
  it('uses an application tray and explicitly opens its menu on right click', () => {
    const { main, event } = setup()
    mocks.trayEvents.get('click')?.()
    expect(main.show).toHaveBeenCalledOnce()
    expect(main.setIcon).toHaveBeenCalledOnce()
    expect(mocks.windows).toHaveLength(0)
    mocks.trayEvents.get('right-click')?.()
    expect(mocks.popup).toHaveBeenCalledWith(mocks.menu)
    expect(mocks.tooltip).toHaveBeenLastCalledWith('YumpooPlatform')
    menu('查找工作项…').click?.()
    expect(main.show).toHaveBeenCalledTimes(2)
    invoke('state', event, state())
    expect(menu('计时器置顶').checked).toBe(true)
    menu('计时器置顶').click?.()
    expect(menu('计时器置顶').checked).toBe(false)
    expect(invoke('window-state', event)).toMatchObject({ pinned: false })
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
    expect(mocks.windows[0]?.options).toMatchObject({ width: 424, height: 216 })
    expect(invoke('window-state', event)).toMatchObject({ mode: 'compact', savedAt })
    invoke('state', event, stopped)
    expect(mini().setBounds).not.toHaveBeenCalled()
  })
  it('detects hover across the native drag surface and keeps it while crossing into details', async () => {
    const { event } = setup()
    await invoke('show', event, 'compact')
    mini().setBounds({ x: 476, y: 360, width: 424, height: 216 })
    mocks.cursor = { x: 788, y: 450 }
    await vi.advanceTimersByTimeAsync(100)
    expect(mini().webContents.send).toHaveBeenLastCalledWith('yumpoo:timer:orb-hover', true)
    const source = { sender: mini().webContents, senderFrame: mini().webContents.mainFrame }
    invoke('orb-layout', source, { details: true })
    mocks.cursor = { x: 600, y: 470 }
    const before = mini().webContents.send.mock.calls.length
    await vi.advanceTimersByTimeAsync(200)
    expect(mini().webContents.send.mock.calls).toHaveLength(before)
    mocks.cursor = { x: 50, y: 50 }
    await vi.advanceTimersByTimeAsync(100)
    expect(mini().webContents.send).toHaveBeenLastCalledWith('yumpoo:timer:orb-hover', false)
  })
  it('shows and hides the title without moving the window or reapplying unchanged native geometry', async () => {
    const { event } = setup()
    await invoke('show', event, 'compact')
    const source = { sender: mini().webContents, senderFrame: mini().webContents.mainFrame }
    invoke('orb-layout', source, { titleVisible: true })
    if (process.platform !== 'darwin') expect(mini().setShape.mock.calls.at(-1)![0]).toContainEqual({ x: 232, y: 4, width: 160, height: 30 })
    const shapes = mini().setShape.mock.calls.length
    await invoke('show', event, 'compact', false)
    invoke('mode', source, 'compact')
    invoke('orb-layout', source, { titleVisible: true })
    expect(mini().setShape.mock.calls).toHaveLength(shapes)
    expect(invoke('window-state', source)).toMatchObject({ orb: { titleVisible: true } })
    invoke('orb-layout', source, { titleVisible: false })
    expect(mini().setBounds).not.toHaveBeenCalled()
    if (process.platform !== 'darwin') expect(mini().setShape.mock.calls.at(-1)![0]).toHaveLength(176)
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
    menu('打开 YumpooPlatform').click?.()
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
    const miniEvent = { sender: mini().webContents, senderFrame: mini().webContents.mainFrame }
    expect(() => invoke('state', miniEvent, idle)).toThrow('UNTRUSTED')
    expect(invoke('window-state', miniEvent)).toMatchObject({ surface: 'timer' })
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
})
