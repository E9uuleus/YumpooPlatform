import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DesktopBridge } from '@yumpoo/preload-contract'

const mocks = vi.hoisted(() => ({
  bridge: undefined as DesktopBridge | undefined,
  invoke: vi.fn(async () => ({ toasts: true })),
  listeners: new Map<string, (event: unknown, value: unknown) => void>(),
  remove: vi.fn(),
}))
vi.mock('electron', () => ({
  contextBridge: { exposeInMainWorld: (_name: string, value: DesktopBridge) => { mocks.bridge = value } },
  ipcRenderer: { invoke: mocks.invoke, on: (name: string, callback: (event: unknown, value: unknown) => void) => mocks.listeners.set(name, callback), removeListener: mocks.remove },
}))

beforeEach(async () => { vi.resetModules(); vi.clearAllMocks(); mocks.listeners.clear(); await import('../src/preload/index') })

describe('inbox preload bridge', () => {
  it('exposes a frozen narrow bridge with a separate preference channel', async () => {
    const bridge = mocks.bridge!.inbox!
    expect(Object.isFrozen(bridge)).toBe(true)
    const state = { accountId: null, unreadCount: 0, latest: [] }
    await bridge.publishState(state)
    await bridge.getPreferences()
    await bridge.setPreferences({ toasts: false })
    expect(mocks.invoke.mock.calls).toEqual([
      ['yumpoo:inbox:state', state], ['yumpoo:inbox:get-preferences'], ['yumpoo:inbox:preferences', { toasts: false }],
    ])
  })

  it('only delivers UUID or null open requests and removes the wrapped listener', () => {
    const listener = vi.fn()
    const off = mocks.bridge!.inbox!.onOpen(listener)
    const wrapped = mocks.listeners.get('yumpoo:inbox:open')!
    for (const value of [undefined, '', {}, '/inbox', '------------------------------------']) wrapped({}, value)
    expect(listener).not.toHaveBeenCalled()
    const id = '11111111-1111-1111-1111-111111111111'
    wrapped({}, id); wrapped({}, null)
    expect(listener.mock.calls).toEqual([[id], [null]])
    off()
    expect(mocks.remove).toHaveBeenCalledWith('yumpoo:inbox:open', wrapped)
    expect(() => mocks.bridge!.inbox!.onOpen(null as never)).toThrow(TypeError)
  })
})
