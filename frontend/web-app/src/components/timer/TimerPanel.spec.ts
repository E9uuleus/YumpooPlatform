import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { WorkItemLabelColorToken, type TimerCandidate } from '@yumpoo/api-client'

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
async function panel(initialExpanded = true) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/timer', component: { template: '<div />' } }] })
  await router.push('/timer'); await router.isReady()
  const wrapper = mount(TimerPanel, { props: { floating: true, initialExpanded }, global: { plugins: [router], stubs: { transition: false } } })
  wrappers.push(wrapper)
  await vi.advanceTimersByTimeAsync(1); await flushPromises()
  return wrapper
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
  it('reveals controls after dwelling over the orb and keeps details until the pointer leaves both surfaces', async () => {
    const wrapper = await panel(false)
    await wrapper.get('.orb-layout').trigger('pointerenter')
    await vi.advanceTimersByTimeAsync(300)
    expect(wrapper.get('.orb-layout').classes()).not.toContain('show-controls')
    await vi.advanceTimersByTimeAsync(60)
    expect(wrapper.get('.orb-layout').classes()).toContain('show-controls')
    await wrapper.get('[aria-label="展开工作详情"]').trigger('click')
    await wrapper.get('.orb-layout').trigger('pointerleave')
    await vi.advanceTimersByTimeAsync(150)
    await wrapper.get('.orb-layout').trigger('pointerenter')
    await vi.advanceTimersByTimeAsync(300)
    expect(wrapper.find('.orb-details').exists()).toBe(true)
    await wrapper.get('.orb-layout').trigger('pointerleave')
    await vi.advanceTimersByTimeAsync(260)
    expect(wrapper.find('.orb-details').exists()).toBe(false)
    expect(wrapper.get('.orb-layout').classes()).not.toContain('show-controls')
  })
  it('uses native hover notifications over the drag region instead of requiring button hover', async () => {
    let hover!: (inside: boolean) => void
    const state = { mode: 'compact', pinned: true, savedAt: 0, orb: { size: 176, side: null, detailWidth: 0 } }
    vi.stubGlobal('yumpooDesktop', { timer: { getWindowState: async () => state, onMode: vi.fn(), onCommandFailed: vi.fn(), onOrbHover: (listener: typeof hover) => { hover = listener; return vi.fn() } } })
    const wrapper = await panel(false)
    hover(true); await vi.advanceTimersByTimeAsync(360)
    expect(wrapper.get('.orb-layout').classes()).toContain('show-controls')
    hover(false); await vi.advanceTimersByTimeAsync(260)
    expect(wrapper.get('.orb-layout').classes()).not.toContain('show-controls')
  })
  it('shows the running title only while hovered and keeps it through polling', async () => {
    tracker.current.value = { session: { id: 'new-session', workItemId: 'a', startedAt: new Date() }, workItemTitle: '一个很长的当前工作项名称', recentItems: [], rowVersion: 1 } as never
    const wrapper = await panel(false)
    expect(wrapper.find('.orb-start-title').exists()).toBe(false)
    await wrapper.get('.orb-layout').trigger('pointerenter'); await vi.advanceTimersByTimeAsync(360)
    expect(wrapper.get('.orb-start-title').text()).toBe('一个很长的当前工作项名称')
    expect(wrapper.emitted('orb')?.at(-1)?.[0]).toMatchObject({ titleVisible: true })
    await vi.advanceTimersByTimeAsync(2000)
    tracker.current.value = { session: { id: 'new-session', workItemId: 'a', startedAt: new Date() }, workItemTitle: '一个很长的当前工作项名称', recentItems: [], rowVersion: 2 } as never
    await flushPromises()
    await vi.advanceTimersByTimeAsync(5000)
    expect(wrapper.get('.orb-start-title').text()).toBe('一个很长的当前工作项名称')
    await wrapper.get('.orb-layout').trigger('pointerleave'); await vi.advanceTimersByTimeAsync(200)
    expect(wrapper.find('.orb-start-title').exists()).toBe(false)
    expect(wrapper.emitted('orb')?.at(-1)?.[0]).not.toMatchObject({ titleVisible: true })
  })
  it('starts across projects in one click and collapses only after success', async () => {
    const wrapper = await panel()
    expect(api.listTimerCandidates).toHaveBeenCalledWith(expect.objectContaining({ scope: 'PERSONAL' }), expect.anything())
    expect(wrapper.text()).toContain('项目乙')
    await wrapper.findAll('[role="option"]')[1]!.trigger('click'); await flushPromises()
    expect(tracker.start).toHaveBeenCalledWith('b')
    expect(wrapper.classes()).toContain('is-compact')
  })
  it('does not resurrect a title when its native layout acknowledgement arrives after stop', async () => {
    const orb = { size: 176, side: null, detailWidth: 0, canvas: { width: 424, height: 216, left: 224, top: 40 } }
    let finish!: (value: typeof orb & { titleVisible: boolean }) => void
    const setOrbLayout = vi.fn().mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
      .mockImplementation(async () => ({ ...orb, titleVisible: false }))
    vi.stubGlobal('yumpooDesktop', { timer: { getWindowState: async () => ({ mode: 'compact', orb, hovered: true, pinned: true, savedAt: 0 }),
      onMode: vi.fn(), onOrbHover: vi.fn(), onCommandFailed: vi.fn(), setOrbLayout } })
    tracker.current.value = { session: { id: 'new-session', workItemId: 'a', startedAt: new Date() }, workItemTitle: '工作a', recentItems: [], rowVersion: 1 } as never
    const wrapper = await panel(false)
    await vi.advanceTimersByTimeAsync(360)
    expect(setOrbLayout).toHaveBeenCalledWith({ titleVisible: true })
    tracker.current.value = { session: null, recentItems: [], rowVersion: 2 } as never
    await flushPromises()
    expect(setOrbLayout).toHaveBeenLastCalledWith({ titleVisible: false })
    finish({ ...orb, titleVisible: true }); await flushPromises()
    expect(wrapper.find('.orb-start-title').exists()).toBe(false)
    expect((wrapper.element as HTMLElement).style.getPropertyValue('--orb-height')).toBe('216px')
  })
  it('starts the native title once when resuming from the compact orb', async () => {
    const orb = { size: 176, side: null, detailWidth: 0, canvas: { width: 424, height: 216, left: 224, top: 40 } }
    const setOrbLayout = vi.fn(async (change: { titleVisible?: boolean }) => ({ ...orb, ...change }))
    vi.stubGlobal('yumpooDesktop', { timer: { getWindowState: async () => ({ mode: 'compact', orb, hovered: true, pinned: true, savedAt: 0 }),
      onMode: vi.fn(), onOrbHover: vi.fn(), onCommandFailed: vi.fn(), setMode: vi.fn(async () => undefined), setOrbLayout } })
    tracker.current.value = { session: null, recentItems: [{ workItemId: 'a', title: '工作a' }], rowVersion: 0 } as never
    vi.mocked(tracker.start).mockImplementationOnce(async () => {
      tracker.current.value = { session: { id: 'resumed', workItemId: 'a', startedAt: new Date() }, workItemTitle: '工作a', recentItems: [], rowVersion: 1 } as never
      return true
    })
    const wrapper = await panel(false)
    await vi.advanceTimersByTimeAsync(360)
    await wrapper.get('[aria-label="继续计时"]').trigger('click'); await flushPromises()
    expect(wrapper.get('.orb-start-title').text()).toBe('工作a')
    expect(setOrbLayout.mock.calls.filter(([change]) => change.titleVisible)).toHaveLength(1)
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
  it('loads project context when opened directly as an orb and waits for save confirmation', async () => {
    tracker.current.value = { session: { id: 's', workItemId: 'a', projectId: 'p' }, workItemTitle: '工作a', recentItems: [], rowVersion: 1 } as never
    const wrapper = await panel(false)
    expect(wrapper.find('.orb-detail-project').exists()).toBe(false)
    expect(wrapper.get('.orb-core').text()).not.toContain('工作a')
    expect(wrapper.get('.orb-core').text()).not.toContain('项目甲')
    await wrapper.get('[aria-label="展开工作详情"]').trigger('click'); await flushPromises()
    expect(wrapper.get('.orb-detail-project').text()).toBe('项目甲')
    expect(wrapper.get('.orb-detail-title').text()).toBe('工作a')
    expect(wrapper.find('.panel-header').exists()).toBe(false)
    tracker.busy.value = true; await flushPromises()
    expect(wrapper.text()).not.toContain('已保存')
    tracker.busy.value = false
    tracker.current.value = { session: null, recentItems: [{ workItemId: 'a', projectId: 'p', title: '工作a' }], rowVersion: 2 } as never
    tracker.savedAt.value = Date.now(); await flushPromises()
    expect(wrapper.get('.saved-feedback').text()).toBe('已保存')
    await vi.advanceTimersByTimeAsync(2700)
    expect(wrapper.find('.saved-feedback').exists()).toBe(false)
    expect(wrapper.find('[aria-label="继续计时"]').exists()).toBe(true)
  })
  it('expands toward viewport space and resizes from its top-right handle without starting work', async () => {
    const wrapper = await panel(false)
    const rect = vi.spyOn(wrapper.get('.timer-orb').element, 'getBoundingClientRect')
    rect.mockReturnValue({ left: 800, right: 976, top: 300, bottom: 476, width: 176, height: 176 } as DOMRect)
    await wrapper.get('[aria-label="展开工作详情"]').trigger('click')
    expect(wrapper.emitted('orb')?.at(-1)?.[0]).toMatchObject({ side: 'left' })
    await wrapper.get('[aria-label="收起工作详情"]').trigger('click')
    rect.mockReturnValue({ left: 20, right: 196, top: 300, bottom: 476, width: 176, height: 176 } as DOMRect)
    await wrapper.get('[aria-label="展开工作详情"]').trigger('click')
    expect(wrapper.emitted('orb')?.at(-1)?.[0]).toMatchObject({ side: 'right' })
    const handle = wrapper.get('[aria-label="调整悬浮球大小"]')
    await handle.trigger('pointerdown', { button: 0, pointerId: 4, screenX: 180, screenY: 300 })
    await handle.trigger('pointermove', { pointerId: 4, screenX: 240, screenY: 240 })
    await handle.trigger('pointerup', { pointerId: 4, screenX: 240, screenY: 240 })
    expect(wrapper.emitted('orb')?.at(-1)?.[0]).toMatchObject({ size: 236 })
    expect(tracker.start).not.toHaveBeenCalled()
    const completed = wrapper.emitted('orb')?.length
    await handle.trigger('pointermove', { pointerId: 4, screenX: 280, screenY: 200 }); await vi.advanceTimersByTimeAsync(20)
    expect(wrapper.emitted('orb')).toHaveLength(completed!)
    await handle.trigger('keydown', { key: 'ArrowUp' })
    expect(wrapper.emitted('orb')?.at(-1)?.[0]).toMatchObject({ size: 244 })
  })
  it('keeps secondary commands in details so the orb has a single timing action', async () => {
    tracker.current.value = { session: { id: 's', workItemId: 'a' }, workItemTitle: '工作a', recentItems: [], rowVersion: 1 } as never
    const wrapper = await panel(false)
    expect(wrapper.get('.orb-core').findAll('button')).toHaveLength(1)
    expect(wrapper.find('[aria-label="隐藏计时器"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="查找工作项"]').exists()).toBe(false)
    await wrapper.get('[aria-label="展开工作详情"]').trigger('click')
    expect(wrapper.get('.orb-details').find('[aria-label="隐藏计时器"]').exists()).toBe(false)
    await wrapper.get('.orb-details [aria-label="查找工作项"]').trigger('click')
    expect(wrapper.find('input[role="combobox"]').exists()).toBe(true)
  })
  it('has no close button or work-item hover popups in the picker or compact details', async () => {
    const wrapper = await panel()
    expect(wrapper.find('[aria-label="隐藏计时器"]').exists()).toBe(false)
    const option = wrapper.findAll('[role="option"]')[0]!
    expect(option.attributes('title')).toBeUndefined()
    await option.trigger('mouseenter'); await vi.advanceTimersByTimeAsync(500)
    expect(wrapper.find('[role="tooltip"]').exists()).toBe(false)
    await wrapper.get('[aria-label="收起选择器"]').trigger('click')
    await wrapper.get('[aria-label="展开工作详情"]').trigger('click')
    expect(wrapper.get('.orb-details').find('.icon-button').exists()).toBe(false)
    expect(wrapper.get('.orb-detail-title').attributes('title')).toBeUndefined()
    expect((wrapper.element as HTMLElement).style.getPropertyValue('--detail-height')).toBe('112px')
  })
  it('expands a failed orb command for review without showing a saved check', async () => {
    const wrapper = await panel(false)
    tracker.problem.value = '保存失败'; await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('保存失败')
    expect(wrapper.find('.saved-feedback').exists()).toBe(false)
    expect(wrapper.classes()).not.toContain('is-compact')
  })
})
