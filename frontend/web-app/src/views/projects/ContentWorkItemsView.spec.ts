import {
  ContentStatus,
  ContentTableColumn,
  ContentViewType,
  ProjectActorAccess,
  ProjectLifecycle,
  ProjectMembershipStatus,
  ProjectMembershipStatusFilter,
  WorkItemPriority,
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
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ContentWorkItemsView from './ContentWorkItemsView.vue'

const api = vi.hoisted(() => ({
  getProject: vi.fn(),
  listProjectMembers: vi.fn(),
  listProjectContents: vi.fn(),
  listContentWorkItems: vi.fn(),
  createWorkItem: vi.fn(),
  getWorkItem: vi.fn(),
  updateWorkItem: vi.fn(),
  transitionWorkItem: vi.fn(),
}))
const routerPush = vi.hoisted(() => vi.fn())

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { projectId: 'project-1', contentId: 'content-1' } }),
  useRouter: () => ({ push: routerPush }),
}))
vi.mock('../../api/client', () => ({
  projectsApi: { getProject: api.getProject, listProjectMembers: api.listProjectMembers },
  contentsApi: { listProjectContents: api.listProjectContents },
  workItemsApi: {
    listContentWorkItems: api.listContentWorkItems,
    createWorkItem: api.createWorkItem,
    getWorkItem: api.getWorkItem,
    updateWorkItem: api.updateWorkItem,
    transitionWorkItem: api.transitionWorkItem,
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
        sortOrder: 10, initial: true, terminal: false },
      { statusCode: 'IN_PROGRESS', displayName: '进行中', statusCategory: WorkflowStatusCategory.InProgress,
        sortOrder: 20, initial: false, terminal: false },
      { statusCode: 'READY', displayName: '就绪', statusCategory: WorkflowStatusCategory.InProgress,
        sortOrder: 30, initial: false, terminal: false },
      { statusCode: 'DONE', displayName: '已完成', statusCategory: WorkflowStatusCategory.Done,
        sortOrder: 40, initial: false, terminal: true },
    ],
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
    rowVersion: 0, etag: '"0"', capabilities: { canEditFields: true, availableTransitions },
    createdAt: new Date('2026-08-22T01:00:00Z'),
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
    routerPush.mockReset()
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
    api.listContentWorkItems.mockResolvedValue(page([summary()]))
    api.createWorkItem.mockResolvedValue(detail())
    api.getWorkItem.mockResolvedValue(detail())
    api.updateWorkItem.mockResolvedValue({ ...detail(), rowVersion: 1, etag: '"1"' })
    api.transitionWorkItem.mockResolvedValue({
      ...detail(), statusCode: 'READY', statusCategory: WorkItemStatusCategory.InProgress,
      rowVersion: 1, etag: '"1"', capabilities: { canEditFields: true, availableTransitions: [] },
    })
  })

  it('使用服务端分页并呈现全部协作字段列', async () => {
    const wrapper = mountView(); await flushPromises()
    expect(api.listContentWorkItems).toHaveBeenCalledWith({ contentId: 'content-1', page: 0, size: 20 })
    expect(api.listProjectMembers).toHaveBeenCalledWith({ projectId: 'project-1',
      status: ProjectMembershipStatusFilter.Active, page: 0, size: 100 })
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

  it('创建请求提交完整八字段快照并用 UTC 保持自然日', async () => {
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as {
      createForm: { title: string; priority: WorkItemPriority; assigneeUserId: string;
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
        title: '新任务', priority: WorkItemPriority.Medium, assigneeUserId: 'owner-1',
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

  it('只读 Kanban 按状态组独立请求并展示同一真源卡片', async () => {
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
    expect(wrapper.text()).toContain('待办')
    expect(wrapper.text()).toContain('进行中')
    expect(wrapper.text()).toContain('实现核心闭环')
    expect(wrapper.text()).toContain('开发中')
    expect(wrapper.text()).not.toContain('拖拽')
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

  it('终态或只读详情不显示状态迁移入口', async () => {
    api.getWorkItem.mockResolvedValue({
      ...detail('安全纯文本', []),
      capabilities: { canEditFields: false, availableTransitions: [] },
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
    expect(wrapper.findAll('button').some(button => button.text().includes('创建工作项'))).toBe(false)
    wrapper.unmount()
  })
})
