import {
  AuthenticationClientType,
  AuthenticationRole,
  ClientCompatibility,
  CurrentAuthenticationCompanyWeekStartDayEnum,
  ProjectActorAccess,
  ProjectLifecycle,
  ProjectLifecycleFilter,
  ProjectType,
  type CurrentAuthentication,
  type ProjectPage,
  type ProjectSummary,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSession } from '../composables/useSession'
import AppShell from './AppShell.vue'

const route = vi.hoisted(() => ({
  name: 'workspace',
  meta: { shellSection: 'work' },
  params: { workspaceSlug: 'member' } as Record<string, string>,
  fullPath: '/workspace/member',
}))
const push = vi.hoisted(() => vi.fn())
const api = vi.hoisted(() => ({ listProjects: vi.fn() }))

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ push }),
}))
vi.mock('../api/client', () => ({ projectsApi: { listProjects: api.listProjects } }))

const capabilities = {
  canUpdateSettings: true,
  canActivate: false,
  canManageMembers: true,
  canReassignOwner: true,
  canManageProductLinks: true,
  canArchive: true,
  canRestore: false,
  canMoveWorkspace: false,
  canOverrideArchive: true,
}

function project(index: number): ProjectSummary {
  return {
    id: `project-${index}`,
    workspaceId: 'workspace-main',
    workspaceCode: 'MAIN',
    workspaceName: '主工作空间',
    code: `YP_${String(index).padStart(2, '0')}`,
    name: `项目 ${index}`,
    projectType: ProjectType.ProductDevelopment,
    lifecycle: ProjectLifecycle.Active,
    ownerUserId: 'owner-1',
    ownerDisplayName: '负责人甲',
    actorAccess: ProjectActorAccess.Owner,
    capabilities,
    rowVersion: 1,
    etag: '"1"',
    createdAt: new Date('2026-08-23T02:30:00Z'),
    updatedAt: new Date('2026-08-23T02:30:00Z'),
  }
}

const authentication: CurrentAuthentication = {
  user: { id: 'user-1', displayName: '测试用户', workspaceSlug: 'member' },
  company: {
    id: 'company-1',
    displayName: '测试公司',
    timezone: 'Asia/Shanghai',
    weekStartDay: CurrentAuthenticationCompanyWeekStartDayEnum.Monday,
  },
  roles: new Set([AuthenticationRole.CompanyMember]),
  client: { type: AuthenticationClientType.Web, compatibility: ClientCompatibility.Supported },
}

