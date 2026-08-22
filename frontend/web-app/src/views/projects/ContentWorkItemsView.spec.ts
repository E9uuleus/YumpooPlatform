import {
  ContentStatus,
  ContentTableColumn,
  ContentViewType,
  ProjectActorAccess,
  ProjectLifecycle,
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
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ContentWorkItemsView from './ContentWorkItemsView.vue'

const api = vi.hoisted(() => ({
  getProject: vi.fn(),
  listProjectContents: vi.fn(),
  listContentWorkItems: vi.fn(),
  createWorkItem: vi.fn(),
  getWorkItem: vi.fn(),
}))
const routerPush = vi.hoisted(() => vi.fn())

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { projectId: 'project-1', contentId: 'content-1' } }),
  useRouter: () => ({ push: routerPush }),
}))
vi.mock('../../api/client', () => ({
  projectsApi: { getProject: api.getProject },
  contentsApi: { listProjectContents: api.listProjectContents },
  workItemsApi: {
    listContentWorkItems: api.listContentWorkItems,
    createWorkItem: api.createWorkItem,
    getWorkItem: api.getWorkItem,
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
          ContentTableColumn.Priority, ContentTableColumn.Reporter,
          ContentTableColumn.Description, ContentTableColumn.UpdatedAt,
        ]),
        hiddenColumns: new Set([ContentTableColumn.Description]),
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
    ],
  }
}

function summary(id = 'work-item-1', statusCode = 'BACKLOG', title = '实现核心闭环'): WorkItemSummary {
  return {
    id, projectId: 'project-1', contentId: 'content-1', itemNo: `PROJECT_1-${id.endsWith('2') ? 2 : 1}`,
    type: WorkItemType.Task, title, statusCode,
    statusCategory: statusCode === 'BACKLOG' ? WorkItemStatusCategory.Todo : WorkItemStatusCategory.InProgress,
    priority: WorkItemPriority.Medium, reporterUserId: 'member-1', reporterDisplayName: '项目成员',
    updatedAt: new Date('2026-08-22T02:00:00Z'),
  }
}

function detail(description = '安全纯文本'): WorkItemDetail {
  return {
    ...summary(), description, notes: null,
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
    api.listProjectContents.mockResolvedValue(catalog())
    api.listContentWorkItems.mockResolvedValue(page([summary()]))
    api.createWorkItem.mockResolvedValue(detail())
    api.getWorkItem.mockResolvedValue(detail())
  })

  it('使用服务端分页并按共享配置决定表格列顺序和显隐', async () => {
    const wrapper = mountView(); await flushPromises()
    expect(api.listContentWorkItems).toHaveBeenCalledWith({ contentId: 'content-1', page: 0, size: 20 })
    const text = wrapper.text()
    expect(text).toContain('事项编号')
    expect(text).toContain('标题')
    expect(text).toContain('报告人')
    expect(text).not.toContain('描述安全纯文本')
    expect(text).toContain('PROJECT_1-1')
    wrapper.unmount()
  })

  it('创建请求仅提交 M2-10 字段并明确提交默认中优先级', async () => {
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as {
      createForm: { title: string; priority: WorkItemPriority; description: string; notes: string }
      createWorkItem: () => Promise<void>
    }
    vm.createForm.title = '  新任务  '
    vm.createForm.description = '  描述  '
    vm.createForm.notes = ''
    await vm.createWorkItem()
    const request = api.createWorkItem.mock.calls[0]?.[0]
    expect(request).toEqual({
      contentId: 'content-1', xXSRFTOKEN: 'csrf-token', idempotencyKey: expect.any(String),
      workItemCreateRequest: {
        title: '新任务', priority: WorkItemPriority.Medium, description: '描述', notes: null,
      },
    })
    expect(Object.keys(request.workItemCreateRequest).sort())
      .toEqual(['description', 'notes', 'priority', 'title'])
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

  it('详情抽屉按纯文本呈现描述和备注', async () => {
    const unsafe = '<img src=x onerror=alert(1)>'
    api.getWorkItem.mockResolvedValue(detail(unsafe))
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as { openDetail: (item: WorkItemSummary) => Promise<void> }
    await vm.openDetail(summary()); await flushPromises()
    expect(wrapper.find('.plain-text').text()).toBe(unsafe)
    expect(wrapper.find('.plain-text img').exists()).toBe(false)
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
