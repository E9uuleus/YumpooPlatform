import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { readCsrfToken, type TeamDashboardOptions, type TeamMemberTask, type TeamTimesheet, type TeamWorkload } from '@yumpoo/api-client'
import { teamDashboardApi } from '../../../api/client'
import { problemMessage, toApiProblem } from '../../../api/problems'
import { useSession } from '../../../composables/useSession'
import { periodFor, todayIn, toApiDate, type Period } from './teamDashboardModel'

export interface MemberTasks { items: TeamMemberTask[]; total: number; loading: boolean; error: string }

/** Company-wide team reporting; the view has no project or member filter, so every query sends empty sets. */
export function useTeamDashboard() {
  const session = useSession()
  const timezone = computed(() => options.value?.timezone ?? session.authentication.value?.company.timezone ?? 'Asia/Shanghai')
  const options = ref<TeamDashboardOptions>(), optionsError = ref('')
  const period = ref<Period>(periodFor('WEEK', todayIn(session.authentication.value?.company.timezone ?? 'Asia/Shanghai')))
  const timesheet = ref<TeamTimesheet>(), timesheetLoading = ref(false), timesheetError = ref('')
  const workload = ref<TeamWorkload>(), workloadLoading = ref(false), workloadError = ref('')
  const tasks = ref(new Map<string, MemberTasks>())
  let timesheetToken = 0, workloadToken = 0
  const csrf = () => readCsrfToken() || ''
  const message = async (reason: unknown) => problemMessage(await toApiProblem(reason))

  async function loadOptions() {
    optionsError.value = ''
    try { options.value = await teamDashboardApi.getTeamDashboardOptions() } catch (reason) { optionsError.value = await message(reason) }
  }
  async function loadTimesheet() {
    const token = ++timesheetToken; timesheetLoading.value = true; timesheetError.value = ''
    try {
      const result = await teamDashboardApi.queryTeamTimesheet({ xXSRFTOKEN: csrf(), teamTimesheetQuery: {
        from: toApiDate(period.value.from), to: toApiDate(period.value.to), projectIds: new Set(), userIds: new Set() } })
      if (token === timesheetToken) timesheet.value = result
    } catch (reason) { if (token === timesheetToken) timesheetError.value = await message(reason) }
    finally { if (token === timesheetToken) timesheetLoading.value = false }
  }
  async function loadWorkload() {
    const token = ++workloadToken; workloadLoading.value = true; workloadError.value = ''
    try {
      const result = await teamDashboardApi.queryTeamWorkload({ xXSRFTOKEN: csrf(), teamWorkloadQuery: { projectIds: new Set(), userIds: new Set() } })
      if (token === workloadToken) { tasks.value = new Map(); workload.value = result }
    } catch (reason) { if (token === workloadToken) workloadError.value = await message(reason) }
    finally { if (token === workloadToken) workloadLoading.value = false }
  }
  /** `key` is the member id, or `''` for unassigned work. */
  async function loadTasks(key: string, append = false) {
    const current = tasks.value.get(key), token = workloadToken
    if (current?.loading) return
    const state: MemberTasks = { items: append ? current?.items ?? [] : [], total: current?.total ?? 0, loading: true, error: '' }
    tasks.value = new Map(tasks.value).set(key, state)
    try {
      const page = await teamDashboardApi.queryTeamMemberTasks({ xXSRFTOKEN: csrf(), teamMemberTasksQuery: {
        userId: key || null, projectIds: new Set(), offset: state.items.length, limit: 50 } })
      if (token !== workloadToken) return
      tasks.value = new Map(tasks.value).set(key, { items: [...state.items, ...page.items], total: page.totalElements, loading: false, error: '' })
    } catch (reason) {
      if (token === workloadToken) tasks.value = new Map(tasks.value).set(key, { ...state, loading: false, error: await message(reason) })
    }
  }
  function refresh() { void loadOptions(); void loadTimesheet(); void loadWorkload() }

  watch(period, () => void loadTimesheet())
  onBeforeUnmount(() => { ++timesheetToken; ++workloadToken })
  refresh()

  return { options, optionsError, timezone, period, timesheet, timesheetLoading, timesheetError,
    workload, workloadLoading, workloadError, tasks, loadTimesheet, loadWorkload, loadTasks, refresh }
}
