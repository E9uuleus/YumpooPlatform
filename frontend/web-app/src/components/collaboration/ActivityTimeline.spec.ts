import {
  ActivityActorTypeEnum,
  ActivityAudienceType,
  type ActivityItem,
  type ActivityPage,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ActivityTimeline from './ActivityTimeline.vue'

const api = vi.hoisted(() => ({ project: vi.fn(), workItem: vi.fn() }))
vi.mock('../../api/client', () => ({
  activityApi: {
    listProjectActivity: api.project,
    listWorkItemActivity: api.workItem,
  },
}))
vi.mock('../../composables/useSession', () => ({
  useSession: () => ({
    authentication: { value: { company: { timezone: 'Asia/Shanghai' } } },
  }),
}))
vi.mock('../../api/problems', async importOriginal => ({
  ...await importOriginal<typeof import('../../api/problems')>(),
  toApiProblem: async (reason: unknown) => reason,
}))

function item(id: string, summary: string, occurredAt: string): ActivityItem {
  return {
    id,
    audienceType: ActivityAudienceType.Project,
    sourceEventType: 'workitem.work_item_created',
    entityType: 'WORK_ITEM',
    entityId: id,
    entityRef: 'YMP-20 投影验收',
    relatedWorkItemIds: new Set([id]),
    actor: { type: ActivityActorTypeEnum.User, userId: '44000000-0000-4000-8000-000000000003', displayName: '林晓' },
    occurredAt: new Date(occurredAt),
    templateCode: 'WORK_ITEM_CREATED',
    summary,
    safeParameters: { entityRef: 'YMP-20 投影验收' },
    requestId: 'm2-20-web-test',
  }
}

function page(items: ActivityItem[], nextCursor: string | null): ActivityPage {
  return { items, nextCursor, historyStartedAt: new Date('2026-08-30T08:00:00Z') }
}

describe('ActivityTimeline', () => {
  beforeEach(() => vi.clearAllMocks())

  it('按公司时区分组、以纯文本渲染摘要并加载更早游标', async () => {
    api.project.mockResolvedValueOnce(page([
      item('44000000-0000-4000-8000-000000000011', '<img src=x> 创建了事项', '2026-08-30T10:00:00Z'),
    ], 'older')).mockResolvedValueOnce(page([
      item('44000000-0000-4000-8000-000000000012', '更新了事项', '2026-08-29T10:00:00Z'),
    ], null))

    const wrapper = mount(ActivityTimeline, { props: { projectId: 'project-1' } })
    await flushPromises()

    expect(wrapper.text()).toContain('动态从 2026-08-30 16:00 开始记录')
    expect(wrapper.text()).toContain('<img src=x> 创建了事项')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('2026-08-30')

    await wrapper.get('.activity-timeline__older').trigger('click')
    await flushPromises()
    expect(api.project).toHaveBeenLastCalledWith(expect.objectContaining({ cursor: 'older' }))
    expect(wrapper.text()).toContain('更新了事项')
  })

  it('事项范围只调用事项动态接口', async () => {
    api.workItem.mockResolvedValue(page([], null))
    const wrapper = mount(ActivityTimeline, { props: { workItemId: 'item-1', compact: true } })
    await flushPromises()
    expect(api.workItem).toHaveBeenCalledWith(expect.objectContaining({ workItemId: 'item-1' }))
    expect(api.project).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('刷新后忽略仍在途的加载更早响应', async () => {
    let resolveOlder!: (value: ActivityPage) => void
    const older = new Promise<ActivityPage>(resolve => { resolveOlder = resolve })
    api.project.mockResolvedValueOnce(page([
      item('44000000-0000-4000-8000-000000000021', '初始动态', '2026-08-30T10:00:00Z'),
    ], 'older')).mockReturnValueOnce(older).mockResolvedValueOnce(page([
      item('44000000-0000-4000-8000-000000000022', '刷新动态', '2026-08-30T11:00:00Z'),
    ], null))

    const wrapper = mount(ActivityTimeline, { props: { projectId: 'project-1' } })
    await flushPromises()
    await wrapper.get('.activity-timeline__older').trigger('click')
    await wrapper.get('.activity-timeline__toolbar button').trigger('click')
    await flushPromises()

    resolveOlder(page([
      item('44000000-0000-4000-8000-000000000023', '迟到动态', '2026-08-29T10:00:00Z'),
    ], null))
    await flushPromises()

    expect(wrapper.text()).toContain('刷新动态')
    expect(wrapper.text()).not.toContain('迟到动态')
    wrapper.unmount()
  })
})
