import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TimerMenuState } from '@yumpoo/preload-contract'

const mocks = vi.hoisted(() => ({
  handlers: new Map<string, (...args: unknown[]) => unknown>(),
  windows: [] as Array<{ options: Record<string, unknown>; instance: unknown }>,
  cursor: { x: 1200, y: 780 },
  focused: null as unknown,
  grant: true,
  load: vi.fn(async (): Promise<void> => undefined),
}))
vi.mock('electron', () => ({
  app: { isPackaged: false },
  ipcMain: { handle: (name: string, fn: (...args: unknown[]) => unknown) => mocks.handlers.set(name, fn) },
  screen: {
    getCursorScreenPoint: () => mocks.cursor,
    getDisplayNearestPoint: () => ({ bounds: { x: 0, y: 0, width: 1280, height: 800 }, workArea: { x: 0, y: 0, width: 1280, height: 752 } }),
  },
  BrowserWindow: class {
    static getFocusedWindow = () => mocks.focused
    events = new Map<string, (...args: unknown[]) => void>()
    contentEvents = new Map<string, (...args: unknown[]) => void>()
    webContents = { mainFrame: { url: 'https://yumpoo.example/timer/menu' }, send: vi.fn(), on: (name: string, fn: (...args: unknown[]) => void) => this.contentEvents.set(name, fn) }
    destroyed = false
    visible = false
    isDestroyed = () => this.destroyed
    isVisible = () => this.visible
    destroy = vi.fn(() => { this.destroyed = true; this.events.get('closed')?.() })
    setAlwaysOnTop = vi.fn()
    setBounds = vi.fn()
    show = vi.fn(() => { this.visible = true })
    hide = vi.fn(() => { this.visible = false })
    focus = vi.fn(() => { if (mocks.grant) { mocks.focused = this; this.events.get('focus')?.() } })
    isFocused = () => mocks.focused === this
    loadURL = mocks.load
    constructor(options: Record<string, unknown>) { mocks.windows.push({ options, instance: this }) }
    on(name: string, fn: (...args: unknown[]) => void) { this.events.set(name, fn) }
  },
}))
vi.mock('../src/main/security-guards', () => ({ installSecurityGuards: vi.fn() }))
import { TimerQuickMenu } from '../src/main/timer-menu'

type Popup = { events: Map<string, (...args: unknown[]) => void>; contentEvents: Map<string, (...args: unknown[]) => void>; webContents: { mainFrame: { url: string }; send: ReturnType<typeof vi.fn> }; visible: boolean; show: ReturnType<typeof vi.fn>; hide: ReturnType<typeof vi.fn>; setBounds: ReturnType<typeof vi.fn>; setAlwaysOnTop: ReturnType<typeof vi.fn>; destroy: ReturnType<typeof vi.fn> }
const popup = (index = 0) => mocks.windows[index]!.instance as Popup
const state: TimerMenuState = { signedIn: true, ready: true, enabled: true, running: null, recent: { title: '最近工作' }, clockOffsetMs: 0, savedAt: 0, visible: true, display: 'orb', pinned: true }
function setup() {
  const onAction = vi.fn()
  const menu = new TimerQuickMenu('https://yumpoo.example', '/preload.js', onAction)
  menu.install()
  return { menu, onAction }
}
const act = (event: unknown, action: unknown) => mocks.handlers.get('yumpoo:timer:menu-action')?.(event, action)
const sender = () => ({ sender: popup().webContents, senderFrame: popup().webContents.mainFrame })

beforeEach(() => { mocks.handlers.clear(); mocks.windows.length = 0; mocks.cursor = { x: 1200, y: 780 }; mocks.focused = null; mocks.grant = true; vi.clearAllMocks(); vi.useFakeTimers() })
afterEach(() => { vi.useRealTimers() })

