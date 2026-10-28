import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { RailState } from '@yumpoo/preload-contract'

const api = vi.hoisted(() => ({ listProjects: vi.fn() }))
vi.mock('../../api/client', () => ({ projectsApi: api }))
const tracker = vi.hoisted(() => ({ running: { value: [] as unknown[] }, current: { value: undefined as unknown } }))
const session = vi.hoisted(() => ({ authentication: { value: { company: { id: 'c' }, user: { id: 'u' } } } }))
vi.mock('../../composables/useSession', async () => {
  const { ref } = await import('vue')
  session.authentication = ref(session.authentication.value)
  return { useSession: () => session }
})
vi.mock('../../composables/useTimeTracker', async () => {
  const { computed, ref } = await import('vue')
  const running = ref<unknown[]>([])
  tracker.running = running
  const current = ref<unknown>()
  tracker.current = current
  return { useTimeTracker: () => ({ running: computed(() => running.value), current, busy: ref(false), wallDuration: ref(0),
    stopAll: vi.fn(), startMany: vi.fn() }) }
})
vi.mock('../../composables/useProjectRecents', async () => {
  const { ref } = await import('vue')
  return { useProjectRecents: () => ({ items: ref([]) }) }
})
vi.mock('../../composables/useDesktopSurfaceOpacity', () => ({ useDesktopSurfaceOpacity: vi.fn() }))
import ProjectRailView from './ProjectRailView.vue'

let emit: ((state: RailState) => void) | undefined
const base: RailState = { side: 'left', size: 'medium', mode: 'autohide', tucked: true, timer: false,
  projects: { owner: 'c:u', items: [{ id: 'p1', name: '研发' }, { id: 'p2', name: '运营' }] }, active: 'p2' } as RailState

beforeEach(() => {
  session.authentication.value = { company: { id: 'c' }, user: { id: 'u' } }
  api.listProjects.mockResolvedValue({ items: [{ id: 'p1' }, { id: 'p2' }] })
  Object.assign(window, { yumpooDesktop: { rail: {
    onState: (listener: (state: RailState) => void) => { emit = listener; return () => undefined },
    getState: () => Promise.resolve(base), setProjects: vi.fn(), setVisibleCount: vi.fn(), openProject: vi.fn(), openSettings: vi.fn(),
  } } })
})
afterEach(() => { delete (window as { yumpooDesktop?: unknown }).yumpooDesktop })
enableAutoUnmount(afterEach)

