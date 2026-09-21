import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DashboardExploreDialog from './DashboardExploreDialog.vue'
import { emptyFilters, newWidget } from './dashboardModel'

const api = vi.hoisted(() => ({ queryDashboardItems: vi.fn() }))
vi.mock('../../api/client', () => ({ dashboardsApi: api }))
vi.mock('../projects/WorkItemTimerCell.vue', () => ({ default: {} }))
const Chart = defineComponent({ props: ['selection'], emits: ['select'], template: '<div />' })
const closeAllowed = vi.fn(async () => true)
const Table = defineComponent({ props: ['selection', 'refreshKey'], emits: ['changed'], setup(_, { expose }) { expose({ canClose: closeAllowed }); return () => null } })
const Dialog = defineComponent({ props: ['modelValue'], template: '<div><slot name="header"/><slot/><slot name="footer"/></div>' })
const Button = defineComponent({ emits: ['click'], template: '<button @click="$emit(\'click\')"><slot/></button>' })
const wrappers: ReturnType<typeof mount>[] = []
function render() {
  const wrapper = mount(DashboardExploreDialog, { props: { dashboardId: 'dashboard', widget: newWidget('CHART'), filters: emptyFilters(), projects: [] }, global: { stubs: { DashboardChart: Chart, DashboardItemsTable: Table, ElDialog: Dialog, ElButton: Button, ElTooltip: { template: '<div><slot/></div>' }, ElDropdown: { template: '<div><slot/></div>' }, ElPagination: true, ElAlert: true, ElTag: true } } })
  wrappers.push(wrapper); return wrapper
}
beforeEach(() => { closeAllowed.mockResolvedValue(true); api.queryDashboardItems.mockReset().mockResolvedValue({ items: [], totalElements: 50 }) })
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.useRealTimers() })
describe('组件展开视图', () => {
  it('keeps the selected scope when a shared table has an unfinished edit', async () => {
    const wrapper = render(); await flushPromises()
    closeAllowed.mockResolvedValue(false)
    wrapper.findComponent(Chart).vm.$emit('select', { key: 'other' }); await flushPromises()
    expect(wrapper.findComponent(Chart).props('selection')).toBeUndefined()
    expect(api.queryDashboardItems).toHaveBeenCalledTimes(1)
  })
  it('passes category and series scope to the shared project tables', async () => {
    const wrapper = render(); await flushPromises()
    const chart = wrapper.findComponent(Chart)
    chart.vm.$emit('select', { key: 'status:doing', seriesKey: 'user:one' }); await flushPromises()
    expect(api.queryDashboardItems.mock.calls.at(-1)?.[0].dashboardItemsQuery).toMatchObject({ limit: 1, offset: 0, selection: { key: 'status:doing', seriesKey: 'user:one' } })
    expect(chart.props('selection')).toEqual({ key: 'status:doing', seriesKey: 'user:one' })
    expect(wrapper.findComponent(Table).props('selection')).toEqual({ key: 'status:doing', seriesKey: 'user:one' })
    expect(wrapper.findComponent({ name: 'ElPagination' }).exists()).toBe(false)
    chart.vm.$emit('select', { key: 'status:doing', seriesKey: 'user:one' }); await flushPromises()
    expect(api.queryDashboardItems.mock.calls.at(-1)?.[0].dashboardItemsQuery.selection).toBeUndefined()
    expect(chart.props('selection')).toBeUndefined()
  })
  it('refreshes chart and matching rows after a work-item edit and retains selection across settings', async () => {
    const wrapper = render(); await flushPromises()
    wrapper.findComponent(Chart).vm.$emit('select', { key: 'doing' }); await flushPromises()
    await wrapper.setProps({ hidden: true }); await wrapper.setProps({ hidden: false }); await flushPromises()
    expect(wrapper.findComponent(Chart).props('selection')).toEqual({ key: 'doing' })
    vi.useFakeTimers()
    wrapper.findComponent(Table).vm.$emit('changed'); await vi.advanceTimersByTimeAsync(100); await flushPromises()
    vi.useRealTimers()
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    expect(api.queryDashboardItems.mock.calls.at(-1)?.[0].dashboardItemsQuery.selection).toEqual({ key: 'doing' })
  })
})
