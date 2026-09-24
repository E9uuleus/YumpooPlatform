import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../../composables/useSession', async () => {
  const { ref } = await import('vue')
  return { useSession: () => ({ phase: ref('authenticated'), authentication: ref({ user: { id: 'timer-user' } }) }) }
})
vi.mock('../../composables/useTimeTracker', async () => {
  const { ref } = await import('vue')
  const tracker = { current: ref({ session: null, recentItems: [] }), runningDuration: ref(0), savedAt: ref(0), connected: ref(true), busy: ref(false), refresh: vi.fn(async () => undefined) }
  return { activateTimeTracker: vi.fn(), confirmTimerExit: vi.fn(), formatDuration: () => '0:00:00', timerClockOffset: () => 1234.4,
    timerExitPrompt: ref(false), chooseTimerExit: vi.fn(),
    useTimeTracker: () => tracker }
})
import { useTimeTracker } from '../../composables/useTimeTracker'
import TimerHost from './TimerHost.vue'

const mounted: Array<ReturnType<typeof mount>> = []
async function host() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/projects/:projectId/overview', component: { template: '<div />' } }] })
  await router.push('/projects/example/overview'); await router.isReady()
  const wrapper = mount(TimerHost, { global: { plugins: [router], stubs: {
    ElButton: { template: '<button><slot /></button>' }, ElDialog: { template: '<div />' },
    TimerPanel: { name: 'TimerPanel', props: ['initialExpanded', 'orbLayout'], emits: ['hide', 'mode', 'orb', 'drag'], template: '<section @pointerdown="$emit(\'drag\', $event)"><button @click="$emit(\'hide\')">隐藏</button>计时面板 {{ initialExpanded }}</section>' },
  } } })
  mounted.push(wrapper)
  return wrapper
}
beforeEach(() => { useTimeTracker().savedAt.value = 0; vi.stubGlobal('open', vi.fn()); vi.stubGlobal('documentPictureInPicture', undefined); vi.stubGlobal('isSecureContext', true); vi.stubGlobal('yumpooDesktop', undefined) })
afterEach(() => { mounted.splice(0).forEach(wrapper => wrapper.unmount()); vi.unstubAllGlobals() })

