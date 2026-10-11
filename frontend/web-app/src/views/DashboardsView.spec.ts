import { enableAutoUnmount, flushPromises, shallowMount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DashboardsView from './DashboardsView.vue'
import { defaultConfiguration, MY_TIME_VIEW_ID, newWidget } from '../components/dashboard/dashboardModel'

const mocks = vi.hoisted(() => ({ state: vi.fn(), pdf: vi.fn(), ready: vi.fn(), route: { params: { dashboardId: 'one' } } }))
vi.mock('../components/dashboard/useDashboard', () => ({ useDashboard: mocks.state }))
vi.mock('../components/dashboard/dashboardPdf', () => ({ exportDashboardPdf: mocks.pdf }))
vi.mock('../onboarding/useOnboarding', () => ({ usePageOnboarding: () => ({ ready: mocks.ready }) }))
vi.mock('../composables/useSession', () => ({ useSession: () => ({ isCompanyAdmin: ref(false), authentication: ref({ company: { timezone: 'Asia/Shanghai' }, user: { id: 'user' } }) }) }))
vi.mock('vue-router', () => ({ useRoute: () => mocks.route }))

enableAutoUnmount(afterEach)
beforeEach(() => { mocks.ready.mockClear(); mocks.route.params.dashboardId = 'one' })

function dashboardState() {
  const state = { configuration: ref(defaultConfiguration(true)), refreshing: ref(false), snapshot: ref(), dashboards: ref([]), dashboard: ref(),
    name: ref('仪表板'), loading: ref(true), reloading: ref(false), saving: ref(false), error: ref(''), saveError: ref(''), recovery: ref(),
    changed: vi.fn(), save: vi.fn(), refresh: vi.fn(), create: vi.fn(), remove: vi.fn(), switchTo: vi.fn(), load: vi.fn(), reload: vi.fn(), resolveRecovery: vi.fn() }
  mocks.state.mockReturnValue(state)
  return state
}

describe('dashboard onboarding readiness', () => {
  it('waits through loading and failure, then starts after a successful retry', async () => {
    const state = dashboardState()
    shallowMount(DashboardsView)
    await flushPromises()
    expect(mocks.ready).not.toHaveBeenCalled()
    state.error.value = 'offline'
    state.loading.value = false
    await flushPromises()
    expect(mocks.ready).not.toHaveBeenCalled()
    state.refreshing.value = true
    state.error.value = ''
    await flushPromises()
    expect(mocks.ready).not.toHaveBeenCalled()
    state.refreshing.value = false
    await flushPromises()
    expect(mocks.ready).toHaveBeenCalledOnce()
  })

  it('requires both the dashboard list and fixed view to load successfully', async () => {
    mocks.route.params.dashboardId = MY_TIME_VIEW_ID
    const state = dashboardState(), ready = ref(false)
    shallowMount(DashboardsView, { global: { stubs: { MyTimeView: defineComponent({
      setup(_, { expose }) { expose({ ready, loading: false, refresh: vi.fn() }); return () => null },
    }) } } })
    state.loading.value = false
    await flushPromises()
    expect(mocks.ready).not.toHaveBeenCalled()
    state.error.value = 'list failed'
    ready.value = true
    await flushPromises()
    expect(mocks.ready).not.toHaveBeenCalled()
    state.error.value = ''
    await flushPromises()
    expect(mocks.ready).toHaveBeenCalledOnce()
  })
})

describe('dashboard PDF availability', () => {
  it('waits for every newly added widget result during the preview debounce and refresh', async () => {
    const configuration = ref(defaultConfiguration(true)), refreshing = ref(false)
    const widget = newWidget('METRIC', [])
    configuration.value.widgets.push(widget)
    const snapshot = ref({ charts: [{ id: widget.id }], projects: [] })
    mocks.state.mockReturnValue({ configuration, refreshing, snapshot, dashboards: ref([]), dashboard: ref({ id: 'one', projects: [] }),
      name: ref('仪表板'), loading: ref(false), reloading: ref(false), saving: ref(false), error: ref(''), saveError: ref(''), recovery: ref(),
      changed: vi.fn(), save: vi.fn(), refresh: vi.fn(), create: vi.fn(), remove: vi.fn(), switchTo: vi.fn(), load: vi.fn(), reload: vi.fn(), resolveRecovery: vi.fn() })
    const wrapper = shallowMount(DashboardsView)
    const button = () => wrapper.findAllComponents({ name: 'ElButton' }).find(component => component.attributes('aria-label') === '导出 PDF')!
    expect(button().props('disabled')).toBe(false)
    const added = newWidget('METRIC', configuration.value.widgets)
    configuration.value.widgets.push(added)
    await wrapper.vm.$nextTick()
    expect(refreshing.value).toBe(false)
    expect(button().props('disabled')).toBe(true)
    button().vm.$emit('click')
    await wrapper.vm.$nextTick()
    expect(mocks.pdf).not.toHaveBeenCalled()
    refreshing.value = true
    snapshot.value.charts.push({ id: added.id })
    await wrapper.vm.$nextTick()
    expect(button().props('disabled')).toBe(true)
    refreshing.value = false
    await wrapper.vm.$nextTick()
    expect(button().props('disabled')).toBe(false)
    wrapper.unmount()
  })
})
