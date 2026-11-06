import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { nextTick, reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import WorkItemDetailDrawer from './WorkItemDetailDrawer.vue'
import { state } from './projectWorkItemsTestMocks'
import { mountView, installWorkItemsTestDefaults, restoreWorkItemsTestGlobals } from './projectWorkItemsTestSupport'

vi.mock('../../onboarding/useOnboarding', async () => (await import('./projectWorkItemsTestMocks')).onboardingModule())
vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))
vi.mock('vue-router', async () => (await import('./projectWorkItemsTestMocks')).vueRouterModule())
vi.mock('../../api/client', async () => (await import('./projectWorkItemsTestMocks')).apiClientModule())

enableAutoUnmount(afterEach)

describe('项目级工作项首页 · 详情抽屉与深链', () => {
  beforeEach(installWorkItemsTestDefaults)
  afterEach(restoreWorkItemsTestGlobals)

  it('名称右侧详情按钮、讨论和更新时间打开同一详情抽屉的对应区域', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('.work-item-detail-button').trigger('click')
    await flushPromises()
    expect(state.push).toHaveBeenCalledWith({ query: { workItemId: 'item-1' } })
    expect(state.getWorkItem).toHaveBeenLastCalledWith({ workItemId: 'item-1' })
    expect((wrapper.vm as unknown as { detailTab: string }).detailTab).toBe('details')

    await wrapper.get('[aria-label="打开协作讨论"]').trigger('click')
    await flushPromises()
    expect(state.getWorkItem).toHaveBeenCalledTimes(2)
    expect((wrapper.vm as unknown as { detailTab: string }).detailTab).toBe('discussion')

    await wrapper.get('button.work-item-updated-cell').trigger('click')
    await flushPromises()
    expect(state.getWorkItem).toHaveBeenCalledTimes(3)
    expect((wrapper.vm as unknown as { detailTab: string }).detailTab).toBe('activity')
  })

  it('通知深链消费 discussion 参数，关闭后普通标题恢复详情且不重载列表', async () => {
    state.route.query = { workItemId: 'item-1', tab: 'discussion' }
    const wrapper = mountView()
    await flushPromises()
    expect((wrapper.vm as unknown as { detailTab: string }).detailTab).toBe('discussion')
    expect(state.replace).toHaveBeenCalledWith({ query: { workItemId: 'item-1' } })
    expect(state.route.query).not.toHaveProperty('tab')
    expect(state.getWorkItem).toHaveBeenCalledTimes(1)
    const listCalls = state.listProjectWorkItems.mock.calls.length
    const view = wrapper.vm as unknown as { detailTab: string; onDetailModelValue: (value: boolean) => void }
    view.onDetailModelValue(false)
    await nextTick(); await flushPromises()
    expect(state.route.query).toEqual({})
    await wrapper.get('.work-item-detail-button').trigger('click')
    await flushPromises()
    expect(view.detailTab).toBe('details')
    expect(state.listProjectWorkItems).toHaveBeenCalledTimes(listCalls)
    state.route.query.tab = 'discussion'
    await nextTick(); await flushPromises()
    expect(view.detailTab).toBe('discussion')
    expect(state.route.query).not.toHaveProperty('tab')
    expect(state.listProjectWorkItems).toHaveBeenCalledTimes(listCalls)
  })

  it('直达 workItemId 路由恢复抽屉，外部改为未加载工作项时重载列表定位', async () => {
    state.route.query = { view: 'table', workItemId: 'item-1' }
    const wrapper = mountView()
    await flushPromises()
    const listCalls = state.listProjectWorkItems.mock.calls.length

    expect(state.getWorkItem).toHaveBeenCalledWith({ workItemId: 'item-1' })
    state.route.query.workItemId = 'item-2'
    await nextTick()
    await flushPromises()

    expect(state.getWorkItem).toHaveBeenLastCalledWith({ workItemId: 'item-2' })
    expect(state.listProjectWorkItems).toHaveBeenCalledTimes(listCalls + 1)
    wrapper.unmount()
  })

  it('旧关系页链接回退详情，清理 tab 后不会再次加载或沿用当前讨论页', async () => {
    state.route.query = { workItemId: 'item-1', tab: 'relations' }
    const wrapper = mountView()
    await flushPromises()
    const view = wrapper.vm as unknown as { detailTab: string }
    expect(view.detailTab).toBe('details')
    expect(state.route.query).toEqual({ workItemId: 'item-1' })
    expect(state.getWorkItem).toHaveBeenCalledTimes(1)
    view.detailTab = 'discussion'
    state.route.query.tab = 'relations'
    await nextTick(); await flushPromises()
    expect(view.detailTab).toBe('details')
    expect(state.route.query).toEqual({ workItemId: 'item-1' })
    expect(state.getWorkItem).toHaveBeenCalledTimes(2)
  })

  it('分别高亮可交互内容块，并让固定名称列的表格独立滚动', async () => {
    const wrapper = mountView()
    await flushPromises()

    const view = wrapper.vm as unknown as {
      detailOpen: boolean
      detail: { id: string } | undefined
      selectedRowId: string | undefined
      selectedCellKey: string | undefined
      selectCell: (rowId: string, cellKey: string) => void
      isRowSelected: (rowId: string) => boolean
      syncPageScrollbars: () => void
      horizontalOverflow: boolean
    }

    const table = wrapper.findComponent({ name: 'ElTable' })
    const tableColumns = wrapper.findAllComponents({ name: 'ElTableColumn' })
    const selectionColumn = tableColumns.find(column => column.props('type') === 'selection')
    const titleColumn = tableColumns.find(column => column.props('columnKey') === 'title')
    expect(table.props('height')).toBe('100%')
    expect(selectionColumn?.props('width')).toBe(48)
    expect(selectionColumn?.props('fixed')).toBe(true)
    expect(selectionColumn?.props('reserveSelection')).toBe(true)
    expect(titleColumn?.props('fixed')).toBe(true)
    expect(titleColumn?.props('minWidth')).toBe('')
    expect(titleColumn?.props('width')).toBe(320)
    expect(wrapper.find('.el-table__append-wrapper .monday-quick-add').exists()).toBe(true)

    const tableScroll = wrapper.get('.monday-table .el-scrollbar__wrap').element as HTMLElement
    const tableHeaderScroll = wrapper.get('.monday-table .el-table__header-wrapper').element as HTMLElement
    const horizontalScrollbar = document.body.querySelector<HTMLElement>('.project-table-scrollbar--horizontal')
    const verticalScrollbar = document.body.querySelector<HTMLElement>('.project-table-scrollbar--vertical')
    expect(horizontalScrollbar).not.toBeNull()
    expect(verticalScrollbar).not.toBeNull()
    Object.defineProperties(tableScroll, {
      clientWidth: { configurable: true, value: 800 },
      scrollWidth: { configurable: true, value: 1120 },
      clientHeight: { configurable: true, value: 300 },
      scrollHeight: { configurable: true, value: 850 },
    })
    Object.defineProperties(horizontalScrollbar!, {
      clientWidth: { configurable: true, value: 900 },
    })
    Object.defineProperties(verticalScrollbar!, {
      clientHeight: { configurable: true, value: 700 },
    })
    view.syncPageScrollbars()
    await nextTick()
    expect(view.horizontalOverflow).toBe(true)
    expect(horizontalScrollbar!.style.display).not.toBe('none')
    expect(horizontalScrollbar!.querySelector<HTMLElement>('.project-table-scrollbar__horizontal-spacer')?.style.width).toBe('1220px')
    expect(verticalScrollbar!.querySelector<HTMLElement>('.project-table-scrollbar__vertical-spacer')?.style.height).toBe('1250px')

    horizontalScrollbar!.scrollLeft = 120
    horizontalScrollbar!.dispatchEvent(new Event('scroll'))
    expect(tableScroll.scrollLeft).toBe(120)
    expect(tableHeaderScroll.scrollLeft).toBe(120)
    verticalScrollbar!.scrollTop = 180
    verticalScrollbar!.dispatchEvent(new Event('scroll'))
    expect(tableScroll.scrollTop).toBe(180)
    tableScroll.scrollLeft = 240
    tableScroll.scrollTop = 360
    tableScroll.dispatchEvent(new Event('scroll'))
    expect(horizontalScrollbar!.scrollLeft).toBe(240)
    expect(tableHeaderScroll.scrollLeft).toBe(240)
    expect(verticalScrollbar!.scrollTop).toBe(360)

    // 名称高亮只覆盖名称按钮，不延伸到讨论按钮所在区域
    view.selectCell('item-1', 'title')
    await nextTick()
    const titleBtn = wrapper.find('.work-item-link')
    const discussionBtn = wrapper.find('.monday-discussion-btn')
    expect(titleBtn.classes()).toContain('monday-cell--selected')
    expect(titleBtn.element.closest('td')?.classList.contains('monday-cell--selected')).toBe(false)
    expect(discussionBtn.classes()).not.toContain('monday-cell--selected')

    // 选中状态单元格
    const statusBtn = wrapper.find('.monday-status-cell')
    await statusBtn.trigger('click')
    expect(view.selectedRowId).toBe('item-1')
    expect(view.selectedCellKey).toBe('item-1:status')
    expect(statusBtn.element.closest('td')?.classList.contains('monday-cell--selected')).toBe(true)

    // 选中优先级单元格
    const priorityBtn = wrapper.find('.monday-priority-cell')
    expect(priorityBtn.text()).toBe('-')
    expect(priorityBtn.classes()).toContain('monday-priority-cell--empty')
    await priorityBtn.trigger('click')
    expect(view.selectedCellKey).toBe('item-1:priority')
    expect(priorityBtn.element.closest('td')?.classList.contains('monday-cell--selected')).toBe(true)
    expect(statusBtn.element.closest('td')?.classList.contains('monday-cell--selected')).toBe(false)

    const assigneeBtn = wrapper.find('.monday-cell-centered')
    await assigneeBtn.trigger('click')
    expect(view.selectedCellKey).toBe('item-1:assignee')
    expect(assigneeBtn.element.closest('td')?.classList.contains('monday-cell--selected')).toBe(true)

    const dueDateBtn = wrapper.find('.monday-due-date-cell')
    await dueDateBtn.trigger('click')
    expect(view.selectedCellKey).toBe('item-1:dueDate')
    expect(dueDateBtn.element.closest('td')?.classList.contains('monday-cell--selected')).toBe(true)

    // 抽屉打开时，同时高亮打开的工作项行和新点击的标签行
    view.detailOpen = true
    view.detail = { id: 'item-1' }
    view.selectCell('item-2', 'status')
    await nextTick()
    expect(view.isRowSelected('item-1')).toBe(true)
    expect(view.isRowSelected('item-2')).toBe(true)
    expect(view.selectedCellKey).toBe('item-2:status')
    expect(document.body.classList.contains('yp-project-overview-scroll')).toBe(true)
    expect(document.body.classList.contains('yp-work-items-drawer-open')).toBe(true)
    expect(document.body.style.getPropertyValue('--yp-work-items-drawer-width')).toBe('560px')

    // 详情抽屉不带深色蒙版且包含左侧关闭按钮与拖动手柄
    const drawer = wrapper.findAllComponents({ name: 'ElDrawer' })
      .find(candidate => candidate.props('title') === '工作项详情')!
    expect(drawer.props('modal')).toBe(false)
    expect(drawer.props('modalPenetrable')).toBe(true)
    expect(drawer.props('appendToBody')).toBe(true)
    expect(drawer.props('showClose')).toBe(true)
    expect(document.body.querySelector('.work-items-detail-drawer__header .el-drawer__close-btn')).not.toBeNull()
    expect(document.body.querySelector('.drawer-resize-handle')).not.toBeNull()

    // 拖动手柄调整抽屉宽度
    const resize = wrapper.getComponent(WorkItemDetailDrawer).vm as unknown as {
      drawerWidth: number
      isResizingDrawer: boolean
      onDrawerResizePointerDown: (event: PointerEvent) => void
    }
    resize.onDrawerResizePointerDown({
      clientX: 500,
      preventDefault: vi.fn(),
      stopPropagation: vi.fn(),
    } as unknown as PointerEvent)
    expect(resize.isResizingDrawer).toBe(true)
    await nextTick()
    expect(document.body.querySelector('.work-items-detail-drawer--resizing')).not.toBeNull()
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 400 }))
    expect(resize.drawerWidth).toBe(660)
    expect(document.body.style.getPropertyValue('--yp-work-items-drawer-width')).toBe('660px')
    window.dispatchEvent(new MouseEvent('pointerup'))
    expect(resize.isResizingDrawer).toBe(false)
    await nextTick()
    expect(document.body.querySelector('.work-items-detail-drawer--resizing')).toBeNull()
    expect(document.body.style.getPropertyValue('--yp-work-items-drawer-width')).toBe('660px')

    // 抽屉在动态单行布局允许的 480px 下限停止缩窄
    resize.onDrawerResizePointerDown({
      clientX: 400,
      preventDefault: vi.fn(),
      stopPropagation: vi.fn(),
    } as unknown as PointerEvent)
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 900 }))
    expect(resize.drawerWidth).toBe(480)
    window.dispatchEvent(new MouseEvent('pointerup'))

    drawer.vm.$emit('close')
    await nextTick()
    expect(view.detailOpen).toBe(false)
    expect(document.body.classList.contains('yp-work-items-drawer-open')).toBe(false)
    expect(document.body.style.getPropertyValue('--yp-work-items-drawer-inset')).toBe('0px')
    drawer.vm.$emit('update:modelValue', false)
    await nextTick()
    expect(document.body.style.getPropertyValue('--yp-work-items-drawer-width')).toBe('')
  })

  it('标签、抽屉关闭与工作项路由共用草稿保护，保存期间禁止离开', async () => {
    state.route.query.workItemId = 'item-1'
    const draft = reactive({ hasDraft: true, busy: false, discardDraft: vi.fn(() => { draft.hasDraft = false }) })
    const wrapper = mountView(draft)
    await flushPromises()
    const guard = state.routeLeave.mock.calls[0]![0] as () => Promise<boolean>
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValueOnce('cancel')
    expect(await guard()).toBe(false)
    expect(draft.discardDraft).not.toHaveBeenCalled()
    draft.busy = true
    expect(await guard()).toBe(false)
    expect(confirm).toHaveBeenCalledTimes(1)
    draft.busy = false
    const drawer = wrapper.findAllComponents({ name: 'ElDrawer' })
      .find(candidate => candidate.props('title') === '工作项详情')!
    const done = vi.fn()
    confirm.mockResolvedValueOnce('confirm' as never)
    await (drawer.props('beforeClose') as (done: () => void) => Promise<void>)(done)
    expect(done).toHaveBeenCalledOnce()
    expect(draft.discardDraft).toHaveBeenCalledOnce()
    const routeGuard = state.routeUpdate.mock.calls[0]![0]
    draft.hasDraft = true
    confirm.mockRejectedValueOnce('cancel')
    expect(await routeGuard({ params: { projectId: 'project-1' }, query: { workItemId: 'item-2' } }, state.route)).toBe(false)
    confirm.mockResolvedValueOnce('confirm' as never)
    expect(await routeGuard({ params: { projectId: 'project-1' }, query: { workItemId: 'item-2' } }, state.route)).toBe(true)
    expect(draft.discardDraft).toHaveBeenCalledTimes(2)
    expect(wrapper.findComponent({ name: 'WorkItemDetailPanel' }).props('detail')).toMatchObject({ id: 'item-1' })
  })
})
