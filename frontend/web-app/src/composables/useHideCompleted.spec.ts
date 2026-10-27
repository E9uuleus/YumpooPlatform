import type { Ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'

const tracker = vi.hoisted(() => ({ preferences: undefined as unknown, setPreferences: vi.fn(async () => true) }))
vi.mock('./useTimeTracker', async () => {
  const { ref } = await import('vue')
  tracker.preferences = ref({ timerEnabled: true, workdayStartMinute: 540, workdayEndMinute: 1080, hideCompleted: false })
  return { useTimeTracker: () => tracker }
})
import { hideCompleted, setHideCompleted } from './useHideCompleted'

describe('隐没已完成', () => {
  it('读取本人计时偏好，并经原保存流程更新', async () => {
    const preferences = tracker.preferences as Ref<Record<string, unknown>>
    expect(hideCompleted.value).toBe(false)
    preferences.value = { ...preferences.value, hideCompleted: true }
    expect(hideCompleted.value).toBe(true)
    await setHideCompleted(false)
    expect(tracker.setPreferences).toHaveBeenCalledWith({ timerEnabled: true, workdayStartMinute: 540, workdayEndMinute: 1080, hideCompleted: false })
  })
})
