import {
  AccountStatus,
  AuthenticationClientType,
  AuthenticationRole,
  ClientCompatibility,
  EmploymentStatus,
  ProjectActorAccess,
  ProjectLifecycle,
  ProjectTemplateKey,
  ProjectTemplateVersionStatus,
  ProjectType,
  WorkspaceStatus,
  type CurrentAuthentication,
  type Member,
  type Project,
  type ProjectPage,
  type ProjectSummary,
  type ProjectTemplateVersion,
  type Workspace,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useSession } from '../../composables/useSession'
import ProjectsView from './ProjectsView.vue'

const push = vi.hoisted(() => vi.fn())
const api = vi.hoisted(() => ({
  listProjects: vi.fn(),
  createProject: vi.fn(),
  listWorkspaces: vi.fn(),
  listProjectTemplates: vi.fn(),
  listMembers: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
}))
vi.mock('../../api/client', () => ({
  projectsApi: { listProjects: api.listProjects, createProject: api.createProject },
  workspacesApi: { listWorkspaces: api.listWorkspaces },
  projectTemplatesApi: { listProjectTemplates: api.listProjectTemplates },
  identityAdministrationApi: { listMembers: api.listMembers },
}))
vi.mock('@yumpoo/api-client', async (importOriginal) => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))

const workspace: Workspace = {
  id: 'workspace-1', code: 'WSP', name: '产品工作区', description: null,
  sortOrder: 1, status: WorkspaceStatus.Active, visibleProjectCount: 1, rowVersion: 1,
}
const deliveryWorkspace: Workspace = {
  ...workspace,
  id: 'workspace-2', code: 'DELIVERY', name: '交付工作区', sortOrder: 2,
}
const summary: ProjectSummary = {
  id: 'project-1', workspaceId: workspace.id, workspaceCode: workspace.code,
  workspaceName: workspace.name, code: 'YP-01', name: '研发门户升级',
  projectType: ProjectType.ProductDevelopment, lifecycle: ProjectLifecycle.Active,
  ownerUserId: 'owner-1', ownerDisplayName: '负责人甲', actorAccess: ProjectActorAccess.Owner,
  capabilities: {
    canUpdateSettings: true, canActivate: false, canManageMembers: true, canReassignOwner: true,
  },
  rowVersion: 1, etag: '"v1"',
}
const archivedSummary: ProjectSummary = {
  ...summary,
  id: 'project-2', workspaceId: deliveryWorkspace.id, workspaceCode: deliveryWorkspace.code,
  workspaceName: deliveryWorkspace.name, code: 'YP-02', name: '客户交付复盘',
  projectType: ProjectType.Implementation, lifecycle: ProjectLifecycle.Archived,
  actorAccess: ProjectActorAccess.Member, rowVersion: 2, etag: '"v2"',
}
const template: ProjectTemplateVersion = {
  templateVersionId: 'template-1', templateKey: ProjectTemplateKey.Rnd, version: 3,
  versionCode: 'v3', projectType: ProjectType.ProductDevelopment, displayName: '产品研发',
  lifecycleStatus: ProjectTemplateVersionStatus.Published, rowVersion: 1,
  publishedAt: new Date(), retiredAt: null, contentBlueprints: [], statuses: [], transitions: [],
}
const owner: Member = {
  userId: 'owner-1', displayName: '负责人甲', externalUserId: 'wecom-owner', email: null,
  mobile: null, departmentSummary: '产品研发', employmentStatus: EmploymentStatus.Active,
  accountStatus: AccountStatus.Enabled, directorySyncedAt: new Date(), leftAt: null,
  accountDisabledAt: null, accountDisabledByUserId: null, platformRoles: new Set(),
  authorizationVersion: 1, rowVersion: 1, etag: '"v1"',
}

function projectPage(items: ProjectSummary[] = [summary]): ProjectPage {
  return { items, page: 0, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0 }
}

function authentication(role: AuthenticationRole): CurrentAuthentication {
  return {
    user: { id: 'user-1', displayName: '测试用户' },
    company: { id: 'company-1', displayName: '测试公司', timezone: 'Asia/Shanghai', weekStartDay: 'MONDAY' },
    roles: new Set([role]),
    client: { type: AuthenticationClientType.Web, compatibility: ClientCompatibility.Supported },
  } as CurrentAuthentication
}

function mountView() {
  return mount(ProjectsView, { attachTo: document.body })
}

