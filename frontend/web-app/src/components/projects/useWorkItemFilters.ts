import { computed, onScopeDispose, reactive, ref, watch, type Ref } from 'vue'
import type { LocationQuery } from 'vue-router'
import {
  ListProjectWorkItemFilterOptionsFieldEnum, ProjectMembershipStatusFilter,
  type ListProjectWorkItemsRequest, type ProjectMember, type ProjectWorkItemListItem,
} from '@yumpoo/api-client'
import { projectsApi } from '../../api/client'
import { toApiProblem, type ApiProblem } from '../../api/problems'
import { workItemAssigneeIds } from './workItemAssignees'
import type { WorkItemTableSource } from './workItemTableSource'

interface FilterOptions {
  route: { readonly query: LocationQuery }
  projectId: Readonly<Ref<string>>
  searchInput: Readonly<Ref<string>>
  activeMembers: Readonly<Ref<ProjectMember[]>>
  tableItems: Readonly<Ref<ProjectWorkItemListItem[]>>
  tableSource: Pick<WorkItemTableSource, 'listProjectWorkItemFilterOptions'>
  error: Ref<ApiProblem | undefined>
  listRequest: (cursor: null) => ListProjectWorkItemsRequest
  syncUrl: () => Promise<void>
  getRevision: () => number
  getSignal: () => AbortSignal | null
}

