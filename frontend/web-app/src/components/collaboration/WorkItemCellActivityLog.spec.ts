import {
  ActivityActorTypeEnum,
  WorkItemCellActivityChangeType,
  WorkItemCellActivityColumn,
  WorkItemCellActivityTimeRange,
  WorkItemCellActivityValueType,
  type WorkItemCellActivityEntry,
  type WorkItemCellActivityPage,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WorkItemCellActivityLog from './WorkItemCellActivityLog.vue'

const api = vi.hoisted(() => ({ list: vi.fn() }))
vi.mock('../../api/client', () => ({ activityApi: { listWorkItemCellActivity: api.list } }))
vi.mock('../../composables/useSession', () => ({
  useSession: () => ({ authentication: { value: { company: { timezone: 'Asia/Shanghai' } } } }),
}))
vi.mock('../../api/problems', async importOriginal => ({
  ...await importOriginal<typeof import('../../api/problems')>(),
  toApiProblem: async (reason: unknown) => reason,
}))

const actorId = '46000000-0000-4000-8000-000000000001'
const contentId = '46000000-0000-4000-8000-000000000002'

function entry(id: string, changeType: WorkItemCellActivityChangeType, column: WorkItemCellActivityColumn): WorkItemCellActivityEntry {
  return {
    id,
    eventType: 'workitem.work_item_fields_changed',
    actor: { type: ActivityActorTypeEnum.User, userId: actorId, displayName: '林晓' },
    occurredAt: new Date(Date.now() - 120_000),
    column,
    changeType,
    beforeValue: changeType === WorkItemCellActivityChangeType.Added ? null : {
      type: WorkItemCellActivityValueType.Label, referenceId: 'LOW', displayName: '低', colorToken: 'BRIGHT_BLUE',
    },
    afterValue: changeType === WorkItemCellActivityChangeType.Removed ? null : {
      type: WorkItemCellActivityValueType.Label, referenceId: 'HIGH', displayName: '高', colorToken: 'DARK_ORANGE',
    },
    contentId,
    contentDisplayName: '需求',
  }
}

function page(items: WorkItemCellActivityEntry[], nextCursor: string | null): WorkItemCellActivityPage {
  return {
    items,
    nextCursor,
    historyStartedAt: new Date('2026-09-01T08:00:00Z'),
    facets: {
      timeRanges: Object.values(WorkItemCellActivityTimeRange)
        .filter(value => value !== WorkItemCellActivityTimeRange.UnknownDefaultOpenApi)
        .map(value => ({ value, count: 2, selected: false })),
      actors: [{ userId: actorId, displayName: '林晓', count: 2, selected: false }],
      columns: [{ value: WorkItemCellActivityColumn.Priority, count: 2, selected: false }],
    },
  }
}

const global = {
  stubs: {
    ElPopover: {
      name: 'ElPopover',
      props: ['width', 'showArrow', 'visible'],
      emits: ['update:visible'],
      template: '<div><slot name="reference"/><div><slot/></div></div>',
    },
    ElTooltip: { template: '<span><slot/></span>' },
  },
}

describe('WorkItemCellActivityLog', () => {
  beforeEach(() => vi.clearAllMocks())

  it('渲染替换值、切点提示并加载更早动态', async () => {
    api.list.mockResolvedValueOnce(page([
      entry('46000000-0000-4000-8000-000000000011', WorkItemCellActivityChangeType.Changed,
        WorkItemCellActivityColumn.Priority),
    ], 'older')).mockResolvedValueOnce(page([
      entry('46000000-0000-4000-8000-000000000012', WorkItemCellActivityChangeType.Added,
        WorkItemCellActivityColumn.Priority),
    ], null))
    const wrapper = mount(WorkItemCellActivityLog, { props: { workItemId: 'item-1' }, global })
    await flushPromises()

    expect(wrapper.text()).toContain('单元格动态从 2026年9月1日 16:00 开始记录')
    expect(wrapper.text()).toContain('优先级')
    expect(wrapper.text()).toContain('低')
    expect(wrapper.text()).toContain('高')
    const toolbarButtons = wrapper.get('.cell-activity__toolbar').findAll('button')
    const refreshButton = toolbarButtons.at(-1)
    expect(refreshButton?.classes()).toContain('cell-activity__refresh')
    expect(refreshButton?.attributes('aria-label')).toBe('刷新动态')
    expect(refreshButton?.classes()).not.toContain('is-circle')
    await wrapper.get('.cell-activity__older').trigger('click')
    await flushPromises()
    expect(api.list).toHaveBeenLastCalledWith(expect.objectContaining({ cursor: 'older' }))
    expect(wrapper.get('.cell-entry__label--empty').text()).toBe('-')
    expect(wrapper.text()).not.toContain('新增')
    wrapper.unmount()
  })

  it('即时切换时间筛选并把后端完整计数显示在标签右侧', async () => {
    api.list.mockResolvedValue(page([], null))
    const wrapper = mount(WorkItemCellActivityLog, { props: { workItemId: 'item-1' }, global })
    await flushPromises()
    expect(wrapper.text()).toContain('今天2')
    expect(wrapper.findAll('.activity-filter__columns section')).toHaveLength(3)
    expect(wrapper.get('.activity-filter__header').text()).toContain('筛选动态显示 0 条动态清除')
    expect(wrapper.findAll('.activity-filter__columns h3').map(heading => heading.text())).toEqual(['时间', '成员', '字段'])
    const actorOption = wrapper.get('[aria-label="按成员 林晓 筛选"]')
    expect(actorOption.find('.yp-assignee__name').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('工作项类别')
    const popover = wrapper.findComponent({ name: 'ElPopover' })
    expect(popover.props('width')).toBe(420)
    expect(popover.props('showArrow')).toBe(true)
    const today = wrapper.findAll('button').find(button => button.text().includes('今天'))
    await today?.trigger('click')
    await flushPromises()
    expect(api.list).toHaveBeenLastCalledWith(expect.objectContaining({
      workItemId: 'item-1', timeRange: WorkItemCellActivityTimeRange.Today,
    }))
    expect(wrapper.get('.cell-activity__filter').text()).toContain('动态筛选 / 1')
    expect(wrapper.find('.cell-activity__filter-count').exists()).toBe(false)
    expect(wrapper.text()).toContain('没有符合筛选条件的动态')
    wrapper.unmount()
  })

  it('将空标签到具体标签渲染为灰色短横线到新值，普通新增仍显示新增', async () => {
    const priority = entry('46000000-0000-4000-8000-000000000021',
      WorkItemCellActivityChangeType.Added, WorkItemCellActivityColumn.Priority)
    const status = {
      ...entry('46000000-0000-4000-8000-000000000022',
        WorkItemCellActivityChangeType.Changed, WorkItemCellActivityColumn.Status),
      beforeValue: {
        type: WorkItemCellActivityValueType.Label, referenceId: 'BACKLOG', displayName: '待开始',
        colorToken: 'AMERICAN_GRAY',
      },
      afterValue: {
        type: WorkItemCellActivityValueType.Label, referenceId: 'IN_PROGRESS', displayName: '进行中',
        colorToken: 'BRIGHT_GREEN',
      },
    }
    const dueDate = {
      ...entry('46000000-0000-4000-8000-000000000023',
        WorkItemCellActivityChangeType.Added, WorkItemCellActivityColumn.DueDate),
      afterValue: {
        type: WorkItemCellActivityValueType.Date, referenceId: null, displayName: '2026-09-12',
        colorToken: null,
      },
    }
    api.list.mockResolvedValue(page([priority, status, dueDate], null))
    const wrapper = mount(WorkItemCellActivityLog, { props: { workItemId: 'item-1' }, global })
    await flushPromises()

    expect(wrapper.get('.cell-entry__label--empty').text()).toBe('-')
    expect(wrapper.text()).toContain('待开始')
    expect(wrapper.text()).toContain('进行中')
    expect(wrapper.text()).toContain('新增')
    expect(wrapper.text()).toContain('2026-09-12')
    wrapper.unmount()
  })

  it('在首次请求尚未返回时仍显示全部时间范围', () => {
    api.list.mockImplementation(() => new Promise(() => undefined))
    const wrapper = mount(WorkItemCellActivityLog, { props: { workItemId: 'item-1' }, global })

    for (const label of ['今天', '昨天', '本周', '本月', '今年']) {
      expect(wrapper.text()).toContain(`${label}0`)
    }
    wrapper.unmount()
  })
})