describe('项目目录 Workspace 式重构', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    Object.values(api).forEach(mock => mock.mockReset())
    push.mockReset()
    api.listProjects.mockResolvedValue(projectPage())
    api.listWorkspaces.mockResolvedValue({ items: [workspace] })
    api.listProjectTemplates.mockResolvedValue({ items: [template] })
    api.listMembers.mockResolvedValue({ items: [owner], page: 0, size: 100, totalElements: 1, totalPages: 1 })
    api.createProject.mockResolvedValue({ id: 'project-created' } as Project)
  })

  it('保留分页与筛选参数，并展示摘要接口已有字段', async () => {
    useSession().authentication.value = authentication(AuthenticationRole.CompanyMember)
    const wrapper = mountView()
    await flushPromises()

    expect(api.listProjects).toHaveBeenCalledWith({ page: 0, size: 20 })
    expect(wrapper.text()).toContain('研发门户升级')
    expect(wrapper.text()).toContain('YP-01')
    expect(wrapper.text()).toContain('产品工作区')
    expect(wrapper.text()).toContain('负责人甲')
    expect(wrapper.text()).not.toContain('创建项目')
    expect(wrapper.find('input[placeholder*="搜索"]').exists()).toBe(false)
    expect(wrapper.findAll('.project-board-group')).toHaveLength(1)
    expect(wrapper.get('.project-board-group__identity').text()).toContain('WSP · 1 个项目')
    expect(wrapper.get('.project-status-column .yp-status-tag').classes()).toContain('yp-status-tag--cell')
    expect(wrapper.get('.project-mobile-list').text()).toContain('活跃')
    expect(wrapper.get('.project-mobile-list').text()).toContain('产品研发')

    const vm = wrapper.vm as unknown as {
      workspaceId?: string
      page: number
      applyFilters: () => void
      load: () => Promise<void>
    }
    vm.workspaceId = workspace.id
    vm.applyFilters()
    await flushPromises()
    expect(api.listProjects).toHaveBeenLastCalledWith({ workspaceId: workspace.id, page: 0, size: 20 })
    vm.page = 1
    await vm.load()
    expect(api.listProjects).toHaveBeenLastCalledWith({ workspaceId: workspace.id, page: 1, size: 20 })
    wrapper.unmount()
  })

  it('按 Workspace 分组项目，并允许独立折叠每个分组', async () => {
    useSession().authentication.value = authentication(AuthenticationRole.CompanyMember)
    api.listProjects.mockResolvedValue(projectPage([summary, archivedSummary]))
    api.listWorkspaces.mockResolvedValue({ items: [workspace, deliveryWorkspace] })
    const wrapper = mountView()
    await flushPromises()

    const groups = wrapper.findAll('.project-board-group')
    expect(groups).toHaveLength(2)
    expect(groups[0]?.text()).toContain('产品工作区')
    expect(groups[1]?.text()).toContain('交付工作区')
    expect(groups[1]?.text()).toContain('已归档 1')

    const firstToggle = groups[0]?.get('.project-board-group__toggle')
    expect(firstToggle?.attributes('aria-expanded')).toBe('true')
    await firstToggle?.trigger('click')
    expect(firstToggle?.attributes('aria-expanded')).toBe('false')
    expect(groups[0]?.find('.project-board-group__content').exists()).toBe(false)
    expect(groups[1]?.find('.project-board-group__content').exists()).toBe(true)
    wrapper.unmount()
  })

  it('管理员创建入口继续提交原有草稿载荷', async () => {
    useSession().authentication.value = authentication(AuthenticationRole.CompanyAdmin)
    const wrapper = mountView()
    await flushPromises()

    await wrapper.findAll('button').find(button => button.text().includes('创建项目'))?.trigger('click')
    await nextTick()
    const vm = wrapper.vm as unknown as {
      createForm: {
        workspaceId: string
        templateVersionId: string
        ownerUserId: string
        code: string
        name: string
      }
      createProject: () => Promise<void>
    }
    Object.assign(vm.createForm, {
      workspaceId: workspace.id,
      templateVersionId: template.templateVersionId,
      ownerUserId: owner.userId,
      code: ' yp-02 ',
      name: ' 新产品门户 ',
    })
    await vm.createProject()
    await flushPromises()

    expect(api.createProject).toHaveBeenCalledOnce()
    expect(api.createProject.mock.calls[0]?.[0]).toMatchObject({
      xXSRFTOKEN: 'csrf-token',
      projectCreateRequest: {
        workspaceId: workspace.id,
        code: 'YP-02',
        name: '新产品门户',
        projectType: ProjectType.ProductDevelopment,
        ownerUserId: owner.userId,
        templateKey: ProjectTemplateKey.Rnd,
        templateVersion: 3,
      },
    })
    expect(push).toHaveBeenCalledWith({ name: 'project-overview', params: { projectId: 'project-created' } })
    wrapper.unmount()
  })

  it('无数据时在列表表面内显示紧凑空态', async () => {
    useSession().authentication.value = authentication(AuthenticationRole.CompanyMember)
    api.listProjects.mockResolvedValue(projectPage([]))
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.get('.project-list-surface .yp-empty-state').classes()).toContain('yp-empty-state--compact')
    wrapper.unmount()
  })
})
