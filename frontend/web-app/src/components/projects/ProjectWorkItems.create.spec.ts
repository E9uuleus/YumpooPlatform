import {
  type WorkItemDetail,
  type ProjectWorkItemCursorPage,
} from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { state } from './projectWorkItemsTestMocks'
import { catalog, item, mountView, page, installWorkItemsTestDefaults, restoreWorkItemsTestGlobals } from './projectWorkItemsTestSupport'

vi.mock('../../onboarding/useOnboarding', async () => (await import('./projectWorkItemsTestMocks')).onboardingModule())
vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))
vi.mock('vue-router', async () => (await import('./projectWorkItemsTestMocks')).vueRouterModule())
vi.mock('../../api/client', async () => (await import('./projectWorkItemsTestMocks')).apiClientModule())

enableAutoUnmount(afterEach)

describe('项目级工作项首页 · 新建与草稿', () => {
  beforeEach(installWorkItemsTestDefaults)
  afterEach(restoreWorkItemsTestGlobals)

  it('在指定行下插入空白草稿，输入后外部点击才创建并按返回版本定位', async () => {
    state.getProjectWorkItemLabels.mockResolvedValue({ statuses: [
      { code: 'CUSTOM_TODO', displayName: '自定义待办', colorToken: 'BLUE', statusCategory: 'TODO', sortOrder: 1, active: true },
      { code: 'NOT_STARTED', displayName: '未开始', colorToken: 'GRAY', statusCategory: 'TODO', sortOrder: 10, active: true },
    ], priorities: [], rowVersion: 0, etag: '"0"', canManage: true })
    const first = { ...item(), title: '第一行', priority: 'HIGH', assigneeUserId: 'owner-1' }
    const second = { ...item('second'), title: '第二行' }
    state.listProjectWorkItems.mockResolvedValue(page([first, second]))
    const wrapper = mountView()
    await flushPromises()
    wrapper.findComponent({ name: 'WorkItemRowActions' }).vm.$emit('createBelow', first)
    await flushPromises()
    const rows = wrapper.findAll('tr.work-item-table-row')
    expect(rows).toHaveLength(3)
    expect(rows[1]!.classes()).toContain('work-item-draft-row')
    expect(rows[2]!.text()).toContain('第二行')
    expect(rows[1]!.get<HTMLInputElement>('.work-item-name-input input').element.value).toBe('')
    expect(rows[1]!.get('.work-item-name-input input').attributes('placeholder')).toBe('*新工作项')
    expect(rows[1]!.find('.work-item-row-menu-trigger').exists()).toBe(false)
    expect(rows[1]!.find('[aria-label="查看计时日志"]').exists()).toBe(false)
    expect(rows[1]!.get('.monday-column--status').text()).toBe('未开始')
    expect(rows[1]!.get('input[type="checkbox"]').attributes('disabled')).toBeDefined()
    expect(state.createWorkItem).not.toHaveBeenCalled()
    await rows[1]!.get('.work-item-name-input input').setValue('下方新行')
    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await flushPromises()
    expect(state.createWorkItem).toHaveBeenCalledTimes(1)
    expect(state.createWorkItem).toHaveBeenCalledWith(expect.objectContaining({ workItemCreateRequest: {
      title: '下方新行', contentId: 'content-1', priority: null, assigneeUserId: null, description: null,
      notes: null, timelineStartDate: null, timelineEndDate: null, dueDate: null,
    } }))
    expect(state.moveProjectWorkItemOrder).toHaveBeenCalledWith(expect.objectContaining({ workItemId: 'created', ifMatch: '"1"',
      projectWorkItemOrderMoveRequest: { previousVisibleWorkItemId: first.id, nextVisibleWorkItemId: null } }))
    expect(wrapper.find('.work-item-draft-row').exists()).toBe(false)
  })

  it('草稿可取消；创建失败保留名称且重复失焦不产生并发创建', async () => {
    const wrapper = mountView()
    await flushPromises()
    const menu = wrapper.findComponent({ name: 'WorkItemRowActions' })
    menu.vm.$emit('createBelow', item())
    await flushPromises()
    await wrapper.get('.work-item-name-input input').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('.work-item-draft-row').exists()).toBe(false)
    expect(state.createWorkItem).not.toHaveBeenCalled()
    menu.vm.$emit('createBelow', item())
    await flushPromises()
    await wrapper.get('.work-item-name-input input').trigger('blur')
    expect(wrapper.find('.work-item-draft-row').exists()).toBe(false)
    expect(state.createWorkItem).not.toHaveBeenCalled()
    menu.vm.$emit('createBelow', item())
    await flushPromises()
    let rejectCreate!: (reason: Error) => void
    state.createWorkItem.mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectCreate = reject }))
    const input = wrapper.get<HTMLInputElement>('.work-item-name-input input')
    await input.setValue('失败后保留')
    await input.trigger('blur')
    await input.trigger('blur')
    expect(state.createWorkItem).toHaveBeenCalledTimes(1)
    rejectCreate(new Error('offline'))
    await flushPromises()
    expect(input.element.value).toBe('失败后保留')
    expect(wrapper.find('.work-item-draft-row').exists()).toBe(true)
    const requestKey = state.createWorkItem.mock.calls[0]![0].idempotencyKey
    await input.trigger('blur')
    await flushPromises()
    expect(state.createWorkItem.mock.calls[1]![0].idempotencyKey).toBe(requestKey)
    expect(wrapper.find('.work-item-draft-row').exists()).toBe(false)
  })

  it('创建成功但定位失败时仍结束草稿，避免重复创建', async () => {
    vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({ close() {} }))
    state.moveProjectWorkItemOrder.mockRejectedValue(new Error('conflict'))
    const wrapper = mountView()
    await flushPromises()
    wrapper.findComponent({ name: 'WorkItemRowActions' }).vm.$emit('createBelow', item())
    await flushPromises()
    await wrapper.get('.work-item-name-input input').setValue('定位失败仍保留')
    await wrapper.get('.work-item-name-input input').trigger('blur')
    await flushPromises()
    expect(state.createWorkItem).toHaveBeenCalledTimes(1)
    expect(ElMessage.warning).toHaveBeenCalledWith(expect.stringContaining('已创建 WI-2'))
    expect(wrapper.find('.work-item-draft-row').exists()).toBe(false)
  })

  it('创建请求未返回时切换项目，不把草稿或迟到的新行插入另一个项目', async () => {
    let resolveCreate!: (created: WorkItemDetail) => void
    state.createWorkItem.mockImplementationOnce(() => new Promise(resolve => { resolveCreate = resolve }))
    const wrapper = mountView()
    await flushPromises()
    wrapper.findComponent({ name: 'WorkItemRowActions' }).vm.$emit('createBelow', item())
    await flushPromises()
    await wrapper.get('.work-item-name-input input').setValue('切换前提交')
    await wrapper.get('.work-item-name-input input').trigger('blur')
    const other = { ...item('other-project-row'), projectId: 'project-2', title: '另一个项目的工作项' }
    state.listProjectWorkItems.mockResolvedValue(page([other]))
    state.route.params.projectId = 'project-2'
    await flushPromises()
    expect(wrapper.find('.work-item-draft-row').exists()).toBe(false)
    resolveCreate({ ...item('created'), itemNo: 'WI-2' } as unknown as WorkItemDetail)
    await flushPromises()
    expect(wrapper.findAll('.work-item-title-text').map(node => node.text())).toEqual(['另一个项目的工作项'])
  })

  it('多个类别时无需选择即可 Enter 创建，自动使用首个启用类别且重复按键不会重复提交', async () => {
    const multipleContents = catalog()
    multipleContents.items = [
      { ...multipleContents.items[0]!, id: 'inactive-content', active: false },
      multipleContents.items[0]!,
      { ...multipleContents.items[0]!, id: 'content-2', name: '任务', sortOrder: 20 },
    ]
    state.listProjectContents.mockResolvedValue(multipleContents)
    let resolveCreate: ((value: WorkItemDetail) => void) | undefined
    state.createWorkItem.mockImplementation(() => new Promise<WorkItemDetail>(resolve => { resolveCreate = resolve }))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('.quick-add').text()).toBe('添加工作项')
    expect(wrapper.find('.quick-add svg').exists()).toBe(false)
    expect(wrapper.find('.quick-add .monday-quick-checkbox').exists()).toBe(true)
    expect(wrapper.find('.quick-add input[type="checkbox"]').exists()).toBe(false)
    await wrapper.get('.quick-add').trigger('click')
    const input = wrapper.get('input[placeholder="添加工作项"]')
    expect(wrapper.find('.quick-row .el-select').exists()).toBe(false)
    expect(wrapper.find('.quick-row .monday-quick-checkbox').exists()).toBe(true)
    await input.setValue('快速新增事项')
    await input.trigger('keydown', { key: 'Enter' })
    await input.trigger('keydown', { key: 'Enter' })
    expect(state.createWorkItem).toHaveBeenCalledTimes(1)
    expect(state.createWorkItem).toHaveBeenCalledWith(expect.objectContaining({
      projectId: 'project-1',
      workItemCreateRequest: expect.objectContaining({
        contentId: 'content-1', title: '快速新增事项', priority: null,
      }),
    }))

    resolveCreate?.({ ...item('created'), itemNo: 'WI-2' } as unknown as WorkItemDetail)
    await flushPromises()
  })

  it('空标题不创建；Shift+Enter 成功后清空标题并继续输入', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('.quick-add').trigger('click')
    const input = wrapper.get('input[placeholder="添加工作项"]')

    await input.trigger('keydown', { key: 'Enter' })
    expect(state.createWorkItem).not.toHaveBeenCalled()
    await input.setValue('连续新增')
    await input.trigger('keydown', { key: 'Enter', shiftKey: true })
    await flushPromises()

    expect(state.createWorkItem).toHaveBeenCalledTimes(1)
    expect((input.element as HTMLInputElement).value).toBe('')
    expect(wrapper.find('.quick-row').exists()).toBe(true)
  })

  it('点击新增行外关闭输入且不自动创建', async () => {
    state.createWorkItem.mockRejectedValue(new Error('network error'))
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('.quick-add').trigger('click')
    const input = wrapper.get('input[placeholder="添加工作项"]')
    await input.setValue('保留的草稿')

    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await flushPromises()

    expect(state.createWorkItem).not.toHaveBeenCalled()
    expect(wrapper.find('.quick-row').exists()).toBe(false)
  })

  it('新增后保留多页旧行直到后台查询完成，再原位替换', async () => {
    const original = Array.from({ length: 3 }, (_, index) => item(`original-${index}`))
    state.listProjectWorkItems.mockResolvedValueOnce(page(original))
    const wrapper = mountView()
    await flushPromises()
    let resolveRefresh: ((value: ProjectWorkItemCursorPage) => void) | undefined
    state.listProjectWorkItems.mockImplementationOnce(() => new Promise<ProjectWorkItemCursorPage>(resolve => { resolveRefresh = resolve }))
    state.listProjectWorkItems.mockResolvedValueOnce(page(original.slice(1)))
    await wrapper.get('.quick-add').trigger('click')
    const input = wrapper.get('input[placeholder="添加工作项"]')
    await input.setValue('新增刷新验证')
    await input.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(wrapper.find('.quick-row').exists()).toBe(false)
    expect(wrapper.findAll('.el-table__body .el-table__row')).toHaveLength(3)
    expect(wrapper.get('.table-surface').attributes('aria-busy')).toBe('true')
    expect(wrapper.find('.table-surface .el-loading-mask').exists()).toBe(false)
    resolveRefresh?.({ ...page([item('new'), ...original.slice(0, 1)]), nextCursor: 'refresh-page-2' })
    await flushPromises()
    expect(state.listProjectWorkItems).toHaveBeenLastCalledWith(
      expect.objectContaining({ cursor: 'refresh-page-2' }), expect.anything(),
    )
    expect(wrapper.findAll('.el-table__body .el-table__row')).toHaveLength(4)
    expect(wrapper.get('.table-surface').attributes('aria-busy')).toBe('false')
  })

  it('空表可直接添加，空输入点击行外关闭，输入法确认不提交', async () => {
    state.listProjectWorkItems.mockResolvedValue(page([]))
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('.monday-table--empty .quick-add').exists()).toBe(true)
    await wrapper.get('.quick-add').trigger('click')
    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await flushPromises()
    expect(wrapper.find('.quick-row').exists()).toBe(false)
    await wrapper.get('.quick-add').trigger('click')
    const input = wrapper.get('input[placeholder="添加工作项"]')
    await input.setValue('中文输入')
    await input.trigger('keydown', { key: 'Enter', isComposing: true })
    expect(state.createWorkItem).not.toHaveBeenCalled()
    expect(wrapper.get('.quick-hint').text()).toContain('Shift+Enter')
  })

  it('没有 ACTIVE Content 时禁用快速添加', async () => {
    const archivedCatalog = catalog()
    archivedCatalog.items[0]!.active = false
    state.listProjectContents.mockResolvedValue(archivedCatalog)
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('.quick-add').attributes('disabled')).toBeDefined()
    expect(state.createWorkItem).not.toHaveBeenCalled()
  })
})
