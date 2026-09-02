import {
  WorkItemStatusCategory,
  WorkItemLabelColorToken,
  type ProjectWorkItemListItem,
  type WorkItemDetail,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { localProblem } from '../../api/problems'
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
    contentColorToken: contentId === 'content-1' ? WorkItemLabelColorToken.BrightBlue : WorkItemLabelColorToken.BrightGreen,
    itemNo: id.toUpperCase(), title: id,
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
        { id: 'content-1', name: '产品需求', colorToken: WorkItemLabelColorToken.BrightBlue },
        { id: 'content-2', name: '任务', colorToken: WorkItemLabelColorToken.BrightGreen },
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

  it('表头不渲染连接锚点，仅为每条子项和添加行渲染不可交互的层级连接锚点', async () => {
    const wrapper = mountTable()

    expect(wrapper.get('.subitem-hierarchy-branches').attributes('aria-hidden')).toBe('true')
    expect(wrapper.findAll('.subitem-hierarchy-branch--header')).toHaveLength(0)
    expect(wrapper.findAll('.subitem-hierarchy-branch--data')).toHaveLength(2)
    expect(wrapper.findAll('.subitem-hierarchy-branch--add')).toHaveLength(1)
    expect(wrapper.findAll('.subitem-hierarchy-branch')).toHaveLength(3)
    expect(wrapper.get('.subitem-hierarchy-bar').attributes('aria-hidden')).toBe('true')
    expect(wrapper.findAll('.subitem-hierarchy-bar__trailing')).toHaveLength(1)
    expect(wrapper.get('.subitem-add').text()).toBe('添加子项')
    expect(wrapper.find('.subitem-add svg').exists()).toBe(false)
    expect(wrapper.find('.subitem-add .subitem-quick-checkbox').exists()).toBe(true)
    expect(wrapper.find('.subitem-add input[type="checkbox"]').exists()).toBe(false)

    await wrapper.get('.subitem-add').trigger('click')

    expect(wrapper.findAll('.subitem-hierarchy-branch--add')).toHaveLength(0)
    expect(wrapper.findAll('.subitem-hierarchy-branch--quick')).toHaveLength(1)
    expect(wrapper.get('.subitem-hierarchy-bar').classes()).toContain('subitem-hierarchy-bar--quick')
    expect(wrapper.find('.subitem-quick-row').exists()).toBe(true)
    expect(wrapper.get('input[placeholder="添加子项"]').attributes('aria-label')).toContain('Shift+Enter')
    expect(wrapper.find('.subitem-quick-content').exists()).toBe(true)
    expect(wrapper.find('.subitem-quick-row .subitem-quick-checkbox').exists()).toBe(true)
  })

  it('空子项时让添加行紧接表头，加载和错误状态保留占位连接锚点', async () => {
    const wrapper = mountTable([])

    expect(wrapper.findAll('.subitem-hierarchy-branch--data')).toHaveLength(0)
    expect(wrapper.findAll('.subitem-hierarchy-branch--empty')).toHaveLength(0)
    expect(wrapper.findAll('.subitem-hierarchy-branch')).toHaveLength(1)
    expect(wrapper.get('.monday-subitem-table').classes()).toContain('monday-subitem-table--empty')

    await wrapper.setProps({ loading: true })
    expect(wrapper.findAll('.subitem-hierarchy-branch--empty')).toHaveLength(1)
    expect(wrapper.get('.monday-subitem-table').classes()).not.toContain('monday-subitem-table--empty')

    await wrapper.setProps({ loading: false, error: localProblem('子项加载失败') })
    expect(wrapper.findComponent({ name: 'InlineProblem' }).exists()).toBe(true)
    expect(wrapper.findAll('.subitem-hierarchy-branch--empty')).toHaveLength(1)
  })

  it('让每个可调整宽度的表头占满单元格并完整显示列名', async () => {
    const wrapper = mountTable()
    await flushPromises()
    const headers = wrapper.findAll('.subitem-column-header')

    expect(headers).toHaveLength(2)
    expect(headers.map(header => header.text())).toEqual(['工作项名称', '工作项类别'])
  })

  it('让状态和优先级标签占满对应子项单元格', async () => {
    const wrapper = mountTable()
    await wrapper.setProps({
      columns: [
        { key: 'status', label: '状态' },
        { key: 'priority', label: '优先级' },
      ],
    })
    await flushPromises()

    expect(wrapper.findAll('td.subitem-block-column')).toHaveLength(4)
    expect(wrapper.findAll('.subitem-block-cell')).toHaveLength(4)
  })

  it('固定勾选和名称列，并让单行表头整列可拖出表格但仅在当前子表内落下', async () => {
    const wrapper = mountTable()
    await wrapper.setProps({
      columns: [
        { key: 'title', label: '工作项名称' },
        { key: 'status', label: '状态' },
        { key: 'priority', label: '优先级' },
      ],
    })
    await flushPromises()

    const tableColumns = wrapper.findAllComponents({ name: 'ElTableColumn' })
    const selectionColumn = tableColumns.find(column => column.props('type') === 'selection')
    const titleColumn = tableColumns.find(column => column.props('columnKey') === 'title')
    const addColumn = tableColumns.find(column => column.props('columnKey') === 'add-column')
    expect(selectionColumn?.props('fixed')).toBe(true)
    expect(selectionColumn?.props('className')).toBe('subitem-selection-column')
    expect(selectionColumn?.props('labelClassName')).toBe('subitem-selection-column')
    expect(titleColumn?.props('fixed')).toBe(true)
    expect(addColumn?.props('minWidth')).toBe(96)
    expect(addColumn?.props('resizable')).toBe(false)

    const addColumnButton = wrapper.get('.subitem-add-column-button')
    expect(addColumnButton.attributes('aria-label')).toBe('添加列（功能预留）')
    expect(addColumnButton.get('svg').attributes()).toMatchObject({
      viewBox: '0 0 20 20', width: '18', height: '18', fill: 'currentColor',
    })
    expect(addColumnButton.get('path').attributes('d')).toContain('M10 2.25')

    const headers = wrapper.findAll('.subitem-column-header')
    expect(headers.every(header => header.attributes('draggable') === undefined)).toBe(true)
    expect(wrapper.findAll('.monday-column-resize-handle')).toHaveLength(3)
    expect(wrapper.findAll('.monday-column-quick-sort')).toHaveLength(3)

    const view = wrapper.vm as unknown as {
      columnDraggingKey: string | undefined
      columnDropIndex: number | undefined
      columnDropAllowed: boolean
      onColumnPointerDown: (event: PointerEvent) => void
      onColumnPointerMove: (event: PointerEvent) => void
      onColumnPointerUp: (event: PointerEvent) => void
      subitemCellStyle: (context: { column: { columnKey?: string } }) => Record<string, string | number>
    }
    const movableHeaders = wrapper.findAll<HTMLTableCellElement>('th.subitem-movable-column-header')
    expect(movableHeaders).toHaveLength(2)
    const widths = [130, 120]
    let left = 100
    movableHeaders.forEach((header, index) => {
      const width = widths[index]!
      const currentLeft = left
      vi.spyOn(header.element, 'getBoundingClientRect').mockReturnValue({
        x: currentLeft, y: 20, left: currentLeft, top: 20, right: currentLeft + width, bottom: 58,
        width, height: 38, toJSON: () => ({}),
      } as DOMRect)
      Object.defineProperty(header.element, 'offsetWidth', { configurable: true, value: width })
      left += width
    })
    vi.spyOn(wrapper.get('.monday-subitem-table').element, 'getBoundingClientRect').mockReturnValue({
      x: 0, y: 20, left: 0, top: 20, right: 600, bottom: 130,
      width: 600, height: 110, toJSON: () => ({}),
    } as DOMRect)

    view.onColumnPointerDown({
      isPrimary: true, button: 0, pointerId: 7, clientX: 150, clientY: 39,
      target: movableHeaders[0]!.element,
    } as unknown as PointerEvent)
    const movePreventDefault = vi.fn()
    view.onColumnPointerMove({
      pointerId: 7, clientX: 300, clientY: 44,
      preventDefault: movePreventDefault, stopPropagation: vi.fn(),
    } as unknown as PointerEvent)
    await flushPromises()

    expect(movePreventDefault).toHaveBeenCalledOnce()
    expect(view.columnDraggingKey).toBe('status')
    expect(view.columnDropIndex).toBe(2)
    expect(view.columnDropAllowed).toBe(true)
    expect(view.subitemCellStyle({ column: { columnKey: 'status' } })).toEqual({ opacity: 0, pointerEvents: 'none' })
    expect(view.subitemCellStyle({ column: { columnKey: 'priority' } })).toEqual({ transform: 'translateX(-130px)' })
    const preview = document.querySelector<HTMLElement>('.subitem-column-drag-preview')
    expect(preview?.style.transform).toBe('rotate(1deg)')
    expect(preview?.style.top).toBe('25px')
    expect(preview?.querySelectorAll('thead tr')).toHaveLength(1)
    expect(preview?.querySelectorAll('.work-item-column-drag-preview__header')).toHaveLength(1)
    expect(preview?.querySelectorAll('.work-item-column-drag-preview__cell')).toHaveLength(2)
    expect([...preview?.querySelectorAll('tbody .work-item-column-drag-preview__cell') ?? []]
      .map(cell => cell.textContent?.trim())).toEqual(['BACKLOG', 'BACKLOG'])
    expect(movableHeaders[0]!.classes()).toContain('subitem-column-drag-source')

    view.onColumnPointerMove({
      pointerId: 7, clientX: 700, clientY: 200,
      preventDefault: vi.fn(), stopPropagation: vi.fn(),
    } as unknown as PointerEvent)
    await flushPromises()
    expect(preview?.style.left).toBe('650px')
    expect(preview?.style.top).toBe('181px')
    expect(view.columnDropAllowed).toBe(false)
    expect(view.columnDropIndex).toBeUndefined()

    view.onColumnPointerUp({
      pointerId: 7, preventDefault: vi.fn(), stopPropagation: vi.fn(),
    } as unknown as PointerEvent)
    await flushPromises()
    expect(wrapper.emitted('moveColumn')).toBeUndefined()
    expect(document.querySelector('.subitem-column-drag-preview')).toBeNull()
    expect(movableHeaders[0]!.classes()).not.toContain('subitem-column-drag-source')

    view.onColumnPointerDown({
      isPrimary: true, button: 0, pointerId: 8, clientX: 150, clientY: 39,
      target: movableHeaders[0]!.element,
    } as unknown as PointerEvent)
    view.onColumnPointerMove({
      pointerId: 8, clientX: 300, clientY: 44,
      preventDefault: vi.fn(), stopPropagation: vi.fn(),
    } as unknown as PointerEvent)
    view.onColumnPointerUp({
      pointerId: 8, preventDefault: vi.fn(), stopPropagation: vi.fn(),
    } as unknown as PointerEvent)
    await flushPromises()
    expect(wrapper.emitted('moveColumn')?.at(-1)).toEqual(['status', 'priority', 'after'])

    wrapper.findComponent({ name: 'ElTable' }).vm.$emit('header-dragend', 164, 130, { label: '状态' })
    await flushPromises()
    expect(wrapper.emitted('headerResize')?.at(-1)).toEqual([164, 130, { label: '状态' }])
  })
})
