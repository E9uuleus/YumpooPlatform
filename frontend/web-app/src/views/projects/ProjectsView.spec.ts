import {
  AccountStatus, AuthenticationClientType, AuthenticationRole, ClientCompatibility, EmploymentStatus,
  ProjectActorAccess, ProjectLifecycle, ProjectLifecycleFilter, ProjectTemplateKey,
  ProjectTemplateVersionStatus, ProjectType, type CurrentAuthentication, type Member,
  type Project, type ProjectPage, type ProjectSummary, type ProjectTemplateVersion,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSession } from '../../composables/useSession'
import ProjectsView from './ProjectsView.vue'

const push = vi.hoisted(() => vi.fn())
const api = vi.hoisted(() => ({
  listProjects: vi.fn(), listProjectOwnerOptions: vi.fn(), createProject: vi.fn(),
  listProjectTemplates: vi.fn(), listMembers: vi.fn(),
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('../../api/client', () => ({
  projectsApi: { listProjects: api.listProjects, listProjectOwnerOptions: api.listProjectOwnerOptions, createProject: api.createProject },
  projectTemplatesApi: { listProjectTemplates: api.listProjectTemplates },
  identityAdministrationApi: { listMembers: api.listMembers },
}))
vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(), readCsrfToken: () => 'csrf-token',
}))

const now = new Date('2026-08-23T02:30:00Z')
const capabilities = {
  canUpdateSettings: true, canActivate: false, canManageMembers: true, canReassignOwner: true,
  canManageProductLinks: true, canArchive: true, canRestore: false, canOverrideArchive: true,
}
const summary: ProjectSummary = {
  id: 'project-1', workspaceId: 'workspace-main', workspaceCode: 'MAIN', workspaceName: '主工作空间',
  code: 'YP_01', name: '研发门户升级', projectType: ProjectType.ProductDevelopment,
  lifecycle: ProjectLifecycle.Active, ownerUserId: 'owner-1', ownerDisplayName: '负责人甲',
  actorAccess: ProjectActorAccess.Owner, capabilities, rowVersion: 1, etag: '"1"',
  createdAt: now, updatedAt: now,
}
const template: ProjectTemplateVersion = {
  templateVersionId: 'template-1', templateKey: ProjectTemplateKey.Rnd, version: 3,
  versionCode: 'v3', projectType: ProjectType.ProductDevelopment, displayName: '产品研发',
  lifecycleStatus: ProjectTemplateVersionStatus.Published, rowVersion: 1,
  publishedAt: now, retiredAt: null, contentBlueprints: [], statuses: [], transitions: [],
}
const owner: Member = {
  userId: 'owner-1', displayName: '负责人甲', externalUserId: 'wecom-owner', email: null,
  mobile: null, departmentSummary: '产品研发', employmentStatus: EmploymentStatus.Active,
  accountStatus: AccountStatus.Enabled, directorySyncedAt: now, leftAt: null,
  accountDisabledAt: null, accountDisabledByUserId: null, platformRoles: new Set(),
  authorizationVersion: 1, rowVersion: 1, etag: '"1"',
}
const page = (items: ProjectSummary[] = [summary]): ProjectPage => ({
  items, page: 0, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0,
})
const authentication = (role: AuthenticationRole): CurrentAuthentication => ({
  user: { id: 'user-1', displayName: '测试用户', workspaceSlug: 'member' },
  company: { id: 'company-1', displayName: '测试公司', timezone: 'Asia/Shanghai', weekStartDay: 'MONDAY' },
  roles: new Set([role]),
  client: { type: AuthenticationClientType.Web, compatibility: ClientCompatibility.Supported },
} as CurrentAuthentication)

