import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ getTeamDashboardOptions: vi.fn(), queryTeamTimesheet: vi.fn(), queryTeamWorkload: vi.fn(), queryTeamMemberTasks: vi.fn() }))
const onboardingReady = vi.hoisted(() => vi.fn())
vi.mock('../api/client', () => ({ teamDashboardApi: api }))
vi.mock('../onboarding/useOnboarding', () => ({ usePageOnboarding: () => ({ ready: onboardingReady }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {}, params: { view: 'timesheet' } }), useRouter: () => ({ push: vi.fn() }) }))
vi.mock('../composables/useSession', async () => {
  const { ref } = await import('vue')
  return { useSession: () => ({ authentication: ref({ company: { timezone: 'Asia/Shanghai' } }) }) }
})
import TeamReviewView from './TeamReviewView.vue'

const mountPage = () => mount(TeamReviewView, { global: { stubs: { TeamTimesheet: true, TeamWorkload: true } } })

beforeEach(() => {
  vi.clearAllMocks()
  api.getTeamDashboardOptions.mockResolvedValue({ projects: [], members: [], timezone: 'Asia/Shanghai' })
  api.queryTeamTimesheet.mockResolvedValue({ from: new Date(), to: new Date(), timezone: 'Asia/Shanghai', asOf: new Date(), days: [], members: [], workItems: [], entries: [] })
  api.queryTeamWorkload.mockResolvedValue({ asOf: new Date(), today: new Date(), members: [], unassigned: { todo: 0, inProgress: 0, other: 0, overdue: 0 } })
})

describe('团队视图页面导览', () => {
  it('starts onboarding after the team data loads', async () => {
    const wrapper = mountPage()
    await flushPromises()
    expect(onboardingReady).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('does not start onboarding when the timesheet fails to load', async () => {
    api.queryTeamTimesheet.mockRejectedValue(new Error('offline'))
    const wrapper = mountPage()
    await flushPromises()
    expect(onboardingReady).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
