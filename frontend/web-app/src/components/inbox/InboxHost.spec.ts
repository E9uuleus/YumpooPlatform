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
import { activateInbox } from '../../composables/useInbox'
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
beforeEach(() => { vi.clearAllMocks(); vi.stubGlobal('yumpooDesktop', undefined); useSession().phase.value = 'authenticated' })
afterEach(() => { mounted.splice(0).forEach(wrapper => wrapper.unmount()); vi.unstubAllGlobals() })
describe('inbox owner', () => {
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
