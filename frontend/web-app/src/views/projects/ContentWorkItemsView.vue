<script setup lang="ts">
import { ArrowLeft, MoreFilled, Plus, Rank } from '@element-plus/icons-vue'
import {
  ContentStatus,
  ContentTableColumn,
  ContentViewType,
  ProjectActorAccess,
  ProjectLifecycle,
  ProjectMembershipStatus,
  ProjectMembershipStatusFilter,
  WorkItemPriority,
  WorkItemRankPlacement,
  readCsrfToken,
  type Content,
  type ContentStatusGroup,
  type ProjectContentCatalog,
  type ProjectDetail,
  type ProjectMember,
  type WorkItemDetail,
  type WorkItemPage,
  type WorkItemSummary,
  type WorkItemTransitionOption,
} from '@yumpoo/api-client'
import {
  ElButton,
  ElDialog,
  ElDatePicker,
  ElDrawer,
  ElForm,
  ElFormItem,
  ElInput,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElMessage,
  ElOption as ElOptionRaw,
  ElPagination,
  ElSelect as ElSelectRaw,
  ElTable,
  ElTableColumn,
} from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch, type DefineComponent } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { contentsApi, projectsApi, workItemsApi } from '../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpEmptyState from '../../components/yp/YpEmptyState.vue'
import YpPriorityBadge from '../../components/yp/YpPriorityBadge.vue'
import ProjectWorkspaceHeader from '../../components/projects/ProjectWorkspaceHeader.vue'
import ContentTableQueryEditor from '../../components/projects/ContentTableQueryEditor.vue'
import {
  cloneTableQuery,
  encodeTableQuery,
  hasCustomTableQuery,
  parseTableQuery,
  sharedTableQuery,
  withoutTableQuery,
  type ContentTableQuery,
} from '../../components/projects/contentTableQuery'

interface TableColumnDefinition {
  code: ContentTableColumn
  label: string
  minWidth: number
}

interface KanbanLaneState {
  items: WorkItemSummary[]
  page: number
  totalElements: number
  totalPages: number
  loading: boolean
  error?: ApiProblem
}

interface KanbanMoveIntent {
  item: WorkItemSummary
  sourceStatus: string
  targetStatus: string
  placement: WorkItemRankPlacement
  anchorWorkItemId?: string
  resolution?: string
  idempotencyKey: string
}

interface PointerDragState {
  pointerId: number
  item: WorkItemSummary
  sourceStatus: string
  startX: number
  startY: number
  active: boolean
  targetStatus: string | undefined
  placement: WorkItemRankPlacement | undefined
  anchorWorkItemId: string | undefined
}

interface WorkItemFieldsDraft {
  title: string
  priority: WorkItemPriority
  assigneeUserId: string
  description: string
  notes: string
  timelineStartDate: string
  timelineEndDate: string
  dueDate: string
}

interface WorkItemUndoEntry {
  tombstone: WorkItemDetail
  restoreIdempotencyKey: string
  loading: boolean
  problem?: ApiProblem
}