export function useWorkItemFilters({
  route, projectId, searchInput, activeMembers, tableItems, tableSource, error, listRequest, syncUrl, getRevision, getSignal,
}: FilterOptions) {
  const assigneeSearch = ref('')
  const assigneeMatches = ref<ProjectMember[]>()
  const filterOptionCounts = ref(new Map<string, number>())
  const filterOptionsLoading = ref(false)
  let filterOptionsRequestId = 0
  let memberSearchTimer: number | undefined
  const assigneeFilterOpen = ref(false)
  const filterPopoverOpen = ref(false)
  const filters = reactive({
    assignees: new Set<string>(), statuses: new Set<string>(), priorities: new Set<string>(),
    contents: new Set<string>(), dueRange: [] as Date[], updatedAfter: null as Date | null,
  })
  const activeFilterCount = computed(() => filters.assignees.size + filters.statuses.size + filters.priorities.size
    + filters.contents.size + (filters.dueRange.length ? 1 : 0) + (filters.updatedAfter ? 1 : 0))
  /** 已生效的搜索或筛选（以视图查询为准，输入中的搜索词不算）；「隐藏已完成」的隐式状态条件不算。 */
  const explicitFilterActive = computed(() => ['q', 'assignee', 'status', 'priority', 'content', 'dueFrom', 'updatedAfter']
    .some(key => route.query[key] !== undefined))
  const filteredMembers = computed(() => {
    const query = assigneeSearch.value.trim().toLocaleLowerCase()
    return query
      ? (assigneeMatches.value ?? activeMembers.value.filter(item => item.displayName.toLocaleLowerCase().includes(query)))
      : activeMembers.value
  })

  function scheduleMemberSearch(): void {
    if (memberSearchTimer) window.clearTimeout(memberSearchTimer)
    const query = assigneeSearch.value.trim()
    assigneeMatches.value = undefined
    if (!query) return
    const requestedProjectId = projectId.value
    memberSearchTimer = window.setTimeout(async () => {
      try {
        const result = await projectsApi.listProjectMembers({
          projectId: requestedProjectId,
          status: ProjectMembershipStatusFilter.Active,
          q: query,
          page: 0,
          size: 100,
        }, { signal: getSignal() })
        if (requestedProjectId === projectId.value && query === assigneeSearch.value.trim())
          assigneeMatches.value = result.items
      } catch (reason) {
        if (!(reason instanceof DOMException && reason.name === 'AbortError'))
          assigneeMatches.value = activeMembers.value.filter(item => item.displayName
            .toLocaleLowerCase().includes(query.toLocaleLowerCase()))
      }
    }, 250)
  }

  function toggleSet<T>(set: Set<T>, value: T, checked: boolean): void {
    const next = new Set(set)
    if (checked) next.add(value); else next.delete(value)
    if (set === filters.assignees) filters.assignees = next as Set<string>
    else if (set === filters.statuses) filters.statuses = next as Set<string>
    else if (set === filters.priorities) filters.priorities = next as Set<string>
    else filters.contents = next as Set<string>
    void syncUrl()
  }

  function countBy(field: 'statusCode' | 'priority' | 'contentId' | 'assigneeUserId', value: string | null): number {
    const apiField = ({ statusCode: 'STATUS', priority: 'PRIORITY', contentId: 'CONTENT', assigneeUserId: 'ASSIGNEE' } as const)[field]
    return filterOptionCounts.value.get(`${apiField}:${value ?? '__NULL__'}`)
      ?? tableItems.value.filter(item => field === 'assigneeUserId' ? workItemAssigneeIds(item).includes(value ?? '') : item[field] === value).length
  }

  async function loadFilterOptions(): Promise<void> {
    if (filterOptionsLoading.value) return
    filterOptionsLoading.value = true
    const requestId = ++filterOptionsRequestId
    const revision = getRevision()
    const current = () => requestId === filterOptionsRequestId && revision === getRevision()
    const fields = [ListProjectWorkItemFilterOptionsFieldEnum.Assignee,
      ListProjectWorkItemFilterOptionsFieldEnum.Status,
      ListProjectWorkItemFilterOptionsFieldEnum.Priority,
      ListProjectWorkItemFilterOptionsFieldEnum.Content]
    try {
      const base = listRequest(null)
      const { view: _view, limit: _limit, ...context } = base
      void _view; void _limit
      /** 「不显示」的隐式状态条件只作用于表格行；状态计数仍覆盖全部状态，否则已完成状态的数量显示为空。 */
      const { status: _implicitStatus, ...withoutStatus } = context
      void _implicitStatus
      const pages = await Promise.all(fields.map(field => tableSource.listProjectWorkItemFilterOptions({
        ...(field === ListProjectWorkItemFilterOptionsFieldEnum.Status && !filters.statuses.size ? withoutStatus : context),
        projectId: projectId.value, field, limit: 100, countSubitems: true,
      }, { signal: getSignal() })))
      const next = new Map<string, number>()
      if (!current()) return
      pages.forEach((page, index) => page.items.forEach(option => next.set(`${fields[index]}:${option.value}`, option.count)))
      filterOptionCounts.value = next
    } catch (reason) {
      if (!current() || (reason instanceof DOMException && reason.name === 'AbortError')) return
      const problem = await toApiProblem(reason)
      if (current()) error.value = problem
    } finally { if (requestId === filterOptionsRequestId) filterOptionsLoading.value = false }
  }

  function invalidateFilterOptions() {
    filterOptionsRequestId++
    filterOptionsLoading.value = false
  }

  /** 用户设置的搜索与筛选（不含状态）；状态由各请求按场景补上。 */
  function filterRequest() {
    return {
      ...(searchInput.value.trim() ? { q: searchInput.value.trim() } : {}),
      ...(filters.priorities.size ? { priority: filters.priorities } : {}),
      ...(filters.assignees.size ? { assigneeUserId: filters.assignees } : {}),
      ...(filters.contents.size ? { contentId: filters.contents } : {}),
      ...(filters.dueRange[0] ? { dueFrom: filters.dueRange[0] } : {}),
      ...(filters.dueRange[1] ? { dueTo: filters.dueRange[1] } : {}),
      ...(filters.updatedAfter ? { updatedAfter: filters.updatedAfter } : {}),
    }
  }

  function clearFilters(): void {
    filters.assignees = new Set(); filters.statuses = new Set(); filters.priorities = new Set(); filters.contents = new Set()
    filters.dueRange = []; filters.updatedAfter = null
    void syncUrl()
  }

  watch(assigneeSearch, scheduleMemberSearch)
  onScopeDispose(() => { if (memberSearchTimer) window.clearTimeout(memberSearchTimer) })

  return {
    filters, assigneeFilterOpen, filterPopoverOpen, activeFilterCount, explicitFilterActive, assigneeSearch, filteredMembers,
    toggleSet, countBy, loadFilterOptions, invalidateFilterOptions, filterRequest, clearFilters, syncUrl,
  }
}
