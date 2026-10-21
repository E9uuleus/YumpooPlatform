import { computed } from 'vue'
import { useTimeTracker } from './useTimeTracker'

/** 一个开关处处同步：保存在本人计时偏好中，工作项表格据此淡化已完成项，计时与补录的选择列表据此隐藏已完成项。 */
export const hideCompleted = computed(() => useTimeTracker().preferences.value.hideCompleted === true)

/** 复用计时偏好的保存流程（If-Match），其他窗口经计时广播同步。 */
export function setHideCompleted(value: boolean): Promise<boolean> {
  const tracker = useTimeTracker()
  return tracker.setPreferences({ ...tracker.preferences.value, hideCompleted: value })
}

export function useHideCompleted() {
  return { hideCompleted, setHideCompleted }
}