const route = useRoute()
const router = useRouter()
const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent
const projectId = String(route.params.projectId)
const contentId = String(route.params.contentId)
const project = ref<ProjectDetail>()
const catalog = ref<ProjectContentCatalog>()
const content = ref<Content>()
const members = ref<ProjectMember[]>([])
const tableQuery = ref<ContentTableQuery>({
  filters: { query: null, statusCodes: new Set(), priorities: new Set(),
    assigneeUserIds: new Set(), dueFrom: null, dueTo: null, updatedAfter: null },
  sort: [],
})
const sharedQueryConflict = ref<Content>()
const savingSharedQuery = ref(false)
let queryRevision = 0
let syncingRoute = false
const loading = ref(false)
const tableLoading = ref(false)
const error = ref<ApiProblem>()
const selectedView = ref<ContentViewType>(ContentViewType.Table)
const tablePage = ref<WorkItemPage>({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
const createOpen = ref(false)
const creating = ref(false)
const createForm = reactive({
  title: '',
  priority: WorkItemPriority.Medium,
  assigneeUserId: '',
  description: '',
  notes: '',
  timelineStartDate: '',
  timelineEndDate: '',
  dueDate: '',
})
const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<WorkItemDetail>()
const detailDraft = reactive<WorkItemFieldsDraft>({
  title: '', priority: WorkItemPriority.Medium, assigneeUserId: '', description: '', notes: '',
  timelineStartDate: '', timelineEndDate: '', dueDate: '',
})
const detailSaving = ref(false)
const latestConflict = ref<WorkItemDetail>()
const deleteOpen = ref(false)
const deleteReason = ref('')
const deleting = ref(false)
const deleteIdempotencyKey = ref<string>()
const undoEntries = ref<WorkItemUndoEntry[]>([])
const transitionOpen = ref(false)
const transitionSaving = ref(false)
const transitionToStatus = ref('')
const transitionResolution = ref('')
const transitionIdempotencyKey = ref<string>()
const kanbanStates = reactive<Record<string, KanbanLaneState>>({})
const kanbanInitialized = ref(false)
const kanbanBoard = ref<HTMLElement>()
const movingWorkItemId = ref<string>()
const failedMove = ref<KanbanMoveIntent>()
const moveResolutionOpen = ref(false)
const moveResolution = ref('')
const awaitingResolution = ref<KanbanMoveIntent>()
const pointerDrag = ref<PointerDragState>()
let moveAttempt = 0

const supportedColumns: Record<string, TableColumnDefinition> = {
  [ContentTableColumn.ItemNo]: { code: ContentTableColumn.ItemNo, label: '事项编号', minWidth: 130 },
  [ContentTableColumn.Title]: { code: ContentTableColumn.Title, label: '标题', minWidth: 250 },
  [ContentTableColumn.Status]: { code: ContentTableColumn.Status, label: '状态', minWidth: 120 },
  [ContentTableColumn.Priority]: { code: ContentTableColumn.Priority, label: '优先级', minWidth: 100 },
  [ContentTableColumn.Assignee]: { code: ContentTableColumn.Assignee, label: '处理人', minWidth: 160 },
  [ContentTableColumn.Reporter]: { code: ContentTableColumn.Reporter, label: '报告人', minWidth: 160 },
  [ContentTableColumn.Description]: { code: ContentTableColumn.Description, label: '描述', minWidth: 220 },
  [ContentTableColumn.Notes]: { code: ContentTableColumn.Notes, label: '备注', minWidth: 220 },
  [ContentTableColumn.Timeline]: { code: ContentTableColumn.Timeline, label: '计划时间', minWidth: 210 },
  [ContentTableColumn.DueDate]: { code: ContentTableColumn.DueDate, label: '截止日', minWidth: 130 },
  [ContentTableColumn.UpdatedAt]: { code: ContentTableColumn.UpdatedAt, label: '更新时间', minWidth: 180 },
}

const tableColumns = computed(() => {
  if (!content.value) return []
  const hidden = content.value.viewConfig.table.hiddenColumns
  return Array.from(content.value.viewConfig.table.columnOrder)
    .filter(code => !hidden.has(code))
    .map(code => supportedColumns[code])
    .filter((column): column is TableColumnDefinition => Boolean(column))
})
const kanbanGroups = computed(() => content.value?.viewConfig.kanban.statusGroups ?? [])
const configuredKanbanStatuses = computed(() => new Set(
  kanbanGroups.value.flatMap(group => Array.from(group.statusCodes)),
))
const canCreate = computed(() => Boolean(project.value && content.value
  && project.value.lifecycle !== ProjectLifecycle.Archived
  && content.value.status === ContentStatus.Active
  && (project.value.actorAccess === ProjectActorAccess.Owner
    || project.value.actorAccess === ProjectActorAccess.Member)))
const readOnlyReason = computed(() => {
  if (project.value?.lifecycle === ProjectLifecycle.Archived) return 'Project 已归档，工作项仅可查看。'
  if (content.value?.status === ContentStatus.Archived) return 'Content 已归档，不能创建工作项。'
  if (project.value?.actorAccess === ProjectActorAccess.CompanyAdmin) return 'Company Admin 在 Project 工作区中保持只读。'
  if (!canCreate.value) return '当前角色没有创建工作项的权限。'
  return undefined
})
const canEditDetail = computed(() => detail.value?.capabilities.canEditFields === true)
const canDeleteDetail = computed(() => detail.value?.capabilities.canDelete === true)
const detailDraftDirty = computed(() => {
  if (!detail.value) return false
  return detailDraft.title !== detail.value.title
    || detailDraft.priority !== detail.value.priority
    || detailDraft.assigneeUserId !== (detail.value.assigneeUserId ?? '')
    || detailDraft.description !== (detail.value.description ?? '')
    || detailDraft.notes !== (detail.value.notes ?? '')
    || detailDraft.timelineStartDate !== inputDate(detail.value.timelineStartDate)
    || detailDraft.timelineEndDate !== inputDate(detail.value.timelineEndDate)
    || detailDraft.dueDate !== inputDate(detail.value.dueDate)
})
const activeMembers = computed(() => members.value.filter(member =>
  member.membershipStatus === ProjectMembershipStatus.Active))
const canSaveSharedQuery = computed(() => project.value?.actorAccess === ProjectActorAccess.Owner
  && content.value?.status === ContentStatus.Active)
const availableTransitions = computed(() => detail.value?.capabilities.availableTransitions ?? [])
const selectedTransition = computed<WorkItemTransitionOption | undefined>(() =>
  availableTransitions.value.find(item => item.toStatus === transitionToStatus.value))

function groupKey(group: ContentStatusGroup, index: number): string {
  return `${index}:${group.name}:${Array.from(group.statusCodes).join(',')}`
}

function kanbanState(statusCode: string): KanbanLaneState {
  if (!kanbanStates[statusCode]) {
    kanbanStates[statusCode] = { items: [], page: 0, totalElements: 0, totalPages: 0, loading: false }
  }
  return kanbanStates[statusCode]
}

function groupTotal(group: ContentStatusGroup): number {
  return Array.from(group.statusCodes).reduce((total, status) => total + kanbanState(status).totalElements, 0)
}

function statusVisible(statusCode: string): boolean {
  const selected = tableQuery.value.filters.statusCodes
  return !selected.size || selected.has(statusCode)
}

function canDrop(item: WorkItemSummary, statusCode: string): boolean {
  return configuredKanbanStatuses.value.has(statusCode) && statusVisible(statusCode)
    && item.capabilities.canMoveInKanban
    && (statusCode === item.statusCode
      || item.capabilities.availableTransitions.some(option => option.toStatus === statusCode))
}

function statusOption(statusCode: string) {
  return catalog.value?.workflowStatusOptions.find(item => item.statusCode === statusCode)
}

function statusLabel(statusCode: string): string {
  return statusOption(statusCode)?.displayName ?? statusCode
}

function statusTone(statusCode: string): string {
  const category = statusOption(statusCode)?.statusCategory
  if (category === 'DONE') return 'green'
  if (category === 'IN_PROGRESS') return 'blue'
  if (category === 'CANCELED') return 'gray'
  return 'yellow'
}

function typeLabel(type: string): string {
  return ({ TASK: '任务', DEFECT: '缺陷', REQUIREMENT: '需求' } as Record<string, string>)[type] ?? type
}

function formatDate(value: Date): string {
  return value.toLocaleString('zh-CN')
}

function naturalDate(value: Date | null): string {
  return value?.toISOString().slice(0, 10) ?? '—'
}

function timeline(startDate: Date | null, endDate: Date | null): string {
  const start = naturalDate(startDate)
  const end = naturalDate(endDate)
  return start === '—' && end === '—' ? '—' : `${start} → ${end}`
}

function apiDate(value: string): Date | null {
  return value ? new Date(`${value}T00:00:00.000Z`) : null
}

function inputDate(value: Date | null): string {
  return value?.toISOString().slice(0, 10) ?? ''
}

function validateDateRange(draft: WorkItemFieldsDraft): boolean {
  if (draft.timelineStartDate && draft.timelineEndDate
    && draft.timelineEndDate < draft.timelineStartDate) {
    error.value = localProblem('计划结束日不得早于计划开始日。')
    return false
  }
  return true
}

function requestFields(draft: WorkItemFieldsDraft) {
  return {
    title: draft.title.trim(),
    priority: draft.priority,
    assigneeUserId: draft.assigneeUserId || null,
    description: draft.description.trim() || null,
    notes: draft.notes.trim() || null,
    timelineStartDate: apiDate(draft.timelineStartDate),
    timelineEndDate: apiDate(draft.timelineEndDate),
    dueDate: apiDate(draft.dueDate),
  }
}

function assignDraft(target: WorkItemFieldsDraft, source: WorkItemDetail): void {
  target.title = source.title
  target.priority = source.priority
  target.assigneeUserId = source.assigneeUserId ?? ''
  target.description = source.description ?? ''
  target.notes = source.notes ?? ''
  target.timelineStartDate = inputDate(source.timelineStartDate)
  target.timelineEndDate = inputDate(source.timelineEndDate)
  target.dueDate = inputDate(source.dueDate)
}

function workItemRow(row: unknown): WorkItemSummary {
  return row as WorkItemSummary
}

async function loadWorkspace(): Promise<void> {
  loading.value = true
  error.value = undefined
  try {
    const [nextProject, nextCatalog] = await Promise.all([
      projectsApi.getProject({ projectId }),
      contentsApi.listProjectContents({ projectId }),
    ])
    const nextContent = nextCatalog.items.find(item => item.id === contentId)
    if (!nextContent) {
      error.value = localProblem('当前 Project 中不存在该 Content，或当前账号无权查看。')
      return
    }
    project.value = nextProject
    catalog.value = nextCatalog
    content.value = nextContent
    await loadMembers()
    tableQuery.value = hasCustomTableQuery(route.query)
      ? parseTableQuery(route.query)
      : sharedTableQuery(nextContent.viewConfig.table.filters, nextContent.viewConfig.table.sort)
    selectedView.value = nextContent.defaultViewType
    if (selectedView.value === ContentViewType.Kanban) await loadKanban()
    else await loadTable(0)
  } catch (reason) {
    error.value = await toApiProblem(reason)
  } finally {
    loading.value = false
  }
}

async function loadMembers(): Promise<void> {
  const loaded: ProjectMember[] = []
  let page = 0
  let totalPages = 1
  while (page < totalPages) {
    const result = await projectsApi.listProjectMembers({
      projectId,
      status: ProjectMembershipStatusFilter.All,
      page,
      size: 100,
    })
    loaded.push(...result.items)
    totalPages = result.totalPages
    page += 1
  }
  members.value = loaded
}

async function loadTable(page: number): Promise<void> {
  const revision = queryRevision
  tableLoading.value = true
  error.value = undefined
  try {
    const result = await workItemsApi.listContentWorkItems({
      contentId,
      page,
      size: tablePage.value.size,
      ...queryRequest(true),
    })
    if (revision === queryRevision) tablePage.value = result
  } catch (reason) {
    if (revision === queryRevision) error.value = await toApiProblem(reason)
  } finally {
    if (revision === queryRevision) tableLoading.value = false
  }
}

async function loadKanban(): Promise<void> {
  if (kanbanInitialized.value) return
  kanbanInitialized.value = true
  const statuses = kanbanGroups.value.flatMap(group => Array.from(group.statusCodes))
  await Promise.all(statuses.map(status => loadKanbanLane(status, 0)))
}

async function loadKanbanLane(statusCode: string, page: number): Promise<void> {
  const revision = queryRevision
  const state = kanbanState(statusCode)
  state.loading = true
  delete state.error
  if (!statusVisible(statusCode)) {
    state.items = []
    state.page = 0
    state.totalElements = 0
    state.totalPages = 0
    state.loading = false
    return
  }
  try {
    const result = await workItemsApi.listContentWorkItems({
      contentId,
      view: ContentViewType.Kanban,
      page,
      size: 20,
      ...queryRequest(false),
      status: new Set([statusCode]),
    })
    if (revision !== queryRevision) return
    state.items = page === 0 ? result.items : [...state.items, ...result.items]
    state.page = result.page
    state.totalElements = result.totalElements
    state.totalPages = result.totalPages
  } catch (reason) {
    if (revision === queryRevision) state.error = await toApiProblem(reason)
  } finally {
    if (revision === queryRevision) state.loading = false
  }
}

function moveIntent(item: WorkItemSummary, sourceStatus: string, targetStatus: string,
  placement: WorkItemRankPlacement, anchorWorkItemId?: string): KanbanMoveIntent {
  return {
    item,
    sourceStatus,
    targetStatus,
    placement,
    ...(anchorWorkItemId ? { anchorWorkItemId } : {}),
    idempotencyKey: globalThis.crypto.randomUUID(),
  }
}

function transitionFor(item: WorkItemSummary, statusCode: string): WorkItemTransitionOption | undefined {
  return item.capabilities.availableTransitions.find(option => option.toStatus === statusCode)
}

async function requestKanbanMove(intent: KanbanMoveIntent): Promise<void> {
  const transition = intent.sourceStatus === intent.targetStatus
    ? undefined : transitionFor(intent.item, intent.targetStatus)
  if (transition?.requiresResolution && !intent.resolution) {
    awaitingResolution.value = intent
    moveResolution.value = ''
    moveResolutionOpen.value = true
    return
  }
  await performKanbanMove(intent)
}

function confirmMoveResolution(): void {
  const intent = awaitingResolution.value
  const resolution = moveResolution.value.trim()
  if (!intent || !resolution) {
    error.value = localProblem('该状态迁移必须填写说明。')
    return
  }
  moveResolutionOpen.value = false
  awaitingResolution.value = undefined
  void performKanbanMove({ ...intent, resolution })
}

function cancelMoveResolution(): void {
  moveResolutionOpen.value = false
  awaitingResolution.value = undefined
  moveResolution.value = ''
  focusMoveHandle()
}

function snapshotLanes(): Record<string, { items: WorkItemSummary[], totalElements: number }> {
  return Object.fromEntries(Object.entries(kanbanStates).map(([status, state]) => [status, {
    items: [...state.items], totalElements: state.totalElements,
  }]))
}

function restoreLanes(snapshot: Record<string, { items: WorkItemSummary[], totalElements: number }>): void {
  for (const [status, value] of Object.entries(snapshot)) {
    const state = kanbanState(status)
    state.items = value.items
    state.totalElements = value.totalElements
  }
}

function applyOptimisticMove(intent: KanbanMoveIntent): void {
  const source = kanbanState(intent.sourceStatus)
  const target = kanbanState(intent.targetStatus)
  const sourceIndex = source.items.findIndex(item => item.id === intent.item.id)
  if (sourceIndex >= 0) source.items.splice(sourceIndex, 1)
  const moved = { ...intent.item, statusCode: intent.targetStatus }
  let targetIndex = target.items.length
  if (intent.placement === WorkItemRankPlacement.Start) targetIndex = 0
  if (intent.anchorWorkItemId) {
    const anchorIndex = target.items.findIndex(item => item.id === intent.anchorWorkItemId)
    if (anchorIndex >= 0) targetIndex = anchorIndex
      + (intent.placement === WorkItemRankPlacement.After ? 1 : 0)
  }
  target.items.splice(targetIndex, 0, moved)
  if (intent.sourceStatus !== intent.targetStatus) {
    source.totalElements = Math.max(0, source.totalElements - 1)
    target.totalElements += 1
  }
}

async function refreshMoveFacts(intent: KanbanMoveIntent): Promise<void> {
  await Promise.all([
    loadKanbanLane(intent.sourceStatus, 0),
    ...(intent.targetStatus === intent.sourceStatus ? [] : [loadKanbanLane(intent.targetStatus, 0)]),
    loadTable(tablePage.value.page),
  ])
}

async function performKanbanMove(intent: KanbanMoveIntent): Promise<void> {
  if (movingWorkItemId.value) return
  const csrf = readCsrfToken()
  if (!csrf) {
    error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  const snapshot = snapshotLanes()
  const attempt = ++moveAttempt
  movingWorkItemId.value = intent.item.id
  failedMove.value = undefined
  error.value = undefined
  applyOptimisticMove(intent)
  try {
    await workItemsApi.rankMoveWorkItem({
      workItemId: intent.item.id,
      xXSRFTOKEN: csrf,
      ifMatch: intent.item.etag,
      idempotencyKey: intent.idempotencyKey,
      workItemRankMoveRequest: {
        toStatus: intent.targetStatus,
        placement: intent.placement,
        anchorWorkItemId: intent.anchorWorkItemId ?? null,
        resolution: intent.resolution ?? null,
      },
    })
    if (attempt !== moveAttempt) return
    await refreshMoveFacts(intent)
    ElMessage.success(`已移动 ${intent.item.itemNo}`)
  } catch (reason) {
    if (attempt !== moveAttempt) return
    restoreLanes(snapshot)
    const problem = await toApiProblem(reason)
    error.value = problem
    if (problem.kind === 'fallback') failedMove.value = intent
    if (isProblemStatus(problem, 409) || isProblemStatus(problem, 412)) {
      await refreshMoveFacts(intent)
    }
  } finally {
    if (attempt === moveAttempt) movingWorkItemId.value = undefined
    focusMoveHandle(intent.item.id)
  }
}

async function retryKanbanMove(): Promise<void> {
  if (failedMove.value) await performKanbanMove(failedMove.value)
}

function handleMoveCommand(item: WorkItemSummary, sourceStatus: string, command: string): void {
  if (movingWorkItemId.value || !item.capabilities.canMoveInKanban) return
  const state = kanbanState(sourceStatus)
  const index = state.items.findIndex(candidate => candidate.id === item.id)
  if (command === 'TOP') void requestKanbanMove(moveIntent(item, sourceStatus, sourceStatus,
    WorkItemRankPlacement.Start))
  else if (command === 'BOTTOM') void requestKanbanMove(moveIntent(item, sourceStatus, sourceStatus,
    WorkItemRankPlacement.End))
  else if (command === 'UP' && index > 0) void requestKanbanMove(moveIntent(item, sourceStatus,
    sourceStatus, WorkItemRankPlacement.Before, state.items[index - 1]?.id))
  else if (command === 'DOWN' && index >= 0 && index + 1 < state.items.length) {
    void requestKanbanMove(moveIntent(item, sourceStatus, sourceStatus,
      WorkItemRankPlacement.After, state.items[index + 1]?.id))
  } else if (command.startsWith('STATUS:')) {
    const targetStatus = command.slice('STATUS:'.length)
    if (canDrop(item, targetStatus)) void requestKanbanMove(moveIntent(item, sourceStatus,
      targetStatus, WorkItemRankPlacement.Start))
  }
}

function pointerDown(event: PointerEvent, item: WorkItemSummary, sourceStatus: string): void {
  if (event.button !== 0 || movingWorkItemId.value || !item.capabilities.canMoveInKanban) return
  ;(event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId)
  pointerDrag.value = {
    pointerId: event.pointerId, item, sourceStatus, startX: event.clientX, startY: event.clientY,
    active: false, targetStatus: undefined, placement: undefined, anchorWorkItemId: undefined,
  }
}

function pointerMove(event: PointerEvent): void {
  const drag = pointerDrag.value
  if (!drag || drag.pointerId !== event.pointerId) return
  if (!drag.active && Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY) < 8) return
  drag.active = true
  event.preventDefault()
  const element = document.elementFromPoint(event.clientX, event.clientY) as HTMLElement | null
  const lane = element?.closest<HTMLElement>('[data-kanban-lane]')
  const status = lane?.dataset.kanbanLane
  if (!status || !canDrop(drag.item, status)) {
    drag.targetStatus = undefined
    return
  }
  const card = element?.closest<HTMLElement>('[data-work-item-id]')
  drag.targetStatus = status
  if (card && card.dataset.workItemId !== drag.item.id) {
    const rect = card.getBoundingClientRect()
    drag.placement = event.clientY < rect.top + rect.height / 2
      ? WorkItemRankPlacement.Before : WorkItemRankPlacement.After
    drag.anchorWorkItemId = card.dataset.workItemId
  } else {
    drag.placement = WorkItemRankPlacement.End
    drag.anchorWorkItemId = undefined
  }
  const board = kanbanBoard.value
  if (board) {
    const rect = board.getBoundingClientRect()
    if (event.clientX < rect.left + 48) board.scrollLeft -= 18
    else if (event.clientX > rect.right - 48) board.scrollLeft += 18
  }
}

function pointerEnd(event: PointerEvent): void {
  const drag = pointerDrag.value
  if (!drag || drag.pointerId !== event.pointerId) return
  pointerDrag.value = undefined
  if (drag.active && drag.targetStatus && drag.placement) {
    void requestKanbanMove(moveIntent(drag.item, drag.sourceStatus, drag.targetStatus,
      drag.placement, drag.anchorWorkItemId))
  }
}

function cancelPointerDrag(): void {
  pointerDrag.value = undefined
}

function escapeDrag(event: KeyboardEvent): void {
  if (event.key === 'Escape') cancelPointerDrag()
}

function focusMoveHandle(itemId = awaitingResolution.value?.item.id): void {
  if (!itemId) return
  void nextTick(() => document.querySelector<HTMLElement>(`[data-drag-handle="${itemId}"]`)?.focus())
}

function queryRequest(includeSort: boolean) {
  const value = tableQuery.value
  const request: {
    q: string
    status: Set<string>
    priority: Set<WorkItemPriority>
    assigneeUserId: Set<string>
    dueFrom?: Date
    dueTo?: Date
    updatedAfter?: Date
    sort?: string[]
  } = {
    q: value.filters.query ?? '',
    status: new Set(value.filters.statusCodes),
    priority: new Set(value.filters.priorities),
    assigneeUserId: new Set(value.filters.assigneeUserIds),
  }
  if (value.filters.dueFrom) request.dueFrom = value.filters.dueFrom
  if (value.filters.dueTo) request.dueTo = value.filters.dueTo
  if (value.filters.updatedAfter) request.updatedAfter = value.filters.updatedAfter
  if (includeSort) request.sort = value.sort.map(item => `${item.field},${item.direction}`)
  return request
}

async function applyTableQuery(value: ContentTableQuery, replace: boolean): Promise<void> {
  tableQuery.value = cloneTableQuery(value)
  queryRevision += 1
  sharedQueryConflict.value = undefined
  syncingRoute = true
  try {
    const location = { name: route.name, params: route.params,
      query: encodeTableQuery(value, route.query) }
    if (replace) await router.replace(location)
    else await router.push(location)
  } finally {
    syncingRoute = false
  }
  await refreshCurrentView(true)
}

async function resetSharedQuery(): Promise<void> {
  if (!content.value) return
  tableQuery.value = sharedTableQuery(content.value.viewConfig.table.filters,
    content.value.viewConfig.table.sort)
  queryRevision += 1
  sharedQueryConflict.value = undefined
  syncingRoute = true
  try {
    await router.replace({ name: route.name, params: route.params,
      query: withoutTableQuery(route.query) })
  } finally {
    syncingRoute = false
  }
  await refreshCurrentView(true)
}

async function saveSharedQuery(base = content.value): Promise<void> {
  if (!base || !canSaveSharedQuery.value) return
  const csrf = readCsrfToken()
  if (!csrf) { error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  savingSharedQuery.value = true
  error.value = undefined
  try {
    const updated = await contentsApi.updateContent({
      contentId: base.id, xXSRFTOKEN: csrf, ifMatch: base.etag,
      contentUpdateRequest: {
        name: base.name, description: base.description, defaultViewType: base.defaultViewType,
        viewConfig: {
          table: {
            ...base.viewConfig.table,
            filters: cloneTableQuery(tableQuery.value).filters,
            sort: cloneTableQuery(tableQuery.value).sort,
          },
          kanban: base.viewConfig.kanban,
        },
      },
    })
    content.value = updated
    sharedQueryConflict.value = undefined
    ElMessage.success('当前查询已保存为 Content 共享默认')
    await resetSharedQuery()
  } catch (reason) {
    const problem = await toApiProblem(reason)
    error.value = problem
    if (isProblemStatus(problem, 412)) {
      try { sharedQueryConflict.value = await contentsApi.getContent({ contentId }) }
      catch (latestReason) { error.value = await toApiProblem(latestReason) }
    }
  } finally {
    savingSharedQuery.value = false
  }
}

async function retrySharedQuery(): Promise<void> {
  const latest = sharedQueryConflict.value
  if (!latest) return
  sharedQueryConflict.value = undefined
  await saveSharedQuery(latest)
}

async function changeView(view: ContentViewType): Promise<void> {
  selectedView.value = view
  if (view === ContentViewType.Kanban) await loadKanban()
  else if (!tablePage.value.items.length && tablePage.value.totalElements === 0) await loadTable(0)
}

function openCreate(): void {
  createForm.title = ''
  createForm.priority = WorkItemPriority.Medium
  createForm.assigneeUserId = ''
  createForm.description = ''
  createForm.notes = ''
  createForm.timelineStartDate = ''
  createForm.timelineEndDate = ''
  createForm.dueDate = ''
  createOpen.value = true
}

async function createWorkItem(): Promise<void> {
  if (!createForm.title.trim()) {
    error.value = localProblem('请输入工作项标题。')
    return
  }
  if (!validateDateRange(createForm)) return
  const csrf = readCsrfToken()
  if (!csrf) {
    error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  creating.value = true
  error.value = undefined
  try {
    const created = await workItemsApi.createWorkItem({
      contentId,
      xXSRFTOKEN: csrf,
      idempotencyKey: globalThis.crypto.randomUUID(),
      workItemCreateRequest: {
        ...requestFields(createForm),
      },
    })
    createOpen.value = false
    await refreshCurrentView(true)
    ElMessage.success(`已创建 ${created.itemNo}`)
  } catch (reason) {
    error.value = await toApiProblem(reason)
  } finally {
    creating.value = false
  }
}

async function openDetail(item: WorkItemSummary): Promise<void> {
  detailOpen.value = true
  detailLoading.value = true
  detail.value = undefined
  latestConflict.value = undefined
  resetTransition()
  try {
    detail.value = await workItemsApi.getWorkItem({ workItemId: item.id })
    assignDraft(detailDraft, detail.value)
  } catch (reason) {
    error.value = await toApiProblem(reason)
    detailOpen.value = false
  } finally {
    detailLoading.value = false
  }
}

async function refreshCurrentView(firstPage = false): Promise<void> {
  kanbanInitialized.value = false
  Object.keys(kanbanStates).forEach(key => delete kanbanStates[key])
  if (selectedView.value === ContentViewType.Kanban) await loadKanban()
  else await loadTable(firstPage ? 0 : tablePage.value.page)
}

async function saveWorkItem(etag = detail.value?.etag): Promise<void> {
  if (!detail.value || !etag || !canEditDetail.value) return
  if (!detailDraft.title.trim()) {
    error.value = localProblem('请输入工作项标题。')
    return
  }
  if (!validateDateRange(detailDraft)) return
  const csrf = readCsrfToken()
  if (!csrf) {
    error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  detailSaving.value = true
  error.value = undefined
  try {
    const updated = await workItemsApi.updateWorkItem({
      workItemId: detail.value.id,
      xXSRFTOKEN: csrf,
      ifMatch: etag,
      workItemUpdateRequest: requestFields(detailDraft),
    })
    detail.value = updated
    latestConflict.value = undefined
    assignDraft(detailDraft, updated)
    await refreshCurrentView()
    ElMessage.success(`已保存 ${updated.itemNo}`)
  } catch (reason) {
    const problem = await toApiProblem(reason)
    error.value = problem
    if (isProblemStatus(problem, 412)) {
      try {
        latestConflict.value = await workItemsApi.getWorkItem({ workItemId: detail.value.id })
      } catch (latestReason) {
        error.value = await toApiProblem(latestReason)
      }
    }
  } finally {
    detailSaving.value = false
  }
}

function loadLatestDetail(): void {
  if (!latestConflict.value) return
  detail.value = latestConflict.value
  latestConflict.value = undefined
  assignDraft(detailDraft, detail.value)
  resetTransition()
}

function openDelete(): void {
  if (!detail.value?.capabilities.canDelete || latestConflict.value) return
  deleteReason.value = ''
  deleteIdempotencyKey.value = globalThis.crypto.randomUUID()
  deleteOpen.value = true
}

function resetDelete(): void {
  if (deleting.value) return
  deleteOpen.value = false
  deleteReason.value = ''
  deleteIdempotencyKey.value = undefined
}

async function refreshLifecycleFacts(workItemId: string): Promise<void> {
  await refreshCurrentView()
  try {
    latestConflict.value = await workItemsApi.getWorkItem({ workItemId })
  } catch {
    detailOpen.value = false
  }
}

async function deleteWorkItem(): Promise<void> {
  if (!detail.value || !deleteIdempotencyKey.value || !canDeleteDetail.value) return
  const reason = deleteReason.value.trim()
  if (!reason) {
    error.value = localProblem('请输入删除理由。')
    return
  }
  const csrf = readCsrfToken()
  if (!csrf) {
    error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  deleting.value = true
  error.value = undefined
  const deletingId = detail.value.id
  try {
    const tombstone = await workItemsApi.deleteWorkItem({
      workItemId: deletingId,
      xXSRFTOKEN: csrf,
      ifMatch: detail.value.etag,
      idempotencyKey: deleteIdempotencyKey.value,
      workItemDeleteRequest: { reason },
    })
    undoEntries.value.unshift({
      tombstone,
      restoreIdempotencyKey: globalThis.crypto.randomUUID(),
      loading: false,
    })
    deleteOpen.value = false
    detailOpen.value = false
    detail.value = undefined
    deleteReason.value = ''
    deleteIdempotencyKey.value = undefined
    await refreshCurrentView()
    ElMessage.success(`已删除 ${tombstone.itemNo}，可在当前页面撤销。`)
  } catch (reasonValue) {
    const problem = await toApiProblem(reasonValue)
    error.value = problem
    if (isProblemStatus(problem, 409) || isProblemStatus(problem, 412)) {
      await refreshLifecycleFacts(deletingId)
      deleteOpen.value = false
    }
  } finally {
    deleting.value = false
  }
}

async function restoreWorkItem(entry: WorkItemUndoEntry): Promise<void> {
  const csrf = readCsrfToken()
  if (!csrf) {
    entry.problem = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  entry.loading = true
  delete entry.problem
  try {
    const restored = await workItemsApi.restoreWorkItem({
      workItemId: entry.tombstone.id,
      xXSRFTOKEN: csrf,
      ifMatch: entry.tombstone.etag,
      idempotencyKey: entry.restoreIdempotencyKey,
    })
    undoEntries.value = undoEntries.value.filter(candidate => candidate !== entry)
    await refreshCurrentView()
    detail.value = restored
    latestConflict.value = undefined
    assignDraft(detailDraft, restored)
    detailOpen.value = true
    ElMessage.success(`已恢复 ${restored.itemNo}`)
  } catch (reasonValue) {
    const problem = await toApiProblem(reasonValue)
    entry.problem = problem
    error.value = problem
    if (isProblemStatus(problem, 409) || isProblemStatus(problem, 412)) {
      await refreshCurrentView()
    }
  } finally {
    entry.loading = false
  }
}

function dismissUndo(entry: WorkItemUndoEntry): void {
  undoEntries.value = undoEntries.value.filter(candidate => candidate !== entry)
}

function openTransition(): void {
  const first = availableTransitions.value[0]
  if (!first || latestConflict.value) return
  transitionToStatus.value = first.toStatus
  transitionResolution.value = ''
  transitionIdempotencyKey.value = globalThis.crypto.randomUUID()
  transitionOpen.value = true
}

function changeTransitionTarget(): void {
  transitionResolution.value = ''
  transitionIdempotencyKey.value = globalThis.crypto.randomUUID()
}

function resetTransition(): void {
  transitionOpen.value = false
  transitionSaving.value = false
  transitionToStatus.value = ''
  transitionResolution.value = ''
  transitionIdempotencyKey.value = undefined
}

async function transitionWorkItem(): Promise<void> {
  if (!detail.value || !selectedTransition.value || !transitionIdempotencyKey.value) return
  const resolution = transitionResolution.value.trim()
  if (selectedTransition.value.requiresResolution && !resolution) {
    error.value = localProblem('该状态迁移必须填写说明。')
    return
  }
  const csrf = readCsrfToken()
  if (!csrf) {
    error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  transitionSaving.value = true
  error.value = undefined
  try {
    const updated = await workItemsApi.transitionWorkItem({
      workItemId: detail.value.id,
      xXSRFTOKEN: csrf,
      ifMatch: detail.value.etag,
      idempotencyKey: transitionIdempotencyKey.value,
      workItemTransitionRequest: {
        toStatus: selectedTransition.value.toStatus,
        resolution: resolution || null,
      },
    })
    detail.value = updated
    latestConflict.value = undefined
    resetTransition()
    await refreshCurrentView()
    ElMessage.success(`已将 ${updated.itemNo} 迁移到${statusLabel(updated.statusCode)}`)
  } catch (reason) {
    const problem = await toApiProblem(reason)
    error.value = problem
    if (isProblemStatus(problem, 412)) {
      try {
        latestConflict.value = await workItemsApi.getWorkItem({ workItemId: detail.value.id })
        resetTransition()
      } catch (latestReason) {
        error.value = await toApiProblem(latestReason)
      }
    } else if (isProblemStatus(problem, 409) || isProblemStatus(problem, 403)) {
      try {
        detail.value = await workItemsApi.getWorkItem({ workItemId: detail.value.id })
        resetTransition()
        await refreshCurrentView()
      } catch (latestReason) {
        error.value = await toApiProblem(latestReason)
      }
    }
  } finally {
    transitionSaving.value = false
  }
}

watch(() => route.query, async () => {
  if (syncingRoute || !content.value) return
  tableQuery.value = hasCustomTableQuery(route.query)
    ? parseTableQuery(route.query)
    : sharedTableQuery(content.value.viewConfig.table.filters, content.value.viewConfig.table.sort)
  queryRevision += 1
  sharedQueryConflict.value = undefined
  await refreshCurrentView(true)
}, { deep: true })

onMounted(() => {
  window.addEventListener('keydown', escapeDrag)
  void loadWorkspace()
})
onBeforeUnmount(() => {
  moveAttempt += 1
  window.removeEventListener('keydown', escapeDrag)
})
</script>

<template>
  <section
    v-loading="loading"
    class="work-items-page"
  >
    <project-workspace-header
      section="contents"
      :project="project"
      :title="content?.name ?? 'Content 工作区'"
      :description="content?.description ?? '查看和创建工作项。'"
    >
      <template #primary-action>
        <el-button @click="router.push({ name: 'project-contents', params: { projectId } })">
          <arrow-left /> Content 目录
        </el-button>
        <el-button
          v-if="canCreate"
          type="primary"
          @click="openCreate"
        >
          <plus /> 创建工作项
        </el-button>
      </template>
    </project-workspace-header>

    <inline-problem
      v-if="error"
      :problem="error"
    />

    <div
      v-if="undoEntries.length"
      class="undo-queue"
      aria-live="polite"
    >
      <div
        v-for="entry in undoEntries"
        :key="entry.tombstone.id"
        class="undo-entry"
      >
        <div>
          <strong>{{ entry.tombstone.itemNo }} 已删除</strong>
          <span>{{ entry.tombstone.title }} · {{ entry.tombstone.deleteReason }}</span>
          <span v-if="entry.problem" class="undo-entry__error">撤销未完成，可使用同一请求安全重试。</span>
        </div>
        <el-button
          type="primary"
          plain
          :loading="entry.loading"
          @click="restoreWorkItem(entry)"
        >
          撤销删除
        </el-button>
        <el-button :disabled="entry.loading" @click="dismissUndo(entry)">
          忽略
        </el-button>
      </div>
    </div>

    <template v-if="content">
      <div class="workspace-toolbar">
        <div>
          <div class="content-heading">
            <h2>{{ content.name }}</h2>
            <span>{{ content.code }}</span>
            <span>{{ typeLabel(content.workItemType) }}</span>
          </div>
          <p
            v-if="readOnlyReason"
            class="read-only-reason"
          >
            {{ readOnlyReason }}
          </p>
          <p
            v-else
            class="workspace-hint"
          >
            可协作维护标题、优先级、处理人、描述、备注与自然日计划。
          </p>
        </div>
        <div
          class="view-switch"
          role="group"
          aria-label="工作项视图"
        >
          <el-button
            :type="selectedView === ContentViewType.Table ? 'primary' : 'default'"
            @click="changeView(ContentViewType.Table)"
          >
            表格
          </el-button>
          <el-button
            :type="selectedView === ContentViewType.Kanban ? 'primary' : 'default'"
            @click="changeView(ContentViewType.Kanban)"
          >
            看板
          </el-button>
        </div>
      </div>

      <div v-if="sharedQueryConflict" class="query-conflict" role="alert">
        <strong>共享默认已被其他人更新，当前临时查询仍保留。</strong>
        <span>重新提交时只合并当前筛选与排序，不覆盖最新版列配置和 Kanban 分组。</span>
        <el-button type="warning" :loading="savingSharedQuery" @click="retrySharedQuery">合并到最新版并重提</el-button>
      </div>
      <div class="query-toolbar">
        <content-table-query-editor :model-value="tableQuery"
          :statuses="catalog?.workflowStatusOptions ?? []" :members="members"
          @update:model-value="tableQuery = $event" @search="applyTableQuery($event, true)"
          @change="applyTableQuery($event, false)" />
        <div class="query-toolbar__actions">
          <span>{{ hasCustomTableQuery(route.query) ? '临时查询（已同步 URL）' : 'Content 共享默认' }}</span>
          <el-button @click="resetSharedQuery">重置共享默认</el-button>
          <el-button v-if="canSaveSharedQuery" type="primary" :loading="savingSharedQuery"
            @click="saveSharedQuery()">保存为共享默认</el-button>
        </div>
      </div>

      <div
        v-if="selectedView === ContentViewType.Table"
        class="table-surface"
      >
        <el-table
          v-loading="tableLoading"
          :data="tablePage.items"
          row-key="id"
          empty-text="暂无工作项"
          @row-click="openDetail"
        >
          <el-table-column
            v-for="column in tableColumns"
            :key="column.code"
            :label="column.label"
            :min-width="column.minWidth"
          >
            <template #default="scope">
              <button
                v-if="column.code === ContentTableColumn.Title"
                class="item-title"
                type="button"
                @click.stop="openDetail(workItemRow(scope.row))"
              >
                {{ scope.row.title }}
              </button>
              <span
                v-else-if="column.code === ContentTableColumn.ItemNo"
                class="item-no"
              >{{ scope.row.itemNo }}</span>
              <span
                v-else-if="column.code === ContentTableColumn.Status"
                class="status-pill"
                :class="`status-pill--${statusTone(scope.row.statusCode)}`"
              >
                {{ statusLabel(scope.row.statusCode) }}
              </span>
              <yp-priority-badge
                v-else-if="column.code === ContentTableColumn.Priority"
                :priority="scope.row.priority"
              />
              <yp-assignee
                v-else-if="column.code === ContentTableColumn.Assignee"
                :user-id="scope.row.assigneeUserId"
                :display-name="scope.row.assigneeDisplayName"
                size="table"
              />
              <yp-assignee
                v-else-if="column.code === ContentTableColumn.Reporter"
                :user-id="scope.row.reporterUserId"
                :display-name="scope.row.reporterDisplayName"
                size="table"
              />
              <span
                v-else-if="column.code === ContentTableColumn.Description"
                class="plain-cell"
                :title="scope.row.description ?? ''"
              >{{ scope.row.description || '—' }}</span>
              <span
                v-else-if="column.code === ContentTableColumn.Notes"
                class="plain-cell"
                :title="scope.row.notes ?? ''"
              >{{ scope.row.notes || '—' }}</span>
              <span v-else-if="column.code === ContentTableColumn.Timeline">{{ timeline(scope.row.timelineStartDate, scope.row.timelineEndDate) }}</span>
              <span v-else-if="column.code === ContentTableColumn.DueDate">{{ naturalDate(scope.row.dueDate) }}</span>
              <span v-else-if="column.code === ContentTableColumn.UpdatedAt">{{ formatDate(scope.row.updatedAt) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <yp-empty-state
          v-if="!tableLoading && tablePage.items.length === 0"
          compact
          title="还没有工作项"
          :description="canCreate ? '创建第一条工作项，开始推进这个 Content。' : '当前 Content 中暂无可显示的工作项。'"
        >
          <template
            v-if="canCreate"
            #action
          >
            <el-button
              type="primary"
              @click="openCreate"
            >
              创建工作项
            </el-button>
          </template>
        </yp-empty-state>
        <el-pagination
          v-if="tablePage.totalElements > 0"
          class="table-pagination"
          background
          layout="total, prev, pager, next"
          :current-page="tablePage.page + 1"
          :page-size="tablePage.size"
          :total="tablePage.totalElements"
          @current-change="page => loadTable(page - 1)"
        />
      </div>

      <div
        v-else
        ref="kanbanBoard"
        class="kanban-board"
        aria-label="工作项看板"
      >
        <div
          v-if="failedMove"
          class="move-retry"
          role="alert"
        >
          <span>移动未送达，卡片已恢复原位。重试会复用同一个幂等键。</span>
          <el-button
            :disabled="Boolean(movingWorkItemId)"
            @click="retryKanbanMove"
          >重试移动</el-button>
        </div>
        <section
          v-for="(group, index) in kanbanGroups"
          :key="groupKey(group, index)"
          class="kanban-group"
          :aria-label="group.name"
        >
          <header>
            <h3>{{ group.name }}</h3>
            <span>{{ groupTotal(group) }}</span>
          </header>
          <div class="kanban-group__lanes">
            <section
              v-for="status in group.statusCodes"
              :key="status"
              class="kanban-lane"
              :class="{
                'kanban-lane--filtered': !statusVisible(status),
                'kanban-lane--drop': pointerDrag?.targetStatus === status,
              }"
              :data-kanban-lane="status"
              :aria-label="statusLabel(status)"
            >
              <header class="kanban-lane__header">
                <span
                  class="status-pill"
                  :class="`status-pill--${statusTone(status)}`"
                >{{ statusLabel(status) }}</span>
                <span>{{ kanbanState(status).totalElements }}</span>
              </header>
              <inline-problem
                v-if="kanbanState(status).error"
                :problem="kanbanState(status).error!"
              />
              <div
                v-loading="kanbanState(status).loading"
                class="kanban-cards"
              >
                <article
                  v-for="item in kanbanState(status).items"
                  :key="item.id"
                  class="work-item-card"
                  :class="{
                    'work-item-card--pending': movingWorkItemId === item.id,
                    'work-item-card--dragging': pointerDrag?.active && pointerDrag.item.id === item.id,
                  }"
                  :data-work-item-id="item.id"
                >
                  <div class="work-item-card__actions">
                    <button
                      class="drag-handle"
                      type="button"
                      :data-drag-handle="item.id"
                      :disabled="!item.capabilities.canMoveInKanban || Boolean(movingWorkItemId)"
                      :aria-label="`拖动 ${item.itemNo}`"
                      @pointerdown="pointerDown($event, item, status)"
                      @pointermove="pointerMove"
                      @pointerup="pointerEnd"
                      @pointercancel="cancelPointerDrag"
                    >
                      <el-icon><Rank /></el-icon>
                    </button>
                    <el-dropdown
                      trigger="click"
                      :disabled="!item.capabilities.canMoveInKanban || Boolean(movingWorkItemId)"
                      @command="command => handleMoveCommand(item, status, String(command))"
                    >
                      <button
                        class="move-menu"
                        type="button"
                        :aria-label="`${item.itemNo} 移动菜单`"
                      ><el-icon><MoreFilled /></el-icon></button>
                      <template #dropdown>
                        <el-dropdown-menu>
                          <el-dropdown-item command="UP" :disabled="kanbanState(status).items[0]?.id === item.id">上移</el-dropdown-item>
                          <el-dropdown-item command="DOWN" :disabled="kanbanState(status).items.at(-1)?.id === item.id">下移</el-dropdown-item>
                          <el-dropdown-item command="TOP">移到顶部</el-dropdown-item>
                          <el-dropdown-item command="BOTTOM">移到底部</el-dropdown-item>
                          <el-dropdown-item
                            v-for="option in item.capabilities.availableTransitions.filter(candidate => statusVisible(candidate.toStatus))"
                            :key="option.toStatus"
                            :command="`STATUS:${option.toStatus}`"
                            divided
                          >移动到{{ option.displayName }}</el-dropdown-item>
                        </el-dropdown-menu>
                      </template>
                    </el-dropdown>
                  </div>
                  <button
                    class="work-item-card__body"
                    type="button"
                    @click="openDetail(item)"
                  >
                    <span class="work-item-card__meta"><strong>{{ item.itemNo }}</strong>{{ typeLabel(item.type) }}</span>
                    <span class="work-item-card__title">{{ item.title }}</span>
                    <span class="work-item-card__footer">
                      <yp-priority-badge :priority="item.priority" />
                      <yp-assignee
                        :user-id="item.assigneeUserId"
                        :display-name="item.assigneeDisplayName"
                        size="table"
                        :show-name="false"
                      />
                    </span>
                  </button>
                </article>
                <yp-empty-state
                  v-if="!kanbanState(status).loading && !kanbanState(status).items.length"
                  compact
                  :title="statusVisible(status) ? '此泳道为空' : '泳道已被筛选排除'"
                  :description="statusVisible(status) ? '当前没有处于此状态的工作项。' : '清除状态筛选后可查看和投放。'"
                />
              </div>
              <el-button
                v-if="kanbanState(status).page + 1 < kanbanState(status).totalPages"
                class="load-more"
                :loading="kanbanState(status).loading"
                @click="loadKanbanLane(status, kanbanState(status).page + 1)"
              >加载更多</el-button>
            </section>
          </div>
        </section>
      </div>
    </template>

    <el-dialog
      v-model="createOpen"
      title="创建工作项"
      width="min(560px, calc(100vw - 32px))"
    >
      <el-form
        label-position="top"
        @submit.prevent="createWorkItem"
      >
        <el-form-item
          label="标题"
          required
        >
          <el-input
            v-model="createForm.title"
            maxlength="300"
            show-word-limit
          />
        </el-form-item>
        <el-form-item
          label="优先级"
          required
        >
          <el-select v-model="createForm.priority">
            <el-option
              label="低"
              :value="WorkItemPriority.Low"
            />
            <el-option
              label="中"
              :value="WorkItemPriority.Medium"
            />
            <el-option
              label="高"
              :value="WorkItemPriority.High"
            />
            <el-option
              label="紧急"
              :value="WorkItemPriority.Urgent"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="处理人">
          <el-select
            v-model="createForm.assigneeUserId"
            clearable
            filterable
            placeholder="未指派"
          >
            <el-option
              v-for="memberOption in activeMembers"
              :key="memberOption.userId"
              :label="memberOption.displayName"
              :value="memberOption.userId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="createForm.description"
            type="textarea"
            :rows="4"
            maxlength="16384"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="createForm.notes"
            type="textarea"
            :rows="3"
            maxlength="16384"
          />
        </el-form-item>
        <div class="date-fields">
          <el-form-item label="计划开始日">
            <el-date-picker
              v-model="createForm.timelineStartDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择自然日"
              clearable
            />
          </el-form-item>
          <el-form-item label="计划结束日">
            <el-date-picker
              v-model="createForm.timelineEndDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择自然日"
              clearable
            />
          </el-form-item>
          <el-form-item label="截止日">
            <el-date-picker
              v-model="createForm.dueDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择自然日"
              clearable
            />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="creating"
          @click="createWorkItem"
        >
          创建
        </el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="detailOpen"
      title="工作项详情"
      size="min(560px, 100vw)"
    >
      <div
        v-loading="detailLoading"
        class="detail-panel"
      >
        <template v-if="detail">
          <div
            v-if="latestConflict"
            class="work-item-conflict"
            role="alert"
          >
            <strong>服务器版本已更新，本地草稿仍保留。</strong>
            <span>请载入最新版本，或明确基于最新 ETag 再次提交当前草稿。</span>
            <div>
              <el-button @click="loadLatestDetail">
                载入最新版本
              </el-button>
              <el-button
                type="warning"
                @click="saveWorkItem(latestConflict.etag)"
              >
                基于最新版本重新提交
              </el-button>
            </div>
          </div>
          <div class="detail-panel__eyebrow">
            {{ detail.itemNo }} · {{ typeLabel(detail.type) }}
          </div>
          <div class="detail-panel__badges">
            <span
              class="status-pill"
              :class="`status-pill--${statusTone(detail.statusCode)}`"
            >{{ statusLabel(detail.statusCode) }}</span>
            <span class="version-badge">版本 {{ detail.rowVersion }}</span>
          </div>
          <dl>
            <div>
              <dt>处理人</dt><dd>
                <yp-assignee
                  :user-id="detail.assigneeUserId"
                  :display-name="detail.assigneeDisplayName"
                />
              </dd>
            </div>
            <div>
              <dt>报告人</dt><dd>
                <yp-assignee
                  :user-id="detail.reporterUserId"
                  :display-name="detail.reporterDisplayName"
                />
              </dd>
            </div>
            <div><dt>创建时间</dt><dd>{{ formatDate(detail.createdAt) }}</dd></div>
            <div><dt>更新时间</dt><dd>{{ formatDate(detail.updatedAt) }}</dd></div>
          </dl>
          <el-form
            class="work-item-editor"
            label-position="top"
            @submit.prevent="saveWorkItem()"
          >
            <el-form-item
              label="标题"
              required
            >
              <el-input
                v-model="detailDraft.title"
                maxlength="300"
                show-word-limit
                :disabled="!canEditDetail"
              />
            </el-form-item>
            <div class="editor-grid">
              <el-form-item
                label="优先级"
                required
              >
                <el-select
                  v-model="detailDraft.priority"
                  :disabled="!canEditDetail"
                >
                  <el-option
                    label="低"
                    :value="WorkItemPriority.Low"
                  />
                  <el-option
                    label="中"
                    :value="WorkItemPriority.Medium"
                  />
                  <el-option
                    label="高"
                    :value="WorkItemPriority.High"
                  />
                  <el-option
                    label="紧急"
                    :value="WorkItemPriority.Urgent"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="处理人">
                <el-select
                  v-model="detailDraft.assigneeUserId"
                  clearable
                  filterable
                  placeholder="未指派"
                  :disabled="!canEditDetail"
                >
                  <el-option
                    v-for="memberOption in members"
                    :key="memberOption.userId"
                    :label="memberOption.displayName"
                    :value="memberOption.userId"
                  />
                </el-select>
              </el-form-item>
            </div>
            <el-form-item label="描述">
              <el-input
                v-model="detailDraft.description"
                type="textarea"
                :rows="5"
                maxlength="16384"
                :disabled="!canEditDetail"
              />
            </el-form-item>
            <el-form-item label="备注">
              <el-input
                v-model="detailDraft.notes"
                type="textarea"
                :rows="4"
                maxlength="16384"
                :disabled="!canEditDetail"
              />
            </el-form-item>
            <div class="date-fields">
              <el-form-item label="计划开始日">
                <el-date-picker
                  v-model="detailDraft.timelineStartDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  placeholder="选择自然日"
                  clearable
                  :disabled="!canEditDetail"
                />
              </el-form-item>
              <el-form-item label="计划结束日">
                <el-date-picker
                  v-model="detailDraft.timelineEndDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  placeholder="选择自然日"
                  clearable
                  :disabled="!canEditDetail"
                />
              </el-form-item>
              <el-form-item label="截止日">
                <el-date-picker
                  v-model="detailDraft.dueDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  placeholder="选择自然日"
                  clearable
                  :disabled="!canEditDetail"
                />
              </el-form-item>
            </div>
          </el-form>
        </template>
      </div>
      <template #footer>
        <div class="drawer-footer">
          <span v-if="detail && !canEditDetail">当前角色或资源状态仅允许查看。</span>
          <span v-else />
          <el-button @click="detailOpen = false">
            关闭
          </el-button>
          <el-button
            v-if="availableTransitions.length"
            type="success"
            :disabled="Boolean(latestConflict)"
            @click="openTransition"
          >
            变更状态
          </el-button>
          <el-button
            v-if="canDeleteDetail"
            type="danger"
            plain
            :disabled="Boolean(latestConflict)"
            @click="openDelete"
          >
            删除
          </el-button>
          <el-button
            v-if="canEditDetail"
            type="primary"
            :loading="detailSaving"
            :disabled="Boolean(latestConflict)"
            @click="saveWorkItem()"
          >
            保存
          </el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog
      v-model="deleteOpen"
      title="删除工作项"
      width="min(480px, 92vw)"
      :close-on-click-modal="!deleting"
      :close-on-press-escape="!deleting"
      @closed="resetDelete"
    >
      <template v-if="detail">
        <p>将软删除 <strong>{{ detail.itemNo }} · {{ detail.title }}</strong>。普通列表、Kanban 与详情将不再显示它。</p>
        <p v-if="detailDraftDirty" class="delete-draft-warning" role="alert">
          当前有未保存的字段草稿；删除成功后这些草稿不会保存。
        </p>
        <el-form label-position="top" @submit.prevent="deleteWorkItem">
          <el-form-item label="删除理由" required>
            <el-input
              v-model="deleteReason"
              type="textarea"
              :rows="4"
              maxlength="500"
              show-word-limit
              :disabled="deleting"
              placeholder="说明为什么删除此工作项"
            />
          </el-form-item>
        </el-form>
        <p class="transition-note">删除成功后，可在当前页面的撤销提示中恢复；刷新或离开页面后提示不会保留。</p>
      </template>
      <template #footer>
        <el-button :disabled="deleting" @click="resetDelete">取消</el-button>
        <el-button type="danger" :loading="deleting" @click="deleteWorkItem">
          确认删除
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="transitionOpen"
      title="变更工作项状态"
      width="min(480px, 92vw)"
      :close-on-click-modal="!transitionSaving"
      :close-on-press-escape="!transitionSaving"
    >
      <el-form
        v-if="detail"
        label-position="top"
        @submit.prevent="transitionWorkItem"
      >
        <div class="transition-route">
          <span class="status-pill" :class="`status-pill--${statusTone(detail.statusCode)}`">
            {{ statusLabel(detail.statusCode) }}
          </span>
          <span aria-hidden="true">→</span>
          <strong>{{ selectedTransition?.displayName ?? '请选择目标状态' }}</strong>
        </div>
        <el-form-item label="目标状态" required>
          <el-select
            v-model="transitionToStatus"
            :disabled="transitionSaving"
            @change="changeTransitionTarget"
          >
            <el-option
              v-for="option in availableTransitions"
              :key="option.toStatus"
              :label="option.displayName"
              :value="option.toStatus"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          :label="selectedTransition?.requiresResolution ? '迁移说明（必填）' : '迁移说明（可选）'"
          :required="selectedTransition?.requiresResolution === true"
        >
          <el-input
            v-model="transitionResolution"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            :disabled="transitionSaving"
          />
        </el-form-item>
        <p class="transition-note">状态迁移独立保存，不会自动提交当前字段草稿。</p>
      </el-form>
      <template #footer>
        <el-button :disabled="transitionSaving" @click="resetTransition">取消</el-button>
        <el-button type="primary" :loading="transitionSaving" @click="transitionWorkItem">
          确认迁移
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="moveResolutionOpen"
      title="填写移动说明"
      width="min(480px, 92vw)"
      @closed="cancelMoveResolution"
    >
      <p class="transition-note">目标状态要求提供说明；取消后卡片保持原位。</p>
      <el-input
        v-model="moveResolution"
        type="textarea"
        :rows="4"
        maxlength="500"
        show-word-limit
        autofocus
      />
      <template #footer>
        <el-button @click="cancelMoveResolution">取消</el-button>
        <el-button type="primary" @click="confirmMoveResolution">确认移动</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.work-items-page { display: grid; gap: var(--yp-space-5); }
.undo-queue { display: grid; gap: var(--yp-space-2); }
.undo-entry { display: flex; align-items: center; gap: var(--yp-space-2); padding: var(--yp-space-3) var(--yp-space-4); border: 1px solid var(--yp-border-subtle); border-left: 3px solid var(--yp-status-blue); border-radius: var(--yp-radius-md); background: var(--yp-bg-surface); }
.undo-entry > div { display: grid; flex: 1; gap: 2px; color: var(--yp-text-secondary); }
.undo-entry strong { color: var(--yp-text-primary); }
.undo-entry__error { color: var(--yp-status-red); }
.delete-draft-warning { padding: var(--yp-space-3); border: 1px solid var(--yp-status-yellow); border-radius: var(--yp-radius-md); background: var(--yp-bg-selected); color: var(--yp-text-primary); }
.workspace-toolbar { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--yp-space-4); padding: var(--yp-space-4) var(--yp-space-5); border: 1px solid var(--yp-border-subtle); background: var(--yp-bg-surface); }
.content-heading { display: flex; flex-wrap: wrap; align-items: center; gap: var(--yp-space-2); }
.content-heading h2 { margin: 0; font-size: var(--yp-type-section-title-size); }
.content-heading span { padding: 2px var(--yp-space-2); border-radius: var(--yp-radius-sm); color: var(--yp-text-muted); background: var(--yp-bg-sunken); font-size: var(--yp-type-caption-size); }
.read-only-reason, .workspace-hint { margin: var(--yp-space-1) 0 0; color: var(--yp-text-secondary); }
.view-switch { display: flex; }
.view-switch :deep(.el-button + .el-button) { margin-left: 0; }
.query-toolbar { display: grid; gap: var(--yp-space-2); }
.query-toolbar__actions { display: flex; align-items: center; justify-content: flex-end; gap: var(--yp-space-2); color: var(--yp-text-secondary); }
.query-conflict { display: flex; align-items: center; gap: var(--yp-space-3); padding: var(--yp-space-3); border: 1px solid var(--yp-status-yellow); border-radius: var(--yp-radius-md); background: var(--yp-bg-selected); }
.query-conflict span { flex: 1; color: var(--yp-text-secondary); }
.table-surface { overflow: hidden; border: 1px solid var(--yp-border-subtle); background: var(--yp-bg-surface); }
.table-surface :deep(.el-table__row) { cursor: pointer; }
.item-title { padding: 0; border: 0; color: var(--yp-link); background: transparent; font: inherit; font-weight: 600; cursor: pointer; text-align: left; }
.item-title:hover, .item-title:focus-visible { text-decoration: underline; }
.item-no { color: var(--yp-text-secondary); font-variant-numeric: tabular-nums; }
.plain-cell { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.table-pagination { justify-content: flex-end; padding: var(--yp-space-4); border-top: 1px solid var(--yp-border-subtle); }
.status-pill { display: inline-flex; align-items: center; min-height: 24px; padding: 2px var(--yp-space-2); border-radius: var(--yp-radius-sm); color: var(--yp-text-primary); background: var(--yp-bg-sunken); font-size: var(--yp-type-caption-size); font-weight: 700; }
.status-pill--blue { color: var(--yp-status-blue-foreground); background: var(--yp-status-blue); }
.status-pill--green { color: var(--yp-status-green-foreground); background: var(--yp-status-green); }
.status-pill--yellow { color: var(--yp-status-yellow-foreground); background: var(--yp-status-yellow); }
.status-pill--gray { color: var(--yp-status-gray-foreground); background: var(--yp-status-gray); }
.kanban-board { display: flex; align-items: flex-start; gap: var(--yp-space-4); overflow-x: auto; padding-bottom: var(--yp-space-3); }
.move-retry { position: sticky; z-index: 3; left: 0; display: flex; min-width: 300px; align-items: center; justify-content: space-between; gap: var(--yp-space-3); padding: var(--yp-space-3); border: 1px solid var(--yp-status-yellow); border-radius: var(--yp-radius-md); background: var(--yp-bg-surface); }
.kanban-group { display: grid; min-width: max-content; gap: var(--yp-space-2); padding: var(--yp-space-2); border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-md); background: var(--yp-bg-sunken); }
.kanban-group > header { display: flex; align-items: center; justify-content: space-between; padding: var(--yp-space-2); }
.kanban-group > header h3 { margin: 0; font-size: var(--yp-type-card-title-size); }
.kanban-group > header > span, .kanban-lane__header > span:last-child { display: grid; min-width: 24px; height: 24px; place-items: center; border-radius: 12px; color: var(--yp-text-secondary); background: var(--yp-bg-surface); font-size: var(--yp-type-caption-size); }
.kanban-group__lanes { display: flex; align-items: stretch; gap: var(--yp-space-3); }
.kanban-lane { display: flex; width: 300px; min-height: 420px; flex-direction: column; border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-md); background: var(--yp-bg-sunken); transition: border-color 120ms ease, background-color 120ms ease; }
.kanban-lane--filtered { opacity: .58; }
.kanban-lane--drop { border-color: var(--yp-focus-ring); background: var(--yp-bg-selected); }
.kanban-lane__header { display: flex; align-items: center; justify-content: space-between; padding: var(--yp-space-3); border-bottom: 1px solid var(--yp-border-subtle); }
.kanban-cards { display: grid; align-content: start; gap: var(--yp-space-3); min-height: 180px; padding: var(--yp-space-3); }
.work-item-card { position: relative; display: grid; width: 100%; min-height: 132px; border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-md); color: var(--yp-text-primary); background: var(--yp-bg-surface); box-shadow: var(--yp-shadow-card); transition: border-color 120ms ease, transform 120ms ease, opacity 120ms ease; }
.work-item-card:hover { border-color: var(--yp-border-strong); transform: translateY(-1px); }
.work-item-card--pending { opacity: .58; pointer-events: none; }
.work-item-card--dragging { opacity: .45; }
.work-item-card__actions { position: absolute; z-index: 1; top: var(--yp-space-2); right: var(--yp-space-2); display: flex; gap: 2px; }
.drag-handle, .move-menu { display: grid; width: 30px; height: 30px; place-items: center; padding: 0; border: 0; border-radius: var(--yp-radius-sm); color: var(--yp-text-muted); background: var(--yp-bg-sunken); cursor: pointer; }
.drag-handle { touch-action: none; }
.drag-handle:focus-visible, .move-menu:focus-visible, .work-item-card__body:focus-visible { outline: 2px solid var(--yp-focus-ring); outline-offset: 2px; }
.drag-handle:disabled, .move-menu:disabled { cursor: not-allowed; opacity: .45; }
.work-item-card__body { display: grid; gap: var(--yp-space-3); width: 100%; min-height: 132px; padding: var(--yp-space-4); padding-top: calc(var(--yp-space-4) + 22px); border: 0; color: inherit; background: transparent; cursor: pointer; text-align: left; }
.work-item-card__meta, .work-item-card__footer { display: flex; align-items: center; justify-content: space-between; gap: var(--yp-space-2); color: var(--yp-text-muted); font-size: var(--yp-type-caption-size); }
.work-item-card__meta strong { color: var(--yp-text-secondary); }
.work-item-card__title { font-weight: 650; line-height: 1.45; }
.work-item-card__status { justify-self: start; }
.load-more { margin: auto var(--yp-space-3) var(--yp-space-3); }
.detail-panel { min-height: 240px; }
.detail-panel__eyebrow { color: var(--yp-text-muted); font-size: var(--yp-type-caption-size); }
.detail-panel__badges { display: flex; align-items: center; gap: var(--yp-space-3); }
.version-badge { color: var(--yp-text-muted); font-size: var(--yp-type-caption-size); }
.detail-panel dl { display: grid; gap: var(--yp-space-3); margin: var(--yp-space-6) 0; }
.detail-panel dl div { display: grid; grid-template-columns: 96px 1fr; align-items: center; }
.detail-panel dt { color: var(--yp-text-muted); }
.detail-panel dd { margin: 0; }
.detail-panel section { padding: var(--yp-space-4) 0; border-top: 1px solid var(--yp-border-subtle); }
.detail-panel section h3 { margin: 0 0 var(--yp-space-2); font-size: var(--yp-type-card-title-size); }
.plain-text { margin: 0; color: var(--yp-text-secondary); line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
.work-item-editor { padding-top: var(--yp-space-5); border-top: 1px solid var(--yp-border-subtle); }
.editor-grid, .date-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--yp-space-3); }
.date-fields { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.date-fields :deep(.el-date-editor) { width: 100%; }
.work-item-conflict { display: grid; gap: var(--yp-space-2); margin-bottom: var(--yp-space-4); padding: var(--yp-space-4); border: 1px solid var(--yp-status-yellow); border-radius: var(--yp-radius-md); background: var(--yp-bg-selected); }
.drawer-footer { display: flex; align-items: center; justify-content: flex-end; gap: var(--yp-space-3); }
.drawer-footer > span { flex: 1; color: var(--yp-text-secondary); text-align: left; }
.transition-route { display: flex; align-items: center; gap: var(--yp-space-3); margin-bottom: var(--yp-space-4); padding: var(--yp-space-3); border-radius: var(--yp-radius-md); background: var(--yp-bg-sunken); }
.transition-note { margin: 0; color: var(--yp-text-secondary); font-size: var(--yp-type-caption-size); }
@media (max-width: 720px) {
  .workspace-toolbar { flex-direction: column; padding: var(--yp-space-4); }
  .view-switch { width: 100%; }
  .view-switch :deep(.el-button) { min-height: 44px; flex: 1; }
  .query-toolbar__actions, .query-conflict { align-items: stretch; flex-direction: column; }
  .table-surface :deep(.el-table) { display: block; overflow-x: auto; }
  .kanban-lane { width: 86vw; }
  .editor-grid, .date-fields { grid-template-columns: 1fr; }
}
@media (prefers-reduced-motion: reduce) {
  .work-item-card, .kanban-lane { transition: none; }
}
</style>
