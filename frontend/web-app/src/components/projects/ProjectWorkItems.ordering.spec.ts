import {
  type ProjectWorkItemCursorPage,
  type ProjectWorkItemListItem,
} from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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

describe('项目级工作项首页 · 排序与行拖拽', () => {
  beforeEach(installWorkItemsTestDefaults)
  afterEach(restoreWorkItemsTestGlobals)

  it('置底后加载包含目标的后续页，并保持目标名称单元格选中', async () => {
    const target = { ...item(), title: '移动的工作项' }
    state.listProjectWorkItems.mockResolvedValueOnce(page([target]))
    const wrapper = mountView()
    await flushPromises()
    state.listProjectWorkItems.mockResolvedValueOnce({ items: [item('other')], nextCursor: 'next-page' })
      .mockResolvedValueOnce(page([target]))
    wrapper.findComponent({ name: 'WorkItemRowActions' }).vm.$emit('moved', target)
    await flushPromises()
    expect(state.listProjectWorkItems).toHaveBeenLastCalledWith(expect.objectContaining({ cursor: 'next-page' }), expect.anything())
    expect(wrapper.get('.work-item-name-cell.monday-cell--selected').text()).toBe(target.title)
    expect(wrapper.findAll('.work-item-title-text').map(cell => cell.text())).toEqual([item('other').title, target.title])
  })

  it('将搜索、筛选和最多三层排序同步到 URL', async () => {
    const wrapper = mountView()
    await flushPromises()
    const view = wrapper.vm as unknown as {
      searchInput: string
      filters: { statuses: Set<string>, contents: Set<string> }
      sortRules: Array<{ field: string, direction: string }>
      syncUrl: () => Promise<void>
    }
    view.searchInput = 'WI-42'
    view.filters.statuses = new Set(['BACKLOG'])
    view.filters.contents = new Set(['content-1'])
    view.sortRules = [
      { field: 'PRIORITY', direction: 'DESC' },
      { field: 'DUE_DATE', direction: 'ASC' },
      { field: 'UPDATED_AT', direction: 'DESC' },
    ]

    await view.syncUrl()

    expect(state.replace).toHaveBeenLastCalledWith({ query: {
      q: 'WI-42', status: 'BACKLOG', content: 'content-1',
      sort: 'PRIORITY,DESC;DUE_DATE,ASC;UPDATED_AT,DESC',
    } })
  })

  it('列头按钮与排序窗口仅改变排序时保留当前表格，并在结果返回后直接换序', async () => {
    const first = { ...item('item-1'), title: 'Alpha' }
    const second = { ...item('item-2'), title: 'Bravo' }
    const third = { ...item('item-3'), title: 'Charlie' }
    let resolveQuickSort!: (value: ProjectWorkItemCursorPage) => void
    let resolvePopoverSort!: (value: ProjectWorkItemCursorPage) => void
    state.listProjectWorkItems
      .mockResolvedValueOnce(page([third, first, second]))
      .mockImplementationOnce(() => new Promise(resolve => { resolveQuickSort = resolve }))
      .mockImplementationOnce(() => new Promise(resolve => { resolvePopoverSort = resolve }))
    const wrapper = mountView()
    await flushPromises()
    const originalGetBoundingClientRect = HTMLElement.prototype.getBoundingClientRect
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      if (this.matches('.el-table__body-wrapper tbody tr')) {
        const index = [...this.parentElement!.children].indexOf(this)
        return {
          x: 0, y: index * 44, left: 0, top: index * 44, right: 800, bottom: index * 44 + 44,
          width: 800, height: 44, toJSON: () => ({}),
        } as DOMRect
      }
      return originalGetBoundingClientRect.call(this)
    })
    const rowAnimationSpies = wrapper.findAll('.el-table__body-wrapper tbody tr').map(row => {
      const animate = vi.fn(() => ({} as Animation))
      Object.defineProperty(row.element, 'animate', { configurable: true, value: animate })
      return animate
    })
    const view = wrapper.vm as unknown as {
      tableItems: ProjectWorkItemListItem[]
      tableLoading: boolean
      tableSorting: boolean
      sortRules: Array<{ field: string, direction: 'ASC' | 'DESC' }>
      syncUrl: () => Promise<void>
    }

    await wrapper.get('button[aria-label="按工作项名称升序排列"]').trigger('click')
    await nextTick()

    expect(view.tableSorting).toBe(true)
    expect(view.tableLoading).toBe(false)
    expect(view.tableItems.map(row => row.id)).toEqual(['item-3', 'item-1', 'item-2'])
    await vi.waitFor(() => {
      const sortingMask = wrapper.find('.table-surface .el-loading-mask')
      expect(!sortingMask.exists() || !sortingMask.isVisible()).toBe(true)
    })
    expect(state.listProjectWorkItems).toHaveBeenLastCalledWith(expect.objectContaining({
      sort: ['TITLE,ASC'],
    }), expect.anything())

    resolveQuickSort(page([first, second, third]))
    await flushPromises()
    expect(view.tableSorting).toBe(false)
    expect(view.tableItems.map(row => row.id)).toEqual(['item-1', 'item-2', 'item-3'])
    expect(rowAnimationSpies.every(animate => animate.mock.calls.length === 0)).toBe(true)

    view.sortRules = [{ field: 'PRIORITY', direction: 'DESC' }]
    await view.syncUrl()
    await nextTick()
    expect(view.tableSorting).toBe(true)
    expect(view.tableItems.map(row => row.id)).toEqual(['item-1', 'item-2', 'item-3'])
    expect(state.listProjectWorkItems).toHaveBeenLastCalledWith(expect.objectContaining({
      sort: ['PRIORITY,DESC'],
    }), expect.anything())

    resolvePopoverSort(page([third, second, first]))
    await flushPromises()
    expect(view.tableSorting).toBe(false)
    expect(view.tableItems.map(row => row.id)).toEqual(['item-3', 'item-2', 'item-1'])
    expect(rowAnimationSpies.every(animate => animate.mock.calls.length === 0)).toBe(true)
  }, 30_000)

  it('在每个列头复刻 Monday 双三角排序按钮，并在清除后恢复初始状态', async () => {
    vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.findAll('.monday-column-quick-sort')).toHaveLength(8)
    expect(wrapper.findAll('.monday-column-quick-sort__label').map(node => node.text())).toEqual([
      '工作项名称', '处理人', '状态', '优先级', '工作项类别', '截止日期', '时长追踪', '最后更新时间',
    ])
    expect(wrapper.findAll('.sort-button .asc-icon')).toHaveLength(8)
    expect(wrapper.findAll('.sort-button .desc-icon')).toHaveLength(8)
    expect(wrapper.find('.clear-button-wrapper').exists()).toBe(false)
    expect(wrapper.findAllComponents({ name: 'ElTooltip' }).map(component => component.props('content')))
      .toContain('按工作项名称排序')
    const view = wrapper.vm as unknown as {
      columnDraggingKey: string | undefined
      onTableColumnPointerDown: (event: PointerEvent) => void
      onTableColumnPointerMove: (event: PointerEvent) => void
    }
    view.onTableColumnPointerDown({
      isPrimary: true, button: 0, pointerId: 31, clientX: 420, clientY: 205,
      target: wrapper.get('button[aria-label="按处理人升序排列"]').element,
    } as unknown as PointerEvent)
    view.onTableColumnPointerMove({
      pointerId: 31, clientX: 520, clientY: 205, preventDefault: vi.fn(),
    } as unknown as PointerEvent)
    expect(view.columnDraggingKey).toBeUndefined()

    await wrapper.get('button[aria-label="按工作项名称升序排列"]').trigger('click')
    await flushPromises()
    expect(state.replace).toHaveBeenLastCalledWith({ query: { sort: 'TITLE,ASC' } })
    expect(wrapper.get('.work-items-toolbar').text()).toContain('排序 / 1')
    expect(wrapper.get('.sort-by-column').classes()).toContain('sort-by-column--active')
    expect(wrapper.find('button[aria-label="清除工作项名称排序"]').exists()).toBe(true)
    expect(wrapper.find('button[aria-label="保存工作项名称排序后的工作项顺序"]').exists()).toBe(true)

    await wrapper.get('button[aria-label="将工作项名称切换为降序"]').trigger('click')
    await flushPromises()
    expect(state.replace).toHaveBeenLastCalledWith({ query: { sort: 'TITLE,DESC' } })

    await wrapper.get('button[aria-label="清除工作项名称排序"]').trigger('click')
    await flushPromises()
    expect(state.replace).toHaveBeenLastCalledWith({ query: {} })
    expect(wrapper.find('.sort-by-column--active').exists()).toBe(false)
    expect(wrapper.find('.clear-button-wrapper').exists()).toBe(false)
    await wrapper.get('button[aria-label="状态列菜单"]').trigger('click')
    await flushPromises()
    const menu = wrapper.findAllComponents({ name: 'WorkItemColumnHeaderMenu' })
      .find(component => component.props('state').label === '状态')!
    await vi.waitFor(() => {
      expect(menu.findComponent({ name: 'ElDropdownMenu' }).exists()).toBe(true)
    })
    expect(menu.getComponent({ name: 'ElDropdownMenu' }).text()).toMatch(/设置.*筛选.*排序.*折叠.*分组依据/s)
    await menu.getComponent({ name: 'ElDropdownMenu' }).findAll('li[role="menuitem"]')
      .find(option => option.text().trim() === '设置')!.trigger('click')
    await vi.waitFor(() => {
      expect(menu.getComponent({ name: 'ElDropdownMenu' }).find('button[role="menuitem"]').exists()).toBe(true)
    })
    await menu.getComponent({ name: 'ElDropdownMenu' }).get('button[role="menuitem"]').trigger('click')
    await flushPromises()
    const settings = wrapper.getComponent({ name: 'WorkItemColumnSettingsPopover' })
    expect(settings.props('anchor')).toBe(wrapper.get('button[aria-label="状态列菜单"]').element.closest('th'))
    const editor = settings.getComponent({ name: 'WorkItemLabelPopoverContent' })
    expect(editor.props('initialMode')).toBe('edit')
    const popover = settings.getComponent({ name: 'ElPopover' })
    editor.vm.$emit('busyChange', true)
    await nextTick()
    popover.vm.$emit('update:visible', false)
    await nextTick()
    expect(settings.props('kind')).toBe('status')
    editor.vm.$emit('busyChange', false)
    await nextTick()
    popover.vm.$emit('update:visible', false)
    await nextTick()
    expect(settings.props('kind')).toBeUndefined()
    await wrapper.get('button[aria-label="状态列菜单"]').trigger('click')
    await vi.waitFor(() => {
      expect(menu.findComponent({ name: 'ElDropdownMenu' }).exists()).toBe(true)
    })
    const collapse = menu.getComponent({ name: 'ElDropdownMenu' }).findAll('li[role="menuitem"]')
      .find(option => option.text().trim() === '折叠')!
    await collapse.trigger('click')
    await flushPromises()
    expect(JSON.parse(localStorage.getItem('yumpoo:project-work-items:table:v1') ?? '{}').collapsed).toEqual(['status'])

  })

  it('保存排序时加载完整结果，并用相邻项避让接口固化新的工作项顺序', async () => {
    vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
    vi.spyOn(ElMessage, 'info').mockImplementation(() => undefined as never)
    const first = { ...item('item-1'), title: '第一项' }
    const second = { ...item('item-2'), title: '第二项' }
    const third = { ...item('item-3'), title: '第三项' }
    state.listProjectWorkItems.mockImplementation(({ cursor }: { cursor?: string }) => Promise.resolve(cursor
      ? { items: [second], nextCursor: null }
      : { items: [third, first], nextCursor: 'page-2' }))
    state.moveProjectWorkItemOrder
      .mockResolvedValueOnce({ ...first, etag: '"2"', rowVersion: 2 })
      .mockResolvedValueOnce({ ...second, etag: '"2"', rowVersion: 2 })
    const wrapper = mountView()
    await flushPromises()
    const view = wrapper.vm as unknown as {
      sortRules: Array<{ field: string, direction: string }>
      tableLoading: boolean
      saveSortedWorkItemOrder: () => Promise<void>
    }
    view.sortRules = [{ field: 'TITLE', direction: 'ASC' }]
    view.tableLoading = true
    await view.saveSortedWorkItemOrder()
    expect(state.moveProjectWorkItemOrder).not.toHaveBeenCalled()
    expect(ElMessage.info).toHaveBeenCalledWith('排序结果仍在加载，请稍后再保存工作项顺序')
    view.tableLoading = false

    await view.saveSortedWorkItemOrder()

    expect(state.listProjectWorkItems).toHaveBeenCalledWith(expect.objectContaining({ cursor: 'page-2' }), expect.anything())
    expect(state.moveProjectWorkItemOrder).toHaveBeenNthCalledWith(1, expect.objectContaining({
      workItemId: 'item-1',
      projectWorkItemOrderMoveRequest: { previousVisibleWorkItemId: 'item-3', nextVisibleWorkItemId: null },
    }))
    expect(state.moveProjectWorkItemOrder).toHaveBeenNthCalledWith(2, expect.objectContaining({
      workItemId: 'item-2',
      projectWorkItemOrderMoveRequest: { previousVisibleWorkItemId: 'item-1', nextVisibleWorkItemId: null },
    }))
    expect(state.replace).toHaveBeenLastCalledWith({ query: {} })
    expect(ElMessage.success).toHaveBeenCalledWith('已保存 3 个工作项的当前顺序')
    expect(JSON.parse(localStorage.getItem('yumpoo:project-work-items:table:v1') ?? '{}')).not.toHaveProperty('savedSort')
  })

  it('连续拖动使用最新 ETag，且只提交当前可见相邻项', async () => {
    const first = { ...item('item-1'), title: '第一项' }
    const second = { ...item('item-2'), title: '第二项' }
    const moved = { ...item('item-3'), title: '移动项' }
    state.listProjectWorkItems.mockResolvedValue(page([first, second, moved]))
    state.moveProjectWorkItemOrder
      .mockResolvedValueOnce({ ...moved, rowVersion: 2, etag: '"2"' })
      .mockResolvedValueOnce({ ...moved, rowVersion: 3, etag: '"3"' })
    const wrapper = mountView()
    await flushPromises()
    const view = wrapper.vm as unknown as {
      tableDragging: ProjectWorkItemListItem | undefined
      tableDropIndex: number | undefined
      tableItems: ProjectWorkItemListItem[]
      commitTableDrop: () => Promise<void>
    }
    view.tableDragging = moved
    view.tableDropIndex = 1

    await view.commitTableDrop()

    expect(state.moveProjectWorkItemOrder).toHaveBeenNthCalledWith(1, expect.objectContaining({
      workItemId: 'item-3', ifMatch: '"1"',
      projectWorkItemOrderMoveRequest: {
        previousVisibleWorkItemId: 'item-1',
        nextVisibleWorkItemId: 'item-2',
      },
    }))
    expect(view.tableItems.find(candidate => candidate.id === 'item-3')?.etag).toBe('"2"')

    view.tableDragging = view.tableItems.find(candidate => candidate.id === 'item-3')
    view.tableDropIndex = 3
    await view.commitTableDrop()

    expect(state.moveProjectWorkItemOrder).toHaveBeenNthCalledWith(2, expect.objectContaining({
      workItemId: 'item-3', ifMatch: '"2"',
      projectWorkItemOrderMoveRequest: {
        previousVisibleWorkItemId: 'item-2',
        nextVisibleWorkItemId: null,
      },
    }))
    expect(view.tableItems.find(candidate => candidate.id === 'item-3')?.etag).toBe('"3"')
  })

  it('表格拖拽时隐藏源行并动态计算其余行的占位位移', async () => {
    const first = { ...item('item-1'), title: '第一项' }
    const second = { ...item('item-2'), title: '第二项' }
    const third = { ...item('item-3'), title: '第三项' }
    state.listProjectWorkItems.mockResolvedValue(page([first, second, third]))
    const wrapper = mountView()
    await flushPromises()

    const view = wrapper.vm as unknown as {
      tableDragging: ProjectWorkItemListItem | undefined
      tableDraggingIndex: number
      tableDropIndex: number | undefined
      tableRowStyle: ({ row, rowIndex }: { row: ProjectWorkItemListItem; rowIndex: number }) => Record<string, string | number>
      tableRowClassName: ({ row, rowIndex }: { row: ProjectWorkItemListItem; rowIndex: number }) => string
      resetTableDragState: () => void
    }

    expect(view.tableRowStyle({ row: first, rowIndex: 0 })).toEqual({})
    expect(view.tableRowClassName({ row: first, rowIndex: 0 })).toBe('work-item-table-row work-item-table-row--movable')

    // 模拟向下拖动：从 index 0 拖至 index 2
    view.tableDragging = first
    view.tableDraggingIndex = 0
    view.tableDropIndex = 2

    expect(view.tableRowClassName({ row: first, rowIndex: 0 })).toContain('work-item-table-row--dragging')
    expect(view.tableRowStyle({ row: first, rowIndex: 0 })).toEqual({ opacity: 0, pointerEvents: 'none' })
    expect(view.tableRowStyle({ row: second, rowIndex: 1 })).toEqual({ transform: 'translateY(-36px)' })
    expect(view.tableRowStyle({ row: third, rowIndex: 2 })).toEqual({ transform: 'translateY(0px)' })

    // 模拟向上拖动：从 index 2 拖至 index 0
    view.tableDragging = third
    view.tableDraggingIndex = 2
    view.tableDropIndex = 0

    expect(view.tableRowStyle({ row: first, rowIndex: 0 })).toEqual({ transform: 'translateY(36px)' })
    expect(view.tableRowStyle({ row: second, rowIndex: 1 })).toEqual({ transform: 'translateY(36px)' })
    expect(view.tableRowStyle({ row: third, rowIndex: 2 })).toEqual({ opacity: 0, pointerEvents: 'none' })

    // 结束拖拽重置
    view.resetTableDragState()
    expect(view.tableDragging).toBeUndefined()
    expect(view.tableDraggingIndex).toBe(-1)
    expect(view.tableDropIndex).toBeUndefined()
    expect(view.tableRowStyle({ row: first, rowIndex: 0 })).toEqual({})
  })

  it('表格滚动后使用实际滚动容器偏移计算拖拽落点', async () => {
    state.listProjectWorkItems.mockResolvedValue(page(
      Array.from({ length: 4 }, (_, index) => ({
        ...item(`item-${index + 1}`),
        title: `第 ${index + 1} 项`,
      })),
    ))
    const wrapper = mountView()
    await flushPromises()

    const bodyWrapper = wrapper.get('.monday-table .el-table__body-wrapper').element as HTMLElement
    const tableScroll = wrapper.get('.monday-table .el-scrollbar__wrap').element as HTMLElement
    vi.spyOn(bodyWrapper, 'getBoundingClientRect').mockReturnValue({
      x: 0, y: 100, left: 0, top: 100, right: 900, bottom: 400,
      width: 900, height: 300, toJSON: () => ({}),
    } as DOMRect)
    tableScroll.scrollTop = 72

    const view = wrapper.vm as unknown as {
      tableDropIndex: number | undefined
      updateTableDropTarget: (clientY: number) => void
    }
    view.updateTableDropTarget(109)

    expect(bodyWrapper.scrollTop).toBe(0)
    expect(view.tableDropIndex).toBe(2)
  })

  it('从行内按钮移动超过阈值后启动整行指针拖拽，并拦截随后的点击', async () => {
    const wrapper = mountView()
    await flushPromises()
    const view = wrapper.vm as unknown as {
      tableDragging: ProjectWorkItemListItem | undefined
      tableDraggingIndex: number
      tableDropIndex: number | undefined
      onTablePointerDown: (event: PointerEvent) => void
      onTablePointerMove: (event: PointerEvent) => void
      onTablePointerUp: (event: PointerEvent) => void
      onTablePointerCancel: (event: PointerEvent) => void
      onTableClickCapture: (event: MouseEvent) => void
    }
    const titleButton = wrapper.find('.work-item-link').element

    view.onTablePointerDown({
      isPrimary: true, button: 0, pointerId: 7, clientX: 120, clientY: 220, target: titleButton,
    } as unknown as PointerEvent)
    view.onTablePointerMove({
      pointerId: 7, clientX: 123, clientY: 223, preventDefault: vi.fn(),
    } as unknown as PointerEvent)
    expect(view.tableDragging).toBeUndefined()

    const movePreventDefault = vi.fn()
    view.onTablePointerMove({
      pointerId: 7, clientX: 135, clientY: 235, preventDefault: movePreventDefault,
    } as unknown as PointerEvent)
    expect(movePreventDefault).toHaveBeenCalledOnce()
    expect(view.tableDragging?.id).toBe('item-1')
    expect(view.tableDraggingIndex).toBe(0)
    expect(view.tableDropIndex).toBe(0)
    expect(document.querySelector('.work-item-drag-preview')).not.toBeNull()

    const pointerUpPreventDefault = vi.fn()
    const pointerUpStopPropagation = vi.fn()
    view.onTablePointerUp({
      pointerId: 7,
      preventDefault: pointerUpPreventDefault,
      stopPropagation: pointerUpStopPropagation,
    } as unknown as PointerEvent)
    expect(pointerUpPreventDefault).toHaveBeenCalledOnce()
    expect(pointerUpStopPropagation).toHaveBeenCalledOnce()
    expect(view.tableDragging).toBeUndefined()
    expect(document.querySelector('.work-item-drag-preview')).toBeNull()

    const clickPreventDefault = vi.fn()
    const clickStopPropagation = vi.fn()
    view.onTableClickCapture({
      preventDefault: clickPreventDefault,
      stopPropagation: clickStopPropagation,
    } as unknown as MouseEvent)
    expect(clickPreventDefault).toHaveBeenCalledOnce()
    expect(clickStopPropagation).toHaveBeenCalledOnce()

    // 验证点击非工作项名称文字区域（如讨论按钮）不会触发拖拽
    const discussionButton = wrapper.find('.monday-discussion-btn').element
    view.onTablePointerDown({
      isPrimary: true, button: 0, pointerId: 8, clientX: 120, clientY: 220, target: discussionButton,
    } as unknown as PointerEvent)
    view.onTablePointerMove({
      pointerId: 8, clientX: 150, clientY: 250, preventDefault: vi.fn(),
    } as unknown as PointerEvent)
    expect(view.tableDragging).toBeUndefined()

    for (const selector of ['.work-item-detail-button', '.subitem-expand-button']) {
      view.onTablePointerDown({ isPrimary: true, button: 0, pointerId: 8, clientX: 120, clientY: 220,
        target: wrapper.get(selector).element } as unknown as PointerEvent)
      view.onTablePointerMove({ pointerId: 8, clientX: 150, clientY: 250, preventDefault: vi.fn() } as unknown as PointerEvent)
      expect(view.tableDragging).toBeUndefined()
    }

    // 复选框本体及其留白区域均可作为拖拽起点，移动后不触发勾选点击
    const checkbox = wrapper.find('.el-table__body .monday-selection-column .el-checkbox__inner').element
    view.onTablePointerDown({
      isPrimary: true, button: 0, pointerId: 9, clientX: 24, clientY: 220, target: checkbox,
    } as unknown as PointerEvent)
    const checkboxMovePreventDefault = vi.fn()
    view.onTablePointerMove({
      pointerId: 9, clientX: 40, clientY: 236, preventDefault: checkboxMovePreventDefault,
    } as unknown as PointerEvent)
    expect(checkboxMovePreventDefault).toHaveBeenCalledOnce()
    expect(view.tableDragging?.id).toBe('item-1')
    expect(document.querySelector('.work-item-drag-preview')).not.toBeNull()
    view.onTablePointerCancel({ pointerId: 9 } as unknown as PointerEvent)
    expect(view.tableDragging).toBeUndefined()
    expect(document.querySelector('.work-item-drag-preview')).toBeNull()
  })

  it('创建完整的 1 度灰阶拖拽浮层并锚定抓取点跟随鼠标', async () => {
    const wrapper = mountView()
    await flushPromises()
    const view = wrapper.vm as unknown as {
      createTableDragPreview: (source: HTMLElement, clientX: number, clientY: number) => HTMLElement
      moveTableDragPreview: (clientX: number, clientY: number) => void
      resetTableDragState: () => void
    }
    const sourceTable = document.createElement('table')
    const sourceBody = document.createElement('tbody')
    const source = document.createElement('tr')
    source.innerHTML = '<td id="drag-cell"><button tabindex="0">工作项</button></td><td>状态</td><td>负责人</td>'
    sourceBody.appendChild(source)
    sourceTable.appendChild(sourceBody)
    document.body.appendChild(sourceTable)
    vi.spyOn(source, 'getBoundingClientRect').mockReturnValue({
      x: 100, y: 200, left: 100, top: 200, right: 700, bottom: 236,
      width: 600, height: 36, toJSON: () => ({}),
    } as DOMRect)
    const widths = [260, 170, 170]
    source.querySelectorAll('td').forEach((cell, index) => {
      vi.spyOn(cell, 'getBoundingClientRect').mockReturnValue({
        x: 0, y: 0, left: 0, top: 0, right: widths[index]!, bottom: 36,
        width: widths[index]!, height: 36, toJSON: () => ({}),
      } as DOMRect)
    })

    const preview = view.createTableDragPreview(source, 150, 218)

    expect(preview.classList.contains('work-item-drag-preview')).toBe(true)
    expect(preview.style.transform).toBe('rotate(1deg)')
    expect(preview.style.transformOrigin).toBe('50px 18px')
    expect(preview.style.left).toBe('100px')
    expect(preview.style.top).toBe('200px')
    expect(preview.querySelectorAll('td')).toHaveLength(3)
    expect((preview.querySelectorAll('td')[0] as HTMLElement).style.width).toBe('260px')
    expect(preview.querySelector('[id]')).toBeNull()
    expect((preview.querySelector('button') as HTMLButtonElement).tabIndex).toBe(-1)

    view.moveTableDragPreview(260, 300)
    expect(preview.style.left).toBe('210px')
    expect(preview.style.top).toBe('282px')
    view.resetTableDragState()
    expect(document.body.contains(preview)).toBe(false)
    sourceTable.remove()
  })
})
