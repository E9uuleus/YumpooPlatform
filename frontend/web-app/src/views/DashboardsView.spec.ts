import { shallowMount } from '@vue/test-utils'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import DashboardsView from './DashboardsView.vue'
import { defaultConfiguration, newWidget } from '../components/dashboard/dashboardModel'

const mocks = vi.hoisted(() => ({ state: vi.fn(), pdf: vi.fn() }))
vi.mock('../components/dashboard/useDashboard', () => ({ useDashboard: mocks.state }))
vi.mock('../components/dashboard/dashboardPdf', () => ({ exportDashboardPdf: mocks.pdf }))
vi.mock('../composables/useSession', () => ({ useSession: () => ({ isCompanyAdmin: ref(false), authentication: ref({ company: { timezone: 'Asia/Shanghai' }, user: { id: 'user' } }) }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { dashboardId: 'one' } }) }))

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
