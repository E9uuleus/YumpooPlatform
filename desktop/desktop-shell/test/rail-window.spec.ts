import { afterEach, beforeEach, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => {
  class Window {
    webContents = { mainFrame: { url: 'https://yumpoo.example/timer/rail' }, send: vi.fn(), on: vi.fn() }
    bounds: { x: number; y: number; width: number; height: number }
    destroyed = false
    constructor(bounds: Window['bounds']) { this.bounds = bounds; windows.push(this) }
    isDestroyed() { return this.destroyed }
    destroy() { this.destroyed = true }
    getBounds() { return this.bounds }
    setBounds(bounds: Window['bounds']) { this.bounds = bounds }
    isVisible() { return true }
    setShape = vi.fn()
    setAlwaysOnTop = vi.fn()
    on = vi.fn()
    once = vi.fn()
  }
  const windows: Window[] = []
  return { Window, windows, handlers: new Map<string, (...args: unknown[]) => unknown>(), area: { x: 0, y: 0, width: 1280, height: 800 } }
})
vi.mock('electron', () => ({
  app: { isPackaged: false, on: vi.fn() },
  ipcMain: { handle: (name: string, handler: (...args: unknown[]) => unknown) => mocks.handlers.set(name, handler) },
  BrowserWindow: mocks.Window,
  screen: { getPrimaryDisplay: () => ({ workArea: mocks.area }), getDisplayMatching: () => ({ workArea: mocks.area }) },
}))
vi.mock('../src/main/window-policy', () => ({ createWindowOptions: () => ({}) }))
vi.mock('../src/main/security-guards', () => ({ installSecurityGuards: vi.fn() }))
vi.mock('../src/main/application-icon', () => ({ applicationIcon: () => undefined }))
vi.mock('../src/main/surface-load', () => ({ loadSurface: async () => undefined }))
import { RailController } from '../src/main/rail-window'
import { DesktopPreferenceStore } from '../src/main/desktop-preferences'
import { TimerPreferenceStore } from '../src/main/timer-preferences'
import { MAX_RAIL_PROJECTS, placeRail } from '../src/main/timer-geometry'

beforeEach(() => { vi.useFakeTimers(); mocks.handlers.clear(); mocks.windows.length = 0 })
afterEach(() => { vi.clearAllTimers(); vi.useRealTimers() })

it('可见数量 IPC 拒绝不可信来源和非法计数，按可见项收起空位并在重建时清除', () => {
  const prefs = new DesktopPreferenceStore()
  prefs.update({ railProjects: { owner: `${crypto.randomUUID()}:${crypto.randomUUID()}`, items: [
    { id: crypto.randomUUID(), name: '研发' }, { id: crypto.randomUUID(), name: '运营' },
  ] } })
  const timer = new TimerPreferenceStore()
  const controller = new RailController('https://yumpoo.example', '/preload.js', timer, prefs, {
    signedIn: () => true, timer: () => false, pinned: () => false, quickProject: () => null,
    openProject: vi.fn(), openSettings: vi.fn(), openMenu: vi.fn(),
  })
  controller.install()
  controller.sync()
  const window = mocks.windows[0]!
  const event = { sender: window.webContents, senderFrame: window.webContents.mainFrame }
  const invoke = mocks.handlers.get('yumpoo:rail:visible-count')!
  const height = (count: number) => placeRail(timer.get().dockSide, timer.get().dockY, prefs.get().railSize, count, false, mocks.area).bounds.height
  expect(window.getBounds().height).toBe(height(2))
  expect(() => invoke({ ...event, sender: {} }, -1)).toThrow('UNTRUSTED_IPC_SENDER')
  for (const count of [undefined, null, '1', -1, .5, NaN, Infinity, MAX_RAIL_PROJECTS + 1]) {
    expect(() => invoke(event, count)).toThrow('INVALID_RAIL_VISIBLE_COUNT')
  }
  invoke(event, 1)
  expect(window.getBounds().height).toBe(height(1))
  expect(prefs.get().railProjects!.items).toHaveLength(2)
  invoke(event, 0)
  expect(window.getBounds().height).toBe(height(0))
  invoke(event, MAX_RAIL_PROJECTS)
  expect(window.getBounds().height).toBe(height(2))
  invoke(event, 1)
  controller.destroy()
  controller.sync()
  expect(mocks.windows[1]!.getBounds().height).toBe(height(2))
  controller.stop()
})
