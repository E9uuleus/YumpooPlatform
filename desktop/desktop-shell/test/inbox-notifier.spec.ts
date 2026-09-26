import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BrowserWindow, IpcMainInvokeEvent, Tray } from 'electron'
import type { DesktopInboxState } from '@yumpoo/preload-contract'

const mocks = vi.hoisted(() => ({
  handlers: new Map<string, (...args: unknown[]) => unknown>(),
  badge: vi.fn(), alerts: [] as Array<{ options: { body: string }; events: Map<string, () => void>; close: ReturnType<typeof vi.fn> }>,
}))
vi.mock('electron', () => ({
  app: { setBadgeCount: mocks.badge },
  ipcMain: { handle: (name: string, handler: (...args: unknown[]) => unknown) => mocks.handlers.set(name, handler) },
  nativeImage: { createFromBitmap: vi.fn((bitmap: Buffer) => ({ bitmap })) },
  Notification: class {
    static isSupported = () => true
    events = new Map<string, () => void>()
    close = vi.fn()
    show = vi.fn()
    constructor(readonly options: { body: string }) { mocks.alerts.push(this) }
    on(event: string, callback: () => void) { this.events.set(event, callback) }
  },
}))
vi.mock('../src/main/application-icon', () => ({ applicationIcon: () => ({ resize: () => ({ toBitmap: () => Buffer.alloc(32 * 32 * 4) }) }) }))
import { composeUnreadBadge, inboxAlertText, InboxNotifier, InboxPreferenceStore, validInboxState } from '../src/main/inbox-notifier'

const account = '11111111-1111-1111-1111-111111111111'
const origin = 'https://yumpoo.example'
const directories: string[] = []
function item(index: number, timestamp = index): DesktopInboxState['latest'][number] {
  return { id: `22222222-2222-2222-2222-${index.toString().padStart(12, '0')}`, reason: 'MENTION', actorName: '张三', createdAt: new Date(Date.UTC(2026, 8, 26) + timestamp * 1000).toISOString() }
}
function state(...indices: number[]): DesktopInboxState { return { accountId: account, unreadCount: indices.length, latest: indices.map(index => item(index)) } }
function setup(platform: NodeJS.Platform = 'win32') {
  const main = { isDestroyed: () => false, isFocused: vi.fn(() => false), setOverlayIcon: vi.fn(), webContents: { mainFrame: { url: `${origin}/work` }, send: vi.fn() } }
  const events = new Map<string, () => void>()
  const tray = { on: (event: string, callback: () => void) => events.set(event, callback), displayBalloon: vi.fn(), removeBalloon: vi.fn() }
  const surfaces = { inboxTray: () => tray as unknown as Tray, setInboxBadge: vi.fn(), showMain: vi.fn(), isInboxPreferencesSender: vi.fn((_event: IpcMainInvokeEvent) => false) }
  const prefs = new InboxPreferenceStore()
  const notifier = new InboxNotifier(() => main as unknown as BrowserWindow, origin, surfaces, prefs, platform)
  notifier.install()
  const event = { sender: main.webContents, senderFrame: main.webContents.mainFrame }
  const publish = (value: DesktopInboxState) => mocks.handlers.get('yumpoo:inbox:state')!(event, value)
  return { notifier, main, tray, events, surfaces, prefs, event, publish }
}
beforeEach(() => { mocks.handlers.clear(); mocks.alerts.length = 0; vi.clearAllMocks(); vi.useFakeTimers() })
afterEach(async () => { vi.useRealTimers(); for (const directory of directories.splice(0)) await rm(directory, { recursive: true, force: true }) })

