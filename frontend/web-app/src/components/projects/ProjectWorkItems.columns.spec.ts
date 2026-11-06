import {
  WorkItemViewType,
} from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ConnectColumnAddButton from './connect/ConnectColumnAddButton.vue'
import { state } from './projectWorkItemsTestMocks'
import { item, mountView, page, installWorkItemsTestDefaults, restoreWorkItemsTestGlobals } from './projectWorkItemsTestSupport'

vi.mock('../../onboarding/useOnboarding', async () => (await import('./projectWorkItemsTestMocks')).onboardingModule())
vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))
vi.mock('vue-router', async () => (await import('./projectWorkItemsTestMocks')).vueRouterModule())
vi.mock('../../api/client', async () => (await import('./projectWorkItemsTestMocks')).apiClientModule())

enableAutoUnmount(afterEach)

describe('项目级工作项首页 · 列布局与列偏好', () => {
  beforeEach(installWorkItemsTestDefaults)
  afterEach(restoreWorkItemsTestGlobals)

  it('按固定顺序展示业务列和不可拖拽的新增列入口，并将 Content 映射为类别名称', async () => {
    const wrapper = mountView()
    await flushPromises()

    const labels = wrapper.findAll('.el-table__header th').map(node => node.text()).filter(Boolean)
    expect(labels).toEqual(['工作项名称', '处理人', '状态', '优先级', '工作项类别', '截止日期', '时长追踪', '最后更新时间'])
    for (const [key, width] of [['status', 96], ['priority', 90], ['content', 110]] as const) {
      const column = wrapper.findAllComponents({ name: 'ElTableColumn' })
        .find(candidate => candidate.props('prop') === key)
      expect(column?.props('width')).toBe(width)
    }
    const addColumnHeader = wrapper.get('th.monday-add-column-header')
    const addColumn = wrapper.findAllComponents({ name: 'ElTableColumn' })
      .find(column => column.props('columnKey') === 'add-column')
    const addColumnButton = addColumnHeader.get('button.connect-column-add-button')
    expect(addColumnButton.attributes('aria-label')).toBe('添加列')
    expect(addColumnButton.find('svg').exists()).toBe(true)
    expect(wrapper.findComponent(ConnectColumnAddButton).props('canManage')).toBe(true)
    expect(addColumnHeader.classes()).not.toContain('monday-movable-column-header')
    expect(addColumn?.props('width')).toBe('')
    expect(addColumn?.props('minWidth')).toBe(96)
    expect(addColumn?.props('resizable')).toBe(false)
    expect(wrapper.get('td.monday-add-column').text()).toBe('')
    expect(wrapper.text()).toContain('产品需求')
    expect(wrapper.text()).toContain('实现项目工作项首页')
    expect(wrapper.get('.work-item-link').text()).toBe('实现项目工作项首页')
    expect(wrapper.find('.work-item-code-text').exists()).toBe(false)
    expect(state.listProjectWorkItems).toHaveBeenCalledWith(expect.objectContaining({
      projectId: 'project-1', view: WorkItemViewType.Table,
    }), expect.objectContaining({ signal: expect.any(AbortSignal) }))
  })

  it('恢复已有自定义列宽，并将低于最小值的已保存列宽限制到最小值', async () => {
    localStorage.setItem('yumpoo:project-work-items:table:v1', JSON.stringify({
      version: 1, widths: { status: 155, priority: 188, content: 80 },
    }))
    const wrapper = mountView()
    await flushPromises()

    for (const [key, width] of [['status', 155], ['priority', 188], ['content', 110]] as const) {
      const column = wrapper.findAllComponents({ name: 'ElTableColumn' })
        .find(candidate => candidate.props('prop') === key)
      expect(column?.props('width')).toBe(width)
    }
  })

  it('持久化列宽与隐藏列，且工作项名称始终可见', async () => {
    const wrapper = mountView()
    await flushPromises()
    const view = wrapper.vm as unknown as {
      onHeaderDragEnd: (width: number, oldWidth: number, column: { property: string }) => void
      toggleColumn: (key: string, checked: boolean) => void
    }

    view.onHeaderDragEnd(188, 120, { property: 'priority' })
    const widths = () => wrapper.findAllComponents({ name: 'ElTableColumn' })
      .filter(column => column.props('label') && column.props('label') !== '优先级')
      .map(column => [column.props('label'), column.props('width')])
    await nextTick()
    const before = widths()
    view.toggleColumn('priority', false)
    view.toggleColumn('title', false)
    await nextTick()

    const labels = wrapper.findAll('.el-table__header th').map(node => node.text()).filter(Boolean)
    expect(labels).not.toContain('优先级')
    expect(labels).toContain('工作项名称')
    expect(widths()).toEqual(before)
    expect(JSON.parse(localStorage.getItem('yumpoo:project-work-items:table:v1') ?? '{}'))
      .toMatchObject({ version: 1, widths: { priority: 188 }, hidden: ['priority'] })
  })

  it('拖拽业务列表头时复刻原色列浮层、横向避让并持久化新顺序', async () => {
    const wrapper = mountView()
    await flushPromises()
    const table = wrapper.getComponent({ name: 'ElTable' }).vm.$
    const tableElement = wrapper.get('.monday-table').element
    const view = wrapper.vm as unknown as {
      movableColumnOrder: string[]
      columnDraggingKey: string | undefined
      columnDraggingIndex: number
      columnDropIndex: number | undefined
      columnResizingKey: string | undefined
      columnWidths: Record<string, number>
      onTableColumnPointerDown: (event: PointerEvent) => void
      onTableColumnPointerMove: (event: PointerEvent) => void
      onTableColumnPointerUp: (event: PointerEvent) => void
      onTableColumnResizePointerMove: (event: PointerEvent) => void
      onTableColumnResizePointerUp: (event: PointerEvent) => void
      tableColumnDragStyle: (columnKey?: string) => Record<string, string | number>
    }
    const movableHeaders = wrapper.findAll<HTMLTableCellElement>('th.monday-movable-column-header')
    expect(movableHeaders).toHaveLength(7)
    expect(wrapper.findAll('.monday-column-resize-handle')).toHaveLength(8)
    expect(wrapper.get('th.monday-title-column .monday-title-column-resize-handle').attributes('data-column-key')).toBe('title')
    expect(movableHeaders.map(header => header.text())).toEqual(['处理人', '状态', '优先级', '工作项类别', '截止日期', '时长追踪', '最后更新时间'])
    expect(wrapper.get('th.monday-title-column').classes()).not.toContain('monday-movable-column-header')

    const widths = [90, 96, 90, 110, 140, 170]
    let left = 100
    movableHeaders.forEach((header, index) => {
      const width = widths[index]!
      const currentLeft = left
      vi.spyOn(header.element, 'getBoundingClientRect').mockReturnValue({
        x: currentLeft, y: 200, left: currentLeft, top: 200, right: currentLeft + width, bottom: 238,
        width, height: 38, toJSON: () => ({}),
      } as DOMRect)
      Object.defineProperty(header.element, 'offsetWidth', { configurable: true, value: width })
      left += width
    })
    vi.spyOn(wrapper.get('.monday-table').element, 'getBoundingClientRect').mockReturnValue({
      x: 0, y: 200, left: 0, top: 200, right: 900, bottom: 600,
      width: 900, height: 400, toJSON: () => ({}),
    } as DOMRect)

    const resizeHandle = movableHeaders[0]!.get<HTMLElement>('.monday-column-resize-handle')
    expect(resizeHandle.attributes('data-column-key')).toBe('assignee')
    const resizeDownPreventDefault = vi.fn()
    const resizeDownStopPropagation = vi.fn()
    view.onTableColumnPointerDown({
      isPrimary: true, button: 0, pointerId: 20, clientX: 188, clientY: 219, target: resizeHandle.element,
      preventDefault: resizeDownPreventDefault, stopPropagation: resizeDownStopPropagation,
    } as unknown as PointerEvent)
    expect(resizeDownPreventDefault).toHaveBeenCalledOnce()
    expect(resizeDownStopPropagation).toHaveBeenCalledOnce()
    expect(view.columnResizingKey).toBe('assignee')

    const resizeMovePreventDefault = vi.fn()
    view.onTableColumnResizePointerMove({
      pointerId: 20, clientX: 218, clientY: 219,
      preventDefault: resizeMovePreventDefault, stopPropagation: vi.fn(),
    } as unknown as PointerEvent)
    await nextTick()
    expect(resizeMovePreventDefault).toHaveBeenCalledOnce()
    expect(view.columnWidths.assignee).toBe(120)
    expect(wrapper.get('th.monday-column-header--assignee').classes()).toContain('monday-column-resizing')
    expect(view.columnDraggingKey).toBeUndefined()
    expect(document.querySelector('.work-item-column-drag-preview')).toBeNull()

    view.onTableColumnResizePointerUp({
      pointerId: 20, clientX: 218, clientY: 219, preventDefault: vi.fn(), stopPropagation: vi.fn(),
    } as unknown as PointerEvent)
    await nextTick()
    expect(view.columnResizingKey).toBeUndefined()
    expect(JSON.parse(localStorage.getItem('yumpoo:project-work-items:table:v1') ?? '{}').widths.assignee).toBe(120)

    view.onTableColumnPointerDown({
      isPrimary: true, button: 0, pointerId: 21, clientX: 210, clientY: 219, target: movableHeaders[1]!.element,
    } as unknown as PointerEvent)
    view.onTableColumnPointerMove({
      pointerId: 21, clientX: 213, clientY: 220, preventDefault: vi.fn(),
    } as unknown as PointerEvent)
    expect(view.columnDraggingKey).toBeUndefined()

    const movePreventDefault = vi.fn()
    view.onTableColumnPointerMove({
      pointerId: 21, clientX: 390, clientY: 224, preventDefault: movePreventDefault,
    } as unknown as PointerEvent)
    expect(movePreventDefault).toHaveBeenCalledOnce()
    expect(view.columnDraggingKey).toBe('status')
    expect(view.columnDropIndex).toBe(3)
    expect(view.tableColumnDragStyle('status')).toEqual({ opacity: 0, pointerEvents: 'none' })
    expect(view.tableColumnDragStyle('priority')).toEqual({ transform: 'translateX(-96px)' })
    const preview = document.querySelector<HTMLElement>('.work-item-column-drag-preview')
    expect(preview).not.toBeNull()
    expect(preview?.style.transform).toBe('rotate(1deg)')
    expect(preview?.querySelector('.work-item-column-drag-preview__header')?.classList).toContain('monday-column-header--status')
    expect(preview?.querySelectorAll('.work-item-column-drag-preview__cell')).toHaveLength(1)
    expect(preview?.querySelector('.work-item-column-drag-preview__cell')?.classList).toContain('monday-block-column')
    expect(preview?.querySelector('.monday-status-cell')?.getAttribute('style'))
      .toBe(wrapper.get('.monday-status-cell').attributes('style'))

    const pointerUpPreventDefault = vi.fn()
    const pointerUpStopPropagation = vi.fn()
    view.onTableColumnPointerUp({
      pointerId: 21, preventDefault: pointerUpPreventDefault, stopPropagation: pointerUpStopPropagation,
    } as unknown as PointerEvent)
    await flushPromises()
    expect(pointerUpPreventDefault).toHaveBeenCalledOnce()
    expect(pointerUpStopPropagation).toHaveBeenCalledOnce()
    expect(view.movableColumnOrder.slice(0, 3)).toEqual(['assignee', 'priority', 'status'])
    expect(document.querySelector('.work-item-column-drag-preview')).toBeNull()
    expect(wrapper.getComponent({ name: 'ElTable' }).vm.$ === table).toBe(true)
    expect(wrapper.get('.monday-table').element === tableElement).toBe(true)
    expect(wrapper.findAll('th.monday-movable-column-header').map(header => header.text()).slice(0, 3))
      .toEqual(['处理人', '优先级', '状态'])
    expect(wrapper.get('.work-item-table-row').findAll('td.monday-movable-column')
      .map(cell => cell.classes().find(name => name.startsWith('monday-column--'))).slice(0, 3))
      .toEqual(['monday-column--assignee', 'monday-column--priority', 'monday-column--status'])
    expect(JSON.parse(localStorage.getItem('yumpoo:project-work-items:table:v1') ?? '{}').order)
      .toEqual(['assignee', 'priority', 'status', 'content', 'dueDate', 'timeTracking', 'updatedAt'])
  })

  it('换列时保留滚动位置、勾选项和已展开子表中的输入', async () => {
    const parent = { ...item('parent-1'), subitemCount: 1 }
    state.listProjectWorkItems.mockResolvedValue(page([parent]))
    state.listWorkItemSubitems.mockResolvedValue({ items: [item('child-1')] })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('button[aria-label="展开子项"]').trigger('click')
    await flushPromises()
    const subtable = wrapper.getComponent({ name: 'ProjectWorkItemSubitemsTable' })
    const subtableInstance = subtable.vm.$
    await subtable.get('.subitem-add').trigger('click')
    await subtable.get('.subitem-quick-title input').setValue('尚未提交的子项')
    const checkbox = wrapper.get<HTMLInputElement>('.work-item-table-row .monday-selection-column .el-checkbox__original')
    await checkbox.setValue(true)
    const scrollElement = wrapper.get<HTMLElement>('.monday-table .el-scrollbar__wrap').element
    scrollElement.scrollLeft = 120
    scrollElement.scrollTop = 72
    const view = wrapper.vm as unknown as {
      columnDraggingKey: string | undefined
      columnDraggingIndex: number
      columnDropIndex: number | undefined
      selectedWorkItemIds: Set<string>
      commitTableColumnDrop: () => void
    }

    view.columnDraggingKey = 'status'
    view.columnDraggingIndex = 1
    view.columnDropIndex = 3
    view.commitTableColumnDrop()
    await flushPromises()
    expect(wrapper.findAll('.monday-table > .el-table__inner-wrapper th.monday-movable-column-header')
      .map(header => header.text()).slice(0, 3)).toEqual(['处理人', '优先级', '状态'])

    expect(wrapper.get<HTMLElement>('.monday-table .el-scrollbar__wrap').element === scrollElement).toBe(true)
    expect(scrollElement.scrollLeft).toBe(120)
    expect(scrollElement.scrollTop).toBe(72)
    expect(wrapper.get<HTMLInputElement>('.work-item-table-row .monday-selection-column .el-checkbox__original').element.checked).toBe(true)
    expect([...view.selectedWorkItemIds]).toEqual([parent.id])
    expect(wrapper.getComponent({ name: 'ProjectWorkItemSubitemsTable' }).vm.$ === subtableInstance).toBe(true)
    expect(subtable.get<HTMLInputElement>('.subitem-quick-title input').element.value).toBe('尚未提交的子项')
    expect(state.listProjectWorkItems).toHaveBeenCalledTimes(1)
    expect(state.listWorkItemSubitems).toHaveBeenCalledTimes(1)
  })

  it('连续换列和隐藏列后，实际表头与单元格仍对当前字段避让', async () => {
    const wrapper = mountView()
    await flushPromises()
    const tableInstance = wrapper.getComponent({ name: 'ElTable' }).vm.$
    const view = wrapper.vm as unknown as {
      movableColumnOrder: string[]
      columnDraggingKey: string | undefined
      columnDraggingIndex: number
      columnDropIndex: number | undefined
      commitTableColumnDrop: () => void
      toggleColumn: (key: string, checked: boolean) => void
      selectCell: (rowId: string, key: string) => void
    }
    const expectColumn = (key: string, opacity: string, transform: string) => {
      for (const selector of [`th.monday-column-header--${key}`, `.work-item-table-row td.monday-column--${key}`]) {
        const style = wrapper.get<HTMLElement>(selector).element.style
        expect(style.opacity, selector).toBe(opacity)
        expect(style.transform, selector).toBe(transform)
      }
    }
    view.movableColumnOrder = ['assignee', 'updatedAt', 'dueDate', 'status', 'priority', 'content']
    await nextTick()
    view.columnDraggingKey = 'updatedAt'
    view.columnDraggingIndex = 1
    view.columnDropIndex = 5
    await nextTick()

    expectColumn('updatedAt', '0', '')
    for (const key of ['dueDate', 'status', 'priority']) expectColumn(key, '', 'translateX(-170px)')
    for (const key of ['assignee', 'content']) expectColumn(key, '', 'translateX(0px)')
    view.commitTableColumnDrop()
    await nextTick()

    view.toggleColumn('priority', false)
    await nextTick()
    view.columnDraggingKey = 'content'
    view.columnDraggingIndex = 4
    view.columnDropIndex = 1
    await nextTick()

    expectColumn('content', '0', '')
    for (const key of ['dueDate', 'status', 'updatedAt']) expectColumn(key, '', 'translateX(110px)')
    expectColumn('assignee', '', 'translateX(0px)')
    view.commitTableColumnDrop()
    view.selectCell('item-1', 'dueDate')
    await nextTick()

    expect(wrapper.findAll('.work-item-table-row td.monday-cell--selected').map(cell => cell.classes()
      .find(name => name.startsWith('monday-column--')))).toEqual(['monday-column--dueDate'])
    expect(wrapper.getComponent({ name: 'ElTable' }).vm.$ === tableInstance).toBe(true)
    expect(state.listProjectWorkItems).toHaveBeenCalledTimes(1)
  })

  it('恢复旧列偏好时保留已有顺序并追加计时列', async () => {
    const order = ['updatedAt', 'content', 'assignee', 'status', 'priority', 'dueDate']
    localStorage.setItem('yumpoo:project-work-items:table:v1', JSON.stringify({ version: 1, order, subitemOrder: order }))
    const wrapper = mountView()
    await flushPromises()
    const view = wrapper.vm as unknown as { movableColumnOrder: string[]; subitemMovableColumnOrder: string[] }
    expect(view.movableColumnOrder).toEqual([...order, 'timeTracking'])
    expect(view.subitemMovableColumnOrder).toEqual([...order, 'timeTracking'])
  })

  it('主表与子工作项分别持久化列顺序', async () => {
    const wrapper = mountView()
    await flushPromises()
    const view = wrapper.vm as unknown as {
      movableColumnOrder: string[]
      subitemMovableColumnOrder: string[]
      moveSubitemColumn: (source: string, target: string, placement?: 'before' | 'after') => void
    }

    view.moveSubitemColumn('status', 'priority', 'after')
    await flushPromises()

    expect(view.movableColumnOrder.slice(0, 3)).toEqual(['assignee', 'status', 'priority'])
    expect(view.subitemMovableColumnOrder.slice(0, 3)).toEqual(['assignee', 'priority', 'status'])
    expect(JSON.parse(localStorage.getItem('yumpoo:project-work-items:table:v1') ?? '{}'))
      .toMatchObject({
        order: ['assignee', 'status', 'priority', 'content', 'dueDate', 'timeTracking', 'updatedAt'],
        subitemOrder: ['assignee', 'priority', 'status', 'content', 'dueDate', 'timeTracking', 'updatedAt'],
      })
  })
})
