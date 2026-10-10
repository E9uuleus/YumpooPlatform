import { defineComponent, h, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TeamMemberTask, TeamMemberTaskPage, TeamWorkload } from '@yumpoo/api-client'
import TeamWorkloadView from './TeamWorkload.vue'
import { useTeamDashboard } from './useTeamDashboard'

const api = vi.hoisted(() => ({ getTeamDashboardOptions: vi.fn(), queryTeamTimesheet: vi.fn(), queryTeamWorkload: vi.fn(), queryTeamMemberTasks: vi.fn() }))
vi.mock('../../../api/client', () => ({ teamDashboardApi: api }))
vi.mock('../../../composables/useSession', () => ({ useSession: () => ({ authentication: ref({ company: { timezone: 'Asia/Shanghai' } }) }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

const workload = (): TeamWorkload => ({ asOf: new Date(), today: new Date(),
  members: ['张三', '李四'].map((displayName, index) => ({ userId: `member-${index}`, displayName, active: true, todo: 2, inProgress: 0, overdue: 0 })),
  unassigned: { todo: 1, inProgress: 0, overdue: 0 } })
const page = (title: string): TeamMemberTaskPage => ({ items: [{ id: title, title } as TeamMemberTask], totalElements: 2 })
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
let wrapper: ReturnType<typeof mount>, team: ReturnType<typeof useTeamDashboard>
beforeEach(async () => {
  vi.useFakeTimers(); vi.resetAllMocks()
  api.getTeamDashboardOptions.mockResolvedValue({ projects: [], members: [], timezone: 'Asia/Shanghai' })
  api.queryTeamWorkload.mockImplementation(async () => workload())
  api.queryTeamMemberTasks.mockResolvedValue(page('刷新前任务'))
  wrapper = mount(defineComponent({ setup() {
    team = useTeamDashboard()
    return () => h(TeamWorkloadView, { workload: team.workload.value, loading: team.workloadLoading.value,
      error: team.workloadError.value, tasks: team.tasks.value, onLoad: (key, append) => void team.loadTasks(key, append) })
  } }), { global: { directives: { loading: {} }, stubs: {
    YpAssignee: { props: ['displayName'], template: '<span>{{ displayName }}</span>' },
    ElTable: { props: ['data'], template: '<div><span v-for="task in data" :key="task.id">{{ task.title }}</span></div>' },
    ElTableColumn: true,
  } } })
  await flushPromises()
})
afterEach(() => { wrapper.unmount(); vi.useRealTimers() })
const row = (name: string) => wrapper.findAll('.team-workload__row').find(button => button.text().includes(name))!

describe('expanded team tasks', () => {
  it('keeps expanded members and unassigned work loading after refresh, discarding stale pages', async () => {
    await row('张三').trigger('click'); await row('未分配').trigger('click'); await flushPromises()
    const stale = deferred<TeamMemberTaskPage>()
    api.queryTeamMemberTasks.mockReturnValueOnce(stale.promise)
    const appending = team.loadTasks('member-0', true)
    const member = deferred<TeamMemberTaskPage>(), unassigned = deferred<TeamMemberTaskPage>()
    api.queryTeamMemberTasks.mockImplementation(({ teamMemberTasksQuery: query }) => query.userId ? member.promise : unassigned.promise)
    api.queryTeamWorkload.mockResolvedValueOnce({ ...workload(), members: [{ ...workload().members[0]!, todo: 8 }, workload().members[1]!] })
    api.queryTeamMemberTasks.mockClear()
    team.refresh(); await flushPromises()
    expect(row('张三').attributes('aria-expanded')).toBe('true')
    expect(row('未分配').attributes('aria-expanded')).toBe('true')
    expect(row('李四').attributes('aria-expanded')).toBe('false')
    expect(team.tasks.value.get('member-0')).toMatchObject({ items: [], loading: true })
    expect(team.tasks.value.get('')).toMatchObject({ items: [], loading: true })
    expect(api.queryTeamMemberTasks.mock.calls.map(([request]) => request.teamMemberTasksQuery)).toEqual([
      { userId: 'member-0', projectIds: new Set(), offset: 0, limit: 50 },
      { userId: null, projectIds: new Set(), offset: 0, limit: 50 },
    ])
    member.resolve(page('刷新后任务')); unassigned.resolve(page('新未分配任务')); await flushPromises()
    stale.resolve(page('过期分页')); await appending; await flushPromises()
    expect(wrapper.text()).toContain('刷新后任务')
    expect(wrapper.text()).toContain('新未分配任务')
    expect(wrapper.text()).not.toContain('过期分页')
    expect(team.tasks.value.get('member-0')?.loading).toBe(false)
  })

  it('shows only the work item name in the task column', async () => {
    api.queryTeamMemberTasks.mockResolvedValueOnce({ items: [{ id: 't1', itemNo: 'DEL-9', title: '登录页改版' } as TeamMemberTask], totalElements: 1 })
    await row('张三').trigger('click'); await flushPromises()
    const link = wrapper.find('.team-task-link')
    expect(link.text()).toBe('登录页改版')
    expect(link.attributes('title')).toBe('登录页改版')
    expect(wrapper.find('.team-workload__tasks').text()).not.toContain('DEL-9')
  })

  it('reloads active expanded rows after a workload refresh and drops rows without tasks', async () => {
    await row('张三').trigger('click'); await row('李四').trigger('click'); await row('未分配').trigger('click'); await flushPromises()
    api.queryTeamMemberTasks.mockClear()
    api.queryTeamWorkload.mockResolvedValueOnce({ ...workload(), members: [workload().members[0]!, { ...workload().members[1]!, todo: 0 }],
      unassigned: { todo: 0, inProgress: 0, overdue: 0 } })
    await team.loadWorkload(); await flushPromises()
    expect(row('张三').attributes('aria-expanded')).toBe('true')
    expect(wrapper.findAll('.team-workload__tasks')).toHaveLength(1)
    expect(api.queryTeamMemberTasks).toHaveBeenCalledTimes(1)
    expect(api.queryTeamMemberTasks.mock.calls[0]![0].teamMemberTasksQuery).toMatchObject({ userId: 'member-0', offset: 0, projectIds: new Set() })
    await team.loadWorkload(); await flushPromises()
    expect(row('李四').attributes('aria-expanded')).toBe('false')
    expect(row('未分配').attributes('aria-expanded')).toBe('false')
  })
})
