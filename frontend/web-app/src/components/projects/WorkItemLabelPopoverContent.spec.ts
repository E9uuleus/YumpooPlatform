import {
  WorkItemLabelColorToken,
  WorkItemStatusCategory,
  type WorkItemLabelCatalog,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { workItemsApi } from '../../api/client'
import WorkItemLabelPopoverContent from './WorkItemLabelPopoverContent.vue'

vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))

vi.mock('../../api/client', () => ({
  workItemsApi: {
    createProjectWorkItemStatusLabel: vi.fn(),
    createProjectWorkItemPriorityLabel: vi.fn(),
    updateProjectWorkItemStatusLabel: vi.fn(),
    updateProjectWorkItemPriorityLabel: vi.fn(),
    deleteProjectWorkItemStatusLabel: vi.fn(),
    deleteProjectWorkItemPriorityLabel: vi.fn(),
  },
}))

function mockCatalog(): WorkItemLabelCatalog {
  return {
    rowVersion: 1,
    etag: '"etag-1"',
    canManage: true,
    statuses: [
      { code: 'DONE', displayName: '已完成', statusCategory: WorkItemStatusCategory.Done, colorToken: WorkItemLabelColorToken.Green, active: true, sortOrder: 1, inUse: true, protectedLabel: false },
      { code: 'IN_PROGRESS', displayName: '进行中', statusCategory: WorkItemStatusCategory.InProgress, colorToken: WorkItemLabelColorToken.Orange, active: true, sortOrder: 2, inUse: false, protectedLabel: false },
      { code: 'STUCK', displayName: '卡住', statusCategory: WorkItemStatusCategory.InProgress, colorToken: WorkItemLabelColorToken.Red, active: true, sortOrder: 3, inUse: false, protectedLabel: false },
      { code: 'NOT_STARTED', displayName: '未开始', statusCategory: WorkItemStatusCategory.Todo, colorToken: WorkItemLabelColorToken.Gray, active: true, sortOrder: 4, inUse: false, protectedLabel: false },
      { code: 'CLOSED', displayName: '已关闭', statusCategory: WorkItemStatusCategory.Canceled, colorToken: WorkItemLabelColorToken.Blue, active: false, sortOrder: 5, inUse: false, protectedLabel: false },
      { code: 'CUSTOM_1', displayName: '自定义1', statusCategory: WorkItemStatusCategory.InProgress, colorToken: WorkItemLabelColorToken.Purple, active: true, sortOrder: 6, inUse: false, protectedLabel: false },
      { code: 'CUSTOM_2', displayName: '自定义2', statusCategory: WorkItemStatusCategory.InProgress, colorToken: WorkItemLabelColorToken.Cyan, active: true, sortOrder: 7, inUse: false, protectedLabel: false },
    ],
    priorities: [
      { code: 'URGENT', displayName: '紧急', colorToken: WorkItemLabelColorToken.Red, active: true, sortOrder: 1, inUse: false },
      { code: 'HIGH', displayName: '高', colorToken: WorkItemLabelColorToken.Orange, active: true, sortOrder: 2, inUse: false },
      { code: 'LOW', displayName: '低', colorToken: WorkItemLabelColorToken.Blue, active: true, sortOrder: 3, inUse: false },
    ],
  }
}

