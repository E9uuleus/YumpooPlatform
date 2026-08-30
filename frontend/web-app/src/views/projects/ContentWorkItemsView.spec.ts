import {
  ContentStatus,
  ContentSortDirection,
  ContentSortField,
  ContentTableColumn,
  ContentViewType,
  ProjectActorAccess,
  ProjectLifecycle,
  ProjectMembershipStatus,
  ProjectMembershipStatusFilter,
  WorkItemPriority,
  WorkItemRankPlacement,
  WorkItemStatusCategory,
  WorkItemType,
  WorkflowStatusCategory,
  type Content,
  type ProjectContentCatalog,
  type ProjectDetail,
  type WorkItemDetail,
  type WorkItemPage,
  type WorkItemSummary,
  type WorkItemTransitionOption,
} from '@yumpoo/api-client'
import type { ContentTableQuery } from '../../components/projects/contentTableQuery'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ContentWorkItemsView from './ContentWorkItemsView.vue'

interface WorkItemUndoEntryForTest {
  tombstone: WorkItemDetail
  restoreIdempotencyKey: string
  loading: boolean
  problem?: unknown
}

const api = vi.hoisted(() => ({
  getProject: vi.fn(),
  listProjectMembers: vi.fn(),
  listProjectContents: vi.fn(),
  getContent: vi.fn(),
  updateContent: vi.fn(),
  listContentWorkItems: vi.fn(),
  getProjectWorkItemLabels: vi.fn(),
  createWorkItem: vi.fn(),
  getWorkItem: vi.fn(),
  updateWorkItem: vi.fn(),
  transitionWorkItem: vi.fn(),
  rankMoveWorkItem: vi.fn(),
  deleteWorkItem: vi.fn(),
  restoreWorkItem: vi.fn(),
  listWorkItemUpdates: vi.fn(),
  publishWorkItemUpdate: vi.fn(),
}))
const routing = vi.hoisted(() => ({
  route: { name: 'content-work-items', params: { projectId: 'project-1', contentId: 'content-1' },
    query: {} as Record<string, string | string[]> },
  push: vi.fn(), replace: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => routing.route,
  useRouter: () => ({ push: routing.push, replace: routing.replace }),
}))
vi.mock('../../api/client', () => ({
  projectsApi: { getProject: api.getProject, listProjectMembers: api.listProjectMembers },
  contentsApi: { listProjectContents: api.listProjectContents,
    getContent: api.getContent, updateContent: api.updateContent },
  workItemsApi: {
    listContentWorkItems: api.listContentWorkItems,
    getProjectWorkItemLabels: api.getProjectWorkItemLabels,
    createWorkItem: api.createWorkItem,
    getWorkItem: api.getWorkItem,
    updateWorkItem: api.updateWorkItem,
    transitionWorkItem: api.transitionWorkItem,
    rankMoveWorkItem: api.rankMoveWorkItem,
    deleteWorkItem: api.deleteWorkItem,
    restoreWorkItem: api.restoreWorkItem,
  },
  workItemUpdatesApi: {
    listWorkItemUpdates: api.listWorkItemUpdates,
    publishWorkItemUpdate: api.publishWorkItemUpdate,
  },
}))
vi.mock('@yumpoo/api-client', async (importOriginal) => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))
vi.mock('../../api/problems', async (importOriginal) => ({
  ...await importOriginal<typeof import('../../api/problems')>(),
  toApiProblem: async (reason: unknown) => reason,
}))

const project = {
  id: 'project-1', name: '项目一', description: null, code: 'PROJECT_1',
  workspaceId: 'workspace-1', workspaceCode: 'WORKSPACE', workspaceName: '工作区',
  projectType: 'PRODUCT_DEVELOPMENT', lifecycle: ProjectLifecycle.Active,
  ownerUserId: 'owner-1', ownerDisplayName: '负责人', actorAccess: ProjectActorAccess.Owner,
  capabilities: { canUpdateSettings: true, canActivate: false, canManageMembers: true,
    canReassignOwner: false, canManageProductLinks: true }, etag: '"0"', rowVersion: 0,
} as ProjectDetail

function content(defaultViewType = ContentViewType.Table): Content {
  return {
    id: 'content-1', projectId: 'project-1', code: 'TASKS', name: '任务', description: null,
    workItemType: WorkItemType.Task, status: ContentStatus.Active, defaultViewType,
    viewConfig: {
      table: {
        columnOrder: new Set([
          ContentTableColumn.ItemNo, ContentTableColumn.Title, ContentTableColumn.Status,
          ContentTableColumn.Priority, ContentTableColumn.Assignee, ContentTableColumn.Reporter,
          ContentTableColumn.Description, ContentTableColumn.Notes, ContentTableColumn.Timeline,
          ContentTableColumn.DueDate, ContentTableColumn.UpdatedAt,
        ]),
        hiddenColumns: new Set(),
        sort: [],
        filters: { query: null, statusCodes: new Set(), priorities: new Set(),
          assigneeUserIds: new Set(), dueFrom: null, dueTo: null, updatedAfter: null },
      },
      kanban: { statusGroups: [
        { name: '待办', statusCodes: new Set(['BACKLOG']) },
        { name: '进行中', statusCodes: new Set(['IN_PROGRESS']) },
      ] },
    },
    appliedTemplateKey: 'RND', appliedTemplateVersion: 1, appliedBlueprintCode: 'TASKS',
    rowVersion: 0, etag: '"0"', createdAt: new Date(), createdByUserId: 'owner-1',
    updatedAt: new Date(), updatedByUserId: 'owner-1', archivedAt: null, archivedByUserId: null,
  }
}

