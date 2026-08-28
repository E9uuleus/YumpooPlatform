import {
  WorkItemStatusCategory,
  WorkItemType,
  type ProjectWorkItemListItem,
  type WorkItemDetail,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectWorkItemSubitemsTable from './ProjectWorkItemSubitemsTable.vue'

const api = vi.hoisted(() => ({
  createWorkItemSubitem: vi.fn(),
  moveWorkItemSubitemOrder: vi.fn(),
}))

vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))

vi.mock('../../api/client', () => ({
  workItemsApi: api,
}))

function item(id: string, contentId = 'content-1'): ProjectWorkItemListItem {
  return {
    id, projectId: 'project-1', contentId, contentName: contentId === 'content-1' ? '产品需求' : '任务',
    itemNo: id.toUpperCase(), type: WorkItemType.Requirement, title: id,
    statusCode: 'BACKLOG', statusCategory: WorkItemStatusCategory.Todo,
    priority: null, assigneeUserId: null, assigneeDisplayName: null, dueDate: null,
    subitemCount: 0, rowVersion: 1, etag: '"1"', updatedAt: new Date('2026-08-25T01:00:00Z'),
    capabilities: {
      canEditFields: true, canMoveInKanban: true, canMoveInProjectOrder: true,
      canDiscuss: true, canDelete: true, canRestore: false, availableTransitions: [],
    },
  }
}

function mountTable(items = [item('child-1'), item('child-2')]) {
  return mount(ProjectWorkItemSubitemsTable, {
    props: {
      projectId: 'project-1',
      parent: item('parent-1'),
      items,
      loading: false,
      sortRules: [],
      columns: [
        { key: 'title', label: '工作项名称' },
        { key: 'content', label: '工作项类别' },
      ],
      columnWidths: {
        title: 320, assignee: 90, status: 130, priority: 120,
        content: 150, dueDate: 140, updatedAt: 170,
      },
      activeContents: [
        { id: 'content-1', name: '产品需求' },
        { id: 'content-2', name: '任务' },
      ],
      members: [], workflowStatuses: [], priorityOptions: [], canCreate: true, editingCell: false,
    },
    global: {
      stubs: {
        InlineProblem: true,
        WorkItemLabelPopoverContent: true,
      },
    },
  })
}

describe('项目工作项子表格', () => {
  beforeEach(() => {
    api.createWorkItemSubitem.mockReset()
    api.moveWorkItemSubitemOrder.mockReset()
    api.createWorkItemSubitem.mockResolvedValue(item('created') as unknown as WorkItemDetail)
    api.moveWorkItemSubitemOrder.mockImplementation(({ subitemId }: { subitemId: string }) =>
      Promise.resolve(item(subitemId) as unknown as WorkItemDetail))
  })

  it('创建子项时默认继承父项 Content，并允许切换到同项目其他 ACTIVE Content', async () => {
    const wrapper = mountTable()
    const view = wrapper.vm as unknown as {
      quickTitle: string
      quickContentId: string
      createQuick: (continueAdding: boolean) => Promise<void>
    }

    expect(view.quickContentId).toBe('content-1')
    view.quickTitle = '跨 Content 子项'
    view.quickContentId = 'content-2'
    await view.createQuick(false)

    expect(api.createWorkItemSubitem).toHaveBeenCalledWith(expect.objectContaining({
      parentWorkItemId: 'parent-1',
      workItemSubitemCreateRequest: expect.objectContaining({
        contentId: 'content-2', title: '跨 Content 子项',
      }),
    }))
    expect(wrapper.emitted('created')).toHaveLength(1)
  })

  it('按父项独立提交排序规则，并使用直接兄弟锚点移动子项', async () => {
    const first = item('child-1')
    const second = item('child-2')
    const wrapper = mountTable([first, second])
    const view = wrapper.vm as unknown as {
      applySort: (key: 'title') => void
      onRowDragStart: (row: ProjectWorkItemListItem) => void
      dropBefore: (row: ProjectWorkItemListItem) => Promise<void>
    }

    view.applySort('title')
    expect(wrapper.emitted('sortChange')?.[0]).toEqual([[{ field: 'TITLE', direction: 'ASC' }]])

    view.onRowDragStart(second)
    await view.dropBefore(first)
    await flushPromises()
    expect(api.moveWorkItemSubitemOrder).toHaveBeenCalledWith(expect.objectContaining({
      parentWorkItemId: 'parent-1', subitemId: 'child-2', ifMatch: '"1"',
      projectWorkItemOrderMoveRequest: {
        previousVisibleWorkItemId: null,
        nextVisibleWorkItemId: 'child-1',
      },
    }))
  })

  it('保持单层子表，不渲染下一级展开入口', () => {
    const wrapper = mountTable()
    expect(wrapper.find('.subitem-table-shell').attributes('aria-label')).toContain('parent-1')
    expect(wrapper.findAll('.el-table__expand-icon')).toHaveLength(0)
  })

  it('让每个可调整宽度的表头占满单元格并完整显示列名', async () => {
    const wrapper = mountTable()
    await flushPromises()
    const headers = wrapper.findAll('.subitem-column-header')

    expect(headers).toHaveLength(2)
    expect(headers.map(header => header.text())).toEqual(['工作项名称', '工作项类别'])
  })
})
