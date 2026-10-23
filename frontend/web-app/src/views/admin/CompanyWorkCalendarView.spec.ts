import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ getWorkCalendarYear: vi.fn(), getWorkHoursAlert: vi.fn() }))
const onboardingReady = vi.hoisted(() => vi.fn())
vi.mock('../../api/client', () => ({ administrationApi: api }))
vi.mock('../../onboarding/useOnboarding', () => ({ usePageOnboarding: () => ({ ready: onboardingReady }) }))
vi.mock('../../composables/useIdentityAdmin', async () => {
  const { ref } = await import('vue')
  return { useIdentityAdmin: () => ({ canWrite: ref(true) }) }
})
vi.mock('../../composables/useSession', async () => {
  const { ref } = await import('vue')
  return { useSession: () => ({ authentication: ref({ company: { timezone: 'Asia/Shanghai' } }) }) }
})
import CompanyWorkCalendarView from './CompanyWorkCalendarView.vue'

const mountPage = () => mount(CompanyWorkCalendarView, { global: { directives: { loading: {} }, stubs: { RouterLink: true } } })

beforeEach(() => {
  vi.clearAllMocks()
  api.getWorkCalendarYear.mockResolvedValue({ year: 2026, defaultWorkdayMinutes: 480, etag: '"0"', days: [] })
  api.getWorkHoursAlert.mockResolvedValue({ dailyThresholdMinutes: 720, etag: '"0"' })
})

describe('工作日历页面导览', () => {
  it('starts onboarding after the calendar loads', async () => {
    const wrapper = mountPage()
    await flushPromises()
    expect(onboardingReady).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('does not start onboarding when the calendar fails to load', async () => {
    api.getWorkCalendarYear.mockRejectedValue(new Error('offline'))
    const wrapper = mountPage()
    await flushPromises()
    expect(onboardingReady).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