function catalog(item = content()): ProjectContentCatalog {
  return {
    items: [item], canCreate: true, blueprintOptions: [],
    workflowStatusOptions: [
      { statusCode: 'BACKLOG', displayName: '待办', statusCategory: WorkflowStatusCategory.Todo,
        colorToken: 'BLUE' as never, sortOrder: 10, active: true, protectedLabel: false, initial: true, terminal: false },
      { statusCode: 'IN_PROGRESS', displayName: '进行中', statusCategory: WorkflowStatusCategory.InProgress,
        colorToken: 'ORANGE' as never, sortOrder: 20, active: true, protectedLabel: false, initial: false, terminal: false },
      { statusCode: 'READY', displayName: '就绪', statusCategory: WorkflowStatusCategory.InProgress,
        colorToken: 'BLUE' as never, sortOrder: 30, active: true, protectedLabel: false, initial: false, terminal: false },
      { statusCode: 'DONE', displayName: '已完成', statusCategory: WorkflowStatusCategory.Done,
        colorToken: 'GREEN' as never, sortOrder: 40, active: true, protectedLabel: false, initial: false, terminal: true },
    ],
    priorityOptions: [], canManageLabels: true,
  }
}

function summary(id = 'work-item-1', statusCode = 'BACKLOG', title = '实现核心闭环'): WorkItemSummary {
  return {
    id, projectId: 'project-1', contentId: 'content-1', itemNo: `PROJECT_1-${id.endsWith('2') ? 2 : 1}`,
    type: WorkItemType.Task, title, statusCode,
    statusCategory: statusCode === 'BACKLOG' ? WorkItemStatusCategory.Todo : WorkItemStatusCategory.InProgress,
    priority: WorkItemPriority.Medium, reporterUserId: 'member-1', reporterDisplayName: '项目成员',
    assigneeUserId: 'owner-1', assigneeDisplayName: '负责人', description: '列表描述', notes: '列表备注',
    timelineStartDate: new Date('2026-08-22T00:00:00.000Z'),
    timelineEndDate: new Date('2026-08-29T00:00:00.000Z'),
    dueDate: new Date('2026-08-30T00:00:00.000Z'),
    rowVersion: 0, etag: '"0"',
    capabilities: { canEditFields: true, canMoveInKanban: true, canMoveInProjectOrder: false,
      canDiscuss: true, canDelete: true, canRestore: false, availableTransitions: defaultTransitions },
    updatedAt: new Date('2026-08-22T02:00:00Z'),
  }
}

const defaultTransitions: WorkItemTransitionOption[] = [
  { toStatus: 'READY', displayName: '就绪', statusCategory: WorkItemStatusCategory.InProgress,
    requiresResolution: false },
  { toStatus: 'DONE', displayName: '已完成', statusCategory: WorkItemStatusCategory.Done,
    requiresResolution: true },
]

function detail(
  description = '安全纯文本',
  availableTransitions: WorkItemTransitionOption[] = defaultTransitions,
): WorkItemDetail {
  return {
    ...summary(), description, notes: null,
    rowVersion: 0, etag: '"0"', capabilities: { canEditFields: true, canMoveInKanban: true,
      canMoveInProjectOrder: false, canDiscuss: true, canDelete: true, canRestore: false, availableTransitions },
    createdAt: new Date('2026-08-22T01:00:00Z'),
    deleted: false, deletedAt: null, deletedByUserId: null, deleteReason: null,
  }
}

function page(items: WorkItemSummary[], pageNumber = 0, totalPages = 1): WorkItemPage {
  return { items, page: pageNumber, size: 20, totalElements: items.length, totalPages }
}

function mountView() {
  return mount(ContentWorkItemsView, { attachTo: document.body })
}

