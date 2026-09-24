import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { WorkItemLabelColorToken, type TimerCandidate } from '@yumpoo/api-client'
import type { TimerOrbLayout, TimerPreferences } from '@yumpoo/preload-contract'

const api = vi.hoisted(() => ({ listTimerCandidates: vi.fn() }))
vi.mock('../../api/client', () => ({ timeTrackingApi: api }))
vi.mock('../../composables/useTimeTracker', async () => {
  const { ref } = await import('vue')
  const tracker = { current: ref({ session: null, recentItems: [], rowVersion: 0 }), now: ref(Date.now()),
    busy: ref(false), problem: ref(''), connected: ref(true), savedAt: ref(0), runningDuration: ref(120000), start: vi.fn(), stop: vi.fn() }
  return { useTimeTracker: () => tracker, formatDuration: () => '0:02:00', onTimeTrackingChanged: () => () => undefined }
})
import { useTimeTracker } from '../../composables/useTimeTracker'
import TimerPanel from './TimerPanel.vue'
const tracker = useTimeTracker()
const wrappers: Array<ReturnType<typeof mount>> = []
function item(id: string, projectName = '项目甲'): TimerCandidate {
  return { workItemId: id, projectId: `${id}-project`, projectName, itemNo: `TASK-${id}`, title: `工作${id}`,
    contentName: '任务', contentCode: 'TASKS', contentColorToken: WorkItemLabelColorToken.BrightBlue, statusCategory: 'TODO', assignedToMe: true, lastTrackedAt: null, ownDurationMs: 0 }
}
async function panel(initialExpanded = true, attachTo?: HTMLElement) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/timer', component: { template: '<div />' } }] })
  await router.push('/timer'); await router.isReady()
  const wrapper = mount(TimerPanel, { props: { floating: true, initialExpanded }, global: { plugins: [router], stubs: { transition: false } }, ...(attachTo ? { attachTo } : {}) })
  wrappers.push(wrapper)
  await vi.advanceTimersByTimeAsync(1); await flushPromises()
  return wrapper
}
const canvas = { left: 226, top: 10, width: 314, height: 84 }
const collapsed: TimerOrbLayout = { size: 64, side: null, detailWidth: 0, canvas }
const opened: TimerOrbLayout = { size: 64, side: 'left', detailWidth: 216, canvas }
const prefs: TimerPreferences = { display: 'orb', orbSize: 'medium', dockSide: 'right' }
function desktop(state: Record<string, unknown> = {}, extra: Record<string, unknown> = {}) {
  const bridge = {
    hover: (_inside: boolean) => undefined as void, mode: (_mode: string) => undefined as void,
    getWindowState: vi.fn(async () => ({ mode: 'compact', pinned: true, savedAt: 0, surface: 'timer', orb: collapsed, preferences: prefs, ...state })),
    setOrbLayout: vi.fn(async (change: { details?: boolean }) => change.details ? opened : collapsed),
    setMode: vi.fn(async () => undefined), setAlwaysOnTop: vi.fn(async () => undefined), hide: vi.fn(async () => undefined), openWorkItem: vi.fn(async () => undefined),
    onMode: (listener: (mode: string) => void) => { bridge.mode = listener; return vi.fn() },
    onOrbHover: (listener: (inside: boolean) => void) => { bridge.hover = listener; return vi.fn() },
    onCommandFailed: vi.fn(),
    ...extra,
  }
  vi.stubGlobal('yumpooDesktop', { timer: bridge })
  return bridge
}
beforeEach(() => {
  vi.useFakeTimers(); vi.clearAllMocks(); vi.stubGlobal('yumpooDesktop', undefined)
  tracker.current.value = { session: null, recentItems: [], rowVersion: 0 } as never
  tracker.busy.value = false; tracker.problem.value = ''; tracker.connected.value = true
  tracker.savedAt.value = 0
  vi.mocked(tracker.start).mockResolvedValue(true)
  api.listTimerCandidates.mockResolvedValue({ items: [item('a'), item('b', '项目乙')], nextOffset: null })
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.useRealTimers(); vi.unstubAllGlobals() })

