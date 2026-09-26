import { flushPromises } from '@vue/test-utils'
import { effectScope, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ getNotificationUnreadCounts: vi.fn(), listNotifications: vi.fn(), markNotificationRead: vi.fn(), markNotificationUnread: vi.fn(), archiveNotification: vi.fn(), markAllNotificationsRead: vi.fn() }))
vi.mock('../api/client', () => ({ notificationsApi: api }))
vi.mock('@yumpoo/api-client', async importOriginal => ({ ...await importOriginal<object>(), readCsrfToken: () => 'csrf' }))
import { activateInbox, useInbox, useInboxList, type InboxFilter } from './useInbox'

const now = new Date('2026-09-26T12:00:00Z')
const counts = (total = 2, newestUnreadAt: Date | null = now) => ({ total, mention: total, comment: 0, assigned: 0, project: 0, newestUnreadAt, serverNow: now })
beforeEach(() => {
  vi.useFakeTimers(); vi.clearAllMocks(); vi.stubGlobal('BroadcastChannel', undefined)
  api.getNotificationUnreadCounts.mockResolvedValue(counts())
  api.listNotifications.mockResolvedValue({ items: [], nextCursor: null, serverNow: now })
})
afterEach(() => { activateInbox(false); vi.useRealTimers(); vi.unstubAllGlobals() })
describe('inbox synchronization', () => {
  it('polls counts every 30 seconds and reuses the initial list until the newest unread changes', async () => {
    activateInbox(true); await flushPromises()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(api.getNotificationUnreadCounts).toHaveBeenCalledTimes(2)
    expect(api.listNotifications).toHaveBeenCalledOnce()
    api.getNotificationUnreadCounts.mockResolvedValue(counts(3, new Date(now.getTime() + 1)))
    window.dispatchEvent(new Event('focus')); await flushPromises()
    expect(api.listNotifications).toHaveBeenCalledTimes(2)
  })
  it('deduplicates refreshes and discards a previous account response', async () => {
    let resolve!: (value: ReturnType<typeof counts>) => void
    api.getNotificationUnreadCounts.mockImplementationOnce(() => new Promise(done => { resolve = done }))
    activateInbox(true)
    const pending = useInbox().refresh()
    expect(api.getNotificationUnreadCounts).toHaveBeenCalledOnce()
    activateInbox(false); resolve(counts(99)); await pending
    expect(useInbox().unread.value).toBe(0)
    expect(useInbox().latest.value).toEqual([])
    expect(api.listNotifications).not.toHaveBeenCalled()
  })
  it('suppresses hidden Web polling but keeps the desktop main window polling', async () => {
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    activateInbox(true); await flushPromises()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(api.getNotificationUnreadCounts).toHaveBeenCalledOnce()
    vi.stubGlobal('yumpooDesktop', {})
    await vi.advanceTimersByTimeAsync(30_000)
    expect(api.getNotificationUnreadCounts).toHaveBeenCalledTimes(2)
    visibility.mockRestore()
  })
  it('contains polling errors and retries on reconnect', async () => {
    api.getNotificationUnreadCounts.mockRejectedValueOnce(new Error('offline'))
    activateInbox(true); await flushPromises()
    expect(useInbox().ready.value).toBe(false)
    window.dispatchEvent(new Event('online')); await flushPromises()
    expect(useInbox().unread.value).toBe(2)
  })
  it('sends the displayed server watermark and selected group when marking all read', async () => {
    activateInbox(true); await flushPromises()
    api.markAllNotificationsRead.mockResolvedValue(counts(0, null))
    api.getNotificationUnreadCounts.mockResolvedValue(counts(0, null))
    expect(await useInbox().readAll(now, 'mention')).toBe(true)
    expect(api.markAllNotificationsRead).toHaveBeenCalledWith({ xXSRFTOKEN: 'csrf', notificationReadAllRequest: { upTo: now, group: 'MENTION' } })
    expect(useInbox().unread.value).toBe(0)
  })
  it('does not let a slow list overwrite a newer filter', async () => {
    activateInbox(true); await flushPromises()
    let resolve!: (value: unknown) => void
    api.listNotifications.mockImplementationOnce(() => new Promise(done => { resolve = done }))
    const filter = ref<InboxFilter>('unread'), scope = effectScope()
    const list = scope.run(() => useInboxList(filter))!
    filter.value = 'archived'; await flushPromises()
    resolve({ items: [{ id: 'obsolete' }], nextCursor: null, serverNow: now }); await flushPromises()
    expect(list.items.value).toEqual([])
    expect(api.listNotifications).toHaveBeenLastCalledWith({ state: 'ARCHIVED', limit: 20 })
    scope.stop()
  })
  it('clears cached rows before switching accounts even if the next account request fails', async () => {
    activateInbox(true); await flushPromises()
    api.listNotifications.mockResolvedValue({ items: [{ id: 'private-row' }], nextCursor: 'private-cursor', serverNow: now })
    const scope = effectScope(), list = scope.run(() => useInboxList(ref('all')))!
    await flushPromises()
    expect(list.items.value).toHaveLength(1)
    api.listNotifications.mockRejectedValue(new Error('offline'))
    activateInbox(true); await flushPromises()
    expect(list.items.value).toEqual([])
    expect(list.cursor.value).toBeUndefined()
    expect(list.serverNow.value).toBeUndefined()
    scope.stop()
  })
  it('clears the desktop list immediately on read-all and ignores an older pending poll', async () => {
    api.listNotifications.mockResolvedValue({ items: [{ id: 'unread-one' }], nextCursor: null, serverNow: now })
    activateInbox(true); await flushPromises()
    let resolve!: (value: ReturnType<typeof counts>) => void
    api.getNotificationUnreadCounts.mockImplementationOnce(() => new Promise(done => { resolve = done }))
    const pending = useInbox().refresh()
    api.markAllNotificationsRead.mockResolvedValue(counts(0, null))
    api.getNotificationUnreadCounts.mockResolvedValue(counts(0, null))
    api.listNotifications.mockResolvedValue({ items: [], nextCursor: null, serverNow: now })
    await useInbox().readAll(now, 'all')
    expect(useInbox().unread.value).toBe(0)
    expect(useInbox().latest.value).toEqual([])
    resolve(counts(2)); await pending; await flushPromises()
    expect(useInbox().unread.value).toBe(0)
    expect(useInbox().latest.value).toEqual([])
  })
})
