import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { RailState } from '@yumpoo/preload-contract'

const tracker = vi.hoisted(() => ({ running: { value: [] as unknown[] } }))
vi.mock('../../composables/useSession', async () => {
  const { ref } = await import('vue')
  return { useSession: () => ({ authentication: ref({ company: { id: 'c' }, user: { id: 'u' } }) }) }
})
vi.mock('../../composables/useTimeTracker', async () => {
  const { computed, ref } = await import('vue')
  const running = ref<unknown[]>([])
  tracker.running = running
  return { useTimeTracker: () => ({ running: computed(() => running.value), current: ref(undefined), busy: ref(false), wallDuration: ref(0),
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
  Object.assign(window, { yumpooDesktop: { rail: {
    onState: (listener: (state: RailState) => void) => { emit = listener; return () => undefined },
    getState: () => Promise.resolve(base), setProjects: vi.fn(), openProject: vi.fn(), openSettings: vi.fn(),
  } } })
})
afterEach(() => { delete (window as { yumpooDesktop?: unknown }).yumpooDesktop })

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
})
