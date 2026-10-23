import { EventEmitter } from 'node:events'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BrowserWindow } from 'electron'

const mocks = vi.hoisted(() => ({
  handlers: new Map<string, (...args: unknown[]) => unknown>(),
  events: new Map<string, (event: { preventDefault(): void }) => void>(),
  quit: vi.fn(), dialog: vi.fn(async () => ({ response: 0 })),
}))
vi.mock('electron', () => ({
  app: { on: (name: string, handler: (event: { preventDefault(): void }) => void) => mocks.events.set(name, handler), quit: mocks.quit },
  ipcMain: { handle: (name: string, handler: (...args: unknown[]) => unknown) => mocks.handlers.set(name, handler) },
  dialog: { showMessageBox: mocks.dialog },
}))
import { ExitController } from '../src/main/exit-controller'

function setup(ready = true) {
  const main = Object.assign(new EventEmitter(), {
    destroyed: false, isDestroyed() { return this.destroyed }, isMinimized: () => false,
    show: vi.fn(), focus: vi.fn(), restore: vi.fn(),
    webContents: Object.assign(new EventEmitter(), { mainFrame: { url: 'https://yumpoo.example/work' }, send: vi.fn() }),
  })
  const host = { shutdown: vi.fn(), cancelled: vi.fn() }
  const controller = new ExitController(() => main as unknown as BrowserWindow, 'https://yumpoo.example', host)
  controller.install(); controller.attachMain(main as unknown as BrowserWindow)
  if (ready) controller.markReady()
  const event = { sender: main.webContents, senderFrame: main.webContents.mainFrame }
  const invoke = (channel: string, ...args: unknown[]) => mocks.handlers.get(`yumpoo:timer:${channel}`)?.(event, ...args)
  const requestId = () => main.webContents.send.mock.calls.find(call => call[0] === 'yumpoo:timer:exit-request')![1] as string
  return { controller, main, host, event, invoke, requestId }
}
beforeEach(() => { vi.useFakeTimers(); vi.clearAllMocks(); mocks.dialog.mockResolvedValue({ response: 0 }); mocks.events.clear(); mocks.handlers.clear() })
afterEach(() => vi.useRealTimers())

describe('desktop exit lifecycle', () => {
  it('offers a local choice immediately during startup and can cancel without touching timers', async () => {
    const { controller, main, host } = setup(false)
    controller.request(); controller.request()
    expect(mocks.dialog).toHaveBeenCalledOnce()
    expect(main.webContents.send).not.toHaveBeenCalledWith('yumpoo:timer:exit-request', expect.anything())
    await vi.advanceTimersByTimeAsync(0)
    expect(controller.pending).toBe(false)
    expect(host.cancelled).toHaveBeenCalledOnce()
    expect(host.shutdown).not.toHaveBeenCalled()
    expect(mocks.quit).not.toHaveBeenCalled()
  })

  it('keeps the response deadline after receipt, cancels the Web request, and ignores a late phase', async () => {
    const { controller, invoke, requestId, main, host } = setup()
    controller.request()
    const id = requestId()
    invoke('exit-received', id)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(mocks.dialog).toHaveBeenCalledOnce()
    expect(main.webContents.send).toHaveBeenCalledWith('yumpoo:timer:exit-cancelled', id)
    expect(invoke('exit-phase', id, 'prompt')).toBe(false)
    expect(host.cancelled).toHaveBeenCalledOnce()
    expect(mocks.quit).not.toHaveBeenCalled()
  })

  it('waits for a person, but bounds the subsequent save and never claims it succeeded', async () => {
    const { controller, invoke, requestId } = setup()
    controller.request()
    const id = requestId()
    expect(invoke('exit-phase', id, 'prompt')).toBe(true)
    await vi.advanceTimersByTimeAsync(120_000)
    expect(mocks.dialog).not.toHaveBeenCalled()
    invoke('exit-phase', id, 'saving')
    await vi.advanceTimersByTimeAsync(30_000)
    expect(mocks.dialog).toHaveBeenCalledOnce()
    expect(mocks.quit).not.toHaveBeenCalled()
  })

  it.each(['render-process-gone', 'navigation', 'unresponsive', 'closed'])('uses local confirmation when the main page becomes unavailable: %s', async reason => {
    const { controller, main, invoke, requestId } = setup()
    controller.request(); invoke('exit-phase', requestId(), 'prompt')
    if (reason === 'navigation') main.webContents.emit('did-start-navigation', {}, 'https://yumpoo.example/settings', false, true)
    else if (reason === 'render-process-gone') main.webContents.emit(reason)
    else { if (reason === 'closed') main.destroyed = true; main.emit(reason) }
    expect(mocks.dialog).toHaveBeenCalledOnce()
    await vi.advanceTimersByTimeAsync(0)
    expect(controller.quitting).toBe(false)
  })

  it('freezes surfaces once before normal quit, leaving will-quit available for update installation', async () => {
    const { controller, invoke, requestId, host, main } = setup()
    controller.request(); controller.request()
    expect(main.webContents.send).toHaveBeenCalledOnce()
    invoke('exit-complete', requestId(), true)
    expect(controller.quitting).toBe(true)
    expect(host.shutdown).toHaveBeenCalledOnce()
    expect(mocks.quit).not.toHaveBeenCalled()
    main.webContents.emit('render-process-gone')
    controller.request()
    await vi.advanceTimersByTimeAsync(0)
    expect(mocks.quit).toHaveBeenCalledOnce()
    const event = { preventDefault: vi.fn() }
    mocks.events.get('before-quit')?.(event)
    expect(event.preventDefault).not.toHaveBeenCalled()
    expect(mocks.dialog).not.toHaveBeenCalled()
  })

  it('can quit with no surviving main window instead of leaving the tray inert', async () => {
    const { controller, main, host } = setup()
    main.destroyed = true
    controller.request()
    await vi.advanceTimersByTimeAsync(0)
    expect(host.shutdown).toHaveBeenCalledOnce()
    expect(mocks.quit).toHaveBeenCalledOnce()
  })

  it('cancels a declined or failed save, and accepts a later exit request', () => {
    const { controller, invoke, requestId, host } = setup()
    controller.request(); invoke('exit-complete', requestId(), false)
    expect(host.cancelled).toHaveBeenCalledOnce()
    expect(controller.pending).toBe(false)
    controller.request()
    expect(controller.pending).toBe(true)
    expect(mocks.quit).not.toHaveBeenCalled()
  })

  it('rejects forged senders, subframes, mismatched completion IDs and invalid phases', () => {
    const { controller, event, invoke, requestId } = setup()
    expect(invoke('exit-phase', undefined, 'prompt')).toBe(false)
    expect(controller.pending).toBe(false)
    controller.request()
    expect(() => mocks.handlers.get('yumpoo:timer:exit-phase')?.({ ...event, sender: {} }, requestId(), 'prompt')).toThrow('UNTRUSTED_IPC_SENDER')
    expect(() => mocks.handlers.get('yumpoo:timer:exit-phase')?.({ ...event, senderFrame: { url: event.senderFrame.url } }, requestId(), 'prompt')).toThrow('UNTRUSTED_IPC_SENDER')
    expect(() => invoke('exit-phase', requestId(), 'done')).toThrow('INVALID_TIMER_EXIT_PHASE')
    expect(() => invoke('exit-complete', 'wrong', true)).toThrow('INVALID_TIMER_EXIT')
  })
})
