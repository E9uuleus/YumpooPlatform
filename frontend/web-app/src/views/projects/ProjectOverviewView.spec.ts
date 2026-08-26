import {
  ContentStatus,
  ContentViewType,
  ProjectActorAccess,
  ProjectLifecycle,
  ProjectTemplateKey,
  ProjectType,
  WorkItemStatusCategory,
  WorkItemType,
  type ProjectContentCatalog,
  type ProjectDetail,
  type WorkItemDetail,
  type ProjectWorkItemCursorPage,
  type ProjectWorkItemListItem,
} from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
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
  listProjectWorkItemFilterOptions: vi.fn(),
  moveProjectWorkItemOrder: vi.fn(),
  patchWorkItemAssignee: vi.fn(),
  patchWorkItemPriority: vi.fn(),
  patchWorkItemDueDate: vi.fn(),
  createWorkItem: vi.fn(),
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
    listProjectWorkItemFilterOptions: state.listProjectWorkItemFilterOptions,
    moveProjectWorkItemOrder: state.moveProjectWorkItemOrder,
    patchWorkItemAssignee: state.patchWorkItemAssignee,
    patchWorkItemPriority: state.patchWorkItemPriority,
    patchWorkItemDueDate: state.patchWorkItemDueDate,
    createWorkItem: state.createWorkItem,
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
      id: 'content-1', projectId: 'project-1', code: 'REQ', name: '产品需求', description: null,
      workItemType: WorkItemType.Requirement, status: ContentStatus.Active,
      defaultViewType: ContentViewType.Table, viewConfig: {} as never,
      appliedTemplateKey: 'rnd', appliedTemplateVersion: 1, appliedBlueprintCode: 'REQ',
      rowVersion: 1, etag: '"1"', createdAt: new Date(), createdByUserId: 'owner-1',
      updatedAt: new Date(), updatedByUserId: 'owner-1', archivedAt: null, archivedByUserId: null,
    }],
    blueprintOptions: [],
    workflowStatusOptions: [
      { statusCode: 'BACKLOG', displayName: '待开始', statusCategory: 'TODO', sortOrder: 1, initial: true, terminal: false },
      { statusCode: 'DONE', displayName: '已完成', statusCategory: 'DONE', sortOrder: 2, initial: false, terminal: true },
    ] as never,
    canCreate: true,
  }
}

function item(id = 'item-1'): ProjectWorkItemListItem {
  return {
    id, projectId: 'project-1', contentId: 'content-1', contentName: '产品需求', itemNo: 'WI-1', type: WorkItemType.Requirement,
    title: '实现项目工作项首页', statusCode: 'BACKLOG', statusCategory: WorkItemStatusCategory.Todo,
    priority: null, assigneeUserId: null, assigneeDisplayName: null,
    dueDate: null, rowVersion: 1, etag: '"1"',
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
    state.listProjectMembers.mockResolvedValue({ items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 })
    state.listProjectWorkItems.mockResolvedValue(page())
    state.listProjectWorkItemFilterOptions.mockResolvedValue({ items: [], nextCursor: null })
    state.createWorkItem.mockResolvedValue({ ...item('created'), itemNo: 'WI-2' } as unknown as WorkItemDetail)
    state.getWorkItem.mockResolvedValue(item() as unknown as WorkItemDetail)
  })

  afterEach(() => vi.restoreAllMocks())

  it('按固定顺序展示列名，并将 Content 映射为类别名称', async () => {
    const wrapper = mountView()
    await flushPromises()

    const labels = wrapper.findAll('.el-table__header th').map(node => node.text()).filter(Boolean)
    expect(labels).toEqual(['工作项名称', '处理人', '状态', '优先级', '工作项类别', '截止日期', '最后更新时间'])
    expect(wrapper.text()).toContain('产品需求')
    expect(wrapper.text()).toContain('实现项目工作项首页')
    expect(state.listProjectWorkItems).toHaveBeenCalledWith(expect.objectContaining({
      projectId: 'project-1', view: ContentViewType.Table,
    }), expect.objectContaining({ signal: expect.any(AbortSignal) }))
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
      onTableDrop: (event: DragEvent) => Promise<void>
    }
    view.tableDragging = moved
    view.tableDropIndex = 1

    await view.onTableDrop(new Event('drop') as DragEvent)

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
    await view.onTableDrop(new Event('drop') as DragEvent)

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
      view: ContentViewType.Kanban,
      status: new Set(['BACKLOG']),
    }), expect.objectContaining({ signal: expect.any(AbortSignal) }))
  })

  it('名称和讨论按钮打开同一详情抽屉的对应区域', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('.work-item-link').trigger('click')
    await flushPromises()
    expect(state.getWorkItem).toHaveBeenLastCalledWith({ workItemId: 'item-1' })
    expect((wrapper.vm as unknown as { detailTab: string }).detailTab).toBe('details')

    await wrapper.get('[aria-label="打开协作讨论"]').trigger('click')
    await flushPromises()
    expect(state.getWorkItem).toHaveBeenCalledTimes(2)
    expect((wrapper.vm as unknown as { detailTab: string }).detailTab).toBe('discussion')
  })

  it('Enter 快速创建时发送空优先级，且重复按键不会重复提交', async () => {
    let resolveCreate: ((value: WorkItemDetail) => void) | undefined
    state.createWorkItem.mockImplementation(() => new Promise<WorkItemDetail>(resolve => { resolveCreate = resolve }))
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('.quick-add').trigger('click')
    const input = wrapper.get('input[placeholder^="输入工作项名称"]')
    await input.setValue('快速新增事项')
    await input.trigger('keydown', { key: 'Enter' })
    await input.trigger('keydown', { key: 'Enter' })
    expect(state.createWorkItem).toHaveBeenCalledTimes(1)
    expect(state.createWorkItem).toHaveBeenCalledWith(expect.objectContaining({
      contentId: 'content-1',
      workItemCreateRequest: expect.objectContaining({ title: '快速新增事项', priority: null }),
    }))

    resolveCreate?.({ ...item('created'), itemNo: 'WI-2' } as unknown as WorkItemDetail)
    await flushPromises()
  })

  it('空标题不创建；Shift+Enter 成功后保留类别并继续输入', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('.quick-add').trigger('click')
    const input = wrapper.get('input[placeholder^="输入工作项名称"]')

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
    const input = wrapper.get('input[placeholder^="输入工作项名称"]')
    await input.setValue('保留的草稿')

    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await flushPromises()

    expect(state.createWorkItem).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.quick-row').exists()).toBe(true)
    expect((input.element as HTMLInputElement).value).toBe('保留的草稿')
  })

  it('没有 ACTIVE Content 时禁用快速添加', async () => {
    const archivedCatalog = catalog()
    archivedCatalog.items[0]!.status = ContentStatus.Archived
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
})
