import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { TimeCategory, type TeamTimesheet } from '@yumpoo/api-client'
import TeamTimesheetView from './TeamTimesheet.vue'
import { toApiDate } from './teamDashboardModel'

const push = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

const sheet = (): TeamTimesheet => ({
  from: toApiDate('2026-10-05'), to: toApiDate('2026-10-06'), timezone: 'Asia/Shanghai', asOf: new Date(), days: [],
  members: [{ userId: 'u1', displayName: '李四', active: true }], workItems: [], entries: [],
  categoryEntries: [
    { userId: 'u1', timeCategory: TimeCategory.Migrated, projectId: null, projectName: null, date: toApiDate('2026-10-05'), durationMs: 27_000_000 },
    { userId: 'u1', timeCategory: TimeCategory.Meeting, projectId: null, projectName: null, date: toApiDate('2026-10-06'), durationMs: 3_600_000 },
  ],
})
const mountSheet = (overtime = false) => mount(TeamTimesheetView, {
  props: { sheet: sheet(), loading: false, error: '', timezone: 'Asia/Shanghai', overtime, period: { kind: 'WEEK', from: '2026-10-05', to: '2026-10-11' } },
  global: { stubs: { TeamPeriodPicker: true, YpAssignee: true, DayBreakdownPopover: true }, directives: { loading: () => undefined } },
})

beforeEach(() => push.mockReset())

describe('成员工时表的迁入工时', () => {
  it('含迁入工时的格子带角标并在朗读文本里说明，图例随之出现', () => {
    const wrapper = mountSheet()
    const chips = wrapper.findAll('.team-chip')
    expect(chips).toHaveLength(2)
    expect(chips[0]!.find('.team-chip__migrated').text()).toBe('迁')
    expect(chips[0]!.attributes('aria-label')).toBe('李四 2026-10-05 7.5 小时，含迁入工时 7.5 小时，查看工作项')
    expect(chips[1]!.find('.team-chip__migrated').exists()).toBe(false)
    expect(chips[1]!.attributes('aria-label')).toBe('李四 2026-10-06 1 小时，查看工作项')
    expect(wrapper.text()).toContain('迁＝含迁入工时，按日迁入，不代表具体工作时段')
    wrapper.unmount()
  })

  it('入口只在成员工时出现，点击进入迁入页面', async () => {
    const wrapper = mountSheet()
    const entry = wrapper.findAll('button').find(button => button.text() === '迁入工时')
    expect(entry).toBeTruthy()
    await entry!.trigger('click')
    expect(push).toHaveBeenCalledWith({ name: 'team-hours-import' })
    wrapper.unmount()
    const overtime = mountSheet(true)
    expect(overtime.findAll('button').some(button => button.text() === '迁入工时')).toBe(false)
    overtime.unmount()
  })
})
