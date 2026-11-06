import {
  ResponseError,
  WorkItemLabelColorToken,
  WorkItemStatusCategory,
  type WorkItemDetail,
  type ProjectWorkItemListItem,
} from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import WorkItemNameCell from './WorkItemNameCell.vue'
import * as tableSettings from './workItemTableSettings'
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

describe('项目级工作项首页 · 单元格编辑与勾选', () => {
  beforeEach(installWorkItemsTestDefaults)
  afterEach(restoreWorkItemsTestGlobals)

  it('行和单元格重复访问共用一次着色计算，编辑名称后使缓存失效', async () => {
    const target = item(), other = item('other')
    state.listProjectWorkItems.mockResolvedValue(page([target, other]))
    state.getMyWorkItemTableSettings.mockResolvedValue({ ...tableSettings.emptyTableSettings(), projectId: 'project-1', etag: '"1"', updatedAt: new Date(),
      coloringRules: [{ id: 'rule-1', target: 'ROW', colorToken: 'SKY', column: 'TITLE', operator: 'CONTAINS', values: ['首页'] }] })
    const evaluate = vi.spyOn(tableSettings, 'evaluateWorkItemColoring')
    const wrapper = mountView(); await flushPromises()
    const view = wrapper.vm as unknown as {
      tableRowStyle: (context: { row: ProjectWorkItemListItem; rowIndex: number }) => Record<string, unknown>
      tableCellStyle: (context: { row: ProjectWorkItemListItem; column: { property: string } }) => Record<string, unknown>
    }
    evaluate.mockClear()
    for (let i = 0; i < 20; i++) {
      view.tableRowStyle({ row: target, rowIndex: 0 })
      view.tableCellStyle({ row: target, column: { property: 'title' } })
    }
    expect(evaluate).not.toHaveBeenCalled()
    expect(view.tableRowStyle({ row: target, rowIndex: 0 })['--work-item-condition-row-bg']).toBeDefined()
    wrapper.findAllComponents(WorkItemNameCell)[0]!.vm.$emit('updated', target.id, { ...target, title: '修改后的名称' })
    await flushPromises()
    expect(view.tableRowStyle({ row: target, rowIndex: 0 })['--work-item-condition-row-bg']).toBeUndefined()
    expect(view.tableRowStyle({ row: other, rowIndex: 1 })['--work-item-condition-row-bg']).toBeDefined()
  })

  it.each(['filter', 'view', 'project', 'group'] as const)('%s 切换清除 reserve-selection 和子表内部勾选，数量不会回弹', async change => {
    const parent = { ...item('parent'), subitemCount: 1 }
    state.listProjectWorkItems.mockResolvedValue(page([parent]))
    state.listWorkItemSubitems.mockResolvedValue({ items: [item('child')] })
    const wrapper = mountView()
    await flushPromises()
    const view = wrapper.vm as unknown as {
      onTableExpandChange: (item: ProjectWorkItemListItem, expanded: boolean) => void
      changeGrouping: (field: string) => Promise<void>
      selectedCount: number
    }
    view.onTableExpandChange(parent, true); await flushPromises()
    const main = wrapper.getComponent({ name: 'ElTable' }).vm.$.exposed as unknown as { toggleRowSelection: (item: ProjectWorkItemListItem, value: boolean) => void; getSelectionRows: () => unknown[] }
    const child = wrapper.getComponent({ name: 'ProjectWorkItemSubitemsTable' })
    const childTable = child.getComponent({ name: 'ElTable' }).vm.$.exposed as unknown as typeof main
    const clearChild = vi.spyOn(child.vm.$.exposed as unknown as { clearSelection: () => void }, 'clearSelection')
    main.toggleRowSelection(parent, true)
    childTable.toggleRowSelection(item('child'), true)
    await nextTick()
    expect(view.selectedCount).toBe(2)
    expect(wrapper.getComponent({ name: 'WorkItemBatchBar' }).props('subitemCount')).toBe(1)
    if (change === 'filter') state.route.query = { q: 'new' }
    else if (change === 'view') state.route.query = { view: 'kanban' }
    else if (change === 'project') state.route.params.projectId = 'other'
    else await view.changeGrouping('STATUS')
    await flushPromises()
    expect(clearChild).toHaveBeenCalled()
    expect(main.getSelectionRows()).toEqual([])
    expect(view.selectedCount).toBe(0)
  })

  it('文字编辑保存后同步主表名称，单元格空白不打开详情', async () => {
    state.updateWorkItem.mockResolvedValue({ ...item(), title: '已修改的名称' })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('.work-item-link').trigger('click')
    expect(state.push).not.toHaveBeenCalled()
    expect(wrapper.find('.work-item-name-input input').exists()).toBe(false)
    await wrapper.get('.work-item-title-text').trigger('click')
    await wrapper.get('.work-item-name-input input').setValue('已修改的名称')
    await wrapper.get('.work-item-name-input input').trigger('blur')
    await flushPromises()
    expect(wrapper.get('.work-item-title-text').text()).toBe('已修改的名称')
    expect(state.push).not.toHaveBeenCalled()
  })

  it('连续优先级更新不预取详情，并使用最新轻量行 ETag', async () => {
    state.patchWorkItemPriority
      .mockResolvedValueOnce({
        ...item(), priority: 'HIGH', etag: '"2"', rowVersion: 2,
      } as unknown as WorkItemDetail)
      .mockResolvedValueOnce({
        ...item(), priority: 'LOW', etag: '"3"', rowVersion: 3,
      } as unknown as WorkItemDetail)
    const wrapper = mountView()
    await flushPromises()

    const view = wrapper.vm as unknown as {
      patchCell: (row: ProjectWorkItemListItem, field: 'priority', value: string) => Promise<boolean>
      tableItems: ProjectWorkItemListItem[]
    }
    await view.patchCell(view.tableItems[0]!, 'priority', 'HIGH')
    await view.patchCell(view.tableItems[0]!, 'priority', 'LOW')
    await flushPromises()

    expect(state.getWorkItem).not.toHaveBeenCalled()
    expect(state.patchWorkItemPriority).toHaveBeenNthCalledWith(1, expect.objectContaining({
      workItemId: 'item-1', ifMatch: '"1"',
      workItemPriorityPatchRequest: { priority: 'HIGH' },
    }))
    expect(state.patchWorkItemPriority).toHaveBeenNthCalledWith(2, expect.objectContaining({
      workItemId: 'item-1', ifMatch: '"2"',
      workItemPriorityPatchRequest: { priority: 'LOW' },
    }))
    expect(view.tableItems[0]?.etag).toBe('"3"')
  })

  it('单元格弹层同一时间只打开一个，选择优先级后立即收起', async () => {
    state.listProjectWorkItems.mockResolvedValue(page([item('item-1'), item('item-2')]))
    state.patchWorkItemPriority.mockResolvedValue({ ...item(), etag: '"2"', rowVersion: 2 } as unknown as WorkItemDetail)
    const wrapper = mountView()
    await flushPromises()
    const view = wrapper.vm as unknown as { openCell: string | undefined }

    const cells = wrapper.findAll('.el-table__body .monday-priority-cell')
    // Element Plus 通过 0ms 定时器打开弹层。
    const open = async (index: number) => { await cells[index]!.trigger('click'); await new Promise(resolve => setTimeout(resolve, 0)); await flushPromises() }
    await open(0)
    expect(view.openCell).toBe('item-1:priority')
    await open(1)
    expect(view.openCell).toBe('item-2:priority')

    const clear = [...document.body.querySelectorAll<HTMLButtonElement>('.priority-option-pill--empty')].at(-1)!
    clear.click()
    await flushPromises()
    expect(view.openCell).toBeUndefined()
    expect(state.patchWorkItemPriority).toHaveBeenCalledOnce()
    expect(state.patchWorkItemPriority).toHaveBeenCalledWith(expect.objectContaining({ workItemId: 'item-2' }))
  })

  it('编辑已勾选的行同步修改全部勾选项并保留勾选；取消同步或编辑未勾选行只改当前行', async () => {
    state.listProjectWorkItems.mockResolvedValue(page(['item-1', 'item-2', 'item-3'].map(id => ({ ...item(id), priority: 'HIGH' }))))
    state.patchWorkItemPriority.mockImplementation(async ({ workItemId }: { workItemId: string }) =>
      ({ ...item(workItemId), etag: '"2"', rowVersion: 2 }) as unknown as WorkItemDetail)
    const wrapper = mountView()
    await flushPromises()
    const view = wrapper.vm as unknown as { openCell: string | undefined; selectedWorkItemIds: Set<string> }
    const checkboxes = wrapper.findAll<HTMLInputElement>('.el-table__body .monday-selection-column .el-checkbox__original')
    await checkboxes[0]!.setValue(true)
    await checkboxes[1]!.setValue(true)
    const clearPriority = async (row: number) => {
      await wrapper.findAll('.el-table__body .monday-priority-cell')[row]!.trigger('click')
      await new Promise(resolve => setTimeout(resolve, 0))
      await flushPromises()
      return () => [...document.body.querySelectorAll<HTMLButtonElement>('.priority-option-pill--empty')].at(-1)!.click()
    }
    const patched = () => state.patchWorkItemPriority.mock.calls.map(([request]) => request.workItemId)

    const clickClear = await clearPriority(0)
    expect(document.body.textContent).toContain('同步修改已选的 2 项')
    clickClear()
    await flushPromises()
    expect(patched()).toEqual(['item-1', 'item-2'])
    expect(view.openCell).toBeUndefined()
    expect([...view.selectedWorkItemIds]).toEqual(['item-1', 'item-2'])

    const clickClearSecond = await clearPriority(1)
    ;[...document.body.querySelectorAll<HTMLInputElement>('.work-item-batch-edit-scope input')].at(-1)!.click()
    await flushPromises()
    clickClearSecond()
    await flushPromises()
    expect(patched().slice(2)).toEqual(['item-2'])

    const clickClearThird = await clearPriority(2)
    clickClearThird()
    await flushPromises()
    expect(patched().slice(3)).toEqual(['item-3'])
  })

  it('应用类别目录后立即更新表格名称和颜色，无需重载工作项', async () => {
    const wrapper = mountView()
    await flushPromises()
    const listCalls = state.listProjectWorkItems.mock.calls.length
    expect(wrapper.findComponent({ name: 'WorkItemContentPopoverContent' }).exists()).toBe(false)
    await wrapper.get('.monday-content-label').trigger('click')
    await flushPromises()
    const next = catalog()
    next.items[0] = { ...next.items[0]!, name: '更新后的类别', colorToken: WorkItemLabelColorToken.BrightGreen }
    wrapper.findComponent({ name: 'WorkItemContentPopoverContent' }).vm.$emit('updated', next)
    await flushPromises()
    expect(wrapper.get('.monday-content-label').text()).toBe('更新后的类别')
    expect(wrapper.get('.monday-content-label').attributes('style')).toContain('--yp-label-bright-green')
    expect(state.listProjectWorkItems).toHaveBeenCalledTimes(listCalls)
  })

  it('选择具体截止日期立即提交自然日字段命令', async () => {
    state.patchWorkItemDueDate.mockResolvedValue({
      ...item(), dueDate: new Date('2026-09-01T00:00:00.000Z'), rowVersion: 2, etag: '"2"',
    } as unknown as WorkItemDetail)
    const wrapper = mountView()
    await flushPromises()
    const view = wrapper.vm as unknown as {
      tableItems: ProjectWorkItemListItem[]
      onDueDateChange: (item: ProjectWorkItemListItem, value: string | null) => void
    }

    view.onDueDateChange(view.tableItems[0]!, '2026-09-01')
    await flushPromises()

    expect(state.patchWorkItemDueDate).toHaveBeenCalledWith(expect.objectContaining({
      workItemId: 'item-1', ifMatch: '"1"',
      workItemDueDatePatchRequest: { dueDate: new Date('2026-09-01T00:00:00.000Z') },
    }))
    wrapper.unmount()
  })

  it('共享日期编辑提交时分，清空失败保留值且 412 不自动覆盖', async () => {
    const wrapper = mountView()
    await flushPromises()
    const view = wrapper.vm as unknown as {
      tableItems: ProjectWorkItemListItem[]
      onDeadlineChange: (row: ProjectWorkItemListItem, value: { dueDate: string | null; dueTime: string | null }) => void
    }
    state.patchWorkItemDueDate.mockResolvedValue({
      ...item(), dueDate: new Date('2026-09-03T00:00:00Z'), dueTime: '18:05',
      completedAt: new Date('2026-09-02T00:00:00Z'), rowVersion: 2, etag: '"2"',
    })
    view.onDeadlineChange(view.tableItems[0]!, { dueDate: '2026-09-03', dueTime: '18:05' })
    await flushPromises()
    expect(state.patchWorkItemDueDate).toHaveBeenLastCalledWith(expect.objectContaining({
      workItemDueDatePatchRequest: { dueDate: new Date('2026-09-03T00:00:00Z'), dueTime: '18:05' },
    }))
    expect(view.tableItems[0]?.dueTime).toBe('18:05')
    expect(view.tableItems[0]?.completedAt).toEqual(new Date('2026-09-02T00:00:00Z'))
    state.listProjectWorkItems.mockResolvedValue({ items: [...view.tableItems], nextCursor: null })
    state.patchWorkItemDueDate.mockRejectedValue(new ResponseError(new Response(JSON.stringify({
      code: 'VERSION_CONFLICT', message: '数据已被更新', requestId: 'deadline-conflict',
      retryable: false, fieldErrors: [], details: {},
    }), { status: 412, headers: { 'Content-Type': 'application/json' } })))
    view.onDeadlineChange(view.tableItems[0]!, { dueDate: null, dueTime: null })
    await flushPromises()
    expect(state.patchWorkItemDueDate).toHaveBeenCalledTimes(2)
    expect(state.patchWorkItemDueDate).toHaveBeenLastCalledWith(expect.objectContaining({
      ifMatch: '"2"', workItemDueDatePatchRequest: { dueDate: null, dueTime: null },
    }))
    expect(view.tableItems[0]?.dueTime).toBe('18:05')
    expect(view.tableItems[0]?.dueDate).not.toBeNull()
    wrapper.unmount()
  })

  it('表格状态连续迁移无感回写最新状态与 ETag', async () => {
    let current = item()
    current.capabilities = {
      ...current.capabilities,
      availableTransitions: [{
        toStatus: 'DONE', displayName: '已完成', statusCategory: WorkItemStatusCategory.Done,
        requiresResolution: false,
      }],
    }
    state.listProjectWorkItems.mockImplementation(() => Promise.resolve(page([current])))
    state.transitionWorkItem.mockImplementation((request: {
      workItemTransitionRequest: { toStatus: string }
    }) => {
      const nextVersion = current.rowVersion + 1
      const done = request.workItemTransitionRequest.toStatus === 'DONE'
      current = {
        ...current,
        statusCode: done ? 'DONE' : 'BACKLOG',
        statusCategory: done ? WorkItemStatusCategory.Done : WorkItemStatusCategory.Todo,
        rowVersion: nextVersion,
        etag: `"${nextVersion}"`,
        capabilities: {
          ...current.capabilities,
          availableTransitions: [{
            toStatus: done ? 'BACKLOG' : 'DONE',
            displayName: done ? '待开始' : '已完成',
            statusCategory: done ? WorkItemStatusCategory.Todo : WorkItemStatusCategory.Done,
            requiresResolution: false,
          }],
        },
      }
      return Promise.resolve(current as unknown as WorkItemDetail)
    })
    const wrapper = mountView()
    await flushPromises()
    const listCallsBeforeTransition = state.listProjectWorkItems.mock.calls.length
    const view = wrapper.vm as unknown as {
      tableItems: ProjectWorkItemListItem[]
      transitionItem: (row: ProjectWorkItemListItem, statusCode: string) => Promise<void>
    }

    await view.transitionItem(view.tableItems[0]!, 'DONE')
    await view.transitionItem(view.tableItems[0]!, 'BACKLOG')
    await flushPromises()

    expect(state.transitionWorkItem).toHaveBeenNthCalledWith(1, expect.objectContaining({
      ifMatch: '"1"', workItemTransitionRequest: { toStatus: 'DONE', resolution: null },
    }))
    expect(state.transitionWorkItem).toHaveBeenNthCalledWith(2, expect.objectContaining({
      ifMatch: '"2"', workItemTransitionRequest: { toStatus: 'BACKLOG', resolution: null },
    }))
    expect(state.listProjectWorkItems).toHaveBeenCalledTimes(listCallsBeforeTransition)
    expect(view.tableItems[0]).toMatchObject({ statusCode: 'BACKLOG', rowVersion: 3, etag: '"3"' })
  })

  it('通过左侧复选框选择多行，并为选中行应用浅蓝遮罩', async () => {
    const first = item('item-1')
    const second = item('item-2')
    state.listProjectWorkItems.mockResolvedValue(page([first, second]))
    const wrapper = mountView()
    await flushPromises()

    const view = wrapper.vm as unknown as {
      selectedWorkItemIds: Set<string>
      isRowSelected: (rowId: string) => boolean
      tableRowClassName: (context: { row: ProjectWorkItemListItem, rowIndex: number }) => string
    }
    const rowCheckboxes = wrapper.findAll<HTMLInputElement>('.el-table__body .monday-selection-column .el-checkbox__original')
    expect(rowCheckboxes).toHaveLength(2)

    await rowCheckboxes[0]!.setValue(true)
    expect([...view.selectedWorkItemIds]).toEqual(['item-1'])
    expect(wrapper.findAll('.el-table__body tr.work-item-table-row--selected')).toHaveLength(1)

    await rowCheckboxes[1]!.setValue(true)
    expect([...view.selectedWorkItemIds]).toEqual(['item-1', 'item-2'])
    expect(view.isRowSelected('item-1')).toBe(true)
    expect(view.tableRowClassName({ row: second, rowIndex: 1 })).toContain('work-item-table-row--selected')
    expect(wrapper.findAll('.el-table__body tr.work-item-table-row--selected')).toHaveLength(2)

    await rowCheckboxes[0]!.setValue(false)
    expect([...view.selectedWorkItemIds]).toEqual(['item-2'])
    expect(wrapper.findAll('.el-table__body tr.work-item-table-row--selected')).toHaveLength(1)
  })
})
