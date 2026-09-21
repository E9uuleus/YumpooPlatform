import { defineComponent, nextTick, reactive, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defaultConfiguration, newWidget } from './dashboardModel'
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
  vi.useFakeTimers(); vi.resetAllMocks()
  route.params.dashboardId = 'one'
  mocks.push.mockImplementation(async target => { route.params.dashboardId = target.params.dashboardId; await nextTick() })
  mocks.listDashboards.mockResolvedValue({ items: [{ id: 'one', name: '我的仪表板', projectCount: 0, widgetCount: 8, updatedAt: new Date() }] })
  mocks.getDashboard.mockResolvedValue(view()); mocks.queryDashboard.mockResolvedValue({ buckets: [], options: [], projects: [], asOf: new Date() })
  wrapper = mount(defineComponent({ setup() { state = useDashboard(); return () => null } })); await flushPromises()
})
afterEach(() => { wrapper.unmount(); vi.useRealTimers() })
describe('dashboard persistence', () => {
  it('preserves a chosen empty drop row when saving and editing chart settings', async () => {
    const widget = newWidget('CHART')
    widget.wide = { x: 2, y: 9, w: 7, h: 6 }
    widget.medium = { x: 0, y: 4, w: 6, h: 6 }
    state.configuration.value.widgets = [widget]
    mocks.updateDashboard.mockResolvedValue(view(1))
    state.changed(); await state.save()
    state.configuration.value.widgets[0]!.title = '新标题'; state.changed(true); await state.save()
    for (const [request] of mocks.updateDashboard.mock.calls) {
      expect(request.dashboardWrite._configuration.widgets[0]).toMatchObject({ wide: widget.wide, medium: widget.medium })
    }
    expect(state.configuration.value.widgets[0]!.wide.y).toBe(9)
  })
  it('debounces draft chart previews before saving and restores nested filter dates', async () => {
    const widget = newWidget('CHART', [])
    widget.chart!.filters = { ...state.configuration.value.filters, dueFrom: '2026-09-17' as unknown as Date }
    state.configuration.value.widgets.push(widget)
    state.changed(true); state.changed(true)
    await vi.advanceTimersByTimeAsync(249)
    expect(mocks.queryDashboard).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(mocks.queryDashboard).toHaveBeenCalledTimes(2)
    expect(mocks.updateDashboard).not.toHaveBeenCalled()
    const sent = mocks.queryDashboard.mock.calls[1]?.[0].dashboardQuery.widgets.at(-1)
    expect(sent.chart.filters.dueFrom).toBeInstanceOf(Date)
    expect(sent.chart).toMatchObject({ type: 'COLUMN', dimension: 'STATUS', projectIds: null })
  })
  it('ignores an older preview after another edit and preserves the draft on query failure', async () => {
    let resolveFirst!: (value: unknown) => void
    mocks.queryDashboard.mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve }))
    const first = state.refresh()
    state.configuration.value.widgets[0]!.title = '最新草稿'; state.changed(true)
    resolveFirst({ charts: [{ id: 'stale', points: [] }] }); await first
    expect(state.snapshot.value?.charts).toBeUndefined()
    mocks.queryDashboard.mockRejectedValueOnce(new Error('offline'))
    await vi.advanceTimersByTimeAsync(250)
    expect(state.error.value).toBeTruthy()
    expect(state.configuration.value.widgets[0]!.title).toBe('最新草稿')
  })
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
  it('automatically rebases a stale version and saves this window without loading the remote layout', async () => {
    mocks.updateDashboard.mockRejectedValueOnce({ kind: 'response', status: 412 }).mockResolvedValueOnce(view(5))
    mocks.getDashboard.mockResolvedValue({ ...view(4), name: '另一窗口', _configuration: defaultConfiguration(true) })
    state.name.value = '保留我的更改'; state.configuration.value.widgets[0]!.wide.h = 8; state.changed()
    const local = structuredClone(JSON.parse(JSON.stringify(state.configuration.value)))
    expect(await state.save()).toBe(true)
    expect(mocks.updateDashboard.mock.calls[1]?.[0]).toMatchObject({ ifMatch: '"4"', dashboardWrite: { name: '保留我的更改', _configuration: local } })
    expect(mocks.updateDashboard.mock.calls[1]?.[0].idempotencyKey).not.toBe(mocks.updateDashboard.mock.calls[0]?.[0].idempotencyKey)
    expect(state.name.value).toBe('保留我的更改'); expect(state.configuration.value).toEqual(local)
    expect(state.dirty.value).toBe(false); expect(state.saveError.value).toBe(''); expect(state.recovery.value).toBeUndefined()
  })
  it('saves edits made while fetching a newer ETag and ignores that fetch after switching dashboards', async () => {
    let latest!: (value: unknown) => void
    mocks.updateDashboard.mockRejectedValueOnce({ kind: 'response', status: 412 }).mockResolvedValueOnce(view(5))
    mocks.getDashboard.mockImplementationOnce(() => new Promise(resolve => { latest = resolve }))
    state.name.value = '第一版'; state.changed(); const saving = state.save(); await flushPromises()
    state.name.value = '第二版'; state.changed(); latest(view(4)); expect(await saving).toBe(true)
    expect(mocks.updateDashboard.mock.calls[1]?.[0].dashboardWrite.name).toBe('第二版')
    mocks.updateDashboard.mockRejectedValueOnce({ kind: 'response', status: 412 })
    mocks.getDashboard.mockImplementationOnce(() => new Promise(resolve => { latest = resolve }))
    state.changed(); const staleSave = state.save(); await flushPromises()
    mocks.getDashboard.mockResolvedValueOnce({ ...view(), id: 'two', name: '第二个仪表板' })
    await state.load('two'); latest(view(9)); await staleSave
    expect(state.dashboard.value?.id).toBe('two'); expect(state.dashboard.value?.etag).toBe('"0"')
    expect(mocks.updateDashboard).toHaveBeenCalledTimes(3)
  })
  it('backs off sustained version contention then resumes automatically without conflict UI', async () => {
    mocks.updateDashboard.mockRejectedValue({ kind: 'response', status: 412 })
    state.changed(); expect(await state.save()).toBe(false)
    expect(mocks.updateDashboard).toHaveBeenCalledTimes(4)
    expect(state.dirty.value).toBe(true); expect(state.saveError.value).toBe('')
    mocks.updateDashboard.mockResolvedValueOnce(view(6))
    await vi.advanceTimersByTimeAsync(650)
    expect(state.dirty.value).toBe(false)
  })
  it('shows validation reasons and automatically saves a corrected draft', async () => {
    mocks.updateDashboard.mockRejectedValueOnce({ kind: 'response', status: 422, error: { fieldErrors: [{ message: '组件超出 6 列画布' }] } }).mockResolvedValueOnce(view(1))
    state.changed(); expect(await state.save()).toBe(false)
    expect(state.saveError.value).toBe('组件超出 6 列画布')
    state.configuration.value.widgets[0]!.medium.x = 0; state.changed()
    await vi.advanceTimersByTimeAsync(650)
    expect(state.dirty.value).toBe(false)
    expect(mocks.updateDashboard.mock.calls[1]?.[0].idempotencyKey).not.toBe(mocks.updateDashboard.mock.calls[0]?.[0].idempotencyKey)
  })
  it('allows discarding a failed draft when leaving', async () => {
    mocks.updateDashboard.mockRejectedValue({ kind: 'fallback' })
    state.name.value = '坏草稿'; state.changed()
    const leaving = mocks.guard.mock.calls.at(-1)![0]()
    await flushPromises(); expect(state.recovery.value).toBe('leave')
    await state.resolveRecovery('discard')
    expect(await leaving).toBe(true); expect(state.dirty.value).toBe(false)
    expect(state.name.value).toBe('我的仪表板')
  })
  it('only reloads configuration and data on explicit refresh', async () => {
    window.dispatchEvent(new Event('focus')); document.dispatchEvent(new Event('visibilitychange')); window.dispatchEvent(new Event('yumpoo:timer-started'))
    await vi.advanceTimersByTimeAsync(120000)
    expect(mocks.queryDashboard).toHaveBeenCalledTimes(1)
    mocks.getDashboard.mockResolvedValue({ ...view(4), name: '另一窗口更新' })
    await state.reload()
    expect(state.name.value).toBe('另一窗口更新'); expect(state.dashboard.value?.etag).toBe('"4"')
    expect(mocks.queryDashboard).toHaveBeenCalledTimes(2)
  })
  it('retains current content when staged refresh fails', async () => {
    mocks.getDashboard.mockResolvedValue({ ...view(4), name: '新配置' }); mocks.queryDashboard.mockRejectedValueOnce({ kind: 'fallback' })
    await state.reload()
    expect(state.name.value).toBe('我的仪表板'); expect(state.dashboard.value?.etag).toBe('"0"'); expect(state.snapshot.value).toBeDefined()
  })
  it('can save an offline draft as a copy', async () => {
    mocks.updateDashboard.mockRejectedValue({ kind: 'fallback' }); state.changed(); await state.save()
    mocks.createDashboard.mockResolvedValue({ ...view(), id: 'two' }); mocks.getDashboard.mockResolvedValue({ ...view(), id: 'two' })
    await state.create('草稿副本', state.configuration.value, true)
    expect(mocks.updateDashboard).toHaveBeenCalledTimes(1); expect(mocks.createDashboard).toHaveBeenCalledTimes(1)
  })

})
