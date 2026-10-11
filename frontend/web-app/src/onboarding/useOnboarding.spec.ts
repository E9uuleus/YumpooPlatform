import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ getMyOnboarding: vi.fn(), completeMyOnboardingTour: vi.fn() }))
const session = vi.hoisted(() => ({ authentication: { value: { user: { id: 'user-1' } } } }))
vi.mock('../api/client', () => ({ onboardingApi: api }))
vi.mock('../composables/useSession', () => ({ useSession: () => session }))
vi.mock('@yumpoo/api-client', async importOriginal => ({ ...await importOriginal<object>(), readCsrfToken: () => 'csrf' }))
import { TOURS } from './tours'
import { resetOnboardingForTests, useOnboarding } from './useOnboarding'

const records = (...tours: [string, number][]) => ({ tours: tours.map(([tourId, version]) => ({ tourId, version, completedAt: new Date() })) })

beforeEach(() => {
  vi.clearAllMocks()
  api.completeMyOnboardingTour.mockResolvedValue(records())
})
afterEach(resetOnboardingForTests)

describe('onboarding triggers', () => {
  it('opens the welcome dialog first and holds page tours until it is handled', async () => {
    api.getMyOnboarding.mockResolvedValue(records())
    const onboarding = useOnboarding()
    await onboarding.ready('project-work-items')
    expect(onboarding.active.value).toBeUndefined()
    await onboarding.ready('welcome')
    expect(onboarding.welcomeOpen.value).toBe(true)
    onboarding.complete('welcome')
    expect(api.completeMyOnboardingTour).toHaveBeenCalledWith({ tourId: 'welcome', xXSRFTOKEN: 'csrf', onboardingTourCompleteRequest: { version: 1 } })
    await onboarding.ready('project-work-items')
    expect(onboarding.active.value).toBe('project-work-items')
    expect(api.getMyOnboarding).toHaveBeenCalledOnce()
  })

  it('skips handled tours until their version is raised', async () => {
    api.getMyOnboarding.mockResolvedValue(records(['welcome', 1], ['inbox', 1]))
    const onboarding = useOnboarding()
    await onboarding.ready('welcome')
    await onboarding.ready('inbox')
    expect(onboarding.welcomeOpen.value).toBe(false)
    expect(onboarding.active.value).toBeUndefined()
    TOURS.inbox.version = 2
    try {
      await onboarding.ready('inbox')
      expect(onboarding.active.value).toBe('inbox')
    } finally {
      TOURS.inbox.version = 1
    }
  })

  it('replays a handled tour without recording it again', async () => {
    api.getMyOnboarding.mockResolvedValue(records(['welcome', 1]))
    const onboarding = useOnboarding()
    await onboarding.ready('welcome')
    onboarding.start('welcome')
    expect(onboarding.active.value).toBe('welcome')
    onboarding.complete('welcome')
    expect(onboarding.active.value).toBeUndefined()
    expect(api.completeMyOnboardingTour).not.toHaveBeenCalled()
  })
})