describe('项目侧边栏', () => {
  it('自动隐藏收起时整条滑出，只在屏幕边缘留把手；展开后把手隐去', async () => {
    const wrapper = mount(ProjectRailView)
    await flushPromises()
    expect(wrapper.get('.rail').classes()).toContain('is-tucked')
    expect(wrapper.get('.project-rail').classes()).toContain('is-left')
    expect(wrapper.get('.rail-handle').classes()).toContain('is-shown')
    emit!({ ...base, tucked: false })
    await flushPromises()
    expect(wrapper.get('.rail').classes()).not.toContain('is-tucked')
    expect(wrapper.get('.rail-handle').classes()).not.toContain('is-shown')
    emit!({ ...base, mode: 'always', tucked: false, side: 'right' })
    await flushPromises()
    expect(wrapper.find('.rail-handle').exists()).toBe(false)
    expect(wrapper.get('.project-rail').classes()).toContain('is-right')
  })

  it('计时中把手亮起，磁贴以项目色作为底色与字色，当前项目带竖标类名', async () => {
    tracker.running.value = [{ session: { id: 's' } }]
    const wrapper = mount(ProjectRailView)
    await flushPromises()
    expect(wrapper.get('.rail-handle').classes()).toContain('is-running')
    const tiles = wrapper.findAll('.rail-project:not(.is-add)')
    expect(tiles.map(tile => tile.text())).toEqual(['研', '运'])
    expect((tiles[0]!.element as HTMLElement).style.getPropertyValue('--tone')).toMatch(/^var\(--yp-label-/)
    expect(tiles[1]!.classes()).toContain('is-active')
    tracker.running.value = []
  })

  it('已结束项目只隐藏不改写固定项，计时轮询不刷新，重新聚焦后恢复显示', async () => {
    api.listProjects.mockResolvedValueOnce({ items: [{ id: 'p2' }] })
    const wrapper = mount(ProjectRailView)
    await flushPromises()
    expect(api.listProjects).toHaveBeenCalledWith({ lifecycle: 'ACTIVE', ended: false, actorAccesses: ['OWNER', 'MEMBER'], page: 0, size: 100 })
    expect(wrapper.findAll('.rail-project:not(.is-add)').map(tile => tile.text())).toEqual(['运'])
    expect(window.yumpooDesktop!.rail!.setVisibleCount).toHaveBeenLastCalledWith(1)
    expect(window.yumpooDesktop!.rail!.setProjects).not.toHaveBeenCalled()
    tracker.current.value = { rowVersion: 1 }
    await flushPromises()
    expect(api.listProjects).toHaveBeenCalledTimes(1)
    let resolve!: (result: { items: { id: string }[] }) => void
    api.listProjects.mockReturnValueOnce(new Promise(success => { resolve = success }))
    window.dispatchEvent(new Event('focus'))
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(api.listProjects).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.rail-project:not(.is-add)').map(tile => tile.text())).toEqual(['运'])
    resolve({ items: [{ id: 'p1' }, { id: 'p2' }] })
    await flushPromises()
    expect(wrapper.findAll('.rail-project:not(.is-add)').map(tile => tile.text())).toEqual(['研', '运'])
    expect(window.yumpooDesktop!.rail!.setProjects).not.toHaveBeenCalled()
    wrapper.unmount()
    window.dispatchEvent(new Event('focus'))
    expect(api.listProjects).toHaveBeenCalledTimes(2)
    visibility.mockRestore()
  })

  it('账号变化后旧请求不能覆盖新账号的可见项目', async () => {
    let resolveOld!: (result: { items: { id: string }[] }) => void
    api.listProjects.mockReturnValueOnce(new Promise(success => { resolveOld = success }))
    const wrapper = mount(ProjectRailView)
    await flushPromises()
    api.listProjects.mockResolvedValueOnce({ items: [{ id: 'p2' }] })
    session.authentication.value = { company: { id: 'c' }, user: { id: 'other' } }
    emit!({ ...base, projects: { owner: 'c:other', items: base.projects!.items } })
    await flushPromises()
    expect(api.listProjects).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.rail-project:not(.is-add)').map(tile => tile.text())).toEqual(['运'])
    resolveOld({ items: [{ id: 'p1' }] })
    await flushPromises()
    expect(wrapper.findAll('.rail-project:not(.is-add)').map(tile => tile.text())).toEqual(['运'])
  })

  it('拖拽仅调整可见项目，隐藏项保留原位置和简称', async () => {
    api.listProjects.mockResolvedValue({ items: [{ id: 'p1' }, { id: 'p3' }] })
    const wrapper = mount(ProjectRailView)
    await flushPromises()
    const items = [{ id: 'p1', name: '研发', label: 'RD' }, { id: 'p2', name: '运营', label: '运' }, { id: 'p3', name: '设计', label: 'UX' }]
    emit!({ ...base, projects: { owner: 'c:u', items } })
    await flushPromises()
    const tiles = wrapper.findAll('.rail-project:not(.is-add)')
    await tiles[0]!.trigger('dragstart')
    await tiles[1]!.trigger('drop')
    expect(window.yumpooDesktop!.rail!.setProjects).toHaveBeenCalledExactlyOnceWith({ owner: 'c:u', items: [items[2], items[1], items[0]] })
  })

  it('读取未完成或失败时显示全部固定项，仅重新可见时刷新', async () => {
    let reject!: (reason: Error) => void
    api.listProjects.mockReturnValueOnce(new Promise((_, failure) => { reject = failure }))
    const wrapper = mount(ProjectRailView)
    await flushPromises()
    expect(wrapper.findAll('.rail-project:not(.is-add)')).toHaveLength(2)
    reject(new Error('offline'))
    await flushPromises()
    expect(wrapper.findAll('.rail-project:not(.is-add)')).toHaveLength(2)
    const visibility = vi.spyOn(document, 'visibilityState', 'get')
    visibility.mockReturnValue('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(api.listProjects).toHaveBeenCalledTimes(1)
    visibility.mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(api.listProjects).toHaveBeenCalledTimes(2)
    expect(window.yumpooDesktop!.rail!.setProjects).not.toHaveBeenCalled()
    visibility.mockRestore()
  })
})
