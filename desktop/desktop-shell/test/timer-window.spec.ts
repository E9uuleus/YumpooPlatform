import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import type { BrowserWindow } from 'electron'

const mocks = vi.hoisted(() => ({
  handlers: new Map<string, (...args: unknown[]) => unknown>(),
  appEvents: new Map<string, (...args: unknown[]) => void>(),
  windows: [] as Array<Record<string, unknown>>,
  quit: vi.fn(),
}))
vi.mock('electron', () => ({
  app: { isPackaged: false, quit: mocks.quit, on: (name: string, fn: (...args: unknown[]) => void) => mocks.appEvents.set(name, fn) },
  ipcMain: { handle: (name: string, fn: (...args: unknown[]) => unknown) => mocks.handlers.set(name, fn) },
  dialog: { showMessageBox: vi.fn(async () => ({ response: 0 })) },
  BrowserWindow: class {
    events = new Map<string, (...args: unknown[]) => void>()
    webContents = { mainFrame: { url: 'https://yumpoo.example/timer' }, send: vi.fn() }
    isDestroyed = () => false
    showInactive = vi.fn()
    hide = vi.fn()
    setAlwaysOnTop = vi.fn()
    loadURL = vi.fn(async () => undefined)
    constructor(options: unknown) { mocks.windows.push({ options, instance: this }) }
    on(name: string, fn: (...args: unknown[]) => void) { this.events.set(name, fn) }
    once(name: string, fn: (...args: unknown[]) => void) { this.events.set(name, fn) }
  },
}))
vi.mock('../src/main/security-guards', () => ({ installSecurityGuards: vi.fn() }))
import { TimerWindowController } from '../src/main/timer-window'

function setup() {
  const events = new Map<string, (event: { preventDefault: () => void }) => void>()
  const main = { isDestroyed: () => false, isMinimized: () => false, show: vi.fn(), focus: vi.fn(),
    webContents: { mainFrame: { url: 'https://yumpoo.example/work' }, send: vi.fn() },
    on: (name: string, fn: (event: { preventDefault: () => void }) => void) => events.set(name, fn),
  }
  const controller = new TimerWindowController(() => main as unknown as BrowserWindow, 'https://yumpoo.example', '/preload.js')
  controller.install(); controller.attachMain(main as unknown as BrowserWindow)
  const event = { sender: main.webContents, senderFrame: main.webContents.mainFrame }
  return { main, event, events }
}
beforeEach(() => { mocks.handlers.clear(); mocks.appEvents.clear(); mocks.windows.length = 0; mocks.quit.mockClear(); vi.useFakeTimers() })
afterEach(() => vi.useRealTimers())

describe('desktop timer window', () => {
  it('rejects forged windows, subframes and malformed context', async () => {
    const { event } = setup()
    await expect(mocks.handlers.get('yumpoo:timer:project')?.({ ...event, sender: {} }, crypto.randomUUID())).rejects.toThrow('UNTRUSTED')
    await expect(mocks.handlers.get('yumpoo:timer:project')?.({ ...event, senderFrame: { url: event.senderFrame.url } }, crypto.randomUUID())).rejects.toThrow('UNTRUSTED')
    await expect(mocks.handlers.get('yumpoo:timer:project')?.(event, 'https://attacker.example')).rejects.toThrow('INVALID_TIMER_PROJECT')
    expect(mocks.windows).toHaveLength(0)
  })
  it('reuses one isolated window and does not reshow it on same-project navigation', async () => {
    const { event } = setup()
    const project = crypto.randomUUID()
    await mocks.handlers.get('yumpoo:timer:project')?.(event, project)
    await mocks.handlers.get('yumpoo:timer:project')?.(event, project)
    expect(mocks.windows).toHaveLength(1)
    expect(mocks.windows[0]?.options).toMatchObject({ alwaysOnTop: true, show: false, webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false, partition: 'yumpoo-authenticated' } })
    const mini = mocks.windows[0]?.instance as { showInactive: ReturnType<typeof vi.fn> }
    expect(mini.showInactive).not.toHaveBeenCalled()
    await mocks.handlers.get('yumpoo:timer:show')?.(event)
    expect(mini.showInactive).toHaveBeenCalledOnce()
  })
  it('deduplicates exit and only quits after an acknowledged affirmative response', async () => {
    const { events, main, event } = setup()
    events.get('close')?.({ preventDefault: vi.fn() })
    events.get('close')?.({ preventDefault: vi.fn() })
    expect(main.webContents.send).toHaveBeenCalledOnce()
    const id = main.webContents.send.mock.calls[0]?.[1] as string
    expect(() => mocks.handlers.get('yumpoo:timer:exit-complete')?.(event, crypto.randomUUID(), true)).toThrow('INVALID_TIMER_EXIT')
    await mocks.handlers.get('yumpoo:timer:exit-received')?.(event, id)
    await vi.advanceTimersByTimeAsync(40000)
    expect(mocks.quit).not.toHaveBeenCalled()
    await mocks.handlers.get('yumpoo:timer:exit-complete')?.(event, id, false)
    expect(mocks.quit).not.toHaveBeenCalled()
    events.get('close')?.({ preventDefault: vi.fn() })
    const next = main.webContents.send.mock.calls[1]?.[1] as string
    await mocks.handlers.get('yumpoo:timer:exit-complete')?.(event, next, true)
    expect(mocks.quit).toHaveBeenCalledOnce()
  })
})
