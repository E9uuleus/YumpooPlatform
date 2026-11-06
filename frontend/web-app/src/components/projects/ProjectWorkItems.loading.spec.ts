import {
  WorkItemViewType,
  type ProjectDetail,
  type ProjectWorkItemCursorPage,
  type ProjectWorkItemListItem,
} from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { defineComponent, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { WorkItemTableSource } from './workItemTableSource'
import ProjectOverviewView from './ProjectWorkItems.vue'
import { state } from './projectWorkItemsTestMocks'
import { item, mountView, page, project, installWorkItemsTestDefaults, restoreWorkItemsTestGlobals } from './projectWorkItemsTestSupport'

vi.mock('../../onboarding/useOnboarding', async () => (await import('./projectWorkItemsTestMocks')).onboardingModule())
vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))
vi.mock('vue-router', async () => (await import('./projectWorkItemsTestMocks')).vueRouterModule())
vi.mock('../../api/client', async () => (await import('./projectWorkItemsTestMocks')).apiClientModule())

enableAutoUnmount(afterEach)

describe('项目级工作项首页 · 加载、续页与嵌入', () => {
  beforeEach(installWorkItemsTestDefaults)
  afterEach(restoreWorkItemsTestGlobals)

  it.each(['table', 'kanban'])('does not start onboarding when %s work items fail after project metadata has loaded', async view => {
    state.route.query = { view }
    state.listProjectWorkItems.mockRejectedValueOnce(new Error('offline'))
    const wrapper = mountView()
    await flushPromises()
    expect(state.getProject).toHaveBeenCalled()
    expect(state.onboardingReady).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('waits for failed grouping data to recover before starting onboarding', async () => {
    const wrapper = mountView()
    await flushPromises()
    state.onboardingReady.mockClear()
    state.listProjectWorkItemFilterOptions.mockRejectedValueOnce(new Error('offline'))
    const view = wrapper.vm as unknown as { changeGrouping: (field: string) => Promise<void>; refreshCurrentView: () => Promise<void> }
    await view.changeGrouping('STATUS')
    await flushPromises()
    expect(state.onboardingReady).not.toHaveBeenCalled()
    await view.refreshCurrentView()
    await flushPromises()
    expect(state.onboardingReady).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('仪表板嵌入复用原表格，并隔离路由和列偏好', async () => {
    const source: WorkItemTableSource = {
      listProjectWorkItems: vi.fn().mockResolvedValue(page([{ ...item(), subitemCount: 2 }])),
      listProjectWorkItemFilterOptions: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
      listWorkItemSubitems: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
      subitemCount: () => 1,
    }
    const projectPrefs = JSON.stringify({ version: 1, widths: { title: 410 }, hidden: ['priority'], order: ['status', 'assignee'] })
    localStorage.setItem('yumpoo:project-work-items:table:v1', projectPrefs)
    const wrapper = mountView(undefined, { embeddedProjectId: 'project-1', preferenceScope: 'dashboard:one', source })
    await flushPromises()
    expect(wrapper.find('[data-testid="project-name"]').exists()).toBe(false)
    expect(wrapper.find('.monday-view-header').exists()).toBe(false)
    expect(wrapper.find('.monday-table').exists()).toBe(true)
    expect(source.listProjectWorkItems).toHaveBeenCalled()
    expect(state.listProjectWorkItems).not.toHaveBeenCalled()
    expect(wrapper.find('[aria-label="1 Subitems"]').exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'WorkItemRowActions' }).props('item').subitemCount).toBe(2)
    const vm = wrapper.vm as unknown as { searchInput: string; syncUrl: () => Promise<void>; toggleColumn: (key: string, visible: boolean) => void; columnWidths: { title: number }; openDetail: (row: ProjectWorkItemListItem, tab: string) => Promise<void> }
    expect(vm.columnWidths.title).toBe(410)
    vm.searchInput = 'embedded search'
    await vm.syncUrl(); await flushPromises()
    expect(source.listProjectWorkItems).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'embedded search' }), expect.anything())
    vm.toggleColumn('status', false)
    expect(localStorage.getItem('yumpoo:project-work-items:table:v1')).toBe(projectPrefs)
    expect(localStorage.getItem('dashboard:one:columns')).toContain('status')
    await vm.openDetail(item(), 'details'); await flushPromises()
    expect(JSON.parse(localStorage.getItem('dashboard:one:query')!)).not.toHaveProperty('workItemId')
    expect(state.push).not.toHaveBeenCalled()
    expect(state.replace).not.toHaveBeenCalled()
    expect(state.route.query).toEqual({})
    expect(document.body.classList.contains('yp-project-overview-scroll')).toBe(false)
    expect(document.body.classList.contains('yp-work-items-drawer-open')).toBe(false)
  })

  it('我的工作隐藏工具栏，只读取本人完成定义以外的状态', async () => {
    const wrapper = mountView(undefined, { embeddedProjectId: 'project-1', preferenceScope: 'my-work:one', myWork: true })
    await flushPromises()
    expect(wrapper.find('.work-items-toolbar').exists()).toBe(false)
    expect(state.listProjectWorkItems).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: new Set(['BACKLOG']) }), expect.anything())
  })

  it('嵌入表格的未完成输入阻止关闭，完成后解除', async () => {
    const wrapper = mountView(undefined, { embeddedProjectId: 'project-1', preferenceScope: 'dashboard:two' })
    await flushPromises()
    const vm = wrapper.vm as unknown as { quickTitle: string; canClose: () => Promise<boolean> }
    vm.quickTitle = '尚未创建'
    expect(await vm.canClose()).toBe(false)
    vm.quickTitle = ''
    expect(await vm.canClose()).toBe(true)
  })

  it.each(['overflow', 'exhausted', 'error'] as const)('最大化首屏标记持续可见时自动续页，并在 %s 时停止', async outcome => {
    let notifyVisible: IntersectionObserverCallback | undefined
    vi.stubGlobal('IntersectionObserver', class {
      constructor(callback: IntersectionObserverCallback, options?: IntersectionObserverInit) {
        if (options?.rootMargin === '320px 0px') notifyVisible = callback
      }
      observe() {}
      unobserve() {}
      disconnect() {}
    })
    let resolveFirst!: (value: ProjectWorkItemCursorPage) => void
    state.listProjectWorkItems.mockImplementationOnce(() => new Promise<ProjectWorkItemCursorPage>(resolve => { resolveFirst = resolve }))
    if (outcome === 'error') state.listProjectWorkItems.mockRejectedValueOnce(new Error('offline'))
    else state.listProjectWorkItems.mockResolvedValueOnce({
      items: Array.from({ length: outcome === 'overflow' ? 25 : 2 }, (_, i) => item(`next-${i}`)),
      nextCursor: outcome === 'overflow' ? 'page-3' : null,
    })
    const wrapper = mount(ProjectOverviewView, { global: { stubs: {
      InlineProblem: true, WorkItemDetailPanel: true, ProjectWorkspaceHeader: true,
      ElTable: defineComponent({
        props: { data: { type: Array, default: () => [] } },
        setup(_props, { expose }) {
          expose({ doLayout() {}, clearSelection() {}, toggleRowSelection() {} })
          return {}
        },
        template: '<div><div class="el-scrollbar__wrap"><div v-for="row in data" :key="row.id" /><slot name="append" /></div></div>',
      }),
    } } })
    await flushPromises()
    const view = wrapper.vm as unknown as { tableItems: ProjectWorkItemListItem[] }
    const scroll = wrapper.get('.monday-table .el-scrollbar__wrap').element
    const sentinel = wrapper.get('.cursor-sentinel').element
    vi.spyOn(scroll, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 358, 800, 1026))
    vi.spyOn(sentinel, 'getBoundingClientRect').mockImplementation(() => new DOMRect(0, 358 + view.tableItems.length * 37 + 37, 800, 1))
    // 首次可见通知发生在请求途中，之后不再发送交叉事件或窗口 resize。
    notifyVisible!([{ isIntersecting: true, target: sentinel } as IntersectionObserverEntry], {} as IntersectionObserver)
    expect(state.listProjectWorkItems).toHaveBeenCalledTimes(1)
    resolveFirst({ items: Array.from({ length: 25 }, (_, i) => item(`first-${i}`)), nextCursor: 'page-2' })
    for (let i = 0; i < 4; i++) await flushPromises()
    expect(state.listProjectWorkItems).toHaveBeenCalledTimes(2)
    expect(state.listProjectWorkItems).toHaveBeenLastCalledWith(expect.objectContaining({ cursor: 'page-2', limit: 25 }), expect.anything())
    expect(view.tableItems).toHaveLength(outcome === 'overflow' ? 50 : outcome === 'exhausted' ? 27 : 25)
    if (outcome === 'error') expect(wrapper.text()).toContain('加载更多失败')
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

  it('首屏与追加加载显示与列对齐的骨架而不是加载文字', async () => {
    let resolveFirst!: (value: ProjectWorkItemCursorPage) => void
    state.listProjectWorkItems.mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve }))
    const wrapper = mountView()
    await flushPromises()

    const initial = wrapper.get('.table-surface .work-item-table-skeleton')
    expect(initial.attributes('role')).toBe('status')
    expect(initial.findAll('.work-item-table-skeleton__row')).toHaveLength(10)
    expect(wrapper.find('.monday-quick-add').exists()).toBe(false)
    expect(wrapper.find('.table-surface .el-loading-mask').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('正在加载')

    resolveFirst({ items: [item()], nextCursor: 'cursor-1' })
    await flushPromises()
    expect(wrapper.find('.work-item-table-skeleton').exists()).toBe(false)

    let resolveMore!: (value: ProjectWorkItemCursorPage) => void
    state.listProjectWorkItems.mockImplementationOnce(() => new Promise(resolve => { resolveMore = resolve }))
    const loading = (wrapper.vm as unknown as { loadTable: (cursor: string, append: boolean) => Promise<void> })
      .loadTable('cursor-1', true)
    await nextTick()
    const more = wrapper.get('.work-item-table-skeleton')
    expect(more.attributes('aria-label')).toBe('正在加载更多工作项')
    expect(more.findAll('.work-item-table-skeleton__row')).toHaveLength(3)
    expect(wrapper.text()).not.toContain('正在加载更多工作项')

    resolveMore({ items: [item('item-2')], nextCursor: null })
    await loading
    await flushPromises()
    expect(wrapper.find('.work-item-table-skeleton').exists()).toBe(false)
    expect(wrapper.findAll('.work-item-link')).toHaveLength(2)
  })

  it('被取代的首屏请求不会让后续重新加载一直停在骨架', async () => {
    state.listProjectWorkItems.mockImplementationOnce(() => new Promise(() => undefined))
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('.work-item-table-skeleton').exists()).toBe(true)

    await (wrapper.vm as unknown as { resetCurrentData: () => Promise<void> }).resetCurrentData()
    await flushPromises()

    expect(state.listProjectWorkItems).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.work-item-table-skeleton').exists()).toBe(false)
    expect(wrapper.findAll('.work-item-link')).toHaveLength(1)
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
      view: WorkItemViewType.Kanban,
      status: new Set(['BACKLOG']),
    }), expect.objectContaining({ signal: expect.any(AbortSignal) }))
  })

  it('嵌入表格清理旧偏好的 tab，后续保存不包含深链参数', async () => {
    localStorage.setItem('dashboard:inbox:query', JSON.stringify({ tab: 'discussion', view: 'table' }))
    const wrapper = mountView(undefined, { embeddedProjectId: 'project-1', preferenceScope: 'dashboard:inbox' })
    await flushPromises()
    const view = wrapper.vm as unknown as { detailTab: string; setViewQuery: (query: Record<string, string>) => Promise<void> }
    expect(JSON.parse(localStorage.getItem('dashboard:inbox:query')!)).toEqual({ view: 'table' })
    await view.setViewQuery({ view: 'table', workItemId: 'item-1', tab: 'discussion' })
    await flushPromises()
    expect(view.detailTab).toBe('discussion')
    expect(JSON.parse(localStorage.getItem('dashboard:inbox:query')!)).toEqual({ view: 'table' })
    expect(state.route.query).toEqual({})
  })
})