describe('项目管理页', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    window.localStorage.clear()
    push.mockReset()
    Object.values(api).forEach(mock => mock.mockReset())
    api.listProjects.mockResolvedValue(page())
    api.listProjectOwnerOptions.mockResolvedValue([{ userId: owner.userId, displayName: owner.displayName }])
    api.listProjectTemplates.mockResolvedValue({ items: [template] })
    api.listMembers.mockResolvedValue({ items: [owner], page: 0, size: 100, totalElements: 1, totalPages: 1 })
    api.createProject.mockResolvedValue({ id: 'project-created' } as Project)
  })

  afterEach(() => vi.useRealTimers())

  it('默认请求全部生命周期并按精确列序展示单表', async () => {
    useSession().authentication.value = authentication(AuthenticationRole.CompanyMember)
    const wrapper = mount(ProjectsView, { attachTo: document.body })
    await flushPromises()
    expect(api.listProjects).toHaveBeenCalledWith({ lifecycle: ProjectLifecycleFilter.All, page: 0, size: 20 })
    expect(wrapper.findAll('.project-management-table th').map(cell => cell.text())).toEqual([
      '项目名称', '状态', '负责人', '项目类型', '创建时间', '最后修改时间', '我的角色',
    ])
    expect(wrapper.get('.project-name-cell').text()).toBe(summary.name)
    expect(wrapper.find('.project-name-cell__code').exists()).toBe(false)
    expect(wrapper.find('.project-management-table .yp-assignee__name').exists()).toBe(false)
    expect(wrapper.get('.project-management-table .yp-assignee').attributes('aria-label')).toBe(summary.ownerDisplayName)
    expect(wrapper.text()).toContain('2026-08-23')
    expect(wrapper.find('.project-board-group').exists()).toBe(false)
    expect(wrapper.findAll('[role="tab"]').map(tab => tab.text())).toEqual(['最近', '内容'])
    expect(wrapper.get('#project-content-tab').attributes('aria-selected')).toBe('true')
    expect(wrapper.find('.project-list-surface > .yp-filter-bar').exists()).toBe(true)
    wrapper.unmount()
  })

  it('最近页按打开记录展示项目并支持置顶', async () => {
    useSession().authentication.value = authentication(AuthenticationRole.CompanyMember)
    const wrapper = mount(ProjectsView, { attachTo: document.body })
    await flushPromises()

    await wrapper.get('.project-name-cell__link').trigger('click')
    expect(push).toHaveBeenCalledWith({ name: 'project-overview', params: { projectId: summary.id } })
    await wrapper.get('#project-recent-tab').trigger('click')

    expect(wrapper.get('#project-recent-tab').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('.project-recent-list').text()).toContain(summary.name)
    expect(wrapper.get('.project-recent-list').text()).not.toContain(summary.code)
    const pin = wrapper.get(`button[aria-label="置顶项目 ${summary.name}"]`)
    expect(pin.attributes('aria-pressed')).toBe('false')
    await pin.trigger('click')
    expect(wrapper.get(`button[aria-label="取消置顶项目 ${summary.name}"]`).attributes('aria-pressed')).toBe('true')
    wrapper.unmount()
  })

  it('筛选即时请求并保持生命周期 ALL', async () => {
    useSession().authentication.value = authentication(AuthenticationRole.CompanyMember)
    const wrapper = mount(ProjectsView)
    await flushPromises()
    const vm = wrapper.vm as unknown as { projectTypes: ProjectType[]; refreshForFilters: () => void }
    vm.projectTypes = [ProjectType.ProductDevelopment, ProjectType.Implementation]
    vm.refreshForFilters()
    await flushPromises()
    expect(api.listProjects).toHaveBeenLastCalledWith(expect.objectContaining({
      projectTypes: [ProjectType.ProductDevelopment, ProjectType.Implementation],
      lifecycle: ProjectLifecycleFilter.All,
    }))
  })

  it('Escape 会关闭传送到页面根部的筛选浮层', async () => {
    useSession().authentication.value = authentication(AuthenticationRole.CompanyMember)
    const wrapper = mount(ProjectsView, { attachTo: document.body })
    await flushPromises()
    await wrapper.get('button[aria-label="展开筛选"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('button[aria-label="展开筛选"]').classes()).toContain('active')
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(wrapper.get('button[aria-label="展开筛选"]').classes()).not.toContain('active')
    wrapper.unmount()
  })

  it('搜索默认折叠，展开后聚焦并按 300ms 防抖查询', async () => {
    vi.useFakeTimers()
    useSession().authentication.value = authentication(AuthenticationRole.CompanyMember)
    const wrapper = mount(ProjectsView, { attachTo: document.body })
    await flushPromises()

    expect(wrapper.find('input[aria-label="搜索项目名称或编码"]').exists()).toBe(false)
    await wrapper.get('button[aria-label="展开搜索"]').trigger('click')
    await flushPromises()
    const input = wrapper.get('input[aria-label="搜索项目名称或编码"]')
    expect(document.activeElement).toBe(input.element)
    await input.setValue('YP_01')
    expect(api.listProjects).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(300)
    await flushPromises()
    expect(api.listProjects).toHaveBeenLastCalledWith(expect.objectContaining({
      query: 'YP_01', lifecycle: ProjectLifecycleFilter.All, page: 0,
    }))
    wrapper.unmount()
  })

  it('创建载荷不再包含 workspaceId', async () => {
    useSession().authentication.value = authentication(AuthenticationRole.CompanyAdmin)
    const wrapper = mount(ProjectsView)
    await flushPromises()
    const vm = wrapper.vm as unknown as { createForm: Record<string, string>; createProject: () => Promise<void> }
    Object.assign(vm.createForm, { templateVersionId: template.templateVersionId, ownerUserId: owner.userId, code: ' yp_02 ', name: ' 新项目 ' })
    await vm.createProject()
    const request = api.createProject.mock.calls[0]?.[0].projectCreateRequest
    expect(request).toMatchObject({ code: 'YP_02', name: '新项目', ownerUserId: owner.userId })
    expect(request).not.toHaveProperty('workspaceId')
  })
})