describe('inbox IPC and native alerts', () => {
  it('hides inbox surfaces until an authenticated state arrives and hides them again on logout', () => {
    const { publish, surfaces, main } = setup()
    expect(surfaces.setInboxBadge).toHaveBeenLastCalledWith(undefined, expect.anything())
    expect(main.setOverlayIcon).toHaveBeenLastCalledWith(null, '')
    publish(state())
    expect(surfaces.setInboxBadge).toHaveBeenLastCalledWith(0, expect.anything())
    publish({ accountId: null, unreadCount: 0, latest: [] })
    expect(surfaces.setInboxBadge).toHaveBeenLastCalledWith(undefined, expect.anything())
  })
  it('strictly validates reference-only states and rejects unknown or excessive data', () => {
    expect(validInboxState(state(1))).toBe(true)
    for (const value of [null, [], { ...state(1), title: 'secret' }, { ...state(1), unreadCount: -1 }, { ...state(1), unreadCount: 1.5 },
      { ...state(1), accountId: null }, { ...state(1), latest: [item(1), item(1)] }, state(1, 2, 3, 4, 5, 6),
      { ...state(1), latest: [{ ...item(1), reason: 'UNKNOWN' }] }, { ...state(1), latest: [{ ...item(1), actorName: 'a\nb' }] },
      { ...state(1), latest: [{ ...item(1), createdAt: 'yesterday' }] }, { ...state(1), latest: [{ ...item(1), title: 'secret' }] }]) expect(validInboxState(value)).toBe(false)
    expect(validInboxState({ accountId: null, unreadCount: 0, latest: [] })).toBe(true)
  })

  it('accepts publishing only from the trusted main frame and grants menu preferences alone', () => {
    const { event, surfaces } = setup()
    const publish = mocks.handlers.get('yumpoo:inbox:state')!
    const preferences = mocks.handlers.get('yumpoo:inbox:preferences')!
    expect(() => publish({ ...event, sender: {} }, state(1))).toThrow('UNTRUSTED')
    expect(() => publish({ ...event, senderFrame: { url: event.senderFrame.url } }, state(1))).toThrow('UNTRUSTED')
    expect(() => publish(event, { ...state(1), title: 'secret' })).toThrow('INVALID_INBOX_STATE')
    expect(() => preferences(event, { toasts: true, display: 'orb' })).toThrow('INVALID_INBOX_PREFERENCES')
    expect(() => preferences({}, { toasts: false })).toThrow('UNTRUSTED')
    surfaces.isInboxPreferencesSender.mockReturnValue(true)
    expect(preferences({}, { toasts: false })).toEqual({ toasts: false })
    expect(() => publish({}, state(1))).toThrow('UNTRUSTED')
  })

  it('establishes a baseline without backlog, including a count arriving before its list', () => {
    const { publish, tray, main, surfaces } = setup()
    publish({ ...state(), unreadCount: 2 })
    publish(state(2, 1))
    expect(tray.displayBalloon).not.toHaveBeenCalled()
    expect(surfaces.setInboxBadge).toHaveBeenLastCalledWith(2, expect.anything())
    expect(main.setOverlayIcon).toHaveBeenLastCalledWith(expect.anything(), '2 条未读')
    publish(state(3, 2, 1))
    expect(tray.displayBalloon).toHaveBeenCalledWith(expect.objectContaining({ content: '张三 提到了你', respectQuietTime: true }))
    publish(state(3, 2, 1))
    expect(tray.displayBalloon).toHaveBeenCalledOnce()
  })

  it('advances individual balloons on their lifetime or click without depending on a close event', async () => {
    const { publish, tray, events, surfaces, main } = setup()
    publish(state())
    publish(state(1, 2, 3))
    expect(tray.displayBalloon).toHaveBeenCalledOnce()
    await vi.advanceTimersByTimeAsync(10_000)
    expect(tray.displayBalloon).toHaveBeenCalledTimes(2)
    events.get('balloon-click')!()
    expect(surfaces.showMain).toHaveBeenCalledOnce()
    expect(main.webContents.send).toHaveBeenCalledWith('yumpoo:inbox:open', item(2).id)
    expect(tray.displayBalloon).toHaveBeenCalledTimes(3)
    events.get('balloon-click')!()
    expect(main.webContents.send).toHaveBeenLastCalledWith('yumpoo:inbox:open', item(3).id)
  })

  it('does not let late close events retire the next balloon after a click', async () => {
    const { publish, tray, events, main } = setup()
    publish(state()); publish(state(1, 2, 3))
    events.get('balloon-click')!()
    expect(tray.displayBalloon).toHaveBeenCalledTimes(2)
    events.get('balloon-closed')?.()
    await vi.advanceTimersByTimeAsync(500)
    events.get('balloon-closed')?.()
    expect(tray.displayBalloon).toHaveBeenCalledTimes(2)
    events.get('balloon-click')!()
    expect(main.webContents.send).toHaveBeenLastCalledWith('yumpoo:inbox:open', item(2).id)
    expect(tray.displayBalloon).toHaveBeenCalledTimes(3)
  })

  it('drops the pending queue when clicking actually focuses the main window', () => {
    const { publish, tray, events, main, surfaces } = setup()
    surfaces.showMain.mockImplementation(() => { main.isFocused.mockReturnValue(true) })
    publish(state()); publish(state(1, 2, 3))
    events.get('balloon-click')!()
    expect(main.webContents.send).toHaveBeenLastCalledWith('yumpoo:inbox:open', item(1).id)
    expect(tray.displayBalloon).toHaveBeenCalledOnce()
  })

  it('cancels old balloon lifetimes across reset and ignores old close events for the new account', async () => {
    const { publish, tray, notifier, events, main } = setup()
    publish(state()); publish(state(1, 2, 3))
    await vi.advanceTimersByTimeAsync(1000)
    notifier.reset()
    const other = '33333333-3333-3333-3333-333333333333'
    publish({ ...state(), accountId: other })
    publish({ ...state(4, 5), accountId: other })
    await vi.advanceTimersByTimeAsync(9000)
    events.get('balloon-closed')?.()
    expect(tray.displayBalloon).toHaveBeenCalledTimes(2)
    events.get('balloon-click')!()
    expect(main.webContents.send).toHaveBeenLastCalledWith('yumpoo:inbox:open', item(4).id)
    expect(tray.displayBalloon).toHaveBeenCalledTimes(3)
  })

  it('merges batches larger than three and includes count growth beyond the five-item payload', () => {
    const { publish, tray, events, main } = setup()
    publish(state())
    publish({ ...state(1, 2, 3, 4, 5), unreadCount: 10 })
    expect(tray.displayBalloon).toHaveBeenCalledOnce()
    expect(tray.displayBalloon).toHaveBeenCalledWith(expect.objectContaining({ content: '你有 10 条新通知' }))
    events.get('balloon-click')!()
    expect(main.webContents.send).toHaveBeenCalledWith('yumpoo:inbox:open', null)
  })

  it('advances watermarks while focused or muted and never replays those alerts later', () => {
    const { publish, main, tray, event } = setup()
    publish(state())
    main.isFocused.mockReturnValue(true)
    publish(state(1))
    main.isFocused.mockReturnValue(false)
    publish(state(1))
    mocks.handlers.get('yumpoo:inbox:preferences')!(event, { toasts: false })
    publish(state(2, 1))
    mocks.handlers.get('yumpoo:inbox:preferences')!(event, { toasts: true })
    publish(state(2, 1))
    expect(tray.displayBalloon).not.toHaveBeenCalled()
    publish(state(3, 2, 1))
    expect(tray.displayBalloon).toHaveBeenCalledOnce()
  })

  it('distinguishes IDs at the same timestamp and does not toast older unread items resurfacing', () => {
    const { publish, tray } = setup()
    publish(state(3))
    publish({ ...state(3, 4), latest: [item(3), item(4, 3)] })
    expect(tray.displayBalloon).toHaveBeenCalledOnce()
    publish(state(1, 2, 3))
    expect(tray.displayBalloon).toHaveBeenCalledOnce()
  })

  it('resets badges and invalidates clicks on logout and account switching', () => {
    const { publish, tray, notifier, main, surfaces, events } = setup()
    publish(state())
    publish(state(1))
    publish({ ...state(2), accountId: '33333333-3333-3333-3333-333333333333' })
    events.get('balloon-click')!()
    expect(main.webContents.send).not.toHaveBeenCalled()
    expect(tray.displayBalloon).toHaveBeenCalledOnce()
    notifier.reset()
    expect(main.setOverlayIcon).toHaveBeenLastCalledWith(null, '')
    expect(surfaces.setInboxBadge).toHaveBeenLastCalledWith(undefined, expect.anything())
    publish(state(5))
    expect(tray.displayBalloon).toHaveBeenCalledOnce()
  })

  it.each(['darwin', 'linux'] as const)('uses native Notification and app badges on %s with stale-click isolation', platform => {
    const { publish, notifier, main } = setup(platform)
    publish(state())
    publish(state(1, 2, 3))
    expect(mocks.alerts).toHaveLength(3)
    expect(mocks.badge).toHaveBeenLastCalledWith(3)
    mocks.alerts[0]!.events.get('click')!()
    expect(main.webContents.send).toHaveBeenCalledWith('yumpoo:inbox:open', item(1).id)
    main.webContents.send.mockClear()
    notifier.reset()
    expect(mocks.badge).toHaveBeenLastCalledWith(0)
    expect(mocks.alerts[0]!.close).toHaveBeenCalledOnce()
    mocks.alerts[0]!.events.get('click')!()
    expect(main.webContents.send).not.toHaveBeenCalled()
  })

  it('composes a red dot without mutating source pixels or introducing content text', () => {
    const source = Buffer.alloc(16 * 16 * 4, 100)
    const output = composeUnreadBadge(source, 16, 16)
    expect([...output.subarray((3 * 16 + 13) * 4, (3 * 16 + 13) * 4 + 4)]).toEqual([68, 68, 239, 255])
    expect(output.subarray(12 * 16 * 4)).toEqual(source.subarray(12 * 16 * 4))
    expect(source.every(byte => byte === 100)).toBe(true)
    expect(() => composeUnreadBadge(source, 8, 16)).toThrow('INVALID_BADGE_BITMAP')
    expect(inboxAlertText({ ...item(1), reason: 'PROJECT_OWNER_ASSIGNED', actorName: null })).toBe('有人 更新了你的项目成员身份')
  })
})

