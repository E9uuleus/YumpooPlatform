import type { Ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'

const tracker = vi.hoisted(() => ({ preferences: undefined as unknown, setPreferences: vi.fn(async () => true) }))
vi.mock('./useTimeTracker', async () => {
  const { ref } = await import('vue')
  tracker.preferences = ref({ timerEnabled: true, workdayStartMinute: 540, workdayEndMinute: 1080, hideCompleted: false })
  return { useTimeTracker: () => tracker }
})
import { setHideCompleted, visibleCandidates } from './useHideCompleted'

describe('隐没已完成', () => {
  it('开启后没有搜索词时去掉已完成项，搜索时保留，并经计时偏好保存', async () => {
    const items = [{ id: 'a', completed: false }, { id: 'b', completed: true }]
    const preferences = tracker.preferences as Ref<Record<string, unknown>>
    expect(visibleCandidates(items, '')).toEqual(items)
    preferences.value = { ...preferences.value, hideCompleted: true }
    expect(visibleCandidates(items, ' ').map(item => item.id)).toEqual(['a'])
    expect(visibleCandidates(items, '周报')).toEqual(items)
    await setHideCompleted(false)
    expect(tracker.setPreferences).toHaveBeenCalledWith({ timerEnabled: true, workdayStartMinute: 540, workdayEndMinute: 1080, hideCompleted: false })
  })
})
