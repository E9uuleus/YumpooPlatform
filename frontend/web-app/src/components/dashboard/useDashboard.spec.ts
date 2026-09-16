import { defineComponent, nextTick, reactive, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defaultConfiguration } from './dashboardModel'
import { useDashboard } from './useDashboard'

const mocks = vi.hoisted(() => ({ listDashboards: vi.fn(), getDashboard: vi.fn(), queryDashboard: vi.fn(), updateDashboard: vi.fn(), createDashboard: vi.fn(), deleteDashboard: vi.fn(), push: vi.fn(), replace: vi.fn(), guard: vi.fn() }))
vi.mock('../../api/client', () => ({ dashboardsApi: mocks }))
const route = reactive({ params: { dashboardId: 'one', workspaceSlug: 'member' } })
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => mocks, onBeforeRouteLeave: (guard: unknown) => mocks.guard(guard), onBeforeRouteUpdate: vi.fn() }))
vi.mock('../../api/problems', () => ({ toApiProblem: async (reason: unknown) => reason, problemMessage: () => '保存失败' }))
vi.mock('../../composables/useSession', () => ({ useSession: () => ({ authentication: ref({ company: { id: 'company' }, user: { id: 'user' } }) }) }))
const view = (version = 0) => ({ id: 'one', name: '我的仪表板', _configuration: defaultConfiguration(), projects: [], version, etag: `"${version}"`, updatedAt: new Date() })
let wrapper: ReturnType<typeof mount>, state: ReturnType<typeof useDashboard>
beforeEach(async () => {
  vi.useFakeTimers(); vi.clearAllMocks()
  route.params.dashboardId = 'one'
  mocks.push.mockImplementation(async target => { route.params.dashboardId = target.params.dashboardId; await nextTick() })
  mocks.listDashboards.mockResolvedValue({ items: [{ id: 'one', name: '我的仪表板', projectCount: 0, widgetCount: 8, updatedAt: new Date() }] })
  mocks.getDashboard.mockResolvedValue(view()); mocks.queryDashboard.mockResolvedValue({ buckets: [], options: [], projects: [], asOf: new Date() })
  wrapper = mount(defineComponent({ setup() { state = useDashboard(); return () => null } })); await flushPromises()
})
afterEach(() => { wrapper.unmount(); vi.useRealTimers() })
describe('dashboard persistence', () => {
  it('waits for the new dashboard configuration before opening project selection', async () => {
    state.configuration.value.projectIds = ['previous-project']
    let resolveLoad!: (value: unknown) => void
    mocks.createDashboard.mockResolvedValue({ ...view(), id: 'two' })
    mocks.getDashboard.mockImplementationOnce(() => new Promise(resolve => { resolveLoad = resolve }))
    let finished = false
    const creating = state.create('空白仪表板', defaultConfiguration(true)).then(() => { finished = true })
    await flushPromises()
    expect(finished).toBe(false)
    resolveLoad({ ...view(), id: 'two', _configuration: defaultConfiguration(true) })
    await creating
    expect(state.configuration.value.projectIds).toEqual([])
    expect(state.configuration.value.widgets).toEqual([])
  })
  it('serializes concurrent edits and never replaces the newer local draft', async () => {
    let resolveFirst!: (value: unknown) => void
    mocks.updateDashboard.mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve })).mockResolvedValueOnce(view(2))
    state.name.value = '第一版'; state.changed(); const first = state.save()
    state.name.value = '第二版'; state.changed(); const second = state.save()
    expect(mocks.updateDashboard).toHaveBeenCalledTimes(1)
    resolveFirst(view(1)); await Promise.all([first, second]); await nextTick()
    expect(mocks.updateDashboard).toHaveBeenCalledTimes(2)
    expect(mocks.updateDashboard.mock.calls[1]?.[0]).toMatchObject({ ifMatch: '"1"', dashboardWrite: { name: '第二版' } })
    expect(state.name.value).toBe('第二版'); expect(state.dirty.value).toBe(false)
  })
  it('retries an uncertain write with the same idempotency key and payload', async () => {
    mocks.updateDashboard.mockRejectedValueOnce({ kind: 'fallback' }).mockResolvedValueOnce(view(1))
    state.name.value = '网络重试'; state.changed(); expect(await state.save()).toBe(false)
    const first = mocks.updateDashboard.mock.calls[0]?.[0]
    expect(state.dirty.value).toBe(true); expect(await state.save()).toBe(true)
    expect(mocks.updateDashboard.mock.calls[1]?.[0]).toEqual(first)
  })
  it('preserves a conflicting draft and blocks navigation instead of overwriting', async () => {
    mocks.updateDashboard.mockRejectedValue({ kind: 'response', status: 412 })
    state.name.value = '保留我的更改'; state.changed(); expect(await state.save()).toBe(false)
    expect(state.conflict.value).toBe(true); expect(state.name.value).toBe('保留我的更改')
    await state.switchTo('two'); expect(mocks.push).not.toHaveBeenCalled()
    expect(mocks.updateDashboard).toHaveBeenCalledTimes(1)
  })
})