describe('WorkItemLabelPopoverContent', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('选择态：正确渲染状态列表，点击选项触发 selectStatus 事件', async () => {
    const catalog = mockCatalog()
    const wrapper = mount(WorkItemLabelPopoverContent, {
      props: {
        kind: 'status',
        projectId: 'project-1',
        catalog,
        workflowStatuses: catalog.statuses.map(s => ({
          statusCode: s.code,
          displayName: s.displayName,
          colorToken: s.colorToken,
          sortOrder: s.sortOrder,
          active: s.active,
        })),
        currentValue: 'NOT_STARTED',
        canManage: true,
        availableTransitions: [{ toStatus: 'DONE' }, { toStatus: 'IN_PROGRESS' }],
      },
    })

    const buttons = wrapper.findAll('.status-option-pill')
    expect(buttons.length).toBe(7)

    // 点击可迁移状态
    await buttons[0]?.trigger('click')
    expect(wrapper.emitted('selectStatus')?.[0]).toEqual(['DONE'])

    // 点击不可迁移状态不触发
    await buttons[2]?.trigger('click') // STUCK 不是可用迁移
    expect(wrapper.emitted('selectStatus')?.length).toBe(1)
  })

  it('选择态：正确渲染优先级列表及清空按钮，点击触发 selectPriority 事件', async () => {
    const catalog = mockCatalog()
    const wrapper = mount(WorkItemLabelPopoverContent, {
      props: {
        kind: 'priority',
        projectId: 'project-1',
        catalog,
        priorityOptions: catalog.priorities,
        currentValue: 'HIGH',
        canManage: true,
      },
    })

    const pills = wrapper.findAll('.priority-option-pill')
    expect(pills.length).toBe(4) // 3 priorities + 1 empty

    await pills[0]?.trigger('click')
    expect(wrapper.emitted('selectPriority')?.[0]).toEqual(['URGENT'])

    await pills[3]?.trigger('click')
    expect(wrapper.emitted('selectPriority')?.[1]).toEqual([null])
  })

  it('编辑态切换：点击编辑按钮平滑切换至编辑视图，多列分栏正确', async () => {
    const catalog = mockCatalog()
    const wrapper = mount(WorkItemLabelPopoverContent, {
      props: {
        kind: 'status',
        projectId: 'project-1',
        catalog,
        workflowStatuses: catalog.statuses.map(s => ({
          statusCode: s.code,
          displayName: s.displayName,
          colorToken: s.colorToken,
          sortOrder: s.sortOrder,
        })),
        canManage: true,
      },
    })

    const editBtn = wrapper.find('.edit-action-btn')
    expect(editBtn.exists()).toBe(true)
    await editBtn.trigger('click')

    // 切换到编辑视图
    expect(wrapper.find('.label-edit-view').exists()).toBe(true)

    // 7 个标签应分为 2 列（每列最多 6 个）
    const columns = wrapper.findAll('.label-column')
    expect(columns.length).toBe(2)

    // 点击应用按钮切回选择态
    const applyBtn = wrapper.find('.apply-action-btn')
    expect(applyBtn.exists()).toBe(true)
    await applyBtn.trigger('click')

    expect(wrapper.find('.label-select-view').exists()).toBe(true)
  })

  it('编辑态操作：新增标签仅在点击应用后调用 API', async () => {
    const catalog = mockCatalog()
    const updatedCatalog = {
      ...catalog,
      rowVersion: 2,
      etag: '"etag-2"',
      statuses: [
        ...catalog.statuses,
        {
          code: 'CUSTOM_3',
          displayName: '新标签',
          statusCategory: WorkItemStatusCategory.Todo,
          colorToken: WorkItemLabelColorToken.Blue,
          active: true,
          sortOrder: 8,
          inUse: false,
          protectedLabel: false,
        },
      ],
    }
    vi.mocked(workItemsApi.createProjectWorkItemStatusLabel).mockResolvedValue(updatedCatalog)

    const wrapper = mount(WorkItemLabelPopoverContent, {
      props: {
        kind: 'status',
        projectId: 'project-1',
        catalog,
        canManage: true,
      },
    })

    await wrapper.find('.edit-action-btn').trigger('click')

    const newBtn = wrapper.find('.new-label-action-btn')
    expect(newBtn.exists()).toBe(true)
    await newBtn.trigger('click')

    expect(workItemsApi.createProjectWorkItemStatusLabel).not.toHaveBeenCalled()
    expect(wrapper.emitted('updated')).toBeUndefined()

    await wrapper.find('.apply-action-btn').trigger('click')
    await flushPromises()

    expect(workItemsApi.createProjectWorkItemStatusLabel).toHaveBeenCalledWith(
      expect.objectContaining({
        projectId: 'project-1',
        workItemLabelCreateRequest: expect.objectContaining({
          displayName: '新标签',
          colorToken: WorkItemLabelColorToken.Blue,
        }),
      }),
    )
    expect(wrapper.emitted('updated')?.[0]).toEqual([updatedCatalog])
    expect(wrapper.find('.label-select-view').exists()).toBe(true)
  })

  it('外部关闭时重置到选择态并丢弃未应用的新标签', async () => {
    const catalog = mockCatalog()
    const wrapper = mount(WorkItemLabelPopoverContent, {
      props: {
        kind: 'status',
        projectId: 'project-1',
        catalog,
        canManage: true,
      },
    })

    await wrapper.find('.edit-action-btn').trigger('click')
    await wrapper.find('.new-label-action-btn').trigger('click')
    expect(wrapper.findAll('.label-edit-row')).toHaveLength(8)

    wrapper.vm.resetEditor()
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.label-select-view').exists()).toBe(true)
    expect(workItemsApi.createProjectWorkItemStatusLabel).not.toHaveBeenCalled()
  })

  it('颜色更改先保留在草稿中，应用后才提交', async () => {
    const catalog = mockCatalog()
    const updatedCatalog = {
      ...catalog,
      rowVersion: 2,
      etag: '"etag-2"',
      statuses: catalog.statuses.map(label => label.code === 'DONE'
        ? { ...label, colorToken: WorkItemLabelColorToken.Lime }
        : label),
    }
    vi.mocked(workItemsApi.updateProjectWorkItemStatusLabel).mockResolvedValue(updatedCatalog)

    const wrapper = mount(WorkItemLabelPopoverContent, {
      props: {
        kind: 'status',
        projectId: 'project-1',
        catalog,
        canManage: true,
      },
      global: {
        stubs: {
          ElPopover: {
            template: '<div><slot name="reference" /><slot /></div>',
          },
        },
      },
    })

    await wrapper.find('.edit-action-btn').trigger('click')
    const firstColor = wrapper.find('.color-swatch-item')
    expect(firstColor.exists()).toBe(true)
    await firstColor.trigger('click')

    expect(workItemsApi.updateProjectWorkItemStatusLabel).not.toHaveBeenCalled()

    await wrapper.find('.apply-action-btn').trigger('click')
    await flushPromises()

    expect(workItemsApi.updateProjectWorkItemStatusLabel).toHaveBeenCalledWith(
      expect.objectContaining({
        code: 'DONE',
        workItemLabelUpdateRequest: expect.objectContaining({
          colorToken: WorkItemLabelColorToken.Lime,
        }),
      }),
    )
    expect(wrapper.emitted('updated')?.[0]).toEqual([updatedCatalog])
  })
})
