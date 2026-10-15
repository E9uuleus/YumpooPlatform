import { computed, onBeforeUnmount, ref, type Ref } from 'vue'
import type { MyTimeSessionPage, TimeSessionFillEntry, TimeSessionFillMode } from '@yumpoo/api-client'
import { timeTrackingApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'
import { onTimeTrackingChanged, timerMutation } from '../../composables/useTimeTracker'
import { addDays } from '../team/teamDashboardModel'
import { parseCompanyDateTime } from '../timer/timeFormat'
import type { DayBlock } from './myDayModel'

export function useMyDay(timezone: Ref<string>) {
  const date = ref('')
  const page = ref<MyTimeSessionPage>()
  const loading = ref(false)
  const problem = ref('')
  const dayStart = computed(() => date.value ? parseCompanyDateTime(`${date.value}T00:00:00`, timezone.value).getTime() : 0)
  const dayEnd = computed(() => date.value ? parseCompanyDateTime(`${addDays(date.value, 1)}T00:00:00`, timezone.value).getTime() : 0)
  const blocks = computed<DayBlock[]>(() => page.value?.items.map(item => ({
    id: item.session.id, workItemId: item.session.workItemId, title: item.title ?? '不可见工作项',
    itemNo: item.itemNo, projectName: item.projectName, colorToken: item.contentColorToken,
    start: item.session.startedAt.getTime(), end: item.session.stoppedAt?.getTime() ?? null,
    canEdit: item.session.canEdit, allocatedMs: item.session.allocatedDurationMs, parallelItemCount: item.session.parallelItemCount,
  })) ?? [])
  let revision = 0

  async function load(value: string) {
    const request = ++revision
    if (date.value !== value) page.value = undefined
    date.value = value
    loading.value = true
    problem.value = ''
    try {
      const result = await timeTrackingApi.listMyTimeSessions({ from: new Date(dayStart.value), to: new Date(dayEnd.value) })
      if (request === revision) page.value = result
    } catch (error) {
      if (request === revision) problem.value = problemMessage(await toApiProblem(error))
    } finally { if (request === revision) loading.value = false }
  }

  function fill(mode: TimeSessionFillMode, entries: TimeSessionFillEntry[]) {
    const body = { mode, entries }
    return timerMutation(JSON.stringify(body), (idempotencyKey, xXSRFTOKEN) => timeTrackingApi.fillMyTimeSessions({
      idempotencyKey, xXSRFTOKEN, timeSessionFillCommand: body,
    }))
  }

  const off = onTimeTrackingChanged(() => { if (date.value) void load(date.value) })
  onBeforeUnmount(() => { ++revision; off() })
  return { date, page, blocks, dayStart, dayEnd, loading, problem, load, fill }
}
