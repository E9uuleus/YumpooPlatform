import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
vi.mock('../../composables/useSession', async () => {
  const { ref } = await import('vue')
  const session = { phase: ref('authenticated'), authentication: ref({ user: { id: 'user' } }) }
  return { useSession: () => session }
})
vi.mock('../../composables/useInbox', async () => {
  const { ref } = await import('vue')
  const inbox = { unread: ref(2), latest: ref([]), ready: ref(true), changeState: vi.fn() }
  return { activateInbox: vi.fn(), useInbox: () => inbox }
})
import { activateInbox, useInbox } from '../../composables/useInbox'
import { NotificationReason, NotificationState, NotificationTargetKind, type NotificationItem } from '@yumpoo/api-client'
import type { DesktopInboxState } from '@yumpoo/preload-contract'
import { useSession } from '../../composables/useSession'
import InboxHost from './InboxHost.vue'

const mounted: Array<ReturnType<typeof mount>> = []
async function host(path = '/inbox') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push(path); await router.isReady()
  const wrapper = mount(InboxHost, { global: { plugins: [router] } }); mounted.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}
beforeEach(() => {
  vi.clearAllMocks(); vi.stubGlobal('yumpooDesktop', undefined); useSession().phase.value = 'authenticated'
  useInbox().latest.value = []; useInbox().ready.value = true
})
afterEach(() => { mounted.splice(0).forEach(wrapper => wrapper.unmount()); vi.unstubAllGlobals() })
describe('inbox owner', () => {
  const notification = (id: string, reason: NotificationItem['reason']): NotificationItem => ({
    id, reason, state: NotificationState.Unread, createdAt: new Date('2026-10-03T00:00:00Z'), readAt: null,
    actor: { id: 'actor', displayName: '张三' }, subject: null,
    target: { kind: NotificationTargetKind.WorkItem, accessible: true, projectId: 'target-project', workItemId: 'target-item',
      title: '不发送到桌面的目标标题', projectName: '目标项目' },
  })
  it.each([undefined, [NotificationReason.Mention]])('filters connection reasons for an old or non-supporting shell (%s) without rejecting the packet', async supportedReasons => {
    const publishState = vi.fn(async (value: DesktopInboxState) => {
      if (value.latest.some(item => item.reason !== 'MENTION')) throw new Error('OLD_SHELL_REJECTED_PACKET')
    })
    useInbox().latest.value = [notification('connection', NotificationReason.ConnectionCreated), notification('mention', NotificationReason.Mention)]
    vi.stubGlobal('yumpooDesktop', { timer: { getWindowState: async () => ({ surface: 'main' }) },
      inbox: { supportedReasons, publishState, onOpen: () => vi.fn() } })
    await host()
    expect(publishState).toHaveBeenCalledOnce()
    expect(publishState.mock.lastCall![0]).toEqual({ accountId: 'user', unreadCount: 2, latest: [
      { id: 'mention', reason: 'MENTION', actorName: '张三', createdAt: '2026-10-03T00:00:00.000Z' },
    ] })
    await expect(publishState.mock.results[0]!.value).resolves.toBeUndefined()
  })
  it('forwards a connection reason only after shell capability declaration and keeps the desktop payload minimal', async () => {
    const publishState = vi.fn(async (_value: DesktopInboxState) => undefined)
    useInbox().latest.value = [notification('connection', NotificationReason.ConnectionCreated)]
    vi.stubGlobal('yumpooDesktop', { timer: { getWindowState: async () => ({ surface: 'main' }) },
      inbox: { supportedReasons: ['CONNECTION_CREATED'], publishState, onOpen: () => vi.fn() } })
    await host()
    expect(publishState.mock.lastCall![0].latest).toEqual([
      { id: 'connection', reason: 'CONNECTION_CREATED', actorName: '张三', createdAt: '2026-10-03T00:00:00.000Z' },
    ])
    expect(JSON.stringify(publishState.mock.lastCall![0])).not.toMatch(/目标标题|target-project|target-item|projectName/)
  })
  it('activates in Web and stops on logout', async () => {
    await host()
    expect(activateInbox).toHaveBeenLastCalledWith(true)
    useSession().phase.value = 'anonymous'; await flushPromises()
    expect(activateInbox).toHaveBeenLastCalledWith(false)
  })
  it('does not activate or publish from timer windows', async () => {
    const publishState = vi.fn(async () => undefined)
    vi.stubGlobal('yumpooDesktop', { timer: { getWindowState: async () => ({ surface: 'timer' }) }, inbox: { publishState, onOpen: () => vi.fn() } })
    await host('/timer-menu')
    expect(activateInbox).toHaveBeenLastCalledWith(false)
    expect(publishState).not.toHaveBeenCalled()
  })
  it('publishes only from the main window and resets on logout', async () => {
    const publishState = vi.fn(async () => undefined)
    vi.stubGlobal('yumpooDesktop', { timer: { getWindowState: async () => ({ surface: 'main' }) }, inbox: { publishState, onOpen: () => vi.fn() } })
    await host()
    expect(publishState).toHaveBeenLastCalledWith({ accountId: 'user', unreadCount: 2, latest: [] })
    useSession().phase.value = 'anonymous'; await flushPromises()
    expect(publishState).toHaveBeenLastCalledWith({ accountId: null, unreadCount: 0, latest: [] })
  })
})