describe('timer quick panel window', () => {
  it('prewarms one hidden isolated popup and only opens after the page has loaded', () => {
    const { menu } = setup()
    expect(menu.open('tray', state)).toBe(false)
    menu.prewarm()
    expect(mocks.windows).toHaveLength(1)
    expect(mocks.windows[0]!.options).toMatchObject({ frame: false, transparent: true, resizable: false, movable: false, skipTaskbar: true, type: 'toolbar', show: false, width: 312, height: 356,
      webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false, partition: 'yumpoo-authenticated' } })
    expect(popup().setAlwaysOnTop).toHaveBeenCalledWith(true, 'pop-up-menu')
    expect(mocks.load).toHaveBeenCalledWith('https://yumpoo.example/timer/menu')
    expect(menu.open('tray', state)).toBe(false)
    popup().contentEvents.get('did-finish-load')?.()
    expect(menu.open('tray', state)).toBe(true)
    expect(popup().setBounds).toHaveBeenCalledWith({ x: 968, y: 396, width: 312, height: 356 })
    expect(popup().webContents.send).toHaveBeenCalledWith('yumpoo:timer:menu-state', state)
    expect(popup().show).toHaveBeenCalledOnce()
    menu.update({ ...state, pinned: false })
    expect(popup().webContents.send).toHaveBeenLastCalledWith('yumpoo:timer:menu-state', { ...state, pinned: false })
  })

  it('hides on blur only after gaining focus and treats a quick second tray click as close', () => {
    const { menu } = setup()
    menu.prewarm(); popup().contentEvents.get('did-finish-load')?.()
    menu.open('pointer', state)
    popup().events.get('blur')?.()
    expect(popup().hide).toHaveBeenCalledOnce()
    expect(menu.open('tray', state)).toBe(true)
    expect(popup().show).toHaveBeenCalledOnce()
    popup().visible = true
    expect(menu.open('tray', state)).toBe(true)
    expect(popup().hide).toHaveBeenCalledTimes(2)
    popup().events.get('blur')?.()
    expect(popup().hide).toHaveBeenCalledTimes(2)
    menu.update(state)
    expect(popup().webContents.send).toHaveBeenCalledOnce()
  })

  it('closes when focus leaves the window that opened it even though the panel never took focus', async () => {
    const { menu } = setup()
    menu.prewarm(); popup().contentEvents.get('did-finish-load')?.()
    const orb = { title: 'orb' }
    mocks.focused = orb; mocks.grant = false
    menu.open('pointer', state)
    await vi.advanceTimersByTimeAsync(500)
    expect(popup().hide).not.toHaveBeenCalled()
    mocks.focused = null
    await vi.advanceTimersByTimeAsync(150)
    expect(popup().hide).toHaveBeenCalledOnce()
    await vi.advanceTimersByTimeAsync(300)
    menu.open('tray', state)
    await vi.advanceTimersByTimeAsync(1000)
    expect(popup().hide).toHaveBeenCalledOnce()
    expect(popup().show).toHaveBeenCalledTimes(2)
  })

  it('closes a focused panel once focus moves anywhere else, even without a blur event', async () => {
    const { menu } = setup()
    menu.prewarm(); popup().contentEvents.get('did-finish-load')?.()
    menu.open('pointer', state)
    await vi.advanceTimersByTimeAsync(500)
    expect(popup().hide).not.toHaveBeenCalled()
    mocks.focused = { title: 'main' }
    await vi.advanceTimersByTimeAsync(150)
    expect(popup().hide).toHaveBeenCalledOnce()
  })

  it('runs only closed actions from its own main frame and keeps toggles open', () => {
    const { menu, onAction } = setup()
    menu.prewarm(); popup().contentEvents.get('did-finish-load')?.()
    menu.open('pointer', state)
    expect(() => act({ sender: {}, senderFrame: {} }, 'toggle')).toThrow('UNTRUSTED')
    expect(() => act({ ...sender(), senderFrame: { url: 'https://yumpoo.example/timer/menu' } }, 'toggle')).toThrow('UNTRUSTED')
    expect(() => act(sender(), 'delete-everything')).toThrow('INVALID_TIMER_MENU_ACTION')
    act(sender(), 'toggle')
    act(sender(), 'show-dock')
    expect(popup().hide).not.toHaveBeenCalled()
    act(sender(), 'find')
    expect(popup().hide).toHaveBeenCalledOnce()
    expect(onAction.mock.calls.map(call => call[0])).toEqual(['toggle', 'show-dock', 'find'])
    expect(menu.isSender(sender() as never)).toBe(true)
  })

  it('recreates the popup after a renderer crash or failed load', async () => {
    const { menu } = setup()
    menu.prewarm(); popup().contentEvents.get('did-finish-load')?.()
    popup().contentEvents.get('render-process-gone')?.()
    expect(popup().destroy).toHaveBeenCalledOnce()
    expect(menu.open('tray', state)).toBe(false)
    expect(mocks.windows).toHaveLength(2)
    mocks.load.mockRejectedValueOnce(new Error('offline'))
    mocks.windows.length = 0
    const failed = new TimerQuickMenu('https://yumpoo.example', '/preload.js', vi.fn())
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    failed.prewarm()
    await vi.waitFor(() => expect(popup().destroy).toHaveBeenCalledOnce())
    expect(error).toHaveBeenCalledWith('[YUMPOO_TIMER_MENU_LOAD_FAILED]')
    expect(failed.open('tray', state)).toBe(false)
    error.mockRestore()
  })
})