describe('项目侧栏导航', () => {
  beforeEach(() => {
    window.localStorage.clear()
    push.mockReset()
    api.listProjects.mockReset()
    useSession().authentication.value = authentication
    const items = Array.from({ length: 11 }, (_, index) => project(index + 1))
    api.listProjects.mockResolvedValue({ items, page: 0, size: 11, totalElements: 11, totalPages: 1 } satisfies ProjectPage)
  })

  afterEach(() => vi.useRealTimers())

  it('箭头紧跟标题并在管理入口后展示最多十个项目与省略号', async () => {
    const wrapper = mount(AppShell, { global: { stubs: { RouterView: true } } })
    await flushPromises()

    expect(api.listProjects).toHaveBeenCalledWith({
      lifecycle: ProjectLifecycleFilter.All,
      page: 0,
      size: 11,
    })
    const toggle = wrapper.get('.context-navigation .project-navigation__toggle')
    expect(wrapper.get('.workspace-navigation-heading__identity').text()).toBe('工作台')
    expect(wrapper.findAll('.module-rail__item-label').map(item => item.text())).toEqual(['工作台'])
    expect(toggle.element.children[0]?.textContent).toBe('项目')
    expect(toggle.element.children[1]?.tagName).toBe('svg')
    const projects = wrapper.findAll('#desktop-project-navigation .project-navigation__project')
    expect(projects).toHaveLength(10)
    expect(projects.map(item => item.text())).toEqual(Array.from({ length: 10 }, (_, index) => `项目 ${index + 1}`))
    expect(projects.every(item => item.find('svg').exists())).toBe(true)
    expect(projects.every(item => item.attributes('title') === undefined)).toBe(true)
    expect(wrapper.findAll('#desktop-project-navigation .project-navigation__more svg circle')).toHaveLength(3)

    await projects[0]?.trigger('click')
    expect(push).toHaveBeenCalledWith({ name: 'project-overview', params: { projectId: 'project-1' } })
    wrapper.unmount()
  })

  it('将标题行切换为自动聚焦搜索，并在 250ms 后按名称或编码查询', async () => {
    vi.useFakeTimers()
    const wrapper = mount(AppShell, { attachTo: document.body, global: { stubs: { RouterView: true } } })
    await flushPromises()

    await wrapper.get('button[aria-label="展开工作台菜单"]').trigger('click')
    await wrapper.get('button[aria-label="搜索工作台项目"]').trigger('click')
    await flushPromises()
    const input = wrapper.get('.context-navigation input[aria-label="搜索项目名称或编码"]')
    expect(document.activeElement).toBe(input.element)

    await input.setValue('YP_02')
    expect(api.listProjects).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(249)
    expect(api.listProjects).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()
    expect(api.listProjects).toHaveBeenLastCalledWith({
      query: 'YP_02',
      lifecycle: ProjectLifecycleFilter.All,
      page: 0,
      size: 11,
    })

    await input.trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(wrapper.find('.context-navigation .workspace-navigation-search').exists()).toBe(false)
    expect(wrapper.get('.context-navigation .workspace-navigation-heading').text()).toContain('工作台')
    wrapper.unmount()
  })

  it('收起侧栏时退出搜索、下移一级导航并提供独立展开按钮', async () => {
    const wrapper = mount(AppShell, { global: { stubs: { RouterView: true } } })
    await flushPromises()
    await wrapper.get('button[aria-label="搜索工作台项目"]').trigger('click')
    const vm = wrapper.vm as unknown as { collapseContextNavigation: () => void }
    vm.collapseContextNavigation()
    await flushPromises()

    expect(wrapper.classes()).not.toContain('app-shell--context-open')
    expect(wrapper.find('.context-navigation .workspace-navigation-search').exists()).toBe(false)
    expect(wrapper.get('.module-rail__items').classes()).toContain('module-rail__items--shifted')
    const expand = wrapper.get('button[aria-label="展开工作台菜单"]')
    expect(expand.attributes('tabindex')).toBe('0')
    await expand.trigger('click')
    expect(wrapper.classes()).toContain('app-shell--context-open')
    wrapper.unmount()
  })

  it('较早完成的搜索响应不会覆盖较新的结果', async () => {
    const wrapper = mount(AppShell, { global: { stubs: { RouterView: true } } })
    await flushPromises()
    api.listProjects.mockReset()

    let resolveOlder!: (page: ProjectPage) => void
    let resolveNewer!: (page: ProjectPage) => void
    api.listProjects
      .mockImplementationOnce(() => new Promise<ProjectPage>(resolve => { resolveOlder = resolve }))
      .mockImplementationOnce(() => new Promise<ProjectPage>(resolve => { resolveNewer = resolve }))
    const vm = wrapper.vm as unknown as { loadNavigationProjects: (query: string) => Promise<void> }
    const older = vm.loadNavigationProjects('旧查询')
    const newer = vm.loadNavigationProjects('新查询')

    resolveNewer({ items: [project(2)], page: 0, size: 11, totalElements: 1, totalPages: 1 })
    await newer
    resolveOlder({ items: [project(1)], page: 0, size: 11, totalElements: 1, totalPages: 1 })
    await older
    await flushPromises()

    expect(wrapper.findAll('#desktop-project-navigation .project-navigation__project').map(item => item.text()))
      .toEqual(['项目 2'])
    wrapper.unmount()
  })
})
