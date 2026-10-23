import { enableAutoUnmount, flushPromises, shallowMount } from '@vue/test-utils'
import { ElTour, ElTourStep } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import OnboardingHost from './OnboardingHost.vue'
import { TOURS } from './tours'
import { resetOnboardingForTests, useOnboarding } from './useOnboarding'

const api = vi.hoisted(() => ({ completeMyOnboardingTour: vi.fn() }))
vi.mock('../api/client', () => ({ onboardingApi: api }))
vi.mock('../composables/useSession', () => ({ useSession: () => ({ authentication: { value: { user: { id: 'user-1' } } } }) }))

enableAutoUnmount(afterEach)
let targets: HTMLElement
let frames: Map<number, FrameRequestCallback>
let nextFrame: number

beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('innerWidth', 1440)
  vi.stubGlobal('innerHeight', 900)
  frames = new Map()
  nextFrame = 0
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    const id = ++nextFrame
    frames.set(id, callback)
    return id
  })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => { frames.delete(id) })
  targets = document.createElement('main')
  document.body.append(targets)
})
afterEach(() => {
  resetOnboardingForTests()
  targets.remove()
  vi.unstubAllGlobals()
})

async function flushFrame() {
  const callbacks = [...frames.values()]
  frames.clear()
  callbacks.forEach(callback => callback(0))
  await flushPromises()
}

function target(name: string, height = 100) {
  const element = document.createElement('div')
  element.dataset.tour = name
  element.getClientRects = () => [new DOMRect(0, 0, 400, height)] as unknown as DOMRectList
  element.getBoundingClientRect = () => new DOMRect(0, 0, 400, height)
  targets.append(element)
  return element
}

async function host(stubs: Record<string, object> = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/other', component: { template: '<div />' } },
  ] })
  await router.push('/')
  const wrapper = shallowMount(OnboardingHost, { global: {
    plugins: [router],
    stubs: { ElTour: { props: ['modelValue', 'current'], template: '<div><slot /></div>' }, ...stubs },
  } })
  return { wrapper, router }
}

