import {
  type ProjectWorkItemListItem,
} from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { state } from './projectWorkItemsTestMocks'
import { catalog, item, mountView, page, installWorkItemsTestDefaults, restoreWorkItemsTestGlobals } from './projectWorkItemsTestSupport'
import WorkItemCreateDialog from './WorkItemCreateDialog.vue'
import type { ColumnMenuState } from './workItemColumnMenu'
import type { WorkItemCreateDefaults } from './workItemTableSettings'

vi.mock('../../onboarding/useOnboarding', async () => (await import('./projectWorkItemsTestMocks')).onboardingModule())
vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))
vi.mock('vue-router', async () => (await import('./projectWorkItemsTestMocks')).vueRouterModule())
vi.mock('../../api/client', async () => (await import('./projectWorkItemsTestMocks')).apiClientModule())

enableAutoUnmount(afterEach)

describe('项目级工作项首页 · 分组', () => {
  beforeEach(installWorkItemsTestDefaults)
  afterEach(restoreWorkItemsTestGlobals)

  it('每个分组末尾独立新增，折叠与切换分组保留草稿，新增行继承颜色并带遮罩', async () => {
    state.listProjectWorkItems.mockResolvedValue(page([item(), { ...item('done'), statusCode: 'DONE' }]))
    state.listProjectWorkItemFilterOptions.mockResolvedValue({ items: [
      { value: 'BACKLOG', label: '待开始', count: 1 }, { value: 'DONE', label: '已完成', count: 1 },
    ], nextCursor: null })
    const wrapper = mountView(); await flushPromises()
    const view = wrapper.vm as unknown as { changeGrouping: (field: string) => Promise<void>; onDocumentPointerDown: (event: PointerEvent) => void }
    await wrapper.get('.el-table__append-wrapper .quick-add').trigger('click')
    await wrapper.get('.el-table__append-wrapper input').setValue('平铺草稿')
    await view.changeGrouping('STATUS'); await flushPromises()
    expect(wrapper.find('.el-table__append-wrapper .quick-add').exists()).toBe(false)
    const footers = wrapper.findAll('.work-item-group-add')
    expect(footers).toHaveLength(2)
    for (const footer of footers) {
      expect(footer.element.nextElementSibling?.classList.contains('work-item-group-spacer') ?? true).toBe(true)
      expect(footer.get('.work-item-group-add-mask').attributes('aria-hidden')).toBe('true')
    }
    expect(footers[0]!.attributes('style')).toBe(wrapper.get('.work-item-group-columns').attributes('style'))
    await wrapper.get('[aria-label="添加工作项到待开始"]').trigger('click')
    view.onDocumentPointerDown({ target: wrapper.get('.work-item-group-add input').element } as unknown as PointerEvent)
    await wrapper.get('.work-item-group-add input').setValue('待开始草稿')
    await wrapper.get('[aria-label="添加工作项到已完成"]').trigger('click')
    await wrapper.findAll('.work-item-group-add input')[1]!.setValue('已完成草稿')
    await wrapper.get('[aria-label="收起分组：待开始"]').trigger('click')
    expect(wrapper.get('.work-item-group-add').classes()).toContain('work-item-group-collapsed')
    await wrapper.get('[aria-label="展开分组：待开始"]').trigger('click')
    await view.changeGrouping(''); await flushPromises()
    expect(wrapper.get<HTMLInputElement>('.el-table__append-wrapper input').element.value).toBe('平铺草稿')
    await view.changeGrouping('STATUS'); await flushPromises()
    expect(wrapper.findAll<HTMLInputElement>('.work-item-group-add input').map(input => input.element.value))
      .toEqual(['待开始草稿', '已完成草稿'])
    expect(state.createWorkItem).not.toHaveBeenCalled()
  })

  it('我的工作每组末尾一行新建：颜色跟随分组、随分组收起，窗口预填分组取值，视图切换时重新分组', async () => {
    state.listProjectWorkItems.mockResolvedValue(page([item(), { ...item('done'), statusCode: 'DONE' }]))
    state.listProjectWorkItemFilterOptions.mockResolvedValue({ items: [
      { value: 'BACKLOG', label: '待开始', count: 1 }, { value: 'DONE', label: '已完成', count: 1 },
    ], nextCursor: null })
    const wrapper = mountView(undefined, { embeddedProjectId: 'project-1', preferenceScope: 'my-work:one', myWork: true, groupField: 'STATUS' })
    await flushPromises()
    expect(wrapper.find('.el-table__append-wrapper .quick-add').exists()).toBe(false)
    const footers = wrapper.findAll('.work-item-group-add')
    expect(footers).toHaveLength(2)
    for (const footer of footers) expect(footer.find('.my-work-create-row').exists()).toBe(true)
    expect(footers[0]!.attributes('style')).toBe(wrapper.get('.work-item-group-columns').attributes('style'))
    expect(footers[0]!.attributes('style')).not.toBe(footers[1]!.attributes('style'))

    await wrapper.get('[aria-label="收起分组：待开始"]').trigger('click')
    expect(wrapper.get('.work-item-group-add').classes()).toContain('work-item-group-collapsed')
    expect(wrapper.findAll('.work-item-group-add')[1]!.classes()).not.toContain('work-item-group-collapsed')

    await wrapper.get('[aria-label="新建工作项到已完成"]').trigger('click')
    expect((wrapper.getComponent(WorkItemCreateDialog).props('defaults') as WorkItemCreateDefaults).statusCode).toBe('DONE')

    const view = wrapper.vm as unknown as { columnMenuState: (column: { key: string; label: string }) => ColumnMenuState }
    expect(view.columnMenuState({ key: 'status', label: '状态' }).groupHidden).toBe(true)
    await wrapper.setProps({ groupField: 'PRIORITY' }); await flushPromises()
    expect(state.listProjectWorkItemFilterOptions).toHaveBeenLastCalledWith(expect.objectContaining({ field: 'PRIORITY' }), expect.anything())
  })

  it('从类别组末尾新增时使用所在类别，停用类别仍显示禁用的末尾入口', async () => {
    const contents = catalog()
    contents.items.push({ ...contents.items[0]!, id: 'content-2', name: '任务', sortOrder: 20 },
      { ...contents.items[0]!, id: 'inactive', name: '停用类别', active: false, sortOrder: 30 })
    state.listProjectContents.mockResolvedValue(contents)
    state.listProjectWorkItemFilterOptions.mockResolvedValue({ items: [
      { value: 'content-1', label: '产品需求', count: 1 }, { value: 'content-2', label: '任务', count: 1 },
      { value: 'inactive', label: '停用类别', count: 1 },
    ], nextCursor: null })
    const wrapper = mountView(); await flushPromises()
    await (wrapper.vm as unknown as { changeGrouping: (field: string) => Promise<void> }).changeGrouping('CONTENT')
    await flushPromises()
    expect(wrapper.get('[aria-label="添加工作项到停用类别"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[aria-label="添加工作项到任务"]').trigger('click')
    const input = wrapper.get('.work-item-group-add input')
    await input.setValue('按类别新增'); await input.trigger('keydown', { key: 'Enter' }); await flushPromises()
    expect(state.createWorkItem).toHaveBeenCalledWith(expect.objectContaining({ workItemCreateRequest: expect.objectContaining({ contentId: 'content-2', title: '按类别新增' }) }))
  })

  it('分组切换清空勾选但保留主表和子表草稿，折叠不销毁表格，展示行不进入选择或排序请求', async () => {
    const parent = { ...item('parent-1'), subitemCount: 1 }
    const done = { ...item('done'), statusCode: 'DONE' }
    state.listProjectWorkItems.mockResolvedValue(page([parent, done]))
    state.listProjectWorkItemFilterOptions.mockResolvedValue({ items: [
      { value: 'BACKLOG', label: '待开始', count: 1 }, { value: 'DONE', label: '已完成', count: 1 },
    ], nextCursor: null })
    state.listWorkItemSubitems.mockResolvedValue({ items: [item('child-1')] })
    const wrapper = mountView()
    await flushPromises()
    const table = wrapper.getComponent({ name: 'ElTable' }).vm.$
    await wrapper.findAll('button[aria-label="展开子项"]')[0]!.trigger('click')
    await flushPromises()
    const subtable = wrapper.getComponent({ name: 'ProjectWorkItemSubitemsTable' })
    const subtableInstance = subtable.vm.$
    await subtable.get('.subitem-add').trigger('click')
    await subtable.get('.subitem-quick-title input').setValue('分组前的子项草稿')
    const control = document.createElement('button')
    control.setAttribute('data-work-item-view-control', '')
    document.body.append(control)
    control.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    control.remove()
    await flushPromises()
    expect(subtable.get<HTMLInputElement>('.subitem-quick-title input').element.value).toBe('分组前的子项草稿')
    await wrapper.findAll<HTMLInputElement>('.work-item-table-row .el-checkbox__original')[0]!.setValue(true)
    const view = wrapper.vm as unknown as { changeGrouping: (field: string) => Promise<void>; selectedWorkItemIds: Set<string> }
    await view.changeGrouping('STATUS'); await flushPromises()
    expect(wrapper.findAll('.work-item-group-heading')).toHaveLength(2)
    expect(wrapper.getComponent({ name: 'ElTable' }).vm.$).toBe(table)
    expect(wrapper.getComponent({ name: 'ProjectWorkItemSubitemsTable' }).vm.$).toBe(subtableInstance)
    expect([...view.selectedWorkItemIds]).toEqual([])
    expect(wrapper.findAll('.work-item-group-heading .el-checkbox')).toHaveLength(0)
    await wrapper.get('button[aria-label="收起分组：待开始"]').trigger('click')
    expect(wrapper.get('.work-item-group-heading').classes()).toContain('work-item-group-collapsed')
    expect(wrapper.getComponent({ name: 'ProjectWorkItemSubitemsTable' }).vm.$).toBe(subtableInstance)
    await wrapper.get('button[aria-label="展开分组：待开始"]').trigger('click')
    expect(subtable.get<HTMLInputElement>('.subitem-quick-title input').element.value).toBe('分组前的子项草稿')
    await view.changeGrouping(''); await flushPromises()
    expect(wrapper.find('.work-item-group-heading').exists()).toBe(false)
    expect(wrapper.getComponent({ name: 'ElTable' }).vm.$).toBe(table)
    expect([...view.selectedWorkItemIds]).toEqual([])
    expect(state.moveProjectWorkItemOrder).not.toHaveBeenCalled()
  })

  it('分组态只向顺序接口提交本组锚点，拒绝跨组拖动并逐组保存排序', async () => {
    const first = item('a1'), second = item('a2'), other = { ...item('b1'), statusCode: 'DONE' }
    state.listProjectWorkItems.mockImplementation(async (request: { groupStatus?: string }) =>
      page(request.groupStatus === 'BACKLOG' ? [first, second] : request.groupStatus === 'DONE' ? [other] : [first, second, other]))
    state.listProjectWorkItemFilterOptions.mockResolvedValue({ items: [
      { value: 'BACKLOG', label: '待开始', count: 2 }, { value: 'DONE', label: '已完成', count: 1 },
    ], nextCursor: null })
    state.moveProjectWorkItemOrder.mockResolvedValue(first)
    const wrapper = mountView(); await flushPromises()
    const view = wrapper.vm as unknown as {
      changeGrouping: (field: string) => Promise<void>; grouping: { load: (key: string) => Promise<void> }
      tableDragging: ProjectWorkItemListItem; tableDropIndex: number; commitTableDrop: () => Promise<void>
      saveSortedWorkItemOrder: () => Promise<void>; sortRules: Array<{ field: string; direction: string }>
    }
    await view.changeGrouping('STATUS'); await view.grouping.load('BACKLOG'); await view.grouping.load('DONE')
    view.tableDragging = first; view.tableDropIndex = 3; await view.commitTableDrop()
    expect(state.moveProjectWorkItemOrder).not.toHaveBeenCalled()
    view.tableDragging = first; view.tableDropIndex = 2; await view.commitTableDrop()
    expect(state.moveProjectWorkItemOrder).toHaveBeenCalledWith(expect.objectContaining({ workItemId: first.id,
      projectWorkItemOrderMoveRequest: { previousVisibleWorkItemId: second.id, nextVisibleWorkItemId: null } }))
    state.moveProjectWorkItemOrder.mockClear()
    view.sortRules = [{ field: 'TITLE', direction: 'ASC' }]; await flushPromises()
    expect(wrapper.find('[aria-label="保存工作项名称排序后的工作项顺序"]').exists()).toBe(true)
    await view.saveSortedWorkItemOrder()
    expect(state.moveProjectWorkItemOrder).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ workItemId: second.id,
      projectWorkItemOrderMoveRequest: { previousVisibleWorkItemId: first.id, nextVisibleWorkItemId: null } }))
    expect(wrapper.find('[aria-label="保存工作项名称排序后的工作项顺序"]').exists()).toBe(false)
  })

  it('重复表头复用列宽与换列能力，保持所有表段列序一致', async () => {
    state.listProjectWorkItems.mockResolvedValue(page([item(), { ...item('done'), statusCode: 'DONE' }]))
    state.listProjectWorkItemFilterOptions.mockResolvedValue({ items: [
      { value: 'BACKLOG', label: '待开始', count: 1 }, { value: 'DONE', label: '已完成', count: 1 },
    ], nextCursor: null })
    const wrapper = mountView(); await flushPromises()
    const view = wrapper.vm as unknown as {
      changeGrouping: (field: string) => Promise<void>; columnWidths: Record<string, number>
      onTableColumnResizePointerDown: (event: PointerEvent, handle: HTMLElement) => void
      onTableColumnResizePointerMove: (event: PointerEvent) => void
      onTableColumnResizePointerUp: (event: PointerEvent) => void
      columnDraggingKey: string; columnDraggingIndex: number; columnDropIndex: number
      commitTableColumnDrop: () => void
    }
    await view.changeGrouping('STATUS'); await flushPromises()
    const width = view.columnWidths.title!
    const event = { pointerId: 99, clientX: 100, preventDefault: vi.fn(), stopPropagation: vi.fn() } as unknown as PointerEvent
    const handle = wrapper.get<HTMLElement>('.work-item-group-columns [data-column-key="title"]').element
    view.onTableColumnResizePointerDown(event, handle)
    view.onTableColumnResizePointerMove({ ...event, clientX: 180 })
    view.onTableColumnResizePointerUp({ ...event, clientX: 180 })
    expect(view.columnWidths.title).toBe(width + 80)
    view.columnDraggingKey = 'status'; view.columnDraggingIndex = 1; view.columnDropIndex = 3
    view.commitTableColumnDrop(); await flushPromises()
    for (const row of wrapper.findAll('.work-item-group-columns')) {
      expect(row.findAll('.monday-movable-column-header').map(cell => cell.text()).slice(0, 3)).toEqual(['处理人', '优先级', '状态'])
    }
  })

  it('分组保留发起位置的根项草稿，切换字段不重建输入框', async () => {
    const anchor = { ...item('anchor'), statusCode: 'DONE', title: '草稿锚点', assigneeUserId: 'a', assigneeDisplayName: '张三',
      assignees: [{ userId: 'a', displayName: '张三' }, { userId: 'b', displayName: '李四' }] }
    state.listProjectWorkItems.mockResolvedValue(page([anchor]))
    state.listProjectWorkItemFilterOptions.mockImplementation(async ({ field }: { field: string }) => ({
      items: [{ value: field === 'STATUS' ? 'DONE' : 'a,b', label: field === 'STATUS' ? '分组' : '张三、李四', count: 1 }], nextCursor: null,
    }))
    const wrapper = mountView(); await flushPromises()
    const view = wrapper.vm as unknown as { changeGrouping: (field: string) => Promise<void> }
    await view.changeGrouping('STATUS'); await flushPromises()
    wrapper.findComponent({ name: 'WorkItemRowActions' }).vm.$emit('createBelow', anchor)
    await flushPromises()
    const input = wrapper.get<HTMLInputElement>('.work-item-draft-row .work-item-name-input input')
    await input.setValue('未保存的根项')
    await view.changeGrouping('ASSIGNEE'); await flushPromises()
    expect(wrapper.get<HTMLInputElement>('.work-item-draft-row .work-item-name-input input').element === input.element).toBe(true)
    expect(input.element.value).toBe('未保存的根项')
    expect(state.createWorkItem).not.toHaveBeenCalled()
    expect(state.listProjectWorkItemFilterOptions).toHaveBeenCalledWith(expect.objectContaining({ field: 'ASSIGNEE_SET' }), expect.anything())
    expect(wrapper.text()).toContain('张三、李四')
  })
})
