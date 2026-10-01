import {
  AccountStatus, AuthenticationClientType, AuthenticationRole, ClientCompatibility, EmploymentStatus,
  ProjectActorAccess, ProjectLifecycle, ProjectLifecycleFilter,
    type CurrentAuthentication, type Member,
  type Project, type ProjectPage, type ProjectSummary,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { ElPopover } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSession } from '../../composables/useSession'
import ProjectsView from './ProjectsView.vue'

const push = vi.hoisted(() => vi.fn())
const api = vi.hoisted(() => ({
  listProjects: vi.fn(), listProjectOwnerOptions: vi.fn(), createProject: vi.fn(),
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('../../api/client', () => ({
  projectsApi: { listProjects: api.listProjects, listProjectOwnerOptions: api.listProjectOwnerOptions, createProject: api.createProject },
}))
vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(), readCsrfToken: () => 'csrf-token',
}))

const now = new Date('2026-08-23T02:30:00Z')
const capabilities = {
  canUpdateSettings: true,  canManageMembers: true, canReassignOwner: true,
  canArchive: true, canRestore: false,
  canMoveWorkspace: false, canOverrideArchive: true,
}
const summary: ProjectSummary = {
  id: 'project-1', workspaceId: 'workspace-main', workspaceCode: 'MAIN', workspaceName: '主工作空间',
  code: 'YP_01', name: '研发门户升级',
  lifecycle: ProjectLifecycle.Active, ownerUserId: 'owner-1', ownerDisplayName: '负责人甲',
  actorAccess: ProjectActorAccess.Owner, capabilities, rowVersion: 1, etag: '"1"',
  createdAt: now, updatedAt: now,
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
    api.createProject.mockResolvedValue({ id: 'project-created' } as Project)
  })

  afterEach(() => vi.useRealTimers())

  it('默认请求全部生命周期并按精确列序展示单表', async () => {
    useSession().authentication.value = authentication(AuthenticationRole.CompanyMember)
    const wrapper = mount(ProjectsView, { attachTo: document.body })
    await flushPromises()
    expect(api.listProjects).toHaveBeenCalledWith({ lifecycle: ProjectLifecycleFilter.All, page: 0, size: 20 })
    expect(wrapper.findAll('.project-management-table th').map(cell => cell.text())).toEqual([
      '项目名称', '状态', '负责人', '创建时间', '最后修改时间', '我的角色',
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
    const vm = wrapper.vm as unknown as { ownerUserIds: string[]; refreshForFilters: () => void }
    vm.ownerUserIds = ['owner-1']
    vm.refreshForFilters()
    await flushPromises()
    expect(api.listProjects).toHaveBeenLastCalledWith(expect.objectContaining({
      ownerUserIds: ['owner-1'],
      lifecycle: ProjectLifecycleFilter.All,
    }))
  })

  it('生命周期筛选只提供全部、进行中和已归档并同时过滤最近项目', async () => {
    useSession().authentication.value = authentication(AuthenticationRole.CompanyMember)
    const wrapper = mount(ProjectsView, { attachTo: document.body })
    await flushPromises()
    const vm = wrapper.vm as unknown as { lifecycle: ProjectLifecycleFilter; refreshForFilters: () => void; recentProjectItems: unknown[]; clearFilters: () => void }
    await wrapper.get('.project-name-cell__link').trigger('click')
    expect(vm.recentProjectItems).toHaveLength(1)
    vm.lifecycle = ProjectLifecycleFilter.Archived
    vm.refreshForFilters()
    await flushPromises()
    expect(api.listProjects).toHaveBeenLastCalledWith(expect.objectContaining({ lifecycle: ProjectLifecycleFilter.Archived, page: 0 }))
    expect(vm.recentProjectItems).toHaveLength(0)
    vm.clearFilters()
    await flushPromises()
    expect(api.listProjects).toHaveBeenLastCalledWith(expect.objectContaining({ lifecycle: ProjectLifecycleFilter.All }))
    expect(vm.recentProjectItems).toHaveLength(1)
    wrapper.unmount()
  })

  it('Escape 会关闭传送到页面根部的筛选浮层', async () => {
    useSession().authentication.value = authentication(AuthenticationRole.CompanyMember)
    const wrapper = mount(ProjectsView, { attachTo: document.body })
    await flushPromises()
    wrapper.findComponent(ElPopover).vm.$emit('update:visible', true)
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

  it('普通成员能在当前页打开创建弹窗，成功后记录最近项目并跳转', async () => {
    useSession().authentication.value = authentication(AuthenticationRole.CompanyMember)
    const wrapper = mount(ProjectsView, { attachTo: document.body })
    await flushPromises()
    const dialog = wrapper.findComponent({ name: 'ProjectCreateDialog' })
    const create = wrapper.findAll('button').find(button => button.text().includes('创建项目'))!
    await create.trigger('click')
    expect(dialog.props('modelValue')).toBe(true)
    expect(push).not.toHaveBeenCalled()
    dialog.vm.$emit('created', { ...summary, id: 'project-created', description: null })
    await flushPromises()
    expect(push).toHaveBeenCalledWith({ name: 'project-overview', params: { projectId: 'project-created' } })
    expect(window.localStorage.getItem('yumpoo.projects.recents.v1.company-1%3Auser-1')).toContain('project-created')
    expect(wrapper.text()).not.toContain('项目类型')
    wrapper.unmount()
  })
})
