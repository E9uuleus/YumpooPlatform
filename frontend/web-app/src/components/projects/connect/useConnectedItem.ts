import { workItemAssigneeIds } from '../workItemAssignees'
import { computed, inject, ref, shallowRef, type InjectionKey } from 'vue'
import { readCsrfToken, type ProjectContentCatalog, type ProjectDetail, type WorkItemDetail, type WorkItemLabelCatalog } from '@yumpoo/api-client'
import { contentsApi, projectsApi, workItemsApi } from '../../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../../api/problems'
import { useWorkItemEdits, type WorkItemPatchField, type WorkItemPatchValue } from '../useWorkItemEdits'

export interface ConnectedItemData { detail: WorkItemDetail; project: ProjectDetail; contents: ProjectContentCatalog; labels: WorkItemLabelCatalog }
export type ConnectedItemChange = { field: WorkItemPatchField; value: WorkItemPatchValue; dueTime?: string | null | undefined }
  | { field: 'status' | 'title'; value: string }
/** Replaces the work item endpoints, for example in the visual acceptance page that runs without a backend. */
export interface ConnectedItemSource {
  load(workItemId: string): Promise<ConnectedItemData>
  edit(detail: WorkItemDetail, change: ConnectedItemChange): Promise<WorkItemDetail>
}
export const connectedItemSource: InjectionKey<ConnectedItemSource> = Symbol('connectedItemSource')

/**
 * Loads a connected work item through the ordinary, permission-checked work item endpoints and edits it with the same
 * commands as the table, so every change lands in that item's own cell activity. The connection card projection is never
 * widened: callers only use this when the server says the card's project is visible (`canOpen`).
 */
export function useConnectedItem(options: { changed: (detail: WorkItemDetail) => void }) {
  const source = inject(connectedItemSource, undefined)
  const detail = shallowRef<WorkItemDetail>(), project = shallowRef<ProjectDetail>()
  const contents = shallowRef<ProjectContentCatalog>(), labels = shallowRef<WorkItemLabelCatalog>()
  const loading = ref(false), problem = ref<ApiProblem>(), forbidden = ref(false), savingTitle = ref(false)
  let revision = 0
  const edits = useWorkItemEdits({
    updated: (_id, value) => { detail.value = value; options.changed(value) },
    failed: async failure => {
      problem.value = failure
      if (detail.value && isProblemStatus(failure, 412)) await reload(detail.value.id)
    },
  })
  const editable = computed(() => Boolean(detail.value?.capabilities.canEditFields && !detail.value.archived))
  const statuses = computed(() => [...(labels.value?.statuses ?? [])].sort((left, right) => left.sortOrder - right.sortOrder)
    .map(status => ({ ...status, statusCode: status.code })))
  const priorities = computed(() => [...(labels.value?.priorities ?? [])].sort((left, right) => left.sortOrder - right.sortOrder))

  async function load(workItemId: string) {
    const current = ++revision
    loading.value = true; problem.value = undefined; forbidden.value = false
    try {
      const data = source ? await source.load(workItemId) : await fetchItem(workItemId)
      if (current !== revision) return
      detail.value = data.detail; project.value = data.project; contents.value = data.contents; labels.value = data.labels
    } catch (reason) {
      const failure = await toApiProblem(reason)
      if (current !== revision) return
      detail.value = undefined
      if (isProblemStatus(failure, 403) || isProblemStatus(failure, 404)) forbidden.value = true
      else problem.value = failure
    } finally { if (current === revision) loading.value = false }
  }
  async function fetchItem(workItemId: string): Promise<ConnectedItemData> {
    const item = await workItemsApi.getWorkItem({ workItemId })
    const [nextProject, nextContents, nextLabels] = await Promise.all([projectsApi.getProject({ projectId: item.projectId }),
      contentsApi.listProjectContents({ projectId: item.projectId }), workItemsApi.getProjectWorkItemLabels({ projectId: item.projectId })])
    return { detail: item, project: nextProject, contents: nextContents, labels: nextLabels }
  }
  async function viaSource(change: ConnectedItemChange) {
    if (!source || !detail.value) return false
    try { detail.value = await source.edit(detail.value, change); options.changed(detail.value); return true }
    catch (reason) { problem.value = await toApiProblem(reason); return false }
  }
  async function reload(workItemId: string) {
    try { detail.value = await workItemsApi.getWorkItem({ workItemId }) } catch { /* Keep the current values; the problem stays visible. */ }
  }
  function reset() { revision++; detail.value = undefined; project.value = undefined; loading.value = false; problem.value = undefined; forbidden.value = false }

  async function patch(field: WorkItemPatchField, value: WorkItemPatchValue, dueTime?: string | null) {
    if (!detail.value || !editable.value) return false
    problem.value = undefined
    if (source) return viaSource({ field, value, dueTime })
    return edits.patch(detail.value, field, value, dueTime)
  }
  async function transition(statusCode: string) {
    if (!detail.value || !editable.value) return false
    problem.value = undefined
    if (source) return viaSource({ field: 'status', value: statusCode })
    return edits.transition(detail.value, statusCode)
  }
  /** The title has no single-field endpoint; the full update keeps every other field from the latest detail. */
  async function saveTitle(title: string) {
    const item = detail.value
    const value = title.trim()
    if (!item || !editable.value || savingTitle.value || !value || value === item.title) return false
    if (source) return viaSource({ field: 'title', value })
    const csrf = readCsrfToken()
    if (!csrf) { problem.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return false }
    savingTitle.value = true; problem.value = undefined
    try {
      const current = await workItemsApi.getWorkItem({ workItemId: item.id })
      const updated = await workItemsApi.updateWorkItem({ workItemId: item.id, xXSRFTOKEN: csrf, ifMatch: current.etag, workItemUpdateRequest: {
        title: value, priority: current.priority, assigneeUserId: current.assigneeUserId, assigneeUserIds: workItemAssigneeIds(current), description: current.description, notes: current.notes,
        timelineStartDate: current.timelineStartDate, timelineEndDate: current.timelineEndDate, dueDate: current.dueDate, dueTime: current.dueTime ?? null } })
      detail.value = updated; options.changed(updated)
      return true
    } catch (reason) {
      problem.value = await toApiProblem(reason)
      if (isProblemStatus(problem.value, 412)) await reload(item.id)
      return false
    } finally { savingTitle.value = false }
  }
  return { detail, project, contents, labels, loading, problem, forbidden, editable, statuses, priorities, busy: edits.busy, savingTitle,
    load, reset, patch, transition, saveTitle }
}