describe('inbox preference persistence', () => {
  it('persists independent versioned preferences atomically and serializes changes', async () => {
    const directory = await mkdtemp(path.join(tmpdir(), 'yumpoo-inbox-')); directories.push(directory)
    await writeFile(path.join(directory, 'timer-preferences.json'), '{"display":"dock"}')
    const store = new InboxPreferenceStore(directory)
    expect(store.get()).toEqual({ toasts: true })
    store.update({ toasts: false }); store.update({ toasts: true }); store.update({ toasts: false })
    await store.flush()
    expect(JSON.parse(await readFile(path.join(directory, 'inbox-preferences.json'), 'utf8'))).toEqual({ version: 1, toasts: false })
    expect(new InboxPreferenceStore(directory).get()).toEqual({ toasts: false })
    expect(await readFile(path.join(directory, 'timer-preferences.json'), 'utf8')).toBe('{"display":"dock"}')
    await expect(readFile(path.join(directory, 'inbox-preferences.json.tmp'))).rejects.toThrow()
  })

  it('falls back for corrupt, oversized, unsupported-version or unknown-key files', async () => {
    const directory = await mkdtemp(path.join(tmpdir(), 'yumpoo-inbox-')); directories.push(directory)
    for (const value of ['{', ' '.repeat(4097), '{"version":2,"toasts":false}', '{"version":1,"toasts":false,"extra":1}']) {
      await writeFile(path.join(directory, 'inbox-preferences.json'), value)
      expect(new InboxPreferenceStore(directory).get()).toEqual({ toasts: true })
    }
  })
})
