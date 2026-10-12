import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
const api = vi.hoisted(() => ({ getMyOnboarding: vi.fn(), completeMyOnboardingTour: vi.fn() }))
const session = vi.hoisted(() => ({ authentication: { value: { user: { id: 'user-1' } } } }))
vi.mock('../api/client', () => ({ onboardingApi: api }))
vi.mock('../composables/useSession', () => ({ useSession: () => session }))
vi.mock('@yumpoo/api-client', async importOriginal => ({ ...await importOriginal<object>(), readCsrfToken: () => 'csrf' }))
import { TOURS, type TourId } from './tours'
import { resetOnboardingForTests, useOnboarding, usePageOnboarding } from './useOnboarding'

const records = (...tours: [string, number][]) => ({ tours: tours.map(([tourId, version]) => ({ tourId, version, completedAt: new Date() })) })
enableAutoUnmount(afterEach)

function deferred<T = void>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

async function page(id: TourId, isReady: () => boolean = () => true) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/page', name: 'renamed-route', component: { template: '<div />' }, meta: { onboardingTour: id } },
    { path: '/other', component: { template: '<div />' } },
  ] })
  await router.push('/page')
  let onboarding!: ReturnType<typeof usePageOnboarding>
  const wrapper = mount(defineComponent({ setup() {
    onboarding = usePageOnboarding(isReady)
    return () => null
  } }), { global: { plugins: [router] } })
  return { ready: onboarding.ready, router, wrapper }
}

beforeEach(() => {
  vi.clearAllMocks()
  session.authentication.value.user.id = 'user-1'
  api.completeMyOnboardingTour.mockResolvedValue(records())
})
afterEach(resetOnboardingForTests)

