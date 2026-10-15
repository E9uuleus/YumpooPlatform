import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import { TimeSessionFillMode, type MyTimeSessionPage, type TeamTimesheetDay } from '@yumpoo/api-client'
import { myWorkApi, timeTrackingApi } from '../../../api/client'
import { timeProblemMessage } from '../../../composables/timeTrackingProblems'
import { onTimeTrackingChanged, timerMutation, useTimeTracker } from '../../../composables/useTimeTracker'
import { toApiDate } from '../../team/teamDashboardModel'
import { atMinute, coveredMs, dayWindow, durationLabel, itemTotals, layout, MINUTE, shares, type DayBlock, type Range } from './dayModel'

export function useDayTimeline(date: Ref<string>, timezone: Ref<string>) {
  const tracker = useTimeTracker()
  const page = ref<MyTimeSessionPage>()
  const day = ref<TeamTimesheetDay>()
  const loading = ref(false)
  const busy = ref(false)
  const problem = ref('')
  /** Sessions that appeared after one of our own saves, flashed briefly so the result is easy to spot. */
  const fresh = ref<ReadonlySet<string>>(new Set())
  let revision = 0, held = 0, deferred = false, flashTimer: ReturnType<typeof setTimeout> | undefined
  let known: Set<string> | undefined

  const dayRange = computed(() => dayWindow(date.value, timezone.value))
  /** Geometry follows the minute: at 1.6px per minute nothing visible changes in between. */
  const now = computed(() => Math.floor(tracker.now.value / MINUTE) * MINUTE)
  const limit = computed(() => Math.min(dayRange.value.end, now.value))
  const preferences = tracker.preferences
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

  /** Runs one write; conflicts and failures are explained here and the day reloads, so callers only learn success or not. */
  async function mutate<T>(identity: unknown[], call: (idempotencyKey: string, xXSRFTOKEN: string) => Promise<T>): Promise<T | undefined> {
    if (busy.value) return undefined
    busy.value = true
    try {
      return await timerMutation(JSON.stringify(identity), call, { quiet: true })
    } catch (error) {
      ElMessage.error(await timeProblemMessage(error))
      void load()
      return undefined
    } finally { busy.value = false }
  }

  function fill(mode: TimeSessionFillMode, workItemIds: string[], ranges: Range[]) {
    const body = { mode, entries: workItemIds.flatMap(workItemId => ranges.map(range =>
      ({ workItemId, startedAt: new Date(range.start), stoppedAt: new Date(range.end) }))) }
    return mutate(['fill', body], (idempotencyKey, xXSRFTOKEN) =>
      timeTrackingApi.fillMyTimeSessions({ idempotencyKey, xXSRFTOKEN, timeSessionFillCommand: body }))
  }

  async function create(mode: TimeSessionFillMode, workItemIds: string[], ranges: Range[]): Promise<boolean> {
    const result = await fill(mode, workItemIds, ranges)
    if (!result) return false
    if (!result.createdCount && !result.changedCount && !result.deletedCount) ElMessage.warning('这段时间已全部有记录，没有新增。')
    else if (result.skippedDurationMs > 0) ElMessage.info(mode === TimeSessionFillMode.Replace
      ? `已更新记录；所选工作项已有的 ${durationLabel(result.skippedDurationMs)} 记录已保留。`
      : `已补录；其中 ${durationLabel(result.skippedDurationMs)} 已有记录，已跳过。`)
    return true
  }

  async function edit(block: DayBlock, range: Range): Promise<boolean> {
    const timeTrackingCommand = { startedAt: new Date(range.start), stoppedAt: new Date(range.end) }
    return !!await mutate(['edit', block.id, block.etag, timeTrackingCommand], (idempotencyKey, xXSRFTOKEN) => timeTrackingApi.editTimeSession({
      workItemId: block.workItemId!, sessionId: block.id, ifMatch: block.etag, idempotencyKey, xXSRFTOKEN, timeTrackingCommand }))
  }

  async function remove(block: DayBlock): Promise<boolean> {
    return !!await mutate(['delete', block.id, block.etag], (idempotencyKey, xXSRFTOKEN) => timeTrackingApi.deleteTimeSession({
      workItemId: block.workItemId!, sessionId: block.id, ifMatch: block.etag, idempotencyKey, xXSRFTOKEN, timeTrackingCommand: {} }))
  }

  async function reassign(block: DayBlock, workItemId: string): Promise<boolean> {
    const result = await mutate(['reassign', block.id, block.etag, workItemId], (idempotencyKey, xXSRFTOKEN) =>
      timeTrackingApi.reassignMyTimeSession({ sessionId: block.id, ifMatch: block.etag, idempotencyKey, xXSRFTOKEN,
        timeSessionReassignCommand: { workItemId } }))
    if (result?.skippedDurationMs) ElMessage.info(`目标工作项在其中 ${durationLabel(result.skippedDurationMs)} 已有记录，这部分已合并。`)
    return !!result
  }

  /** Keeps only this item over its own range by trimming the other editable records there. */
  async function exclusive(block: DayBlock): Promise<boolean> {
    const result = await fill(TimeSessionFillMode.Replace, [block.workItemId!], [{ start: block.start, end: block.end! }])
    if (!result) return false
    if (result.createdCount || result.changedCount || result.deletedCount) ElMessage.success('已调整可编辑的重叠记录。')
    else ElMessage.info('没有可调整的重叠记录。')
    return true
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

  return { page, day, loading, busy, problem, fresh, dayRange, now, limit, preferences, workday, blocks, layoutMap, shareMap, totals,
    recordedMs, load, hold, create, edit, remove, reassign, exclusive }
}
