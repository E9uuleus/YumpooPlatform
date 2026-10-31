import {
  AuthenticationClientType,
  AuthenticationRole,
  ClientCompatibility,
  CurrentAuthenticationCompanyWeekStartDayEnum,
  ProjectActorAccess,
  ProjectLifecycle,
  ProjectLifecycleFilter,

  type CurrentAuthentication,
  type ProjectPage,
  type ProjectSummary,
} from '@yumpoo/api-client'
import { DOMWrapper, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { useProjectRecents } from '../composables/useProjectRecents'
import { useSession } from '../composables/useSession'
import AppShell from './AppShell.vue'
import { notifyProjectSettingsChanged } from '../composables/projectSettingsEvents'

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
vi.mock('../composables/useReleaseVersion', () => ({ useReleaseVersion: () => ({ value: null }) }))

const capabilities = {
  canUpdateSettings: true,

  canManageMembers: true,
  canReassignOwner: true,
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
    route.params = { workspaceSlug: 'member' }
    route.name = 'workspace'
    route.meta.shellSection = 'work'
    window.localStorage.clear()
    push.mockReset()
    api.listProjects.mockReset()
    useSession().authentication.value = authentication
    const items = Array.from({ length: 11 }, (_, index) => project(index + 1))
    api.listProjects.mockResolvedValue({ items, page: 0, size: 11, totalElements: 11, totalPages: 1 } satisfies ProjectPage)
  })

  afterEach(() => vi.useRealTimers())

  it.each([
    ['work', '工作台'], ['company', '公司管理'], ['team', '团队视图'], ['operations', '运维中心'],
  ])('%s 模块提供名称匹配的收起、展开按钮并恢复原导航', async (section, title) => {
    route.meta.shellSection = section
    const wrapper = mount(AppShell, { global: { stubs: { RouterView: true } } })
    await flushPromises()
    const expand = wrapper.get(`button[aria-label="展开${title}菜单"]`)
    await expand.trigger('click')
    const navigation = wrapper.get('#desktop-context-navigation')
    const contents = navigation.text()
    const collapse = wrapper.get(`button[aria-label="收起${title}菜单"]`)
    expect(collapse.attributes('aria-expanded')).toBe('true')
    await collapse.trigger('click')
    expect(wrapper.classes()).not.toContain('app-shell--context-open')
    expect(navigation.attributes('aria-hidden')).toBe('true')
    expect(navigation.attributes('inert')).toBeDefined()
    expect(expand.attributes('tabindex')).toBe('0')
    await expand.trigger('click')
    expect(wrapper.classes()).toContain('app-shell--context-open')
    expect(navigation.attributes('aria-hidden')).toBe('false')
    expect(navigation.text()).toBe(contents)
    wrapper.unmount()
  })

  it('箭头紧跟标题并在管理入口后展示最多十个项目与省略号', async () => {
    const wrapper = mount(AppShell, { global: { stubs: { RouterView: true } } })
    await flushPromises()

    expect(api.listProjects).toHaveBeenCalledWith({
      lifecycle: ProjectLifecycleFilter.Active,
      ended: false,
      actorAccesses: [ProjectActorAccess.Owner, ProjectActorAccess.Member],
      page: 0,
      size: 11,
    })
    const toggle = wrapper.get('.context-navigation .project-navigation__toggle')
    expect(wrapper.get('.workspace-navigation-heading__identity').text()).toBe('工作台')
    expect(wrapper.findAll('.module-rail__item-label').map(item => item.text())).toEqual(['首页', '工作台'])
    expect(toggle.element.children[0]?.textContent).toBe('项目')
    expect(toggle.element.children[1]?.tagName).toBe('svg')
    const projects = wrapper.findAll('#desktop-project-navigation .project-navigation__project')
    expect(projects).toHaveLength(10)
    expect(projects.map(item => item.text())).toEqual(Array.from({ length: 10 }, (_, index) => `项目 ${index + 1}`))
    expect(projects.every(item => item.find('svg').exists())).toBe(true)
    expect(projects.every(item => item.attributes('title') === undefined)).toBe(true)
    expect(wrapper.findAll('#desktop-project-navigation .project-navigation__more svg circle')).toHaveLength(3)
    expect(wrapper.get('#desktop-project-navigation').text()).not.toContain('产品')
    const archived = wrapper.findAll('#desktop-project-navigation button').find(button => button.text() === '归档项目')!
    await archived.trigger('click')
    expect(push).toHaveBeenCalledWith({ name: 'archived-projects' })

    await projects[0]?.trigger('click')
    expect(push).toHaveBeenCalledWith({ name: 'project-overview', params: { projectId: 'project-1' } })
    wrapper.unmount()
  })

  it('置顶与最近打开的项目排在菜单前面，经服务端确认可见，并随置顶变化重排', async () => {
    const recent = (item: ProjectSummary, openedAt: number, pinned = false) => ({
      ...item, createdAt: item.createdAt.toISOString(), updatedAt: item.updatedAt.toISOString(), endedAt: null, openedAt, pinned,
    })
    window.localStorage.setItem('yumpoo.projects.recents.v1.company-1%3Auser-1', JSON.stringify([
      recent(project(5), 1, true), recent(project(14), 3), recent(project(30), 2),
    ]))
    api.listProjects.mockImplementation(async (request: { ids?: string[] }) => {
      if (request.ids) {
        const items = [project(5), project(14)]
        return { items, page: 0, size: request.ids.length, totalElements: items.length, totalPages: 1 }
      }
      return { items: Array.from({ length: 11 }, (_, index) => project(index + 1)), page: 0, size: 11, totalElements: 20, totalPages: 2 }
    })
    const wrapper = mount(AppShell, { global: { stubs: { RouterView: true } } })
    await flushPromises()

    expect(api.listProjects).toHaveBeenCalledWith(expect.objectContaining({
      ids: ['project-5', 'project-14', 'project-30'], size: 3, lifecycle: ProjectLifecycleFilter.Active, ended: false,
    }))
    const names = () => wrapper.findAll('#desktop-project-navigation .project-navigation__project').map(item => item.text())
    expect(names()).toEqual(['项目 5', '项目 14', '项目 1', '项目 2', '项目 3', '项目 4', '项目 6', '项目 7', '项目 8', '项目 9'])
    expect(wrapper.findAll('#desktop-project-navigation .project-navigation__pin')).toHaveLength(1)
    expect(wrapper.find('#desktop-project-navigation .project-navigation__more').exists()).toBe(true)

    const elsewhere = mount(defineComponent({ setup: () => useProjectRecents(() => 'company-1:user-1'), template: '<div />' }))
    elsewhere.vm.togglePinned('project-14')
    await flushPromises()
    expect(names().slice(0, 3)).toEqual(['项目 14', '项目 5', '项目 1'])
    expect(wrapper.findAll('#desktop-project-navigation .project-navigation__pin')).toHaveLength(2)

    await wrapper.findAll('#desktop-project-navigation .project-navigation__project')[3]?.trigger('click')
    await flushPromises()
    expect(names().slice(0, 4)).toEqual(['项目 14', '项目 5', '项目 2', '项目 1'])
    elsewhere.unmount()
    wrapper.unmount()
  })

  it('十个置顶项目与当前路由项目一并校验，不会反复补取最后一个置顶项', async () => {
    route.params.projectId = 'project-99'
    const pinned = Array.from({ length: 10 }, (_, index) => project(20 + index))
    window.localStorage.setItem('yumpoo.projects.recents.v1.company-1%3Auser-1', JSON.stringify(pinned.map(item => ({
      ...item, openedAt: 1, pinned: true,
    }))))
    let idRequests = 0
    api.listProjects.mockImplementation(async (request: { ids?: string[] }) => {
      if (request.ids && ++idRequests > 3) throw new Error('重复补取')
      const items = request.ids
        ? [...pinned, project(99)].filter(item => request.ids!.includes(item.id))
        : [project(1)]
      return { items, page: 0, size: items.length, totalElements: 11, totalPages: 1 }
    })
    const wrapper = mount(AppShell, { global: { stubs: { RouterView: true } } })
    try {
      for (let i = 0; i < 4; i++) await flushPromises()
      expect(api.listProjects).toHaveBeenCalledWith(expect.objectContaining({
        ids: ['project-99', ...pinned.map(item => item.id)], size: 11,
      }))
      expect(idRequests).toBe(1)
      expect(wrapper.findAll('#desktop-project-navigation .project-navigation__project').map(item => item.text()))
        .toEqual(pinned.map(item => item.name))
    } finally {
      wrapper.unmount()
    }
  })

  it('移动导航保留管理项目与项目跳转，移除产品入口', async () => {
    const wrapper = mount(AppShell, {
      attachTo: document.body,
      global: { stubs: { RouterView: true } },
    })
    await flushPromises()
    await wrapper.get('.mobile-nav-toggle').trigger('click')
    await flushPromises()
    const navigationElement = document.querySelector<HTMLElement>('nav[aria-label="移动端当前区域导航"]')
    expect(navigationElement).not.toBeNull()
    const navigation = new DOMWrapper(navigationElement!)
    expect(navigation.findAll('button').some(button => button.text() === '产品')).toBe(false)
    expect(navigation.text()).toContain('管理项目')
    expect(navigation.text()).toContain('归档项目')
    await navigation.get('.project-navigation__project').trigger('click')
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
      lifecycle: ProjectLifecycleFilter.Active,
      ended: false,
      actorAccesses: [ProjectActorAccess.Owner, ProjectActorAccess.Member],
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

  it('设置保存后同步桌面与移动导航，并更新最近项目名称', async () => {
    const wrapper = mount(AppShell, { attachTo: document.body, global: { stubs: { RouterView: true } } })
    await flushPromises()
    await wrapper.get('#desktop-project-navigation .project-navigation__project').trigger('click')
    await wrapper.get('.mobile-nav-toggle').trigger('click')
    await flushPromises()
    const renamed = { ...project(1), name: '保存后的完整名称', description: null, archivedAt: null }
    api.listProjects.mockResolvedValue({ items: [renamed], page: 0, size: 11, totalElements: 1, totalPages: 1 })
    notifyProjectSettingsChanged(renamed)
    await flushPromises()

    expect(wrapper.get('#desktop-project-navigation .project-navigation__project').text()).toBe(renamed.name)
    expect(document.querySelector('nav[aria-label="移动端当前区域导航"] .project-navigation__project')?.textContent).toContain(renamed.name)
    const recents = JSON.parse(window.localStorage.getItem('yumpoo.projects.recents.v1.company-1%3Auser-1') ?? '[]')
    expect(recents).toEqual([expect.objectContaining({ id: renamed.id, name: renamed.name })])
    wrapper.unmount()
    const requests = api.listProjects.mock.calls.length
    notifyProjectSettingsChanged(renamed)
    expect(api.listProjects).toHaveBeenCalledTimes(requests)
  })
})