describe('onboarding host lifecycle', () => {
  it('keeps the welcome dialog open when login repeats the same home URL', async () => {
    const { router } = await host()
    useOnboarding().welcomeOpen.value = true
    await router.replace('/')
    await flushPromises()
    expect(useOnboarding().welcomeOpen.value).toBe(true)
    expect(api.completeMyOnboardingTour).not.toHaveBeenCalled()
  })

  it.each(['welcome', 'active'] as const)('cancels %s on route changes without completing it', async kind => {
    const { wrapper, router } = await host()
    const onboarding = useOnboarding()
    if (kind === 'welcome') onboarding.welcomeOpen.value = true
    else onboarding.start('welcome')
    await flushPromises()
    await router.push('/other')
    expect(onboarding.active.value).toBeUndefined()
    expect(onboarding.welcomeOpen.value).toBe(false)
    expect(wrapper.findComponent(ElTour).props('modelValue')).toBe(false)
    expect(api.completeMyOnboardingTour).not.toHaveBeenCalled()
  })

  it('repositions the current step across desktop and phone widths without restarting or recording', async () => {
    target('home-tasks', 600)
    const { wrapper } = await host()
    useOnboarding().replay('welcome')
    await flushPromises()
    wrapper.findComponent(ElTour).vm.$emit('update:current', 1)
    await flushPromises()
    const task = () => wrapper.findAllComponents(ElTourStep).find(step => step.props('title') === '我的工作项')!
    expect(task().props('placement')).toBe('right-start')

    vi.stubGlobal('innerWidth', 375)
    vi.stubGlobal('innerHeight', 812)
    window.dispatchEvent(new Event('resize'))
    await flushFrame()
    expect(task().props('placement')).toBe('bottom')
    expect(task().props('contentStyle')).toMatchObject({ position: 'fixed', top: '50%', left: '50%' })
    expect(task().props('showArrow')).toBe(false)
    expect(wrapper.findComponent(ElTour).props('current')).toBe(1)

    vi.stubGlobal('innerWidth', 1440)
    window.dispatchEvent(new Event('resize'))
    await flushFrame()
    expect(task().props('placement')).toBe('right-start')
    expect(task().props('contentStyle')).toBeUndefined()
    expect(wrapper.findComponent(ElTour).props('current')).toBe(1)
    expect(api.completeMyOnboardingTour).not.toHaveBeenCalled()
  })

  it('preserves the current target when resize hides earlier steps and skips a newly hidden target', async () => {
    const modules = target('shell-modules')
    const tasks = target('home-tasks')
    target('home-calendar')
    const { wrapper } = await host()
    useOnboarding().replay('welcome')
    await flushPromises()
    wrapper.findComponent(ElTour).vm.$emit('update:current', 2)
    await flushPromises()
    const currentTitle = () => wrapper.findAllComponents(ElTourStep)[wrapper.findComponent(ElTour).props('current') ?? 0]!.props('title')
    modules.setAttribute('inert', '')
    window.dispatchEvent(new Event('resize'))
    await flushFrame()
    expect(currentTitle()).toBe('我的工作项')
    tasks.setAttribute('inert', '')
    window.dispatchEvent(new Event('resize'))
    await flushFrame()
    expect(currentTitle()).toBe('工时日历')
    expect(api.completeMyOnboardingTour).not.toHaveBeenCalled()
  })

  it('measures once for a burst of resize events and cancels pending work when the tour stops', async () => {
    const tasks = target('home-tasks')
    const measure = vi.spyOn(tasks, 'getClientRects')
    const { wrapper } = await host()
    useOnboarding().replay('welcome')
    await flushPromises()
    measure.mockClear()
    for (let index = 0; index < 20; index++) window.dispatchEvent(new Event('resize'))
    expect(frames.size).toBe(1)
    expect(measure).not.toHaveBeenCalled()
    await flushFrame()
    expect(measure).toHaveBeenCalledOnce()
    window.dispatchEvent(new Event('resize'))
    useOnboarding().stop()
    expect(frames.size).toBe(0)
    useOnboarding().replay('welcome')
    await flushPromises()
    window.dispatchEvent(new Event('resize'))
    wrapper.unmount()
    expect(frames.size).toBe(0)
  })

  it('shows only steps newer than the handled version with a new-feature tag', async () => {
    target('home-tasks')
    target('home-calendar')
    target('topbar-help')
    const { wrapper } = await host({ ElTourStep: { props: ['title'], template: '<section class="step"><slot name="header">{{ title }}</slot></section>' } })
    useOnboarding().active.value = { id: 'welcome', mode: 'update', from: 1 }
    await flushPromises()
    expect(wrapper.findAll('.step').map(step => step.text())).toEqual(['新功能工时日历', '新功能帮助'])
    expect(wrapper.findAll('.step .onboarding-new-tag')).toHaveLength(2)
  })

  it('points to the fallback target and copy while the primary target is inert', async () => {
    target('context-nav').setAttribute('inert', '')
    const fallback = target('context-nav-expand')
    const { wrapper } = await host()
    useOnboarding().replay('workspace')
    await flushPromises()
    const steps = wrapper.findAllComponents(ElTourStep)
    expect(steps).toHaveLength(1)
    expect(steps[0]!.props()).toMatchObject({
      title: '工作台导航',
      target: expect.any(Function),
      placement: 'right-start',
      description: TOURS.workspace.steps[0]!.fallback!.description,
    })
    expect((steps[0]!.props('target') as () => HTMLElement)()).toBe(fallback)
  })

  it('remeasures a moving target without restarting the step, then cancels tracking on close', async () => {
    const stats = target('my-time-stats')
    let left = 20
    stats.getBoundingClientRect = () => new DOMRect(left, 100, 800, 80)
    const { wrapper } = await host()
    useOnboarding().replay('my-time')
    await flushPromises()
    const step = () => wrapper.findComponent(ElTourStep)
    const initial = step().props('target')
    expect(typeof initial).toBe('function')
    await flushFrame()
    expect(step().props('target')).toBe(initial)
    left = 40
    await flushFrame()
    expect(step().props('target')).not.toBe(initial)
    expect((step().props('target') as () => HTMLElement)()).toBe(stats)
    expect(wrapper.findComponent(ElTour).props('current')).toBe(0)
    expect(api.completeMyOnboardingTour).not.toHaveBeenCalled()
    expect(frames.size).toBe(1)
    useOnboarding().stop()
    expect(frames.size).toBe(0)
  })
})
