import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
vi.mock('../../composables/useSession', async () => {
  const { ref } = await import('vue')
  return { useSession: () => ({ authentication: ref({ user: { id: 'reader' } }) }) }
})
vi.mock('../../composables/useInbox', async () => {
  const { ref } = await import('vue')
  const item = { id: 'one', reason: 'MENTION', state: 'UNREAD', createdAt: new Date(), target: { accessible: true, projectId: 'project', workItemId: 'item' } }
  return { useInbox: () => ({ unread: ref(103), counts: ref(), refresh: vi.fn(), readAll: vi.fn() }),
    useInboxList: () => ({ items: ref([item]), loading: ref(false), error: ref(''), serverNow: ref(new Date()), load: vi.fn(), setState: vi.fn(async () => true) }) }
})
import InboxBell from './InboxBell.vue'
const mounted: Array<ReturnType<typeof mount>> = []
afterEach(() => mounted.splice(0).forEach(wrapper => wrapper.unmount()))
describe('inbox bell', () => {
  it('announces the full unread count and opens the discussion deep link', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
    await router.push('/'); await router.isReady()
    const wrapper = mount(InboxBell, { global: { plugins: [router], stubs: {
      ElPopover: { template: '<div><slot name="reference" /><slot /></div>' },
      InboxRow: { name: 'InboxRow', props: ['item'], emits: ['open'], template: '<button class="row" @click="$emit(\'open\', item)">通知</button>' },
    } } }); mounted.push(wrapper)
    expect(wrapper.get('.inbox-trigger').attributes('aria-label')).toBe('收件箱，103 条未读')
    expect(wrapper.text()).toContain('99+')
    await wrapper.get('.row').trigger('click'); await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/projects/project/overview?workItemId=item&tab=discussion')
  })
})
