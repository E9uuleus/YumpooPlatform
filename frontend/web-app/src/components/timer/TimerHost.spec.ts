import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../../composables/useSession', async () => {
  const { ref } = await import('vue')
  return { useSession: () => ({ phase: ref('authenticated'), authentication: ref({ user: { id: 'timer-user' } }) }) }
})
vi.mock('../../composables/useTimeTracker', async () => {
  const { ref } = await import('vue')
  return { activateTimeTracker: vi.fn(), confirmTimerExit: vi.fn(), formatDuration: () => '0:00:00',
    timerExitPrompt: ref(false), chooseTimerExit: vi.fn(),
    useTimeTracker: () => ({ current: ref({ session: null }), runningDuration: ref(0), refresh: vi.fn(async () => undefined) }) }
})
import TimerHost from './TimerHost.vue'

const mounted: Array<ReturnType<typeof mount>> = []
async function host() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/projects/:projectId/overview', component: { template: '<div />' } }] })
  await router.push('/projects/example/overview'); await router.isReady()
  const wrapper = mount(TimerHost, { global: { plugins: [router], stubs: {
    ElButton: { template: '<button><slot /></button>' },
    ElDialog: { template: '<div />' },
    ElDrawer: { props: ['modelValue'], template: '<aside v-if="modelValue"><slot /></aside>' },
    TimerPanel: { template: '<section>计时面板</section>' },
  } } })
  mounted.push(wrapper)
  return wrapper
}
beforeEach(() => { vi.stubGlobal('open', vi.fn(() => null)); vi.stubGlobal('documentPictureInPicture', undefined); vi.stubGlobal('isSecureContext', true) })
afterEach(() => { mounted.splice(0).forEach(wrapper => wrapper.unmount()); vi.unstubAllGlobals() })

describe('Web timer window fallback', () => {
  it('keeps an inline panel when the browser blocks a normal popup', async () => {
    const wrapper = await host(); await wrapper.get('button').trigger('click'); await flushPromises()
    expect(window.open).toHaveBeenCalledWith('/timer?projectId=example', 'yumpoo-timer', 'popup,width=380,height=550')
    expect(wrapper.find('aside').text()).toContain('计时面板')
  })
  it('mounts the same timer panel in a granted picture-in-picture document', async () => {
    const pipDocument = document.implementation.createHTMLDocument('timer')
    const requestWindow = vi.fn(async () => ({ document: pipDocument, addEventListener: vi.fn(), close: vi.fn(), closed: false }))
    vi.stubGlobal('documentPictureInPicture', { requestWindow })
    const wrapper = await host(); await wrapper.get('button').trigger('click'); await flushPromises()
    expect(requestWindow).toHaveBeenCalledWith({ width: 380, height: 550 })
    expect(window.open).not.toHaveBeenCalled()
    expect(pipDocument.body.textContent).toContain('计时面板')
    expect(wrapper.find('aside').exists()).toBe(false)
  })
  it('uses the popup fallback when picture-in-picture is rejected', async () => {
    vi.stubGlobal('documentPictureInPicture', { requestWindow: vi.fn(async () => { throw new Error('NotAllowedError') }) })
    const wrapper = await host(); await wrapper.get('button').trigger('click'); await flushPromises()
    expect(window.open).toHaveBeenCalledOnce()
    expect(wrapper.find('aside').exists()).toBe(true)
  })
})
