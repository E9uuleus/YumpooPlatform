import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ getMyTimesheet: vi.fn() }))
const onboardingReady = vi.hoisted(() => vi.fn())
vi.mock('../api/client', () => ({ myWorkApi: api }))
vi.mock('../onboarding/useOnboarding', () => ({ usePageOnboarding: () => ({ ready: onboardingReady }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {}, params: {} }), useRouter: () => ({ push: vi.fn() }) }))
vi.mock('../composables/useSession', async () => {
  const { ref } = await import('vue')
  return { useSession: () => ({ authentication: ref({ company: { timezone: 'Asia/Shanghai' } }) }) }
})
vi.mock('../composables/useTimeTracker', async () => {
  const { ref } = await import('vue')
  return { useTimeTracker: () => ({ now: ref(Date.now()), running: ref([]) }), onTimeTrackingChanged: () => () => undefined }
})
import MyTimePageView from './MyTimePageView.vue'

const sheet = { from: new Date(), to: new Date(), timezone: 'Asia/Shanghai', asOf: new Date(), days: [], members: [], workItems: [], entries: [] }
const mountPage = () => mount(MyTimePageView, { global: { directives: { loading: {} }, stubs: { TimePreferencesPopover: true } } })

beforeEach(() => { vi.clearAllMocks() })

describe('我的工时页面导览', () => {
  it('starts onboarding after the month loads', async () => {
    api.getMyTimesheet.mockResolvedValue(sheet)
    const wrapper = mountPage()
    await flushPromises()
    expect(onboardingReady).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('does not start onboarding when the month fails to load', async () => {
    api.getMyTimesheet.mockRejectedValue(new Error('offline'))
    const wrapper = mountPage()
    await flushPromises()
    expect(onboardingReady).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
