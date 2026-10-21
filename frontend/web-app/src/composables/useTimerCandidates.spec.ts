import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ listTimerCandidates: vi.fn() }))
vi.mock('../api/client', () => ({ timeTrackingApi: api }))
vi.mock('./useTimeTracker', () => ({ onTimeTrackingChanged: () => () => {} }))
vi.mock('./useHideCompleted', async () => ({ hideCompleted: (await import('vue')).ref(false) }))
import { hideCompleted } from './useHideCompleted'
import { useTimerCandidates } from './useTimerCandidates'

const wrappers: ReturnType<typeof mount>[] = []
const projects = ref<readonly string[]>([])
const assignedToMe = ref(false)
const counts = [{ projectId: 'p', projectName: '项目', count: 40 }]
async function candidates() {
  let result!: ReturnType<typeof useTimerCandidates>
  wrappers.push(mount(defineComponent({ setup() { result = useTimerCandidates(ref(true), { projects, assignedToMe }); return () => null } })))
  await settle()
  return result
}
async function settle(ms = 0) { await nextTick(); await vi.advanceTimersByTimeAsync(ms); await flushPromises() }
beforeEach(() => {
  vi.useFakeTimers()
  projects.value = []; assignedToMe.value = false; (hideCompleted as { value: boolean }).value = false
  api.listTimerCandidates.mockReset().mockResolvedValue({ items: [], nextOffset: null, projects: counts, hiddenCompletedCount: 12 })
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.useRealTimers() })

describe('候选服务端筛选', () => {
  it('映射三个筛选参数，搜索时保留已完成项并扩大范围', async () => {
    projects.value = ['p']; assignedToMe.value = true; (hideCompleted as { value: boolean }).value = true
    const result = await candidates()
    expect(api.listTimerCandidates).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: ['p'], assignedToMe: true, excludeCompleted: true, scope: 'PERSONAL' }), expect.anything())
    result.query.value = ' 周报 '
    await settle(200)
    expect(api.listTimerCandidates).toHaveBeenLastCalledWith(expect.objectContaining({ q: '周报', scope: 'ALL', excludeCompleted: undefined, projectId: ['p'], assignedToMe: true }), expect.anything())
  })

  it('只从首段读取计数，追加时保留，重新加载缺省字段归零', async () => {
    api.listTimerCandidates.mockResolvedValueOnce({ items: [], nextOffset: 25, projects: counts, hiddenCompletedCount: 12 })
    const result = await candidates()
    api.listTimerCandidates.mockResolvedValueOnce({ items: [], nextOffset: null, projects: [], hiddenCompletedCount: 99 })
    await result.load(true)
    expect(api.listTimerCandidates).toHaveBeenLastCalledWith(expect.objectContaining({ offset: 25 }), expect.anything())
    expect(result.projectCounts.value).toEqual(counts)
    expect(result.hiddenCount.value).toBe(12)
    api.listTimerCandidates.mockResolvedValueOnce({ items: [], nextOffset: null })
    await result.load()
    expect(result.projectCounts.value).toEqual([])
    expect(result.hiddenCount.value).toBe(0)
  })

  it('项目、分配和隐藏开关变化时清空计数并从首段重新加载', async () => {
    const result = await candidates()
    projects.value = ['other']
    await nextTick()
    expect(result.projectCounts.value).toEqual([])
    expect(result.hiddenCount.value).toBe(0)
    await settle()
    expect(api.listTimerCandidates).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: ['other'], offset: 0 }), expect.anything())
    assignedToMe.value = true
    await settle()
    expect(api.listTimerCandidates).toHaveBeenLastCalledWith(expect.objectContaining({ assignedToMe: true, offset: 0 }), expect.anything())
    ;(hideCompleted as { value: boolean }).value = true
    await settle()
    expect(api.listTimerCandidates).toHaveBeenLastCalledWith(expect.objectContaining({ excludeCompleted: true, offset: 0 }), expect.anything())
    expect(api.listTimerCandidates).toHaveBeenCalledTimes(4)
  })
})
