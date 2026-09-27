import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { NotificationReason, NotificationState, NotificationTargetKind, type ListNotificationsRequest, type NotificationItem } from '@yumpoo/api-client'

const api = vi.hoisted(() => ({ getNotificationUnreadCounts: vi.fn(), listNotifications: vi.fn(), markNotificationRead: vi.fn(), markNotificationUnread: vi.fn(), archiveNotification: vi.fn(), markAllNotificationsRead: vi.fn() }))
vi.mock('../../api/client', () => ({ notificationsApi: api }))
vi.mock('@yumpoo/api-client', async importOriginal => ({ ...await importOriginal<object>(), readCsrfToken: () => 'csrf' }))
vi.mock('../../composables/useSession', async () => {
  const { ref } = await import('vue')
  return { useSession: () => ({ authentication: ref({ user: { id: 'reader' }, company: { timezone: 'America/New_York' } }) }) }
})
import { activateInbox } from '../../composables/useInbox'
import InboxView from './InboxView.vue'

const now = new Date('2026-09-26T12:00:00Z')
const pageTime = new Date('2026-09-26T11:59:00Z')
const counts = (total = 2) => ({ total, mention: total, comment: 0, assigned: 0, project: 0, newestUnreadAt: total ? now : null, serverNow: now })
const item = (id = 'one', accessible = true): NotificationItem => ({ id, reason: NotificationReason.Mention, state: NotificationState.Unread, readAt: null, createdAt: new Date('2026-09-26T03:00:00Z'), actor: { id: 'actor', displayName: '张三' }, subject: null,
  target: { kind: NotificationTargetKind.WorkItemUpdate, accessible, projectId: 'project', workItemId: 'work', itemNo: 'YP-1', title: '需求评审', excerpt: '评论正文', projectName: '项目名称' } })
const wrappers: Array<ReturnType<typeof mount>> = []
let intersect: (entries: Array<{ isIntersecting: boolean }>) => void
const disconnect = vi.fn()

async function setup(query = '') {
  activateInbox(true); await flushPromises()
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push(`/inbox${query}`); await router.isReady()
  const wrapper = mount(InboxView, { global: { plugins: [router] } })
  wrappers.push(wrapper); await flushPromises()
  return { wrapper, router }
}
const button = (wrapper: ReturnType<typeof mount>, text: string) => wrapper.findAll('button').find(candidate => candidate.text() === text)!

beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('BroadcastChannel', undefined)
  vi.stubGlobal('IntersectionObserver', class {
    observe = vi.fn()
    disconnect = disconnect
    constructor(callback: typeof intersect) { intersect = callback }
  })
  api.getNotificationUnreadCounts.mockResolvedValue(counts())
  api.listNotifications.mockImplementation(async (query: ListNotificationsRequest) => ({ items: query.limit === 5 ? [] : [item()], nextCursor: null, serverNow: pageTime }))
  api.markAllNotificationsRead.mockResolvedValue(counts(0))
  api.markNotificationRead.mockResolvedValue(counts(1))
  api.archiveNotification.mockResolvedValue(counts(1))
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); activateInbox(false); vi.unstubAllGlobals() })