describe('onboarding triggers', () => {
  it('opens the welcome dialog first and holds page tours until it is handled', async () => {
    api.getMyOnboarding.mockResolvedValue(records())
    const onboarding = useOnboarding()
    const project = await page('project-work-items')
    const home = await page('welcome')
    await project.ready()
    expect(onboarding.active.value).toBeUndefined()
    await home.ready()
    expect(onboarding.welcomeOpen.value).toBe(true)
    onboarding.complete('welcome')
    expect(api.completeMyOnboardingTour).toHaveBeenCalledWith({ tourId: 'welcome', xXSRFTOKEN: 'csrf', onboardingTourCompleteRequest: { version: 1 } })
    await project.ready()
    expect(onboarding.active.value).toEqual({ id: 'project-work-items', mode: 'automatic' })
    expect(api.getMyOnboarding).toHaveBeenCalledOnce()
  })

  it('skips handled tours until their version is raised', async () => {
    api.getMyOnboarding.mockResolvedValue(records(['welcome', 1], ['inbox', 1]))
    const onboarding = useOnboarding()
    const home = await page('welcome')
    const inbox = await page('inbox')
    await home.ready()
    await inbox.ready()
    expect(onboarding.welcomeOpen.value).toBe(false)
    expect(onboarding.active.value).toBeUndefined()
    TOURS.inbox.version = 2
    try {
      await inbox.ready()
      expect(onboarding.active.value).toEqual({ id: 'inbox', mode: 'automatic' })
    } finally {
      TOURS.inbox.version = 1
    }
  })

  it.each([true, false])('never records a replay, including an unhandled tour (handled: %s)', async handled => {
    api.getMyOnboarding.mockResolvedValue(handled ? records(['welcome', 1], ['inbox', 1]) : records(['welcome', 1]))
    const onboarding = useOnboarding()
    const home = await page('welcome')
    const inbox = await page('inbox')
    await home.ready()
    onboarding.replay('inbox')
    expect(onboarding.active.value).toEqual({ id: 'inbox', mode: 'replay' })
    onboarding.complete('inbox')
    expect(onboarding.active.value).toBeUndefined()
    expect(api.completeMyOnboardingTour).not.toHaveBeenCalled()
    await inbox.ready()
    expect(onboarding.active.value?.id).toBe(handled ? undefined : 'inbox')
    if (!handled) {
      expect(onboarding.active.value?.mode).toBe('automatic')
      onboarding.complete('inbox')
      expect(api.completeMyOnboardingTour).toHaveBeenCalledOnce()
    }
  })

  it('allows local replay when records could not be loaded without writing or retrying', async () => {
    api.getMyOnboarding.mockRejectedValue(new Error('offline'))
    const home = await page('welcome')
    const onboarding = useOnboarding()
    await home.ready()
    onboarding.replay('welcome')
    onboarding.complete('welcome')
    await home.ready()
    expect(onboarding.welcomeOpen.value).toBe(false)
    expect(api.getMyOnboarding).toHaveBeenCalledOnce()
    expect(api.completeMyOnboardingTour).not.toHaveBeenCalled()
  })

  it.each(['workspace', 'company'] as const)('ignores %s data finishing after its page unmounted', async id => {
    api.getMyOnboarding.mockResolvedValue(records(['welcome', 1]))
    const previous = await page(id)
    const data = deferred()
    const loading = data.promise.then(previous.ready)
    await previous.router.push('/other')
    previous.wrapper.unmount()
    data.resolve()
    await loading
    expect(useOnboarding().active.value).toBeUndefined()
    expect(api.getMyOnboarding).not.toHaveBeenCalled()
    expect(api.completeMyOnboardingTour).not.toHaveBeenCalled()
  })

  it.each(['welcome', 'workspace'] as const)('ignores pending %s records after navigating away and back', async id => {
    const response = deferred<ReturnType<typeof records>>()
    api.getMyOnboarding.mockReturnValue(response.promise)
    const previous = await page(id)
    const pending = previous.ready()
    await previous.router.push('/other')
    await previous.router.push('/page')
    response.resolve(id === 'welcome' ? records() : records(['welcome', 1]))
    await pending
    expect(useOnboarding().active.value).toBeUndefined()
    expect(useOnboarding().welcomeOpen.value).toBe(false)
    expect(api.completeMyOnboardingTour).not.toHaveBeenCalled()
  })

  it('does not start a tour for a session that changed while records were loading', async () => {
    const response = deferred<ReturnType<typeof records>>()
    api.getMyOnboarding.mockReturnValue(response.promise)
    const home = await page('welcome')
    const pending = home.ready()
    session.authentication.value.user.id = 'user-2'
    response.resolve(records())
    await pending
    expect(useOnboarding().welcomeOpen.value).toBe(false)
  })

  it('does not complete a tour cancelled by navigation', () => {
    const onboarding = useOnboarding()
    onboarding.start('welcome')
    onboarding.stop()
    onboarding.complete('welcome')
    expect(api.completeMyOnboardingTour).not.toHaveBeenCalled()
  })

  it('waits for successful page data and keeps the tour eligible after a failure', async () => {
    api.getMyOnboarding.mockResolvedValue(records(['welcome', 1]))
    let succeeded = false
    const workspace = await page('workspace', () => succeeded)
    await workspace.ready()
    expect(api.getMyOnboarding).not.toHaveBeenCalled()
    expect(useOnboarding().active.value).toBeUndefined()
    succeeded = true
    await workspace.ready()
    expect(useOnboarding().active.value).toEqual({ id: 'workspace', mode: 'automatic' })
    expect(api.completeMyOnboardingTour).not.toHaveBeenCalled()
  })

  it('rechecks page readiness after loading onboarding records', async () => {
    const response = deferred<ReturnType<typeof records>>()
    api.getMyOnboarding.mockReturnValue(response.promise)
    let succeeded = true
    const company = await page('company', () => succeeded)
    const pending = company.ready()
    succeeded = false
    response.resolve(records(['welcome', 1]))
    await pending
    expect(useOnboarding().active.value).toBeUndefined()
    expect(api.completeMyOnboardingTour).not.toHaveBeenCalled()
  })
})