describe('shared timer surfaces', () => {
  it('uses a nonmodal inline panel directly when PiP is unavailable', async () => {
    const wrapper = await host(); await wrapper.get('button').trigger('click'); await flushPromises()
    expect(window.open).not.toHaveBeenCalled()
    expect(wrapper.get('.timer-floating').text()).toContain('计时面板 true')
    await wrapper.get('.timer-floating button').trigger('click')
    expect(wrapper.find('.timer-floating').exists()).toBe(false)
    expect(wrapper.find('.global-timer-entry').exists()).toBe(true)
  })
  it('mounts the same panel in PiP and returns it to the page on native close', async () => {
    const pipDocument = document.implementation.createHTMLDocument('timer')
    const events = new Map<string, () => void>()
    const requestWindow = vi.fn(async () => ({ document: pipDocument, addEventListener: (name: string, fn: () => void) => events.set(name, fn), close: vi.fn(), closed: false, resizeTo: vi.fn() }))
    vi.stubGlobal('documentPictureInPicture', { requestWindow })
    const wrapper = await host(); await wrapper.get('button').trigger('click'); await flushPromises()
    expect(requestWindow).toHaveBeenCalledWith({ width: 360, height: 480 })
    expect(pipDocument.body.textContent).toContain('计时面板')
    expect(wrapper.find('.timer-floating').exists()).toBe(false)
    events.get('pagehide')?.(); await flushPromises()
    expect(wrapper.find('.timer-floating').exists()).toBe(true)
  })
  it('keeps inline selection available after PiP rejection without a second popup', async () => {
    vi.stubGlobal('documentPictureInPicture', { requestWindow: vi.fn(async () => { throw new Error('NotAllowedError') }) })
    const wrapper = await host(); await wrapper.get('button').trigger('click'); await flushPromises()
    expect(window.open).not.toHaveBeenCalled()
    expect(wrapper.get('.timer-floating').text()).toContain('计时面板')
  })
  it('opens compactly after a table start without requesting browser permission', async () => {
    const wrapper = await host()
    window.dispatchEvent(new Event('yumpoo:timer-started')); await flushPromises()
    expect(wrapper.get('.timer-floating').text()).toContain('计时面板 false')
    expect(window.open).not.toHaveBeenCalled()
    expect(wrapper.get('.timer-floating').classes()).toContain('is-orb')
  })
  it('shows a hidden orb after a confirmed table stop', async () => {
    const wrapper = await host()
    expect(wrapper.find('.timer-floating').exists()).toBe(false)
    useTimeTracker().savedAt.value = Date.now(); await flushPromises()
    expect(wrapper.get('.timer-floating').classes()).toContain('is-orb')
  })
  it('keeps compact details geometry when a start event repeats the current mode', async () => {
    const wrapper = await host()
    window.dispatchEvent(new Event('yumpoo:timer-started')); await flushPromises()
    const panel = wrapper.findComponent({ name: 'TimerPanel' })
    const layout = { size: 64, side: 'left', detailWidth: 216 }
    panel.vm.$emit('orb', layout); await flushPromises()
    window.dispatchEvent(new Event('yumpoo:timer-started')); await flushPromises()
    panel.vm.$emit('mode', false); await flushPromises()
    expect(panel.props('orbLayout')).toEqual(layout)
  })
  it('moves the circle from its background and preserves its anchor when details or size change', async () => {
    const wrapper = await host()
    window.dispatchEvent(new Event('yumpoo:timer-started')); await flushPromises()
    const floating = wrapper.get('.timer-floating')
    let rect = { left: 700, top: 400, right: 764, bottom: 464, width: 64, height: 64 }
    vi.spyOn(floating.element, 'getBoundingClientRect').mockImplementation(() => {
      const style = (floating.element as HTMLElement).style
      const width = parseFloat(style.width) || rect.width, height = parseFloat(style.height) || rect.height
      const left = parseFloat(style.left) || rect.left, top = parseFloat(style.top) || rect.top
      return { left, top, width, height, right: left + width, bottom: top + height } as DOMRect
    })
    await wrapper.get('.timer-floating section').trigger('pointerdown', { button: 0, pointerId: 3, clientX: 730, clientY: 430 })
    window.dispatchEvent(new PointerEvent('pointermove', { pointerId: 3, clientX: 650, clientY: 380 }))
    window.dispatchEvent(new PointerEvent('pointerup', { pointerId: 3 }))
    await flushPromises()
    expect(floating.attributes('style')).toContain('left: 620px')
    expect(floating.attributes('style')).toContain('top: 350px')
    rect = { left: 620, top: 350, right: 684, bottom: 414, width: 64, height: 64 }
    const panel = wrapper.findComponent({ name: 'TimerPanel' })
    panel.vm.$emit('orb', { size: 80, side: 'left', detailWidth: 200 }); await flushPromises()
    expect(floating.attributes('style')).toContain('left: 420px')
    expect(floating.attributes('style')).toContain('top: 334px')
    expect(floating.attributes('style')).toContain('width: 280px')
    rect = { left: 420, top: 334, right: 700, bottom: 414, width: 280, height: 80 }
    panel.vm.$emit('orb', { size: 80, side: null, detailWidth: 0 }); await flushPromises()
    expect(floating.attributes('style')).toContain('left: 620px')
    expect(floating.attributes('style')).toContain('width: 80px')
    vi.stubGlobal('innerWidth', 260)
    panel.vm.$emit('orb', { size: 80, side: 'left', detailWidth: 200 }); await flushPromises()
    expect(floating.attributes('style')).toContain('width: 244px')
    expect(floating.attributes('style')).toContain('left: 8px')
  })
  it('uses native window capabilities before any browser fallback', async () => {
    const timer = { show: vi.fn(async () => undefined), getWindowState: vi.fn(async () => ({ surface: 'main', mode: 'picker', pinned: true })), publishState: vi.fn(async () => undefined), onExitRequest: vi.fn(), onCommand: vi.fn() }
    vi.stubGlobal('yumpooDesktop', { timer })
    const wrapper = await host(); await wrapper.get('button').trigger('click'); await flushPromises()
    expect(timer.show).toHaveBeenCalledWith('picker')
    expect(timer.publishState).toHaveBeenLastCalledWith(expect.objectContaining({ accountId: 'timer-user', clockOffsetMs: 1234 }))
    expect(wrapper.find('.timer-floating').exists()).toBe(false)
    expect(window.open).not.toHaveBeenCalled()
  })
  it('keeps selection available while PiP is pending and discards a late grant after hiding', async () => {
    const target = { document: document.implementation.createHTMLDocument('timer'), close: vi.fn(), addEventListener: vi.fn() }
    let grant!: (target: unknown) => void
    vi.stubGlobal('documentPictureInPicture', { requestWindow: vi.fn(() => new Promise(resolve => { grant = resolve })) })
    const wrapper = await host(); await wrapper.get('button').trigger('click'); await flushPromises()
    expect(wrapper.find('.timer-floating').exists()).toBe(true)
    await wrapper.get('.timer-floating button').trigger('click')
    grant(target); await flushPromises()
    expect(target.close).toHaveBeenCalledOnce()
    expect(target.document.body.textContent).not.toContain('计时面板')
  })
})
