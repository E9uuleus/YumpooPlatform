import {
  WorkItemViewType,
  WorkItemLabelColorToken,
  ProjectActorAccess,
  ProjectLifecycle,
  ProjectTemplateKey,
  ProjectType,
  WorkItemStatusCategory,
  type ProjectContentCatalog,
  type ProjectDetail,
  type WorkItemDetail,
  type ProjectWorkItemCursorPage,
  type ProjectWorkItemListItem,
} from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { ElMessage, ElMessageBox } from 'element-plus'
import { nextTick, reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectOverviewView from './ProjectOverviewView.vue'

enableAutoUnmount(afterEach)

const state = vi.hoisted(() => ({
  route: undefined as unknown as { params: { projectId: string }, query: Record<string, string> },
  push: vi.fn(),
  replace: vi.fn(),
  getProject: vi.fn(),
  listProjectContents: vi.fn(),
  listProjectMembers: vi.fn(),
  listProjectWorkItems: vi.fn(),
  listWorkItemSubitems: vi.fn(),
  listProjectWorkItemFilterOptions: vi.fn(),
  getProjectWorkItemLabels: vi.fn(),
  moveProjectWorkItemOrder: vi.fn(),
  moveWorkItemSubitemOrder: vi.fn(),
  patchWorkItemAssignee: vi.fn(),
  patchWorkItemPriority: vi.fn(),
  patchWorkItemDueDate: vi.fn(),
  createWorkItem: vi.fn(),
  createWorkItemSubitem: vi.fn(),
  getWorkItem: vi.fn(),
  transitionWorkItem: vi.fn(),
}))

vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))
vi.mock('vue-router', () => ({
  useRoute: () => state.route,
  useRouter: () => ({ push: state.push, replace: state.replace }),
}))
vi.mock('../../api/client', () => ({
  projectsApi: { getProject: state.getProject, listProjectMembers: state.listProjectMembers },
  contentsApi: { listProjectContents: state.listProjectContents },
  workItemsApi: {
    listProjectWorkItems: state.listProjectWorkItems,
    listWorkItemSubitems: state.listWorkItemSubitems,
    listProjectWorkItemFilterOptions: state.listProjectWorkItemFilterOptions,
    getProjectWorkItemLabels: state.getProjectWorkItemLabels,
    moveProjectWorkItemOrder: state.moveProjectWorkItemOrder,
    moveWorkItemSubitemOrder: state.moveWorkItemSubitemOrder,
    patchWorkItemAssignee: state.patchWorkItemAssignee,
    patchWorkItemPriority: state.patchWorkItemPriority,
    patchWorkItemDueDate: state.patchWorkItemDueDate,
    createWorkItem: state.createWorkItem,
    createWorkItemSubitem: state.createWorkItemSubitem,
    getWorkItem: state.getWorkItem,
    transitionWorkItem: state.transitionWorkItem,
  },
}))

function project(id: string): ProjectDetail {
  return {
    id,
    workspaceId: 'workspace-1', workspaceCode: 'MAIN', workspaceName: '主工作空间',
    code: id.toUpperCase(), name: `项目 ${id}`, description: null,
    projectType: ProjectType.ProductDevelopment, lifecycle: ProjectLifecycle.Active,
    ownerUserId: 'owner-1', ownerDisplayName: '负责人', templateKey: ProjectTemplateKey.Rnd,
    templateVersion: 1, customerName: null, customerReference: null, deliverySite: null,
    contactNote: null, actorAccess: ProjectActorAccess.Owner,
    capabilities: {
      canUpdateSettings: true, canActivate: false, canManageMembers: true,
      canReassignOwner: true, canManageProductLinks: true, canArchive: true,
      canRestore: false, canMoveWorkspace: false, canOverrideArchive: false,
    },
    rowVersion: 1, etag: '"1"', createdAt: new Date('2026-08-25T00:00:00Z'),
    updatedAt: new Date('2026-08-25T00:00:00Z'), activatedAt: null, archivedAt: null,
  }
}

function catalog(): ProjectContentCatalog {
  return {
    items: [{
      id: 'content-1', projectId: 'project-1', code: 'REQ', name: '产品需求',
      colorToken: WorkItemLabelColorToken.BrightBlue, sortOrder: 10, active: true,
      protectedContent: true, inUse: true,
      rowVersion: 1, createdAt: new Date(), createdByUserId: 'owner-1',
      updatedAt: new Date(), updatedByUserId: 'owner-1',
    }],
    rowVersion: 1, etag: '"1"', canManage: true,
  }
}

