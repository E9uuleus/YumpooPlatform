import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import type { MyTimeSessionPage, TeamTimesheetDay } from '@yumpoo/api-client'
import { myWorkApi, timeTrackingApi } from '../../../api/client'
import { timeProblemMessage } from '../../../composables/timeTrackingProblems'
import { onTimeTrackingChanged, useTimeTracker } from '../../../composables/useTimeTracker'
import { toApiDate } from '../../team/teamDashboardModel'
import { atMinute, coveredMs, dayWindow, HOUR, itemTotals, layout, MINUTE, shares, type DayBlock } from './dayModel'

export const DEFAULT_TIME_PREFERENCES = { timerEnabled: true, workdayStartMinute: 540, workdayEndMinute: 1080 }

export function useDayTimeline(date: Ref<string>, timezone: Ref<string>) {
  const tracker = useTimeTracker()
  const page = ref<MyTimeSessionPage>()
  const day = ref<TeamTimesheetDay>()
  const loading = ref(false)
  const problem = ref('')
  /** Sessions that appeared after one of our own saves, flashed briefly so the result is easy to spot. */
  const fresh = ref<ReadonlySet<string>>(new Set())
  let revision = 0, held = 0, deferred = false, flashTimer: ReturnType<typeof setTimeout> | undefined
  let known: Set<string> | undefined

  const dayRange = computed(() => dayWindow(date.value, timezone.value))
  /** Geometry follows the minute: at 1px per minute nothing visible changes in between. */
  const now = computed(() => Math.floor(tracker.now.value / MINUTE) * MINUTE)
  const limit = computed(() => Math.min(dayRange.value.end, now.value))
  const preferences = computed(() => tracker.current.value?.preferences ?? DEFAULT_TIME_PREFERENCES)
  const workday = computed(() => ({
    start: atMinute(date.value, preferences.value.workdayStartMinute, timezone.value),
    end: atMinute(date.value, preferences.value.workdayEndMinute, timezone.value),
  }))
  const blocks = computed<DayBlock[]>(() => page.value?.items.map(item => ({
    id: item.session.id, workItemId: item.session.workItemId, projectId: item.session.projectId,
    title: item.title ?? '不可见工作项', itemNo: item.itemNo, projectName: item.projectName, colorToken: item.contentColorToken,
    start: item.session.startedAt.getTime(), end: item.session.stoppedAt?.getTime() ?? null,
    canEdit: item.session.canEdit, etag: item.session.etag,
  })) ?? [])
  const layoutMap = computed(() => layout(blocks.value, now.value))
  const shareMap = computed(() => shares(blocks.value, now.value, dayRange.value))
  const totals = computed(() => itemTotals(blocks.value, now.value, dayRange.value))
  const recordedMs = computed(() => coveredMs(blocks.value, now.value, dayRange.value))
  const targetMs = computed(() => day.value ? day.value.standardMinutes * MINUTE : 8 * HOUR)

  async function load(): Promise<void> {
    if (held) { deferred = true; return }
    const request = ++revision, target = date.value, { start, end } = dayRange.value
    loading.value = true
    problem.value = ''
    try {
      const [sessions, sheet] = await Promise.all([
        timeTrackingApi.listMyTimeSessions({ from: new Date(start), to: new Date(end) }),
        myWorkApi.getMyTimesheet({ from: toApiDate(target), to: toApiDate(target) }).catch(() => undefined),
      ])
      if (request !== revision) return
      const ids = new Set(sessions.items.map(item => item.session.id))
      if (known) flash([...ids].filter(id => !known!.has(id)))
      known = ids
      page.value = sessions
      day.value = sheet?.days[0] ?? day.value
    } catch (error) {
      if (request === revision) problem.value = await timeProblemMessage(error)
    } finally { if (request === revision) loading.value = false }
  }

  function flash(ids: string[]): void {
    if (!ids.length) return
    clearTimeout(flashTimer)
    fresh.value = new Set(ids)
    flashTimer = setTimeout(() => { fresh.value = new Set() }, 1600)
  }

  /** Defers reloads while the person is dragging, so a background refresh never yanks the block from under the pointer. */
  function hold(): () => void {
    held++
    let released = false
    return () => {
      if (released) return
      released = true
      if (--held === 0 && deferred) { deferred = false; void load() }
    }
  }

  const reloadVisible = () => { if (document.visibilityState === 'visible') void load() }
  watch([date, timezone], () => { page.value = undefined; day.value = undefined; known = undefined; void load() }, { immediate: true })
  const off = onTimeTrackingChanged(() => void load())
  document.addEventListener('visibilitychange', reloadVisible)
  onBeforeUnmount(() => {
    ++revision
    off()
    clearTimeout(flashTimer)
    document.removeEventListener('visibilitychange', reloadVisible)
  })

  return { page, day, loading, problem, fresh, dayRange, now, limit, preferences, workday, blocks, layoutMap, shareMap, totals,
    recordedMs, targetMs, load, hold }
}