describe('inbox page', () => {
  it('maps route filters to list state/group and hides read-all for archives', async () => {
    const { wrapper, router } = await setup('?filter=mention')
    expect(api.listNotifications).toHaveBeenLastCalledWith({ state: 'ALL', group: 'MENTION', limit: 20 })
    await router.push('/inbox?filter=comment'); await flushPromises()
    expect(api.listNotifications).toHaveBeenLastCalledWith({ state: 'ALL', group: 'COMMENT', limit: 20 })
    await router.push('/inbox?filter=archived'); await flushPromises()
    expect(api.listNotifications).toHaveBeenLastCalledWith({ state: 'ARCHIVED', limit: 20 })
    expect(button(wrapper, '全部标为已读')).toBeUndefined()
    await router.push('/inbox?filter=invalid'); await flushPromises()
    expect(api.listNotifications).toHaveBeenLastCalledWith({ state: 'UNREAD', limit: 20 })
  })

  it('marks the displayed group read only through the page response watermark', async () => {
    const { wrapper } = await setup('?filter=project')
    await button(wrapper, '全部标为已读').trigger('click'); await flushPromises()
    expect(api.markAllNotificationsRead).toHaveBeenCalledWith({ xXSRFTOKEN: 'csrf', notificationReadAllRequest: { upTo: pageTime, group: 'PROJECT' } })
  })

  it('loads more at the sentinel and retries the same failed cursor without duplicating rows', async () => {
    let failed = false
    api.listNotifications.mockImplementation(async (query: ListNotificationsRequest) => {
      if (query.limit === 5) return { items: [], nextCursor: null, serverNow: now }
      if (query.cursor && !failed) { failed = true; throw new Error('offline') }
      return { items: query.cursor ? [item(), item('two')] : [item()], nextCursor: query.cursor ? null : 'next-page', serverNow: pageTime }
    })
    const { wrapper } = await setup()
    expect(wrapper.findAll('.inbox-row')).toHaveLength(1)
    intersect([{ isIntersecting: true }]); await flushPromises()
    expect(wrapper.text()).toContain('通知暂时无法加载')
    await button(wrapper, '重试').trigger('click'); await flushPromises()
    expect(api.listNotifications).toHaveBeenLastCalledWith({ state: 'UNREAD', cursor: 'next-page', limit: 20 })
    expect(wrapper.findAll('.inbox-row')).toHaveLength(2)
    wrapper.unmount(); wrappers.pop()
    expect(disconnect).toHaveBeenCalledOnce()
  })

  it('shows the actor and removal reason without disclosing an inaccessible project', async () => {
    const removed = { ...item('removed', false), reason: NotificationReason.ProjectMemberRemoved, subject: { id: 'reader', displayName: '李四' } }
    api.listNotifications.mockResolvedValue({ items: [removed], nextCursor: null, serverNow: pageTime })
    const { wrapper, router } = await setup()
    expect(wrapper.get('.inbox-row__sentence').text()).toBe('张三 将你移出了一个项目')
    expect(wrapper.text()).not.toContain('项目名称')
    expect(wrapper.text()).not.toContain('相关内容已不可访问')
    expect(wrapper.find('[role="link"]').exists()).toBe(false)
    await wrapper.get('.inbox-row__body').trigger('click')
    expect(router.currentRoute.value.path).toBe('/inbox')
  })

  it('keeps inaccessible content hidden and non-navigable while allowing archive', async () => {
    api.listNotifications.mockResolvedValue({ items: [item('hidden', false)], nextCursor: null, serverNow: pageTime })
    const { wrapper, router } = await setup()
    const body = wrapper.get('.inbox-row__body')
    expect(body.attributes('role')).toBeUndefined()
    expect(body.attributes('tabindex')).toBeUndefined()
    expect(wrapper.text()).toContain('相关内容已不可访问')
    expect(wrapper.text()).not.toContain('需求评审')
    expect(wrapper.text()).not.toContain('评论正文')
    expect(wrapper.text()).not.toContain('项目名称')
    expect(wrapper.find('[aria-label="标为已读"]').exists()).toBe(false)
    await body.trigger('click'); await body.trigger('keydown', { key: 'Enter' }); await flushPromises()
    expect(router.currentRoute.value.path).toBe('/inbox')
    expect(api.markNotificationRead).not.toHaveBeenCalled()
    await wrapper.get('[aria-label="归档"]').trigger('click'); await flushPromises()
    expect(api.archiveNotification).toHaveBeenCalledWith({ id: 'hidden', xXSRFTOKEN: 'csrf' })
  })

  it('groups by company date and marks unread rows read before navigating to discussion', async () => {
    const { wrapper, router } = await setup()
    expect(wrapper.get('.inbox-date-group h2').text()).toBe('昨天')
    await wrapper.get('.inbox-row__body').trigger('click'); await flushPromises()
    expect(api.markNotificationRead).toHaveBeenCalledWith({ id: 'one', xXSRFTOKEN: 'csrf' })
    expect(router.currentRoute.value.fullPath).toBe('/projects/project/overview?workItemId=work&tab=discussion')
  })
})