function item(id = 'item-1'): ProjectWorkItemListItem {
  return {
    id, projectId: 'project-1', contentId: 'content-1', contentName: '产品需求', contentColorToken: WorkItemLabelColorToken.BrightBlue, itemNo: 'WI-1',
    title: '实现项目工作项首页', statusCode: 'BACKLOG', statusCategory: WorkItemStatusCategory.Todo,
    priority: null, assigneeUserId: null, assigneeDisplayName: null,
    dueDate: null, rowVersion: 1, etag: '"1"',
    subitemCount: 0,
    capabilities: { canEditFields: true, canMoveInKanban: true, canMoveInProjectOrder: true,
      canDiscuss: true, canDelete: true, canRestore: false, availableTransitions: [] },
    updatedAt: new Date('2026-08-25T01:00:00Z'),
  }
}

function page(items = [item()]): ProjectWorkItemCursorPage {
  return { items, nextCursor: null }
}

function mountView() {
  return mount(ProjectOverviewView, {
    global: {
      stubs: {
        InlineProblem: true,
        WorkItemDetailPanel: true,
        ProjectWorkspaceHeader: {
          props: ['project'],
          template: '<div data-testid="project-name">{{ project.name }}</div>',
        },
      },
    },
  })
}

describe('项目级工作项首页', () => {
  beforeEach(() => {
    localStorage.clear()
    state.route = reactive({ params: { projectId: 'project-1' }, query: {} })
    Object.values(state).filter(value => typeof value === 'function').forEach(mock => mock.mockReset())
    state.push.mockImplementation(async ({ query }: { query: Record<string, string> }) => {
      state.route.query = query
    })
    state.replace.mockImplementation(async ({ query }: { query: Record<string, string> }) => {
      state.route.query = query
    })
    state.getProject.mockImplementation(({ projectId }: { projectId: string }) => Promise.resolve(project(projectId)))
    state.listProjectContents.mockResolvedValue(catalog())
    state.getProjectWorkItemLabels.mockResolvedValue({
      statuses: [
        { code: 'BACKLOG', displayName: '待开始', colorToken: 'BLUE', statusCategory: 'TODO',
          sortOrder: 10, active: true, protectedLabel: false, inUse: true },
        { code: 'DONE', displayName: '已完成', colorToken: 'GREEN', statusCategory: 'DONE',
          sortOrder: 20, active: true, protectedLabel: false, inUse: false },
      ], priorities: [], rowVersion: 0, etag: '"0"', canManage: true,
    } as never)
    state.listProjectMembers.mockResolvedValue({ items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 })
    state.listProjectWorkItems.mockResolvedValue(page())
    state.listWorkItemSubitems.mockResolvedValue({ items: [] })
    state.listProjectWorkItemFilterOptions.mockResolvedValue({ items: [], nextCursor: null })
    state.createWorkItem.mockResolvedValue({ ...item('created'), itemNo: 'WI-2' } as unknown as WorkItemDetail)
    state.createWorkItemSubitem.mockResolvedValue({ ...item('subitem-created'), itemNo: 'WI-3' } as unknown as WorkItemDetail)
    state.getWorkItem.mockResolvedValue(item() as unknown as WorkItemDetail)
  })

  afterEach(() => vi.restoreAllMocks())

  it('按固定顺序展示业务列和不可拖拽的新增列入口，并将 Content 映射为类别名称', async () => {
    const wrapper = mountView()
    await flushPromises()

    const labels = wrapper.findAll('.el-table__header th').map(node => node.text()).filter(Boolean)
    expect(labels).toEqual(['工作项名称', '处理人', '状态', '优先级', '工作项类别', '截止日期', '最后更新时间'])
    const addColumnHeader = wrapper.get('th.monday-add-column-header')
    const addColumn = wrapper.findAllComponents({ name: 'ElTableColumn' })
      .find(column => column.props('columnKey') === 'add-column')
    const addColumnButton = addColumnHeader.get('button.monday-add-column-icon')
    expect(addColumnButton.attributes('aria-label')).toBe('添加列（功能预留）')
    expect(addColumnButton.get('svg').attributes()).toMatchObject({
      viewBox: '0 0 20 20', width: '18', height: '18', fill: 'currentColor',
    })
    expect(addColumnButton.get('path').attributes('d')).toContain('M10 2.25')
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

  it('首次展开时懒加载直接子项，折叠后复用缓存且不显示递归入口', async () => {
    const parent = { ...item('parent-1'), subitemCount: 1 }
    const child = { ...item('child-1'), title: '直接子项' }
    state.listProjectWorkItems.mockResolvedValue(page([parent]))
    state.listWorkItemSubitems.mockResolvedValue({ items: [child] })
    const wrapper = mountView()
    await flushPromises()
    const expand = wrapper.get('button[aria-label="展开子项"]')
    expect(expand.attributes('aria-expanded')).toBe('false')
    await expand.trigger('click')
    await flushPromises()

    expect(state.listWorkItemSubitems).toHaveBeenCalledTimes(1)
    expect(state.listWorkItemSubitems).toHaveBeenCalledWith({ parentWorkItemId: parent.id })
    expect(wrapper.text()).toContain('直接子项')
    expect(wrapper.get('.monday-subitems-counter-component__subitems-count').text()).toBe('1')
    expect(wrapper.findAll('.subitem-hierarchy-branch--data')).toHaveLength(1)
    expect(wrapper.findAll('.monday-subitem-table .el-table__expand-icon')).toHaveLength(0)

    const collapse = wrapper.get('button[aria-label="收起子项"]')
    expect(collapse.attributes('aria-expanded')).toBe('true')
    await collapse.trigger('click')
    await nextTick()
    await wrapper.get('button[aria-label="展开子项"]').trigger('click')
    await flushPromises()
    expect(state.listWorkItemSubitems).toHaveBeenCalledTimes(1)
  })

  it('持久化列宽与隐藏列，且工作项名称始终可见', async () => {
    const wrapper = mountView()
    await flushPromises()
    const view = wrapper.vm as unknown as {
      onHeaderDragEnd: (width: number, oldWidth: number, column: { label: string }) => void
      toggleColumn: (key: string, checked: boolean) => void
    }

    view.onHeaderDragEnd(188, 120, { label: '优先级' })
    view.toggleColumn('priority', false)
    view.toggleColumn('title', false)
    await nextTick()

    const labels = wrapper.findAll('.el-table__header th').map(node => node.text()).filter(Boolean)
    expect(labels).not.toContain('优先级')
    expect(labels).toContain('工作项名称')
    expect(JSON.parse(localStorage.getItem('yumpoo:project-work-items:table:v1') ?? '{}'))
      .toMatchObject({ version: 1, widths: { priority: 188 }, hidden: ['priority'] })
  })

  it('拖拽业务列表头时复刻原色列浮层、横向避让并持久化新顺序', async () => {
    const wrapper = mountView()
    await flushPromises()
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
    expect(movableHeaders).toHaveLength(6)
    expect(wrapper.findAll('.monday-column-resize-handle')).toHaveLength(7)
    expect(wrapper.get('th.monday-title-column .monday-title-column-resize-handle').attributes('data-column-key')).toBe('title')
    expect(movableHeaders.map(header => header.text())).toEqual(['处理人', '状态', '优先级', '工作项类别', '截止日期', '最后更新时间'])
    expect(wrapper.get('th.monday-title-column').classes()).not.toContain('monday-movable-column-header')

    const widths = [90, 130, 120, 150, 140, 170]
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
    expect(view.tableColumnDragStyle('priority')).toEqual({ transform: 'translateX(-130px)' })
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
    await vi.waitFor(() => {
      expect(wrapper.findAll('th.monday-movable-column-header').map(header => header.text()).slice(0, 3))
        .toEqual(['处理人', '优先级', '状态'])
    })
    expect(JSON.parse(localStorage.getItem('yumpoo:project-work-items:table:v1') ?? '{}').order)
      .toEqual(['assignee', 'priority', 'status', 'content', 'dueDate', 'updatedAt'])
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
        order: ['assignee', 'status', 'priority', 'content', 'dueDate', 'updatedAt'],
        subitemOrder: ['assignee', 'priority', 'status', 'content', 'dueDate', 'updatedAt'],
      })
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

    expect(wrapper.findAll('.monday-column-quick-sort')).toHaveLength(7)
    expect(wrapper.findAll('.monday-column-quick-sort__label').map(node => node.text())).toEqual([
      '工作项名称', '处理人', '状态', '优先级', '工作项类别', '截止日期', '最后更新时间',
    ])
    expect(wrapper.findAll('.sort-button .asc-icon')).toHaveLength(7)
    expect(wrapper.findAll('.sort-button .desc-icon')).toHaveLength(7)
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

  it('按游标无感追加并按 ID 去重', async () => {
    const second = { ...item('item-2'), itemNo: 'WI-2', title: '第二个事项' }
    state.listProjectWorkItems.mockImplementation(({ cursor }: { cursor?: string }) => Promise.resolve(
      cursor ? { items: [item(), second], nextCursor: null } : { items: [item()], nextCursor: 'cursor-1' },
    ))
    const wrapper = mountView()
    await flushPromises()

    await (wrapper.vm as unknown as { loadTable: (cursor: string, append: boolean) => Promise<void> })
      .loadTable('cursor-1', true)
    await flushPromises()

    expect(wrapper.findAll('.work-item-link')).toHaveLength(2)
    expect(wrapper.text()).toContain('第二个事项')
    expect(state.listProjectWorkItems).toHaveBeenLastCalledWith(expect.objectContaining({
      cursor: 'cursor-1', limit: 25,
    }), expect.objectContaining({ signal: expect.any(AbortSignal) }))
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

  it('连续优先级更新不预取详情，并使用最新轻量行 ETag', async () => {
    state.patchWorkItemPriority
      .mockResolvedValueOnce({
        ...item(), priority: 'HIGH', etag: '"2"', rowVersion: 2,
      } as unknown as WorkItemDetail)
      .mockResolvedValueOnce({
        ...item(), priority: 'LOW', etag: '"3"', rowVersion: 3,
      } as unknown as WorkItemDetail)
    const wrapper = mountView()
    await flushPromises()

    const view = wrapper.vm as unknown as {
      patchCell: (row: ProjectWorkItemListItem, field: 'priority', value: string) => Promise<boolean>
      tableItems: ProjectWorkItemListItem[]
    }
    await view.patchCell(view.tableItems[0]!, 'priority', 'HIGH')
    await view.patchCell(view.tableItems[0]!, 'priority', 'LOW')
    await flushPromises()

    expect(state.getWorkItem).not.toHaveBeenCalled()
    expect(state.patchWorkItemPriority).toHaveBeenNthCalledWith(1, expect.objectContaining({
      workItemId: 'item-1', ifMatch: '"1"',
      workItemPriorityPatchRequest: { priority: 'HIGH' },
    }))
    expect(state.patchWorkItemPriority).toHaveBeenNthCalledWith(2, expect.objectContaining({
      workItemId: 'item-1', ifMatch: '"2"',
      workItemPriorityPatchRequest: { priority: 'LOW' },
    }))
    expect(view.tableItems[0]?.etag).toBe('"3"')
  })

  it('切换路由中的项目后重新聚合，并忽略晚返回的旧项目', async () => {
    let resolveFirst: ((value: ProjectDetail) => void) | undefined
    state.getProject
      .mockImplementationOnce(() => new Promise<ProjectDetail>(resolve => { resolveFirst = resolve }))
      .mockResolvedValueOnce(project('project-2'))
    const wrapper = mountView()

    state.route.params.projectId = 'project-2'
    await nextTick()
    await flushPromises()
    expect(wrapper.get('[data-testid="project-name"]').text()).toContain('project-2')

    resolveFirst?.(project('project-1'))
    await flushPromises()
    expect(wrapper.get('[data-testid="project-name"]').text()).toContain('project-2')
  })

  it('通过查询参数同步表格和看板标签', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.findAll('[role="tab"]')[1]!.trigger('click')
    await flushPromises()
    expect(state.push).toHaveBeenCalledWith({ query: { view: 'kanban' } })
    expect(state.listProjectWorkItems).toHaveBeenCalledWith(expect.objectContaining({
      view: WorkItemViewType.Kanban,
      status: new Set(['BACKLOG']),
    }), expect.objectContaining({ signal: expect.any(AbortSignal) }))
  })

  it('名称和讨论按钮打开同一详情抽屉的对应区域', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('.work-item-link').trigger('click')
    await flushPromises()
    expect(state.push).toHaveBeenCalledWith({ query: { workItemId: 'item-1' } })
    expect(state.getWorkItem).toHaveBeenLastCalledWith({ workItemId: 'item-1' })
    expect((wrapper.vm as unknown as { detailTab: string }).detailTab).toBe('details')

    await wrapper.get('[aria-label="打开协作讨论"]').trigger('click')
    await flushPromises()
    expect(state.getWorkItem).toHaveBeenCalledTimes(2)
    expect((wrapper.vm as unknown as { detailTab: string }).detailTab).toBe('discussion')
  })

  it('直达 workItemId 路由恢复抽屉且不重复加载项目列表', async () => {
    state.route.query = { view: 'table', workItemId: 'item-1' }
    const wrapper = mountView()
    await flushPromises()
    const listCalls = state.listProjectWorkItems.mock.calls.length

    expect(state.getWorkItem).toHaveBeenCalledWith({ workItemId: 'item-1' })
    state.route.query.workItemId = 'item-2'
    await nextTick()
    await flushPromises()

    expect(state.getWorkItem).toHaveBeenLastCalledWith({ workItemId: 'item-2' })
    expect(state.listProjectWorkItems).toHaveBeenCalledTimes(listCalls)
    wrapper.unmount()
  })

  it('跨项目关系跳转到目标项目上下文并携带 workItemId', async () => {
    const wrapper = mountView()
    await flushPromises()
    await (wrapper.vm as unknown as {
      openRelatedWorkItem: (target: { workItemId: string, projectId: string }) => Promise<void>
    }).openRelatedWorkItem({ workItemId: 'remote-item', projectId: 'project-2' })
    expect(state.push).toHaveBeenCalledWith({
      name: 'project-overview',
      params: { projectId: 'project-2' },
      query: { workItemId: 'remote-item' },
    })
  })

  it('选择具体截止日期立即提交自然日字段命令', async () => {
    state.patchWorkItemDueDate.mockResolvedValue({
      ...item(), dueDate: new Date('2026-09-01T00:00:00.000Z'), rowVersion: 2, etag: '"2"',
    } as unknown as WorkItemDetail)
    const wrapper = mountView()
    await flushPromises()
    const view = wrapper.vm as unknown as {
      tableItems: ProjectWorkItemListItem[]
      onDueDateChange: (item: ProjectWorkItemListItem, value: string | null) => void
    }

    view.onDueDateChange(view.tableItems[0]!, '2026-09-01')
    await flushPromises()

    expect(state.patchWorkItemDueDate).toHaveBeenCalledWith(expect.objectContaining({
      workItemId: 'item-1', ifMatch: '"1"',
      workItemDueDatePatchRequest: { dueDate: new Date('2026-09-01T00:00:00.000Z') },
    }))
    wrapper.unmount()
  })

  it('Enter 快速创建时发送空优先级，且重复按键不会重复提交', async () => {
    let resolveCreate: ((value: WorkItemDetail) => void) | undefined
    state.createWorkItem.mockImplementation(() => new Promise<WorkItemDetail>(resolve => { resolveCreate = resolve }))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('.quick-add').text()).toBe('添加工作项')
    expect(wrapper.find('.quick-add svg').exists()).toBe(false)
    expect(wrapper.find('.quick-add .monday-quick-checkbox').exists()).toBe(true)
    expect(wrapper.find('.quick-add input[type="checkbox"]').exists()).toBe(false)
    await wrapper.get('.quick-add').trigger('click')
    const input = wrapper.get('input[placeholder="添加工作项"]')
    expect(wrapper.find('.quick-content-field').exists()).toBe(true)
    expect(wrapper.find('.quick-row .monday-quick-checkbox').exists()).toBe(true)
    await input.setValue('快速新增事项')
    await input.trigger('keydown', { key: 'Enter' })
    await input.trigger('keydown', { key: 'Enter' })
    expect(state.createWorkItem).toHaveBeenCalledTimes(1)
    expect(state.createWorkItem).toHaveBeenCalledWith(expect.objectContaining({
      projectId: 'project-1',
      workItemCreateRequest: expect.objectContaining({
        contentId: 'content-1', title: '快速新增事项', priority: null,
      }),
    }))

    resolveCreate?.({ ...item('created'), itemNo: 'WI-2' } as unknown as WorkItemDetail)
    await flushPromises()
  })

  it('空标题不创建；Shift+Enter 成功后保留类别并继续输入', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('.quick-add').trigger('click')
    const input = wrapper.get('input[placeholder="添加工作项"]')

    await input.trigger('keydown', { key: 'Enter' })
    expect(state.createWorkItem).not.toHaveBeenCalled()
    await input.setValue('连续新增')
    await input.trigger('keydown', { key: 'Enter', shiftKey: true })
    await flushPromises()

    expect(state.createWorkItem).toHaveBeenCalledTimes(1)
    expect((input.element as HTMLInputElement).value).toBe('')
    expect(wrapper.find('.quick-row').exists()).toBe(true)
  })

  it('点击新增行外自动创建，失败时保留草稿以便重试', async () => {
    state.createWorkItem.mockRejectedValue(new Error('network error'))
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('.quick-add').trigger('click')
    const input = wrapper.get('input[placeholder="添加工作项"]')
    await input.setValue('保留的草稿')

    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await flushPromises()

    expect(state.createWorkItem).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.quick-row').exists()).toBe(true)
    expect((input.element as HTMLInputElement).value).toBe('保留的草稿')
  })

  it('没有 ACTIVE Content 时禁用快速添加', async () => {
    const archivedCatalog = catalog()
    archivedCatalog.items[0]!.active = false
    state.listProjectContents.mockResolvedValue(archivedCatalog)
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('.quick-add').attributes('disabled')).toBeDefined()
    expect(state.createWorkItem).not.toHaveBeenCalled()
  })

  it('表格状态连续迁移无感回写最新状态与 ETag', async () => {
    let current = item()
    current.capabilities = {
      ...current.capabilities,
      availableTransitions: [{
        toStatus: 'DONE', displayName: '已完成', statusCategory: WorkItemStatusCategory.Done,
        requiresResolution: false,
      }],
    }
    state.listProjectWorkItems.mockImplementation(() => Promise.resolve(page([current])))
    state.transitionWorkItem.mockImplementation((request: {
      workItemTransitionRequest: { toStatus: string }
    }) => {
      const nextVersion = current.rowVersion + 1
      const done = request.workItemTransitionRequest.toStatus === 'DONE'
      current = {
        ...current,
        statusCode: done ? 'DONE' : 'BACKLOG',
        statusCategory: done ? WorkItemStatusCategory.Done : WorkItemStatusCategory.Todo,
        rowVersion: nextVersion,
        etag: `"${nextVersion}"`,
        capabilities: {
          ...current.capabilities,
          availableTransitions: [{
            toStatus: done ? 'BACKLOG' : 'DONE',
            displayName: done ? '待开始' : '已完成',
            statusCategory: done ? WorkItemStatusCategory.Todo : WorkItemStatusCategory.Done,
            requiresResolution: false,
          }],
        },
      }
      return Promise.resolve(current as unknown as WorkItemDetail)
    })
    const wrapper = mountView()
    await flushPromises()
    const listCallsBeforeTransition = state.listProjectWorkItems.mock.calls.length
    const view = wrapper.vm as unknown as {
      tableItems: ProjectWorkItemListItem[]
      transitionItem: (row: ProjectWorkItemListItem, statusCode: string) => Promise<void>
    }

    await view.transitionItem(view.tableItems[0]!, 'DONE')
    await view.transitionItem(view.tableItems[0]!, 'BACKLOG')
    await flushPromises()

    expect(state.transitionWorkItem).toHaveBeenNthCalledWith(1, expect.objectContaining({
      ifMatch: '"1"', workItemTransitionRequest: { toStatus: 'DONE', resolution: null },
    }))
    expect(state.transitionWorkItem).toHaveBeenNthCalledWith(2, expect.objectContaining({
      ifMatch: '"2"', workItemTransitionRequest: { toStatus: 'BACKLOG', resolution: null },
    }))
    expect(state.listProjectWorkItems).toHaveBeenCalledTimes(listCallsBeforeTransition)
    expect(view.tableItems[0]).toMatchObject({ statusCode: 'BACKLOG', rowVersion: 3, etag: '"3"' })
  })

  it('看板只允许 availableTransitions 中的目标状态', async () => {
    state.route.query = { view: 'kanban' }
    const movable = item()
    movable.capabilities = {
      ...movable.capabilities,
      availableTransitions: [{
        toStatus: 'DONE', displayName: '已完成', statusCategory: WorkItemStatusCategory.Done,
        requiresResolution: false,
      }],
    }
    state.listProjectWorkItems.mockImplementation(({ status }: { status?: Set<string> }) =>
      Promise.resolve(page(status?.has('BACKLOG') ? [movable] : [])))
    state.transitionWorkItem.mockResolvedValue({
      ...movable, statusCode: 'DONE', statusCategory: WorkItemStatusCategory.Done,
      rowVersion: 2, etag: '"2"',
    } as unknown as WorkItemDetail)
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('.kanban-card').trigger('dragstart')
    await wrapper.findAll('.kanban-lane')[1]!.trigger('drop')
    await flushPromises()
    expect(state.transitionWorkItem).toHaveBeenCalledWith(expect.objectContaining({
      workItemId: 'item-1',
      workItemTransitionRequest: { toStatus: 'DONE', resolution: null },
    }))

    movable.capabilities = { ...movable.capabilities, availableTransitions: [] }
    state.transitionWorkItem.mockClear()
    await wrapper.get('.kanban-card').trigger('dragstart')
    await wrapper.findAll('.kanban-lane')[1]!.trigger('drop')
    await flushPromises()
    expect(state.transitionWorkItem).not.toHaveBeenCalled()
  })

  it('需说明的看板迁移携带用户输入，并在冲突后刷新泳道', async () => {
    state.route.query = { view: 'kanban' }
    const movable = item()
    movable.capabilities = {
      ...movable.capabilities,
      availableTransitions: [{
        toStatus: 'DONE', displayName: '已完成', statusCategory: WorkItemStatusCategory.Done,
        requiresResolution: true,
      }],
    }
    state.listProjectWorkItems.mockImplementation(({ status }: { status?: Set<string> }) =>
      Promise.resolve(page(status?.has('BACKLOG') ? [movable] : [])))
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '验收完成', action: 'confirm' } as never)
    state.transitionWorkItem.mockRejectedValue(new Error('conflict'))
    const wrapper = mountView()
    await flushPromises()
    const callsBeforeDrop = state.listProjectWorkItems.mock.calls.length

    await wrapper.get('.kanban-card').trigger('dragstart')
    await wrapper.findAll('.kanban-lane')[1]!.trigger('drop')
    await flushPromises()

    expect(state.transitionWorkItem).toHaveBeenCalledWith(expect.objectContaining({
      workItemTransitionRequest: { toStatus: 'DONE', resolution: '验收完成' },
    }))
    expect(state.listProjectWorkItems.mock.calls.length).toBeGreaterThan(callsBeforeDrop)
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

  it('分别高亮可交互内容块，并让固定名称列的表格独立滚动', async () => {
    const wrapper = mountView()
    await flushPromises()

    const view = wrapper.vm as unknown as {
      detailOpen: boolean
      detail: { id: string } | undefined
      drawerWidth: number
      isResizingDrawer: boolean
      selectedRowId: string | undefined
      selectedCellKey: string | undefined
      selectCell: (rowId: string, cellKey: string) => void
      isRowSelected: (rowId: string) => boolean
      onDrawerResizePointerDown: (event: PointerEvent) => void
      syncPageScrollbars: () => void
      horizontalOverflow: boolean
    }

    const table = wrapper.findComponent({ name: 'ElTable' })
    const tableColumns = wrapper.findAllComponents({ name: 'ElTableColumn' })
    const selectionColumn = tableColumns.find(column => column.props('type') === 'selection')
    const titleColumn = tableColumns.find(column => column.props('columnKey') === 'title')
    expect(table.props('height')).toBe('100%')
    expect(selectionColumn?.props('width')).toBe(48)
    expect(selectionColumn?.props('fixed')).toBe(true)
    expect(selectionColumn?.props('reserveSelection')).toBe(true)
    expect(titleColumn?.props('fixed')).toBe(true)
    expect(titleColumn?.props('width')).toBe('')
    expect(titleColumn?.props('minWidth')).toBe(320)
    expect(wrapper.find('.el-table__append-wrapper .monday-quick-add').exists()).toBe(true)

    const tableScroll = wrapper.get('.monday-table .el-scrollbar__wrap').element as HTMLElement
    const tableHeaderScroll = wrapper.get('.monday-table .el-table__header-wrapper').element as HTMLElement
    const horizontalScrollbar = document.body.querySelector<HTMLElement>('.project-table-scrollbar--horizontal')
    const verticalScrollbar = document.body.querySelector<HTMLElement>('.project-table-scrollbar--vertical')
    expect(horizontalScrollbar).not.toBeNull()
    expect(verticalScrollbar).not.toBeNull()
    Object.defineProperties(tableScroll, {
      clientWidth: { configurable: true, value: 800 },
      scrollWidth: { configurable: true, value: 1120 },
      clientHeight: { configurable: true, value: 300 },
      scrollHeight: { configurable: true, value: 850 },
    })
    Object.defineProperties(horizontalScrollbar!, {
      clientWidth: { configurable: true, value: 900 },
    })
    Object.defineProperties(verticalScrollbar!, {
      clientHeight: { configurable: true, value: 700 },
    })
    view.syncPageScrollbars()
    await nextTick()
    expect(view.horizontalOverflow).toBe(true)
    expect(horizontalScrollbar!.style.display).not.toBe('none')
    expect(horizontalScrollbar!.querySelector<HTMLElement>('.project-table-scrollbar__horizontal-spacer')?.style.width).toBe('1220px')
    expect(verticalScrollbar!.querySelector<HTMLElement>('.project-table-scrollbar__vertical-spacer')?.style.height).toBe('1250px')

    horizontalScrollbar!.scrollLeft = 120
    horizontalScrollbar!.dispatchEvent(new Event('scroll'))
    expect(tableScroll.scrollLeft).toBe(120)
    expect(tableHeaderScroll.scrollLeft).toBe(120)
    verticalScrollbar!.scrollTop = 180
    verticalScrollbar!.dispatchEvent(new Event('scroll'))
    expect(tableScroll.scrollTop).toBe(180)
    tableScroll.scrollLeft = 240
    tableScroll.scrollTop = 360
    tableScroll.dispatchEvent(new Event('scroll'))
    expect(horizontalScrollbar!.scrollLeft).toBe(240)
    expect(tableHeaderScroll.scrollLeft).toBe(240)
    expect(verticalScrollbar!.scrollTop).toBe(360)

    // 名称高亮只覆盖名称按钮，不延伸到讨论按钮所在区域
    view.selectCell('item-1', 'title')
    await nextTick()
    const titleBtn = wrapper.find('.work-item-link')
    const discussionBtn = wrapper.find('.monday-discussion-btn')
    expect(titleBtn.classes()).toContain('monday-cell--selected')
    expect(titleBtn.element.closest('td')?.classList.contains('monday-cell--selected')).toBe(false)
    expect(discussionBtn.classes()).not.toContain('monday-cell--selected')

    // 选中状态单元格
    const statusBtn = wrapper.find('.monday-status-cell')
    await statusBtn.trigger('click')
    expect(view.selectedRowId).toBe('item-1')
    expect(view.selectedCellKey).toBe('item-1:status')
    expect(statusBtn.element.closest('td')?.classList.contains('monday-cell--selected')).toBe(true)

    // 选中优先级单元格
    const priorityBtn = wrapper.find('.monday-priority-cell')
    expect(priorityBtn.text()).toBe('-')
    expect(priorityBtn.classes()).toContain('monday-priority-cell--empty')
    await priorityBtn.trigger('click')
    expect(view.selectedCellKey).toBe('item-1:priority')
    expect(priorityBtn.element.closest('td')?.classList.contains('monday-cell--selected')).toBe(true)
    expect(statusBtn.element.closest('td')?.classList.contains('monday-cell--selected')).toBe(false)

    const assigneeBtn = wrapper.find('.monday-cell-centered')
    await assigneeBtn.trigger('click')
    expect(view.selectedCellKey).toBe('item-1:assignee')
    expect(assigneeBtn.element.closest('td')?.classList.contains('monday-cell--selected')).toBe(true)

    const dueDateBtn = wrapper.find('.monday-due-date-cell')
    await dueDateBtn.trigger('click')
    expect(view.selectedCellKey).toBe('item-1:dueDate')
    expect(dueDateBtn.element.closest('td')?.classList.contains('monday-cell--selected')).toBe(true)

    // 抽屉打开时，同时高亮打开的工作项行和新点击的标签行
    view.detailOpen = true
    view.detail = { id: 'item-1' }
    view.selectCell('item-2', 'status')
    await nextTick()
    expect(view.isRowSelected('item-1')).toBe(true)
    expect(view.isRowSelected('item-2')).toBe(true)
    expect(view.selectedCellKey).toBe('item-2:status')
    expect(document.body.classList.contains('yp-project-overview-scroll')).toBe(true)
    expect(document.body.classList.contains('yp-work-items-drawer-open')).toBe(true)
    expect(document.body.style.getPropertyValue('--yp-work-items-drawer-width')).toBe('560px')

    // 详情抽屉不带深色蒙版且包含左侧关闭按钮与拖动手柄
    const drawer = wrapper.findComponent({ name: 'ElDrawer' })
    expect(drawer.props('modal')).toBe(false)
    expect(drawer.props('modalPenetrable')).toBe(true)
    expect(drawer.props('appendToBody')).toBe(true)
    expect(drawer.props('showClose')).toBe(true)
    expect(document.body.querySelector('.work-items-detail-drawer__header .el-drawer__close-btn')).not.toBeNull()
    expect(document.body.querySelector('.drawer-resize-handle')).not.toBeNull()

    // 拖动手柄调整抽屉宽度
    view.onDrawerResizePointerDown({
      clientX: 500,
      preventDefault: vi.fn(),
      stopPropagation: vi.fn(),
    } as unknown as PointerEvent)
    expect(view.isResizingDrawer).toBe(true)
    await nextTick()
    expect(document.body.querySelector('.work-items-detail-drawer--resizing')).not.toBeNull()
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 400 }))
    expect(view.drawerWidth).toBe(660)
    expect(document.body.style.getPropertyValue('--yp-work-items-drawer-width')).toBe('660px')
    window.dispatchEvent(new MouseEvent('pointerup'))
    expect(view.isResizingDrawer).toBe(false)
    await nextTick()
    expect(document.body.querySelector('.work-items-detail-drawer--resizing')).toBeNull()
    expect(document.body.style.getPropertyValue('--yp-work-items-drawer-width')).toBe('660px')

    // 抽屉在动态单行布局允许的 480px 下限停止缩窄
    view.onDrawerResizePointerDown({
      clientX: 400,
      preventDefault: vi.fn(),
      stopPropagation: vi.fn(),
    } as unknown as PointerEvent)
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 900 }))
    expect(view.drawerWidth).toBe(480)
    window.dispatchEvent(new MouseEvent('pointerup'))

    view.detailOpen = false
    await nextTick()
    expect(document.body.classList.contains('yp-work-items-drawer-open')).toBe(false)
    expect(document.body.style.getPropertyValue('--yp-work-items-drawer-width')).toBe('')
  })

  it('通过左侧复选框选择多行，并为选中行应用浅蓝遮罩', async () => {
    const first = item('item-1')
    const second = item('item-2')
    state.listProjectWorkItems.mockResolvedValue(page([first, second]))
    const wrapper = mountView()
    await flushPromises()

    const view = wrapper.vm as unknown as {
      selectedWorkItemIds: Set<string>
      isRowSelected: (rowId: string) => boolean
      tableRowClassName: (context: { row: ProjectWorkItemListItem, rowIndex: number }) => string
    }
    const rowCheckboxes = wrapper.findAll<HTMLInputElement>('.el-table__body .monday-selection-column .el-checkbox__original')
    expect(rowCheckboxes).toHaveLength(2)

    await rowCheckboxes[0]!.setValue(true)
    expect([...view.selectedWorkItemIds]).toEqual(['item-1'])
    expect(wrapper.findAll('.el-table__body tr.work-item-table-row--selected')).toHaveLength(1)

    await rowCheckboxes[1]!.setValue(true)
    expect([...view.selectedWorkItemIds]).toEqual(['item-1', 'item-2'])
    expect(view.isRowSelected('item-1')).toBe(true)
    expect(view.tableRowClassName({ row: second, rowIndex: 1 })).toContain('work-item-table-row--selected')
    expect(wrapper.findAll('.el-table__body tr.work-item-table-row--selected')).toHaveLength(2)

    await rowCheckboxes[0]!.setValue(false)
    expect([...view.selectedWorkItemIds]).toEqual(['item-2'])
    expect(wrapper.findAll('.el-table__body tr.work-item-table-row--selected')).toHaveLength(1)
  })
})
