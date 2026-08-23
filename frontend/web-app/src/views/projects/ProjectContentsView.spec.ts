import {
  ContentSortDirection,
  ContentSortField,
  ContentStatus,
  ContentTableColumn,
  ContentViewType,
  ProjectActorAccess,
  ProjectLifecycle,
  WorkItemType,
  WorkflowStatusCategory,
  type Content,
  type ProjectContentCatalog,
  type ProjectDetail,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectContentsView from './ProjectContentsView.vue'

const api = vi.hoisted(() => ({
  getProject: vi.fn(), listProjectContents: vi.fn(), createContent: vi.fn(),
  listProjectMembers: vi.fn(),
  getContent: vi.fn(), updateContent: vi.fn(), archiveContent: vi.fn(), restoreContent: vi.fn(),
}))
const routerPush = vi.hoisted(() => vi.fn())

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { projectId: 'project-1' } }),
  useRouter: () => ({ push: routerPush }),
}))
vi.mock('../../api/client', () => ({
  projectsApi: { getProject: api.getProject, listProjectMembers: api.listProjectMembers },
  contentsApi: {
    listProjectContents: api.listProjectContents, createContent: api.createContent,
    getContent: api.getContent, updateContent: api.updateContent,
    archiveContent: api.archiveContent, restoreContent: api.restoreContent,
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

function content(etag = '"0"'): Content {
  return {
    id: 'content-1', projectId: 'project-1', code: 'REQ_CORE', name: '核心需求', description: null,
    workItemType: WorkItemType.Requirement, status: ContentStatus.Active,
    defaultViewType: ContentViewType.Table,
    viewConfig: {
      table: {
        columnOrder: new Set(Object.values(ContentTableColumn)),
        hiddenColumns: new Set([ContentTableColumn.Description]),
        sort: [{ field: ContentSortField.UpdatedAt, direction: ContentSortDirection.Desc }],
        filters: { query: null, statusCodes: new Set(), priorities: new Set(),
          assigneeUserIds: new Set(), dueFrom: null, dueTo: null, updatedAfter: null },
      },
      kanban: { statusGroups: [{ name: '待办', statusCodes: new Set(['TODO']) }] },
    },
    appliedTemplateKey: 'SOFTWARE_STANDARD', appliedTemplateVersion: 1,
    appliedBlueprintCode: 'REQUIREMENT', rowVersion: Number(etag.replace(/\D/g, '')), etag,
    createdAt: new Date(), createdByUserId: 'owner-1', updatedAt: new Date(),
    updatedByUserId: 'owner-1', archivedAt: null, archivedByUserId: null,
  }
}

function catalog(canCreate = true): ProjectContentCatalog {
  return {
    items: [content()], canCreate,
    blueprintOptions: [{ blueprintCode: 'REQUIREMENT', displayName: '需求',
      workItemType: WorkItemType.Requirement, defaultViewType: ContentViewType.Table }],
    workflowStatusOptions: [{ statusCode: 'TODO', displayName: '待办', statusCategory: WorkflowStatusCategory.Todo,
      sortOrder: 1, initial: true, terminal: false }],
  }
}

function mountView() { return mount(ProjectContentsView, { attachTo: document.body }) }

describe('M2-09 Content 管理页', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    Object.values(api).forEach(mock => mock.mockReset())
    routerPush.mockReset()
    api.getProject.mockResolvedValue(project)
    api.listProjectContents.mockResolvedValue(catalog())
    api.listProjectMembers.mockResolvedValue({
      items: [], page: 0, size: 100, totalElements: 0, totalPages: 0,
    })
    api.createContent.mockResolvedValue(content())
    api.updateContent.mockResolvedValue(content('"1"'))
    api.getContent.mockResolvedValue(content('"1"'))
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  })

  it('从固定蓝图创建且不提交客户端工作项类型', async () => {
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as { createForm: { code: string; name: string; description: string; blueprintCode: string }; createContent: () => Promise<void> }
    vm.createForm = { code: 'req_extra', name: '额外需求', description: '', blueprintCode: 'REQUIREMENT' }
    await vm.createContent()
    expect(api.createContent).toHaveBeenCalledWith(expect.objectContaining({
      projectId: 'project-1', contentCreateRequest: {
        code: 'REQ_EXTRA', name: '额外需求', description: null, blueprintCode: 'REQUIREMENT',
      },
    }))
    expect(api.createContent.mock.calls[0]?.[0].contentCreateRequest).not.toHaveProperty('workItemType')
    wrapper.unmount()
  })

  it('Content 名称进入工作项深链，配置操作仍留在目录页', async () => {
    const wrapper = mountView(); await flushPromises()
    await wrapper.find('.content-name-button').trigger('click')
    expect(routerPush).toHaveBeenCalledWith({
      name: 'content-work-items', params: { projectId: 'project-1', contentId: 'content-1' },
    })
    expect(wrapper.text()).toContain('配置')
    wrapper.unmount()
  })

  it('只读角色显示原因并隐藏新建入口', async () => {
    api.getProject.mockResolvedValue({ ...project, actorAccess: ProjectActorAccess.Member })
    api.listProjectContents.mockResolvedValue(catalog(false))
    const wrapper = mountView(); await flushPromises()
    expect(wrapper.text()).toContain('当前角色拥有 Content 只读权限')
    expect(wrapper.text()).not.toContain('新建 Content')
    wrapper.unmount()
  })

  it('412 时保留草稿并读取最新 ETag，禁止普通保存', async () => {
    const conflict = { kind: 'response', status: 412, error: {
      code: 'VERSION_CONFLICT', message: '冲突', requestId: 'request-1', retryable: false,
      timestamp: new Date(), fieldErrors: [], details: {},
    } }
    api.updateContent.mockRejectedValue(conflict)
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as { openDrawer: (value: Content) => void; draftName: string; save: () => Promise<void>; latestConflict?: Content }
    vm.openDrawer(content())
    vm.draftName = '我的草稿'
    await vm.save()
    expect(vm.draftName).toBe('我的草稿')
    expect(api.getContent).toHaveBeenCalledWith({ contentId: 'content-1' })
    expect(vm.latestConflict?.etag).toBe('"1"')
    wrapper.unmount()
  })

  it('空目录结束加载后显示可创建空态', async () => {
    api.listProjectContents.mockResolvedValue({ ...catalog(), items: [] })
    const wrapper = mountView(); await flushPromises()
    expect(wrapper.text()).toContain('当前 Project 尚无 Content')
    expect(wrapper.text()).toContain('新建 Content')
    wrapper.unmount()
  })

  it('编辑配置支持键盘按钮对应的列移动并提交完整替换', async () => {
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as {
      openDrawer: (value: Content) => void
      moveColumn: (index: number, offset: number) => void
      draftName: string
      draftConfig: Content['viewConfig']
      save: () => Promise<void>
    }
    vm.openDrawer(content())
    const first = Array.from(vm.draftConfig.table.columnOrder)[0]
    vm.moveColumn(0, 1)
    expect(Array.from(vm.draftConfig.table.columnOrder)[1]).toBe(first)
    vm.draftName = '完整替换后的名称'
    await vm.save()
    expect(api.updateContent).toHaveBeenCalledWith(expect.objectContaining({
      contentId: 'content-1', ifMatch: '"0"',
      contentUpdateRequest: expect.objectContaining({ name: '完整替换后的名称', viewConfig: vm.draftConfig }),
    }))
    expect(wrapper.find('[aria-label="上移列"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="下移列"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('归档与恢复都携带当前 ETag 和独立幂等键', async () => {
    api.archiveContent.mockResolvedValue({ ...content('"1"'), status: ContentStatus.Archived })
    api.restoreContent.mockResolvedValue(content('"2"'))
    const wrapper = mountView(); await flushPromises()
    const vm = wrapper.vm as unknown as { transition: (value: Content) => Promise<void> }
    await vm.transition(content())
    await vm.transition({ ...content('"1"'), status: ContentStatus.Archived })
    expect(api.archiveContent).toHaveBeenCalledWith(expect.objectContaining({
      contentId: 'content-1', ifMatch: '"0"', idempotencyKey: expect.any(String),
    }))
    expect(api.restoreContent).toHaveBeenCalledWith(expect.objectContaining({
      contentId: 'content-1', ifMatch: '"1"', idempotencyKey: expect.any(String),
    }))
    expect(api.archiveContent.mock.calls[0]?.[0].idempotencyKey)
      .not.toBe(api.restoreContent.mock.calls[0]?.[0].idempotencyKey)
    wrapper.unmount()
  })
})
