import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { ListTimerCandidatesScopeEnum, type TimerCandidate, type TimerCandidateProjectCount } from '@yumpoo/api-client'
import { timeTrackingApi } from '../api/client'
import { onTimeTrackingChanged } from './useTimeTracker'
import { hideCompleted } from './useHideCompleted'

export function useTimerCandidates(active: Ref<boolean>, filters: { projects?: Ref<readonly string[]>; assignedToMe?: Ref<boolean> } = {}) {
  const query = ref('')
  const scope = ref(ListTimerCandidatesScopeEnum.Personal)
  const items = ref<TimerCandidate[]>([])
  const projectCounts = ref<TimerCandidateProjectCount[]>([])
  const hiddenCount = ref(0)
  const loading = ref(false)
  const problem = ref('')
  const nextOffset = ref<number | null>(null)
  const searching = computed(() => !!query.value.trim())
  let revision = 0
  let abort: AbortController | undefined
  let timeout: ReturnType<typeof setTimeout> | undefined

  async function load(append = false) {
    if (!active.value || (append && (loading.value || nextOffset.value === null))) return
    const request = ++revision
    abort?.abort()
    abort = new AbortController()
    loading.value = true
    problem.value = ''
    try {
      const page = await timeTrackingApi.listTimerCandidates({
        q: query.value.trim(), scope: searching.value ? ListTimerCandidatesScopeEnum.All : scope.value,
        offset: append ? nextOffset.value ?? 0 : 0, limit: 25,
        ...(filters.projects?.value.length ? { projectId: [...filters.projects.value] } : {}),
        ...(filters.assignedToMe?.value ? { assignedToMe: true } : {}),
        ...(hideCompleted.value && !searching.value ? { excludeCompleted: true } : {}),
      }, { signal: abort.signal })
      if (request !== revision) return
      const merged = append ? [...items.value, ...page.items] : page.items
      items.value = [...new Map(merged.map(item => [item.workItemId, item])).values()]
      nextOffset.value = page.nextOffset
      if (!append) {
        projectCounts.value = page.projects ?? []
        hiddenCount.value = page.hiddenCompletedCount ?? 0
      }
    } catch {
      if (request === revision) problem.value = '工作项加载失败，请检查连接后重试。'
    } finally { if (request === revision) loading.value = false }
  }

  function schedule() {
    ++revision
    abort?.abort()
    clearTimeout(timeout)
    items.value = []
    projectCounts.value = []
    hiddenCount.value = 0
    nextOffset.value = null
    problem.value = ''
    loading.value = active.value
    if (active.value) timeout = setTimeout(() => { void load() }, searching.value ? 200 : 0)
  }
  watch([active, query, scope, () => filters.projects?.value.join(','), () => filters.assignedToMe?.value, hideCompleted], schedule, { immediate: true })
  const off = onTimeTrackingChanged(() => { if (active.value) { clearTimeout(timeout); void load() } })
  onBeforeUnmount(() => { ++revision; clearTimeout(timeout); abort?.abort(); off() })
  return { query, scope, items, projectCounts, hiddenCount, loading, problem, nextOffset, searching, load }
}