describe('M2-10 Content 工作项工作区', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    Object.values(api).forEach(mock => mock.mockReset())
    routing.push.mockReset(); routing.replace.mockReset(); routing.route.query = {}
    api.getProject.mockResolvedValue(project)
    api.listProjectMembers.mockResolvedValue({
      items: [{ membershipId: 'membership-1', projectId: 'project-1', userId: 'owner-1',
        displayName: '负责人', employmentStatus: 'ACTIVE', accountStatus: 'ENABLED',
        membershipStatus: ProjectMembershipStatus.Active, owner: true,
        joinedAt: new Date(), joinedByUserId: 'owner-1', removedAt: null,
        removedByUserId: null, rowVersion: 0, etag: '"0"' }],
      page: 0, size: 100, totalElements: 1, totalPages: 1,
    })
    api.listProjectContents.mockResolvedValue(catalog())
    api.getProjectWorkItemLabels.mockResolvedValue({
      statuses: catalog().workflowStatusOptions.map(status => ({
        code: status.statusCode, displayName: status.displayName, colorToken: status.colorToken,
        statusCategory: status.statusCategory, sortOrder: status.sortOrder, active: true,
        protectedLabel: status.protectedLabel, inUse: true,
      })),
      priorities: [{ code: 'MEDIUM', displayName: '中', colorToken: 'TEAL', sortOrder: 20,
        active: true, inUse: true }], rowVersion: 0, etag: '"0"', canManage: true,
    } as never)
    api.getContent.mockResolvedValue(content())
    api.updateContent.mockResolvedValue(content())
    api.listContentWorkItems.mockResolvedValue(page([summary()]))
    api.createWorkItem.mockResolvedValue(detail())
    api.getWorkItem.mockResolvedValue(detail())
    api.updateWorkItem.mockResolvedValue({ ...detail(), rowVersion: 1, etag: '"1"' })
    api.transitionWorkItem.mockResolvedValue({
      ...detail(), statusCode: 'READY', statusCategory: WorkItemStatusCategory.InProgress,
      rowVersion: 1, etag: '"1"', capabilities: { canEditFields: true, canMoveInKanban: true,
        canMoveInProjectOrder: false, canDiscuss: true, canDelete: true, canRestore: false, availableTransitions: [] },
    })
    api.rankMoveWorkItem.mockResolvedValue(detail())
    api.deleteWorkItem.mockResolvedValue({
      ...detail(), rowVersion: 1, etag: '"1"', deleted: true,
      deletedAt: new Date('2026-08-24T01:00:00Z'), deletedByUserId: 'owner-1',
      deleteReason: '需求已合并', capabilities: { canEditFields: false, canMoveInKanban: false,
        canMoveInProjectOrder: false, canDiscuss: false, canDelete: false, canRestore: true, availableTransitions: [] },
    })
    api.restoreWorkItem.mockResolvedValue({ ...detail(), rowVersion: 2, etag: '"2"' })
    api.listWorkItemUpdates.mockResolvedValue({ items: [], nextCursor: null })
  })

  it('详情抽屉提供双页签且讨论仅在首次进入时加载', async () => {
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as { openDetail: (item: WorkItemSummary) => Promise<void> }
    await vm.openDetail(summary()); await flushPromises()
    expect(wrapper.text()).toContain('详情')
    expect(wrapper.text()).toContain('讨论')
    expect(api.listWorkItemUpdates).not.toHaveBeenCalled()

    const discussionTab = wrapper.findAll('.el-tabs__item')
      .find(tab => tab.text().includes('讨论'))
    await discussionTab?.trigger('click')
    await flushPromises()
    expect(api.listWorkItemUpdates).toHaveBeenCalledWith({ workItemId: 'work-item-1', size: 20 })
    wrapper.unmount()
  }, 10_000)

  it('使用服务端分页并呈现全部协作字段列', async () => {
    const wrapper = mountView(); await flushPromises()
    expect(api.listContentWorkItems).toHaveBeenCalledWith({
      contentId: 'content-1', page: 0, size: 20, q: '', status: new Set(),
      priority: new Set(), assigneeUserId: new Set(), dueFrom: undefined,
      dueTo: undefined, updatedAfter: undefined, sort: [],
    })
    expect(api.listProjectMembers).toHaveBeenCalledWith({ projectId: 'project-1',
      status: ProjectMembershipStatusFilter.All, page: 0, size: 100 })
    const text = wrapper.text()
    expect(text).toContain('事项编号')
    expect(text).toContain('标题')
    expect(text).toContain('处理人')
    expect(text).toContain('报告人')
    expect(text).toContain('描述')
    expect(text).toContain('备注')
    expect(text).toContain('计划时间')
    expect(text).toContain('截止日')
    expect(text).toContain('列表描述')
    expect(text).toContain('2026-08-22 → 2026-08-29')
    expect(text).toContain('PROJECT_1-1')
    wrapper.unmount()
  })

  it('无 URL 自定义时使用共享默认，custom 查询完整覆盖并显式提交', async () => {
    const shared = content()
    shared.viewConfig.table.filters.query = '共享条件'
    shared.viewConfig.table.filters.statusCodes = new Set(['BACKLOG'])
    shared.viewConfig.table.sort = [{ field: ContentSortField.Status,
      direction: ContentSortDirection.Asc }]
    api.listProjectContents.mockResolvedValue(catalog(shared))
    const sharedWrapper = mountView(); await flushPromises()
    expect(api.listContentWorkItems).toHaveBeenLastCalledWith(expect.objectContaining({
      q: '共享条件', status: new Set(['BACKLOG']), sort: ['STATUS,ASC'], page: 0,
    }))
    sharedWrapper.unmount()

    api.listContentWorkItems.mockClear()
    routing.route.query = { custom: '1', q: 'URL 条件', priority: ['HIGH'],
      sort: ['UPDATED_AT,DESC'] }
    const customWrapper = mountView(); await flushPromises()
    expect(api.listContentWorkItems).toHaveBeenLastCalledWith(expect.objectContaining({
      q: 'URL 条件', status: new Set(), priority: new Set([WorkItemPriority.High]),
      sort: ['UPDATED_AT,DESC'], page: 0,
    }))
    customWrapper.unmount()
  }, 10_000)

  it('筛选与排序写入浏览历史、回到第 0 页并忽略迟到响应', async () => {
    let resolveOld!: (value: WorkItemPage) => void
    const oldResult = new Promise<WorkItemPage>(resolve => { resolveOld = resolve })
    api.listContentWorkItems.mockImplementationOnce(() => oldResult)
      .mockResolvedValueOnce(page([summary('work-item-2', 'BACKLOG', '新查询结果')]))
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as {
      tableQuery: ContentTableQuery
      tablePage: WorkItemPage
      applyTableQuery: (value: ContentTableQuery, replace: boolean) => Promise<void>
    }
    const changed: ContentTableQuery = {
      filters: { ...vm.tableQuery.filters, query: '新查询', statusCodes: new Set(['BACKLOG']),
        priorities: new Set(), assigneeUserIds: new Set() },
      sort: [{ field: ContentSortField.Priority, direction: ContentSortDirection.Desc }],
    }
    await vm.applyTableQuery(changed, false)
    expect(routing.push).toHaveBeenCalledWith(expect.objectContaining({ query: expect.objectContaining({
      custom: '1', q: '新查询', status: ['BACKLOG'], sort: ['PRIORITY,DESC'],
    }) }))
    expect(api.listContentWorkItems).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0 }))
    resolveOld(page([summary('work-item-1', 'BACKLOG', '迟到旧结果')]))
    await flushPromises()
    expect(vm.tablePage.items[0]?.title).toBe('新查询结果')
    wrapper.unmount()
  })

  it('Owner 保存共享默认，412 后仅向最新版合并 filters/sort 并明确重提', async () => {
    const conflict = { kind: 'response', status: 412, error: { code: 'VERSION_CONFLICT',
      message: '冲突', requestId: 'request-query', retryable: false, fieldErrors: [] } }
    const latest = { ...content(), etag: '"2"' }
    latest.viewConfig.table.hiddenColumns = new Set([ContentTableColumn.Notes])
    latest.viewConfig.kanban.statusGroups = [{ name: '最新版分组', statusCodes: new Set(['BACKLOG']) }]
    api.updateContent.mockRejectedValueOnce(conflict).mockResolvedValueOnce(latest)
    api.getContent.mockResolvedValue(latest)
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as {
      tableQuery: ContentTableQuery
      sharedQueryConflict?: Content
      saveSharedQuery: (base?: Content) => Promise<void>
      retrySharedQuery: () => Promise<void>
    }
    vm.tableQuery = {
      filters: { ...vm.tableQuery.filters, query: '保留的临时条件',
        statusCodes: new Set(['BACKLOG']), priorities: new Set(), assigneeUserIds: new Set() },
      sort: [{ field: ContentSortField.Title, direction: ContentSortDirection.Asc }],
    }
    await vm.saveSharedQuery(); await flushPromises()
    expect(vm.sharedQueryConflict?.etag).toBe('"2"')
    expect(wrapper.text()).toContain('当前临时查询仍保留')
    await vm.retrySharedQuery()
    const retried = api.updateContent.mock.calls[1]?.[0]
    expect(retried.ifMatch).toBe('"2"')
    expect(retried.contentUpdateRequest.viewConfig.table.filters.query).toBe('保留的临时条件')
    expect(retried.contentUpdateRequest.viewConfig.table.hiddenColumns)
      .toEqual(new Set([ContentTableColumn.Notes]))
    expect(retried.contentUpdateRequest.viewConfig.kanban.statusGroups[0]?.name).toBe('最新版分组')
    wrapper.unmount()
  })

  it('创建请求提交完整八字段快照并用 UTC 保持自然日', async () => {
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as {
      createForm: { title: string; priority: WorkItemPriority | null; assigneeUserId: string;
        description: string; notes: string; timelineStartDate: string;
        timelineEndDate: string; dueDate: string }
      createWorkItem: () => Promise<void>
    }
    vm.createForm.title = '  新任务  '
    vm.createForm.description = '  描述  '
    vm.createForm.notes = ''
    vm.createForm.assigneeUserId = 'owner-1'
    vm.createForm.timelineStartDate = '2026-08-22'
    vm.createForm.timelineEndDate = '2026-08-29'
    vm.createForm.dueDate = '2026-08-30'
    await vm.createWorkItem()
    const request = api.createWorkItem.mock.calls[0]?.[0]
    expect(request).toEqual({
      contentId: 'content-1', xXSRFTOKEN: 'csrf-token', idempotencyKey: expect.any(String),
      workItemCreateRequest: {
        title: '新任务', priority: null, assigneeUserId: 'owner-1',
        description: '描述', notes: null,
        timelineStartDate: new Date('2026-08-22T00:00:00.000Z'),
        timelineEndDate: new Date('2026-08-29T00:00:00.000Z'),
        dueDate: new Date('2026-08-30T00:00:00.000Z'),
      },
    })
    expect(Object.keys(request.workItemCreateRequest).sort())
      .toEqual(['assigneeUserId', 'description', 'dueDate', 'notes', 'priority', 'timelineEndDate',
        'timelineStartDate', 'title'])
    wrapper.unmount()
  })

  it('Kanban 按单一状态泳道独立分页并展示同一真源卡片', async () => {
    api.listProjectContents.mockResolvedValue(catalog(content(ContentViewType.Kanban)))
    api.listContentWorkItems.mockImplementation(({ status }: { status?: Set<string> }) => {
      if (status?.has('IN_PROGRESS')) return Promise.resolve(page([summary('work-item-2', 'IN_PROGRESS', '开发中')]))
      return Promise.resolve(page([summary()]))
    })
    const wrapper = mountView(); await flushPromises()
    const calls = api.listContentWorkItems.mock.calls.map(call => call[0].status as Set<string>)
    expect(calls).toHaveLength(2)
    expect(calls.some(status => status.has('BACKLOG'))).toBe(true)
    expect(calls.some(status => status.has('IN_PROGRESS'))).toBe(true)
    expect(api.listContentWorkItems.mock.calls.every(call => call[0].status.size === 1)).toBe(true)
    expect(api.listContentWorkItems.mock.calls.every(call => call[0].view === ContentViewType.Kanban)).toBe(true)
    expect(api.listContentWorkItems.mock.calls.every(call => call[0].sort === undefined)).toBe(true)
    expect(wrapper.text()).toContain('待办')
    expect(wrapper.text()).toContain('进行中')
    expect(wrapper.text()).toContain('实现核心闭环')
    expect(wrapper.text()).toContain('开发中')
    expect(wrapper.text()).not.toContain('拖拽')
    wrapper.unmount()
  })

  it('保留多状态分组并在组内渲染独立状态泳道', async () => {
    const grouped = content(ContentViewType.Kanban)
    grouped.viewConfig.kanban.statusGroups = [{
      name: '交付中', statusCodes: new Set(['BACKLOG', 'IN_PROGRESS']),
    }]
    api.listProjectContents.mockResolvedValue(catalog(grouped))
    api.listContentWorkItems.mockImplementation(({ status }: { status?: Set<string> }) =>
      Promise.resolve(page(status?.has('IN_PROGRESS')
        ? [summary('work-item-2', 'IN_PROGRESS', '开发中')] : [summary()])))

    const wrapper = mountView(); await flushPromises()
    expect(wrapper.findAll('.kanban-group')).toHaveLength(1)
    expect(wrapper.findAll('.kanban-lane')).toHaveLength(2)
    expect(wrapper.text()).toContain('交付中')
    wrapper.unmount()
  })

  it('键盘触控菜单提交同状态定位并刷新 Kanban 与 Table 真源', async () => {
    api.listProjectContents.mockResolvedValue(catalog(content(ContentViewType.Kanban)))
    const item = summary()
    api.listContentWorkItems.mockImplementation(({ status }: { status?: Set<string> }) =>
      Promise.resolve(page(status?.has('BACKLOG') ? [item, summary('work-item-2')] : [])))
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as {
      handleMoveCommand: (item: WorkItemSummary, status: string, command: string) => void
    }

    vm.handleMoveCommand(item, 'BACKLOG', 'BOTTOM')
    await flushPromises()
    expect(api.rankMoveWorkItem).toHaveBeenCalledWith({
      workItemId: item.id,
      xXSRFTOKEN: 'csrf-token',
      ifMatch: '"0"',
      idempotencyKey: expect.any(String),
      workItemRankMoveRequest: {
        toStatus: 'BACKLOG', placement: WorkItemRankPlacement.End,
        anchorWorkItemId: null, resolution: null,
      },
    })
    expect(api.listContentWorkItems.mock.calls.some(call => call[0].view === undefined)).toBe(true)
    wrapper.unmount()
  })

  it('传输失败回滚并用原幂等键显式重试', async () => {
    api.listProjectContents.mockResolvedValue(catalog(content(ContentViewType.Kanban)))
    const item = summary()
    api.listContentWorkItems.mockImplementation(({ status }: { status?: Set<string> }) =>
      Promise.resolve(page(status?.has('BACKLOG') ? [item, summary('work-item-2')] : [])))
    api.rankMoveWorkItem.mockRejectedValueOnce({ kind: 'fallback', message: '网络连接异常' })
      .mockResolvedValueOnce(detail())
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as {
      failedMove?: unknown
      handleMoveCommand: (item: WorkItemSummary, status: string, command: string) => void
      retryKanbanMove: () => Promise<void>
    }

    vm.handleMoveCommand(item, 'BACKLOG', 'BOTTOM')
    await flushPromises()
    expect(vm.failedMove).toBeTruthy()
    expect(wrapper.text()).toContain('卡片已恢复原位')
    const originalKey = api.rankMoveWorkItem.mock.calls[0]?.[0].idempotencyKey
    await vm.retryKanbanMove(); await flushPromises()
    expect(api.rankMoveWorkItem.mock.calls[1]?.[0].idempotencyKey).toBe(originalKey)
    wrapper.unmount()
  })

  it('跨状态菜单遇到必填说明先对话确认，取消不发送', async () => {
    const configured = content(ContentViewType.Kanban)
    api.listProjectContents.mockResolvedValue(catalog(configured))
    const required: WorkItemTransitionOption = {
      toStatus: 'IN_PROGRESS', displayName: '进行中',
      statusCategory: WorkItemStatusCategory.InProgress, requiresResolution: true,
    }
    const item = { ...summary(), capabilities: {
      canEditFields: true, canMoveInKanban: true, canMoveInProjectOrder: false,
      canDiscuss: true, canDelete: true, canRestore: false, availableTransitions: [required],
    } }
    api.listContentWorkItems.mockImplementation(({ status }: { status?: Set<string> }) =>
      Promise.resolve(page(status?.has('BACKLOG') ? [item] : [])))
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as {
      moveResolutionOpen: boolean
      moveResolution: string
      handleMoveCommand: (item: WorkItemSummary, status: string, command: string) => void
      cancelMoveResolution: () => void
      confirmMoveResolution: () => void
    }
    vm.handleMoveCommand(item, 'BACKLOG', 'STATUS:IN_PROGRESS')
    expect(vm.moveResolutionOpen).toBe(true)
    vm.cancelMoveResolution()
    expect(api.rankMoveWorkItem).not.toHaveBeenCalled()
    vm.handleMoveCommand(item, 'BACKLOG', 'STATUS:IN_PROGRESS')
    vm.moveResolution = '风险已确认'
    vm.confirmMoveResolution()
    await flushPromises()
    expect(api.rankMoveWorkItem.mock.calls[0]?.[0].workItemRankMoveRequest.resolution)
      .toBe('风险已确认')
    wrapper.unmount()
  })

  it('详情抽屉在文本域中安全纯文本呈现描述和备注', async () => {
    const unsafe = '<img src=x onerror=alert(1)>'
    api.getWorkItem.mockResolvedValue(detail(unsafe))
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as { openDetail: (item: WorkItemSummary) => Promise<void> }
    await vm.openDetail(summary()); await flushPromises()
    expect((wrapper.findAll('textarea')[0]?.element as HTMLTextAreaElement).value).toBe(unsafe)
    expect(wrapper.find('.detail-panel img').exists()).toBe(false)
    wrapper.unmount()
  })

  it('详情保存提交完整快照、强 ETag 并刷新当前真源', async () => {
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as {
      detailDraft: { title: string; priority: WorkItemPriority; assigneeUserId: string;
        description: string; notes: string; timelineStartDate: string;
        timelineEndDate: string; dueDate: string }
      openDetail: (item: WorkItemSummary) => Promise<void>
      saveWorkItem: () => Promise<void>
    }
    await vm.openDetail(summary())
    vm.detailDraft.title = '  已编辑  '
    vm.detailDraft.assigneeUserId = ''
    vm.detailDraft.timelineStartDate = '2026-08-22'
    vm.detailDraft.timelineEndDate = '2026-08-23'
    vm.detailDraft.dueDate = ''
    await vm.saveWorkItem()
    expect(api.updateWorkItem).toHaveBeenCalledWith({
      workItemId: 'work-item-1', xXSRFTOKEN: 'csrf-token', ifMatch: '"0"',
      workItemUpdateRequest: {
        title: '已编辑', priority: WorkItemPriority.Medium, assigneeUserId: null,
        description: '安全纯文本', notes: null,
        timelineStartDate: new Date('2026-08-22T00:00:00.000Z'),
        timelineEndDate: new Date('2026-08-23T00:00:00.000Z'), dueDate: null,
      },
    })
    expect(api.listContentWorkItems).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('412 保留本地草稿并提供载入最新或明确重提', async () => {
    const latest = { ...detail(), title: '服务器新版本', rowVersion: 1, etag: '"1"' }
    api.getWorkItem.mockResolvedValueOnce(detail()).mockResolvedValueOnce(latest)
    api.updateWorkItem.mockRejectedValueOnce({ kind: 'response', status: 412,
      error: { code: 'VERSION_CONFLICT', message: '版本冲突', requestId: 'request-1',
        retryable: false, fieldErrors: [] } })
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as {
      detailDraft: { title: string }
      latestConflict?: WorkItemDetail
      openDetail: (item: WorkItemSummary) => Promise<void>
      saveWorkItem: (etag?: string) => Promise<void>
    }
    await vm.openDetail(summary())
    vm.detailDraft.title = '本地草稿'
    await vm.saveWorkItem(); await flushPromises()
    expect(vm.detailDraft.title).toBe('本地草稿')
    expect(vm.latestConflict?.title).toBe('服务器新版本')
    expect(wrapper.text()).toContain('服务器版本已更新，本地草稿仍保留')
    expect(wrapper.text()).toContain('载入最新版本')
    expect(wrapper.text()).toContain('基于最新版本重新提交')
    wrapper.unmount()
  })

  it('详情状态迁移只提交服务端选项并保留未存字段草稿', async () => {
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as {
      detailDraft: { title: string }
      transitionToStatus: string
      transitionResolution: string
      openDetail: (item: WorkItemSummary) => Promise<void>
      openTransition: () => void
      transitionWorkItem: () => Promise<void>
    }
    await vm.openDetail(summary())
    vm.detailDraft.title = '未保存的标题草稿'
    vm.openTransition()
    expect(vm.transitionToStatus).toBe('READY')
    vm.transitionResolution = '  已完成验证  '
    await vm.transitionWorkItem()
    expect(api.transitionWorkItem).toHaveBeenCalledWith({
      workItemId: 'work-item-1', xXSRFTOKEN: 'csrf-token', ifMatch: '"0"',
      idempotencyKey: expect.any(String),
      workItemTransitionRequest: { toStatus: 'READY', resolution: '已完成验证' },
    })
    expect(vm.detailDraft.title).toBe('未保存的标题草稿')
    expect(api.listContentWorkItems).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('需要说明的状态边在客户端阻止空说明提交', async () => {
    api.getWorkItem.mockResolvedValue(detail('安全纯文本', [defaultTransitions[1]!]))
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as {
      transitionResolution: string
      openDetail: (item: WorkItemSummary) => Promise<void>
      openTransition: () => void
      transitionWorkItem: () => Promise<void>
    }
    await vm.openDetail(summary())
    vm.openTransition()
    await vm.transitionWorkItem()
    expect(api.transitionWorkItem).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('该状态迁移必须填写说明')
    vm.transitionResolution = '验收通过'
    await vm.transitionWorkItem()
    expect(api.transitionWorkItem).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('状态迁移 412 载入服务器最新版本但不自动重试且保留草稿', async () => {
    const latest = { ...detail(), title: '服务器新版本', rowVersion: 1, etag: '"1"' }
    api.getWorkItem.mockResolvedValueOnce(detail()).mockResolvedValueOnce(latest)
    api.transitionWorkItem.mockRejectedValueOnce({ kind: 'response', status: 412,
      error: { code: 'VERSION_CONFLICT', message: '版本冲突', requestId: 'request-1',
        retryable: false, fieldErrors: [] } })
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as {
      detailDraft: { title: string }
      latestConflict?: WorkItemDetail
      openDetail: (item: WorkItemSummary) => Promise<void>
      openTransition: () => void
      transitionWorkItem: () => Promise<void>
    }
    await vm.openDetail(summary())
    vm.detailDraft.title = '本地未保存草稿'
    vm.openTransition()
    await vm.transitionWorkItem(); await flushPromises()
    expect(api.transitionWorkItem).toHaveBeenCalledTimes(1)
    expect(vm.detailDraft.title).toBe('本地未保存草稿')
    expect(vm.latestConflict?.title).toBe('服务器新版本')
    expect(wrapper.text()).toContain('服务器版本已更新，本地草稿仍保留')
    wrapper.unmount()
  })

  it('删除要求理由、提示未保存草稿，并在当前页面保留多个撤销项', async () => {
    const firstTombstone = {
      ...detail(), id: 'work-item-1', rowVersion: 1, etag: '"1"', deleted: true,
      deletedAt: new Date(), deletedByUserId: 'owner-1', deleteReason: '需求已合并',
      capabilities: { canEditFields: false, canMoveInKanban: false, canMoveInProjectOrder: false,
        canDiscuss: false, canDelete: false, canRestore: true, availableTransitions: [] },
    }
    const secondTombstone = { ...firstTombstone, id: 'work-item-2', itemNo: 'PROJECT_1-2' }
    api.deleteWorkItem.mockResolvedValueOnce(firstTombstone).mockResolvedValueOnce(secondTombstone)
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as {
      detailDraft: { title: string }
      deleteReason: string
      undoEntries: WorkItemUndoEntryForTest[]
      openDetail: (item: WorkItemSummary) => Promise<void>
      openDelete: () => void
      deleteWorkItem: () => Promise<void>
    }
    await vm.openDetail(summary())
    vm.detailDraft.title = '未保存草稿'
    vm.openDelete(); await flushPromises()
    expect(wrapper.text()).toContain('当前有未保存的字段草稿')
    await vm.deleteWorkItem()
    expect(api.deleteWorkItem).not.toHaveBeenCalled()
    vm.deleteReason = '需求已合并'
    await vm.deleteWorkItem(); await flushPromises()
    await vm.openDetail(summary('work-item-2', 'BACKLOG', '第二项'))
    vm.openDelete(); vm.deleteReason = '重复任务'
    await vm.deleteWorkItem(); await flushPromises()
    expect(vm.undoEntries).toHaveLength(2)
    expect(wrapper.text()).toContain('PROJECT_1-1 已删除')
    expect(wrapper.text()).toContain('PROJECT_1-2 已删除')
    wrapper.unmount()
  })

  it('撤销传输失败使用同一幂等键重试，成功后打开恢复项', async () => {
    api.restoreWorkItem.mockRejectedValueOnce({ kind: 'fallback', message: '网络连接异常' })
      .mockResolvedValueOnce({ ...detail(), rowVersion: 2, etag: '"2"' })
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as {
      deleteReason: string
      detailOpen: boolean
      undoEntries: WorkItemUndoEntryForTest[]
      openDetail: (item: WorkItemSummary) => Promise<void>
      openDelete: () => void
      deleteWorkItem: () => Promise<void>
      restoreWorkItem: (entry: WorkItemUndoEntryForTest) => Promise<void>
    }
    await vm.openDetail(summary()); vm.openDelete(); vm.deleteReason = '临时删除'
    await vm.deleteWorkItem()
    const entry = vm.undoEntries[0]!
    await vm.restoreWorkItem(entry)
    const originalKey = api.restoreWorkItem.mock.calls[0]?.[0].idempotencyKey
    await vm.restoreWorkItem(entry); await flushPromises()
    expect(api.restoreWorkItem.mock.calls[1]?.[0].idempotencyKey).toBe(originalKey)
    expect(vm.undoEntries).toHaveLength(0)
    expect(vm.detailOpen).toBe(true)
    wrapper.unmount()
  })

  it('删除遇到 412 只刷新服务器事实且不自动重提', async () => {
    const latest = { ...detail(), title: '服务器新版本', rowVersion: 1, etag: '"1"' }
    api.getWorkItem.mockResolvedValueOnce(detail()).mockResolvedValueOnce(latest)
    api.deleteWorkItem.mockRejectedValueOnce({ kind: 'response', status: 412,
      error: { code: 'VERSION_CONFLICT', message: '版本冲突', requestId: 'request-delete',
        retryable: false, fieldErrors: [] } })
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as {
      detailDraft: { title: string }
      deleteReason: string
      latestConflict?: WorkItemDetail
      openDetail: (item: WorkItemSummary) => Promise<void>
      openDelete: () => void
      deleteWorkItem: () => Promise<void>
    }
    await vm.openDetail(summary())
    vm.detailDraft.title = '本地未保存草稿'
    vm.openDelete(); vm.deleteReason = '准备删除'
    await vm.deleteWorkItem(); await flushPromises()
    expect(api.deleteWorkItem).toHaveBeenCalledTimes(1)
    expect(vm.detailDraft.title).toBe('本地未保存草稿')
    expect(vm.latestConflict?.title).toBe('服务器新版本')
    wrapper.unmount()
  })

  it('终态或只读详情不显示状态迁移入口', async () => {
    api.getWorkItem.mockResolvedValue({
      ...detail('安全纯文本', []),
      capabilities: { canEditFields: false, canMoveInKanban: false, canMoveInProjectOrder: false,
        canDiscuss: false, canDelete: false, canRestore: false, availableTransitions: [] },
    })
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as { openDetail: (item: WorkItemSummary) => Promise<void> }
    await vm.openDetail(summary()); await flushPromises()
    expect(wrapper.findAll('button').some(button => button.text().includes('变更状态'))).toBe(false)
    wrapper.unmount()
  })

  it('自然日倒置在客户端阻止提交', async () => {
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as {
      detailDraft: { timelineStartDate: string; timelineEndDate: string }
      openDetail: (item: WorkItemSummary) => Promise<void>
      saveWorkItem: () => Promise<void>
    }
    await vm.openDetail(summary())
    vm.detailDraft.timelineStartDate = '2026-08-29'
    vm.detailDraft.timelineEndDate = '2026-08-28'
    await vm.saveWorkItem()
    expect(api.updateWorkItem).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('计划结束日不得早于计划开始日')
    wrapper.unmount()
  })

  it('Company Admin 明示只读原因并隐藏创建入口', async () => {
    api.getProject.mockResolvedValue({ ...project, actorAccess: ProjectActorAccess.CompanyAdmin })
    api.listContentWorkItems.mockResolvedValue(page([]))
    const wrapper = mountView(); await flushPromises()
    expect(wrapper.text()).toContain('Company Admin 在 Project 工作区中保持只读')
    expect(wrapper.text()).not.toContain('创建第一条工作项')
    expect(wrapper.text()).not.toContain('保存为共享默认')
    expect(wrapper.findAll('button').some(button => button.text().includes('创建工作项'))).toBe(false)
    wrapper.unmount()
  })
})
