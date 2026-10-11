import { enableAutoUnmount, flushPromises, shallowMount } from '@vue/test-utils'
import { ElTour, ElTourStep } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import OnboardingHost from './OnboardingHost.vue'
import { resetOnboardingForTests, useOnboarding } from './useOnboarding'

const api = vi.hoisted(() => ({ completeMyOnboardingTour: vi.fn() }))
vi.mock('../api/client', () => ({ onboardingApi: api }))
vi.mock('../composables/useSession', () => ({ useSession: () => ({ authentication: { value: { user: { id: 'user-1' } } } }) }))

enableAutoUnmount(afterEach)
let targets: HTMLElement

beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('innerWidth', 1440)
  vi.stubGlobal('innerHeight', 900)
  targets = document.createElement('main')
  document.body.append(targets)
})
afterEach(() => {
  resetOnboardingForTests()
  targets.remove()
  vi.unstubAllGlobals()
})

function target(name: string, height = 100) {
  const element = document.createElement('div')
  element.dataset.tour = name
  element.getClientRects = () => [new DOMRect(0, 0, 400, height)] as unknown as DOMRectList
  element.getBoundingClientRect = () => new DOMRect(0, 0, 400, height)
  targets.append(element)
  return element
}

async function host() {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/other', component: { template: '<div />' } },
  ] })
  await router.push('/')
  const wrapper = shallowMount(OnboardingHost, { global: {
    plugins: [router],
    stubs: { ElTour: { props: ['modelValue', 'current'], template: '<div><slot /></div>' } },
  } })
  return { wrapper, router }
}

describe('onboarding host lifecycle', () => {
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
    await flushPromises()
    expect(task().props('placement')).toBe('bottom')
    expect(task().props('contentStyle')).toMatchObject({ position: 'fixed', top: '50%', left: '50%' })
    expect(task().props('showArrow')).toBe(false)
    expect(wrapper.findComponent(ElTour).props('current')).toBe(1)

    vi.stubGlobal('innerWidth', 1440)
    window.dispatchEvent(new Event('resize'))
    await flushPromises()
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
    await flushPromises()
    expect(currentTitle()).toBe('我的工作项')
    tasks.setAttribute('inert', '')
    window.dispatchEvent(new Event('resize'))
    await flushPromises()
    expect(currentTitle()).toBe('工时日历')
    expect(api.completeMyOnboardingTour).not.toHaveBeenCalled()
  })
})
