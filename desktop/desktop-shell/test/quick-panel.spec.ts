import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BrowserWindow } from 'electron'

const mocks = vi.hoisted(() => ({
  handlers: new Map<string, (...args: unknown[]) => unknown>(),
  appEvents: new Map<string, () => void>(),
  windows: [] as unknown[],
  cursor: { x: 100, y: 100 },
  display: { workArea: { x: 0, y: 0, width: 1920, height: 1040 }, bounds: { x: 0, y: 0, width: 1920, height: 1080 } },
}))
vi.mock('electron', () => ({
  app: { isPackaged: false, on: (name: string, fn: () => void) => mocks.appEvents.set(name, fn) },
  ipcMain: { handle: (name: string, fn: (...args: unknown[]) => unknown) => mocks.handlers.set(name, fn) },
  nativeImage: { createFromPath: vi.fn(() => ({ isEmpty: () => false })) },
  screen: { getCursorScreenPoint: () => mocks.cursor, getDisplayMatching: () => mocks.display, getDisplayNearestPoint: () => mocks.display },
  BrowserWindow: class {
    static getFocusedWindow = () => null
    events = new Map<string, () => void>()
    contentEvents = new Map<string, () => void>()
    webContents = { mainFrame: { url: 'https://yumpoo.example/timer/quick' }, send: vi.fn(), once: vi.fn(), removeListener: vi.fn(), on: (name: string, fn: () => void) => this.contentEvents.set(name, fn) }
    bounds = { x: 0, y: 0, width: 364, height: 500 }
    isDestroyed = () => false
    isVisible = vi.fn(() => true)
    isFocused = vi.fn(() => true)
    getBounds = vi.fn(() => ({ ...this.bounds }))
    setBounds = vi.fn((bounds: typeof this.bounds) => { this.bounds = { ...bounds } })
    setAlwaysOnTop = vi.fn()
    setSkipTaskbar = vi.fn()
    show = vi.fn()
    focus = vi.fn()
    hide = vi.fn()
    loadURL = vi.fn(async () => undefined)
    on(name: string, fn: () => void) { this.events.set(name, fn) }
    constructor() { mocks.windows.push(this) }
  },
}))
vi.mock('../src/main/security-guards', () => ({ installSecurityGuards: vi.fn() }))
import { QuickPanelController } from '../src/main/quick-panel'
import { TimerPreferenceStore } from '../src/main/timer-preferences'

type Panel = BrowserWindow & { events: Map<string, () => void>; contentEvents: Map<string, () => void> }
function setup(pinned = false) {
  const prefs = new TimerPreferenceStore()
  prefs.update({ quickPinned: pinned })
  const level = { pinned: true, aboveTaskbar: false }
  const controller = new QuickPanelController('https://yumpoo.example', '/preload.js', prefs, { openMain: vi.fn(), changed: vi.fn(), level: () => level })
  controller.install(); controller.prewarm()
  const window = mocks.windows[0] as Panel
  window.contentEvents.get('did-finish-load')!()
  const event = { sender: window.webContents, senderFrame: window.webContents.mainFrame }
  return { controller, prefs, window, level, event }
}
beforeEach(() => { vi.useFakeTimers(); mocks.windows.length = 0; mocks.handlers.clear(); mocks.appEvents.clear(); vi.clearAllMocks() })
afterEach(() => { mocks.appEvents.get('will-quit')?.(); vi.useRealTimers() })

describe('快捷工时固定小窗', () => {
  it('固定时进入任务栏，取消固定时退出任务栏', () => {
    const { controller, window } = setup()
    expect(window.setSkipTaskbar).toHaveBeenLastCalledWith(true)
    controller.setPinned(true)
    expect(window.setSkipTaskbar).toHaveBeenLastCalledWith(false)
    controller.setPinned(false)
    expect(window.setSkipTaskbar).toHaveBeenLastCalledWith(true)
  })
  it('从固定偏好恢复时保留任务栏入口', () => {
    const { window } = setup(true)
    expect(window.setSkipTaskbar).toHaveBeenLastCalledWith(false)
  })
  it('固定后失焦不隐藏', async () => {
    const { controller, window } = setup()
    controller.setPinned(true)
    window.events.get('focus')!(); window.events.get('blur')!()
    await vi.advanceTimersByTimeAsync(500)
    expect(window.hide).not.toHaveBeenCalled()
    expect(window.webContents.send).not.toHaveBeenCalledWith('yumpoo:quick:closing')
  })
  it('固定且可见时仅提到前面，不重置页面状态', () => {
    const { controller, window } = setup()
    controller.setPinned(true)
    vi.mocked(window.webContents.send).mockClear()
    expect(controller.toggle({ source: 'tray' }, [])).toBe(true)
    expect(window.show).toHaveBeenCalledOnce()
    expect(window.focus).toHaveBeenCalledOnce()
    expect(window.webContents.send).not.toHaveBeenCalled()
  })
  it('拖动时固定并记录窗口边界', () => {
    const { prefs, window } = setup()
    window.events.get('will-move')!()
    expect(prefs.get()).toMatchObject({ quickPinned: true, quickBounds: window.getBounds() })
    expect(window.webContents.send).toHaveBeenCalledWith('yumpoo:quick:pinned', true)
    window.setBounds({ x: 20, y: 30, width: 400, height: 600 })
    window.events.get('moved')!()
    expect(prefs.get().quickBounds).toEqual(window.getBounds())
  })
  it('未固定使用弹出层，固定后跟随全局置顶层级', () => {
    const { controller, window, level } = setup()
    expect(window.setAlwaysOnTop).toHaveBeenLastCalledWith(true, 'pop-up-menu')
    controller.setPinned(true)
    expect(window.setAlwaysOnTop).toHaveBeenLastCalledWith(true)
    level.aboveTaskbar = true; controller.applyLevel()
    expect(window.setAlwaysOnTop).toHaveBeenLastCalledWith(true, 'screen-saver')
    level.pinned = false; controller.applyLevel()
    expect(window.setAlwaysOnTop).toHaveBeenLastCalledWith(false)
  })
  it('固定时不占用侧边栏项目', () => {
    const { controller } = setup()
    controller.toggle({ source: 'rail', projectId: 'project', anchor: { x: 0, y: 0, width: 40, height: 40 }, side: 'left' }, [])
    expect(controller.railProject()).toBe('project')
    controller.setPinned(true)
    expect(controller.railProject()).toBeNull()
  })
  it('拒绝非布尔值的固定请求', () => {
    const { event } = setup()
    expect(() => mocks.handlers.get('yumpoo:quick:pin')!(event, 'true')).toThrow('INVALID_QUICK_PIN')
  })
})