describe('personal cross-project timer picker', () => {
  it('opens the capsule after dwelling over the orb and keeps it until the pointer leaves', async () => {
    const wrapper = await panel(false)
    await wrapper.get('.orb-layout').trigger('pointerenter')
    await vi.advanceTimersByTimeAsync(300)
    expect(wrapper.find('.orb-capsule').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(60)
    expect(wrapper.find('.orb-capsule').exists()).toBe(true)
    expect(wrapper.emitted('orb')?.at(-1)?.[0]).toMatchObject({ side: 'right', detailWidth: 216 })
    await wrapper.get('.orb-layout').trigger('pointerleave')
    await vi.advanceTimersByTimeAsync(150)
    await wrapper.get('.orb-layout').trigger('pointerenter')
    await vi.advanceTimersByTimeAsync(300)
    expect(wrapper.find('.orb-capsule').exists()).toBe(true)
    await wrapper.get('.orb-layout').trigger('pointerleave')
    await vi.advanceTimersByTimeAsync(300); await flushPromises()
    expect(wrapper.find('.orb-capsule').exists()).toBe(false)
    expect(wrapper.emitted('orb')?.at(-1)?.[0]).toMatchObject({ side: null, detailWidth: 0 })
  })
  it('grows the native shape before showing the capsule and shrinks it only after the capsule leaves', async () => {
    const bridge = desktop()
    const wrapper = await panel(false)
    bridge.hover(true); await vi.advanceTimersByTimeAsync(360)
    expect(bridge.setOrbLayout).toHaveBeenCalledWith({ details: true })
    expect(wrapper.find('.orb-capsule').exists()).toBe(true)
    expect(wrapper.get('.orb-shell').attributes('style')).toContain('left: 226px')
    bridge.hover(false); await vi.advanceTimersByTimeAsync(250)
    expect(bridge.setOrbLayout).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(50); await flushPromises()
    expect(wrapper.find('.orb-capsule').exists()).toBe(false)
    expect(bridge.setOrbLayout).toHaveBeenLastCalledWith({ details: false })
  })
  it('never shows a capsule whose native layout answered after the pointer had already left', async () => {
    let finish!: (layout: TimerOrbLayout) => void
    const bridge = desktop()
    bridge.setOrbLayout.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const wrapper = await panel(false)
    bridge.hover(true); await vi.advanceTimersByTimeAsync(360)
    bridge.hover(false); await vi.advanceTimersByTimeAsync(260)
    expect(bridge.setOrbLayout).toHaveBeenLastCalledWith({ details: false })
    finish(opened); await flushPromises()
    expect(wrapper.find('.orb-capsule').exists()).toBe(false)
  })
  it('keeps the orb free of buttons and puts the timing commands in the capsule', async () => {
    tracker.current.value = { session: { id: 's', workItemId: 'a' }, workItemTitle: '工作a', recentItems: [], rowVersion: 1 } as never
    const wrapper = await panel(false)
    expect(wrapper.get('.orb-disc').findAll('button')).toHaveLength(0)
    expect(wrapper.get('.orb-disc').text()).not.toContain('工作a')
    expect(wrapper.find('[aria-label="暂停计时"]').exists()).toBe(false)
    await wrapper.get('.orb-disc').trigger('keydown', { key: 'Enter' }); await flushPromises()
    expect(wrapper.get('.capsule-title').text()).toBe('工作a')
    await wrapper.get('.orb-capsule [aria-label="暂停计时"]').trigger('click')
    expect(tracker.stop).toHaveBeenCalledOnce()
    await wrapper.get('.orb-capsule [aria-label="更换工作"]').trigger('click')
    expect(wrapper.find('input[role="combobox"]').exists()).toBe(true)
  })
  it('loads project context when opened directly as an orb and waits for save confirmation', async () => {
    tracker.current.value = { session: { id: 's', workItemId: 'a', projectId: 'p' }, workItemTitle: '工作a', recentItems: [], rowVersion: 1 } as never
    const wrapper = await panel(false)
    expect(wrapper.find('.capsule-project').exists()).toBe(false)
    await wrapper.get('.orb-layout').trigger('pointerenter'); await vi.advanceTimersByTimeAsync(360)
    expect(wrapper.get('.capsule-project').text()).toBe('项目甲')
    expect(wrapper.find('.panel-header').exists()).toBe(false)
    tracker.busy.value = true; await flushPromises()
    expect(wrapper.text()).not.toContain('已保存')
    tracker.busy.value = false
    tracker.current.value = { session: null, recentItems: [{ workItemId: 'a', projectId: 'p', title: '工作a' }], rowVersion: 2 } as never
    tracker.savedAt.value = Date.now(); await vi.advanceTimersByTimeAsync(50); await flushPromises()
    expect(wrapper.get('.saved-feedback').text()).toBe('已保存')
    await vi.advanceTimersByTimeAsync(2700)
    expect(wrapper.find('.saved-feedback').exists()).toBe(false)
    expect(wrapper.get('.orb-disc .timer-digits').text()).toContain('02:00')
    await wrapper.get('[aria-label="继续计时"]').trigger('click'); await flushPromises()
    expect(tracker.start).toHaveBeenCalledWith('a')
  })
  it('opens the web capsule toward the side with more viewport room', async () => {
    const wrapper = await panel(false)
    const rect = vi.spyOn(wrapper.get('.orb-disc').element, 'getBoundingClientRect')
    rect.mockReturnValue({ left: 800, right: 864, top: 300, bottom: 364, width: 64, height: 64 } as DOMRect)
    await wrapper.get('.orb-disc').trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('orb')?.at(-1)?.[0]).toMatchObject({ side: 'left', detailWidth: 216 })
    await wrapper.get('.orb-disc').trigger('keydown', { key: ' ' }); await vi.advanceTimersByTimeAsync(50); await flushPromises()
    expect(wrapper.emitted('orb')?.at(-1)?.[0]).toMatchObject({ side: null })
    rect.mockReturnValue({ left: 20, right: 84, top: 300, bottom: 364, width: 64, height: 64 } as DOMRect)
    await wrapper.get('.orb-disc').trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('orb')?.at(-1)?.[0]).toMatchObject({ side: 'right' })
    expect(tracker.start).not.toHaveBeenCalled()
  })
  it('renders the edge dock and slides its card out from native hover', async () => {
    tracker.current.value = { session: { id: 's', workItemId: 'a', projectId: 'p' }, workItemTitle: '工作a', recentItems: [], rowVersion: 1 } as never
    const dock = { size: 96, side: null, detailWidth: 0, canvas: { left: 10, top: 10, width: 298, height: 172 }, dock: { side: 'right' as const, expanded: false } }
    const bridge = desktop({ orb: dock }, { setOrbLayout: vi.fn(async (change: { details?: boolean }) => ({ ...dock, dock: { side: 'right', expanded: !!change.details } })) })
    const wrapper = await panel(false)
    expect(wrapper.find('.orb-disc').exists()).toBe(false)
    expect(wrapper.get('.dock-tab').text()).toContain('2m')
    bridge.hover(true); await vi.advanceTimersByTimeAsync(360)
    expect(wrapper.get('.dock-card').attributes('style')).toContain('left: 10px')
    expect(wrapper.get('.dock-title').text()).toBe('工作a')
    expect(wrapper.get('.dock-status').text()).toBe('计时中')
    await wrapper.get('.dock-card [aria-label="暂停计时"]').trigger('click')
    expect(tracker.stop).toHaveBeenCalledOnce()
    bridge.hover(false); await vi.advanceTimersByTimeAsync(300); await flushPromises()
    expect(wrapper.find('.dock-card').exists()).toBe(false)
    expect(bridge.setOrbLayout).toHaveBeenLastCalledWith({ details: false })
  })
  it('opens settings from the gear and saves display preferences through the shell', async () => {
    const setPreferences = vi.fn(async (change: Partial<TimerPreferences>) => ({ ...prefs, ...change }))
    const bridge = desktop({ mode: 'picker' }, { setPreferences })
    const wrapper = await panel()
    await wrapper.get('[aria-label="计时设置"]').trigger('click'); await flushPromises()
    expect(bridge.setMode).toHaveBeenLastCalledWith('settings')
    expect(wrapper.find('input[role="combobox"]').exists()).toBe(false)
    expect(wrapper.get('.display-option.is-selected').text()).toContain('悬浮球')
    await wrapper.findAll('[role="radio"]').find(option => option.text() === '大')!.trigger('click'); await flushPromises()
    expect(setPreferences).toHaveBeenLastCalledWith({ orbSize: 'large' })
    await wrapper.findAll('.display-option')[1]!.trigger('click'); await flushPromises()
    expect(setPreferences).toHaveBeenLastCalledWith({ display: 'dock' })
    expect(wrapper.get('.display-option.is-selected').text()).toContain('侧边栏')
    expect(wrapper.text()).toContain('停靠位置')
    await wrapper.get('[role="switch"]').trigger('click'); await flushPromises()
    expect(bridge.setAlwaysOnTop).toHaveBeenCalledWith(false)
    await wrapper.get('[aria-label="返回工作列表"]').trigger('click'); await flushPromises()
    expect(bridge.setMode).toHaveBeenLastCalledWith('picker')
    expect(wrapper.find('input[role="combobox"]').exists()).toBe(true)
  })
  it('follows a shell request for the settings view and reverts a rejected preference', async () => {
    const bridge = desktop({ mode: 'settings' }, { setPreferences: vi.fn(async () => { throw new Error('offline') }) })
    const wrapper = await panel()
    expect(wrapper.text()).toContain('显示方式')
    await wrapper.findAll('.display-option')[1]!.trigger('click'); await flushPromises()
    expect(wrapper.get('.display-option.is-selected').text()).toContain('悬浮球')
    expect(wrapper.get('[role="alert"]').text()).toContain('设置保存失败')
    bridge.mode('picker'); await flushPromises()
    expect(wrapper.find('input[role="combobox"]').exists()).toBe(true)
  })
  it('hides the gear when the installed shell cannot store preferences', async () => {
    desktop({ mode: 'picker' })
    const wrapper = await panel()
    expect(wrapper.find('[aria-label="计时设置"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="取消置顶"]').exists()).toBe(true)
  })
  it('focuses search with Ctrl+K and returns from settings with Escape', async () => {
    desktop({ mode: 'settings' }, { setPreferences: vi.fn() })
    const wrapper = await panel(true, document.body)
    await wrapper.trigger('keydown', { key: 'Escape' }); await flushPromises()
    expect(wrapper.find('input[role="combobox"]').exists()).toBe(true)
    ;(document.activeElement as HTMLElement | null)?.blur()
    await wrapper.get('.panel-header').trigger('keydown', { key: 'k', ctrlKey: true }); await flushPromises()
    expect(document.activeElement).toBe(wrapper.get('input').element)
  })
  it('starts across projects in one click and collapses only after success', async () => {
    const wrapper = await panel()
    expect(api.listTimerCandidates).toHaveBeenCalledWith(expect.objectContaining({ scope: 'PERSONAL' }), expect.anything())
    expect(wrapper.text()).toContain('项目乙')
    await wrapper.findAll('[role="option"]')[1]!.trigger('click'); await flushPromises()
    expect(tracker.start).toHaveBeenCalledWith('b')
    expect(wrapper.classes()).toContain('is-compact')
  })
  it('searches all permitted projects and ignores a late result from an older query', async () => {
    const wrapper = await panel()
    let resolve!: (page: { items: TimerCandidate[]; nextOffset: null }) => void
    api.listTimerCandidates.mockImplementationOnce(() => new Promise(done => { resolve = done }))
    await wrapper.get('input').setValue('old'); await vi.advanceTimersByTimeAsync(210)
    api.listTimerCandidates.mockResolvedValueOnce({ items: [item('new')], nextOffset: null })
    await wrapper.get('input').setValue('new'); await vi.advanceTimersByTimeAsync(210)
    resolve({ items: [item('old')], nextOffset: null }); await flushPromises()
    expect(api.listTimerCandidates).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'new', scope: 'ALL' }), expect.anything())
    expect(wrapper.findAll('[role="option"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('工作new')
    expect(wrapper.text()).not.toContain('工作old')
  })
  it('deduplicates repeated pages and never repeats the running work item', async () => {
    tracker.current.value = { session: { id: 'session', workItemId: 'a', projectId: 'p' }, workItemTitle: '正在做', recentItems: [], rowVersion: 1 } as never
    api.listTimerCandidates.mockResolvedValueOnce({ items: [item('a'), item('b')], nextOffset: 2 })
    const wrapper = await panel()
    expect(wrapper.findAll('[role="option"]')).toHaveLength(1)
    api.listTimerCandidates.mockResolvedValueOnce({ items: [item('b'), item('c')], nextOffset: null })
    await wrapper.get('.load-more').trigger('click'); await flushPromises()
    expect(wrapper.findAll('[role="option"]')).toHaveLength(2)
  })
  it('keeps failed starts reviewable and does not close or clear the query', async () => {
    const wrapper = await panel()
    await wrapper.get('input').setValue('工作'); await vi.advanceTimersByTimeAsync(210)
    vi.mocked(tracker.start).mockResolvedValueOnce(false)
    await wrapper.findAll('[role="option"]')[0]!.trigger('click'); await flushPromises()
    expect(wrapper.classes()).not.toContain('is-compact')
    expect((wrapper.get('input').element as HTMLInputElement).value).toBe('工作')
  })
  it('starts the keyboard selection and ignores Enter during composition', async () => {
    const wrapper = await panel()
    await wrapper.get('input').trigger('keydown', { key: 'Enter', isComposing: true })
    expect(tracker.start).not.toHaveBeenCalled()
    await wrapper.get('input').trigger('keydown', { key: 'ArrowDown' })
    await wrapper.get('input').trigger('keydown', { key: 'Enter' }); await flushPromises()
    expect(tracker.start).toHaveBeenCalledWith('b')
  })
  it('hides without stopping a running timer', async () => {
    const wrapper = await panel(false)
    await wrapper.trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('hide')).toHaveLength(1)
    expect(tracker.stop).not.toHaveBeenCalled()
  })
  it('uses stable category glyphs, keeps project names and removes explanatory row copy', async () => {
    api.listTimerCandidates.mockResolvedValueOnce({ items: [
      { ...item('a'), contentName: '已重命名类别' },
      { ...item('b', '项目乙'), contentCode: 'DEFECTS', contentName: '缺陷' },
      { ...item('c'), contentCode: 'REQUIREMENTS', contentName: '需求' },
      { ...item('d'), contentCode: 'CUSTOM', contentName: '自定义' },
    ], nextOffset: null })
    const wrapper = await panel()
    expect(wrapper.findAll('.work-glyph').map(glyph => glyph.attributes('data-kind'))).toEqual(['task', 'defect', 'requirement', 'custom'])
    expect(wrapper.text()).toContain('项目乙')
    expect(wrapper.text()).not.toMatch(/分配给我|最近计时|只记录本人|所有项目|TASK-a/)
  })
  it('has no close button or work-item hover popups in the picker or the capsule', async () => {
    const wrapper = await panel()
    expect(wrapper.find('[aria-label="隐藏计时器"]').exists()).toBe(false)
    const option = wrapper.findAll('[role="option"]')[0]!
    expect(option.attributes('title')).toBeUndefined()
    await option.trigger('mouseenter'); await vi.advanceTimersByTimeAsync(500)
    expect(wrapper.find('[role="tooltip"]').exists()).toBe(false)
    await wrapper.get('[aria-label="收起选择器"]').trigger('click')
    await wrapper.get('.orb-disc').trigger('keydown', { key: 'Enter' }); await flushPromises()
    expect(wrapper.get('.orb-capsule').find('.icon-button').exists()).toBe(false)
    expect(wrapper.get('.capsule-title').attributes('title')).toBeUndefined()
  })
  it('expands a failed orb command for review without showing a saved check', async () => {
    const wrapper = await panel(false)
    tracker.problem.value = '保存失败'; await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('保存失败')
    expect(wrapper.find('.saved-feedback').exists()).toBe(false)
    expect(wrapper.classes()).not.toContain('is-compact')
  })
})
