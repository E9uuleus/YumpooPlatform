<script setup lang="ts">
import { Filter as FilterIcon, Hide, Plus, Search, Sort, User } from '@element-plus/icons-vue'
import {
  ContentStatus,
  AttachmentOwnerType,
  ContentViewType,
  ProjectActorAccess,
  ProjectLifecycle,
  ProjectMembershipStatus,
  ProjectMembershipStatusFilter,
  WorkItemPriority,
  ListProjectWorkItemFilterOptionsFieldEnum,
  readCsrfToken,
  type ProjectContentCatalog,
  type ProjectDetail,
  type ProjectMember,
  type WorkItemDetail,
  type ProjectWorkItemListItem,
  type WorkItemTransitionOption,
} from '@yumpoo/api-client'
import {
  ElButton,
  ElDrawer,
  ElDatePicker,
  ElCheckbox,
  ElIcon,
  ElInput,
  ElLoading,
  ElMessage,
  ElMessageBox,
  ElOption as ElOptionRaw,
  ElPopover,
  ElSelect as ElSelectRaw,
  ElTable,
  ElTableColumn,
} from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch, type DefineComponent } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { contentsApi, projectsApi, workItemsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import WorkItemDetailPanel from '../../components/collaboration/WorkItemDetailPanel.vue'
import LazyAttachmentPanel from '../../components/collaboration/LazyAttachmentPanel.vue'
import ProjectWorkspaceHeader from '../../components/projects/ProjectWorkspaceHeader.vue'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpPriorityBadge from '../../components/yp/YpPriorityBadge.vue'

type ProjectView = 'table' | 'kanban'

interface KanbanLane {
  items: ProjectWorkItemListItem[]
  nextCursor: string | null
  loading: boolean
  error?: ApiProblem
}

const route = useRoute()
const router = useRouter()
const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent
const projectId = computed(() => String(route.params.projectId))
const selectedView = computed<ProjectView>(() => route.query.view === 'kanban' ? 'kanban' : 'table')
const project = ref<ProjectDetail>()
const catalog = ref<ProjectContentCatalog>()
const members = ref<ProjectMember[]>([])
const tableItems = ref<ProjectWorkItemListItem[]>([])
const tableNextCursor = ref<string | null>(null)
const loading = ref(false)
const tableLoading = ref(false)
const error = ref<ApiProblem>()
const vLoading = ElLoading.directive
const lanes = reactive<Record<string, KanbanLane>>({})
const quickOpen = ref(false)
const quickContentId = ref('')
const quickTitle = ref('')
const quickCreating = ref(false)
const quickRow = ref<HTMLElement>()
const quickTitleInput = ref<InstanceType<typeof ElInput>>()
const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<WorkItemDetail>()
const detailTab = ref<'details' | 'discussion'>('details')
const dragging = ref<ProjectWorkItemListItem>()
const tableDragging = ref<ProjectWorkItemListItem>()
const tableDropIndex = ref<number>()
const tableRef = ref<{ $el: HTMLElement }>()
const tableSentinel = ref<HTMLElement>()
const loadingMoreError = ref<ApiProblem>()
const editingCell = ref('')
const assigneeSearch = ref('')
const assigneeMatches = ref<ProjectMember[]>()
const filterOptionCounts = ref(new Map<string, number>())
const filterOptionsLoading = ref(false)
const searchExpanded = ref(Boolean(route.query.q))
const searchInput = ref(String(route.query.q ?? ''))
const filters = reactive({
  assignees: new Set<string>(), statuses: new Set<string>(), priorities: new Set<WorkItemPriority>(),
  contents: new Set<string>(), dueRange: [] as Date[], updatedAfter: null as Date | null,
})
interface SortRule { field: string; direction: 'ASC' | 'DESC' }
const sortRules = ref<SortRule[]>([])
type ColumnKey = 'title' | 'assignee' | 'status' | 'priority' | 'content' | 'dueDate' | 'updatedAt'
const columns: Array<{ key: ColumnKey; label: string; defaultWidth: number; minWidth: number }> = [
  { key: 'title', label: '工作项名称', defaultWidth: 320, minWidth: 220 },
  { key: 'assignee', label: '处理人', defaultWidth: 90, minWidth: 72 },
  { key: 'status', label: '状态', defaultWidth: 130, minWidth: 96 },
  { key: 'priority', label: '优先级', defaultWidth: 120, minWidth: 90 },
  { key: 'content', label: '工作项类别', defaultWidth: 150, minWidth: 110 },
  { key: 'dueDate', label: '截止日期', defaultWidth: 140, minWidth: 112 },
  { key: 'updatedAt', label: '最后更新时间', defaultWidth: 170, minWidth: 135 },
]
const priorityOptions = [WorkItemPriority.Urgent, WorkItemPriority.High,
  WorkItemPriority.Medium, WorkItemPriority.Low]
const columnWidths = reactive<Record<ColumnKey, number>>(Object.fromEntries(columns.map(item => [item.key, item.defaultWidth])) as Record<ColumnKey, number>)
const hiddenColumns = ref(new Set<ColumnKey>())
let loadRevision = 0
let searchTimer: number | undefined
let memberSearchTimer: number | undefined
let tableObserver: IntersectionObserver | undefined
let kanbanObserver: IntersectionObserver | undefined
let activeController: AbortController | undefined

const TABLE_PREFS_VERSION = 1
const tablePrefsKey = computed(() => `yumpoo:project-work-items:table:v${TABLE_PREFS_VERSION}`)
const visibleColumns = computed(() => columns.filter(item => item.key === 'title' || !hiddenColumns.value.has(item.key)))
const tableMinWidth = computed(() => visibleColumns.value.reduce((sum, item) => sum + columnWidths[item.key], 5))
const quickGridStyle = computed(() => ({ gridTemplateColumns: visibleColumns.value.map(item => `${columnWidths[item.key]}px`).join(' ') }))
const quickContentColumn = computed(() => Math.max(2, visibleColumns.value.findIndex(item => item.key === 'content') + 1))
const quickSubmitColumn = computed(() => visibleColumns.value.length)
const hasExplicitSort = computed(() => sortRules.value.length > 0)
const filteredMembers = computed(() => {
  const query = assigneeSearch.value.trim().toLocaleLowerCase()
  return query
    ? (assigneeMatches.value ?? activeMembers.value.filter(item => item.displayName.toLocaleLowerCase().includes(query)))
    : activeMembers.value
})

const contentsById = computed(() => new Map((catalog.value?.items ?? []).map(item => [item.id, item])))
const activeContents = computed(() => (catalog.value?.items ?? []).filter(item => item.status === ContentStatus.Active))
const workflowStatuses = computed(() => [...(catalog.value?.workflowStatusOptions ?? [])]
  .sort((left, right) => left.sortOrder - right.sortOrder))
const activeMembers = computed(() => members.value.filter(item => item.membershipStatus === ProjectMembershipStatus.Active))
const canCreate = computed(() => Boolean(project.value
  && project.value.lifecycle !== ProjectLifecycle.Archived
  && activeContents.value.length
  && (project.value.actorAccess === ProjectActorAccess.Owner
    || project.value.actorAccess === ProjectActorAccess.Member)))
const detailContent = computed(() => detail.value ? contentsById.value.get(detail.value.contentId) : undefined)
const canPublishDiscussion = computed(() => Boolean(canCreate.value && detailContent.value?.status === ContentStatus.Active))
const discussionReadOnlyReason = computed(() => {
  if (project.value?.lifecycle === ProjectLifecycle.Archived) return 'Project 已归档，工作项讨论仅可查看。'
  if (detailContent.value?.status === ContentStatus.Archived) return 'Content 已归档，工作项讨论仅可查看。'
  if (!canPublishDiscussion.value) return '当前角色没有发布讨论的权限。'
  return undefined
})

function lane(statusCode: string): KanbanLane {
  if (!lanes[statusCode]) lanes[statusCode] = {
    items: [], nextCursor: null, loading: false,
  }
  return lanes[statusCode]
}

function contentName(contentId: string): string {
  return contentsById.value.get(contentId)?.name ?? '未知 Content'
}

function statusLabel(statusCode: string): string {
  return workflowStatuses.value.find(item => item.statusCode === statusCode)?.displayName ?? statusCode
}

function getStatusTone(statusCode: string): string {
  const option = workflowStatuses.value.find(item => item.statusCode === statusCode)
  const category = option?.statusCategory
  if (category === 'DONE') return 'green'
  if (category === 'IN_PROGRESS') return 'yellow'
  if (category === 'CANCELED') return 'gray'

  const upper = statusCode.toUpperCase()
  if (upper.includes('STUCK') || upper.includes('BLOCK') || upper.includes('DEFECT') || upper.includes('BUG')) {
    return 'red'
  }
  if (upper.includes('REVIEW') || upper.includes('AUDIT') || upper.includes('TEST') || upper.includes('INSPECT')) {
    return 'blue'
  }
  if (upper.includes('PLAN') || upper.includes('TODO') || upper.includes('BACKLOG')) {
    return 'neutral'
  }
  return 'gray'
}

function getPriorityPresentation(priority: string | null): { label: string; tone: string } {
  if (!priority) return { label: '—', tone: 'empty' }
  const upper = priority.toUpperCase()
  if (upper === 'URGENT') return { label: '紧急', tone: 'urgent' }
  if (upper === 'HIGH') return { label: '高', tone: 'high' }
  if (upper === 'MEDIUM') return { label: '中', tone: 'medium' }
  if (upper === 'LOW') return { label: '低', tone: 'low' }
  return { label: priority, tone: 'empty' }
}

function isOverdue(dueDate: Date | string | null, statusCode: string): boolean {
  if (!dueDate) return false
  const option = workflowStatuses.value.find(item => item.statusCode === statusCode)
  if (option?.terminal || option?.statusCategory === 'DONE' || option?.statusCategory === 'CANCELED') {
    return false
  }
  const due = new Date(dueDate)
  if (Number.isNaN(due.getTime())) return false
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const dueDateOnly = new Date(due.getFullYear(), due.getMonth(), due.getDate())
  return dueDateOnly < today
}

function formatDate(value: Date | string | null): string {
  return value ? new Date(value).toISOString().slice(0, 10) : '—'
}

function formatTime(value: Date | string): string {
  return new Date(value).toLocaleString('zh-CN')
}

function queryValues(name: string): string[] {
  const value = route.query[name]
  return (Array.isArray(value) ? value : value ? String(value).split(',') : []).filter(Boolean) as string[]
}

function applyRouteState(): void {
  searchInput.value = String(route.query.q ?? '')
  searchExpanded.value = Boolean(searchInput.value)
  filters.assignees = new Set(queryValues('assignee'))
  filters.statuses = new Set(queryValues('status'))
  filters.priorities = new Set(queryValues('priority') as WorkItemPriority[])
  filters.contents = new Set(queryValues('content'))
  const dueFrom = route.query.dueFrom ? new Date(String(route.query.dueFrom)) : undefined
  const dueTo = route.query.dueTo ? new Date(String(route.query.dueTo)) : undefined
  filters.dueRange = dueFrom && dueTo ? [dueFrom, dueTo] : []
  filters.updatedAfter = route.query.updatedAfter ? new Date(String(route.query.updatedAfter)) : null
  const rawSort = route.query.sort
  const sortValues = (Array.isArray(rawSort) ? rawSort : rawSort ? String(rawSort).split(';') : [])
    .filter((value): value is string => Boolean(value))
  sortRules.value = sortValues.slice(0, 3).map(value => {
    const [field = 'UPDATED_AT', direction = 'DESC'] = value.split(',')
    return { field, direction: direction === 'ASC' ? 'ASC' : 'DESC' }
  })
}

async function syncUrl(extra: Record<string, string | undefined> = {}): Promise<void> {
  const next = { ...route.query, ...extra }
  const put = (name: string, values: Iterable<string>) => {
    const value = [...values].join(',')
    if (value) next[name] = value
    else delete next[name]
  }
  if (searchInput.value.trim()) next.q = searchInput.value.trim()
  else delete next.q
  put('assignee', filters.assignees); put('status', filters.statuses)
  put('priority', filters.priorities); put('content', filters.contents)
  if (filters.dueRange.length === 2) {
    next.dueFrom = formatDate(filters.dueRange[0]!)
    next.dueTo = formatDate(filters.dueRange[1]!)
  } else { delete next.dueFrom; delete next.dueTo }
  if (filters.updatedAfter) next.updatedAfter = filters.updatedAfter.toISOString()
  else delete next.updatedAfter
  const sortValue = sortRules.value.map(item => `${item.field},${item.direction}`).join(';')
  if (sortValue) next.sort = sortValue
  else delete next.sort
  await router.replace({ query: next })
}

function scheduleSearch(): void {
  if (searchTimer) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => void syncUrl(), 300)
}

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
      }, { signal: activeController?.signal ?? null })
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
  else if (set === filters.priorities) filters.priorities = next as Set<WorkItemPriority>
  else filters.contents = next as Set<string>
  void syncUrl()
}

function countBy(field: 'statusCode' | 'priority' | 'contentId' | 'assigneeUserId', value: string | null): number {
  const apiField = ({ statusCode: 'STATUS', priority: 'PRIORITY', contentId: 'CONTENT', assigneeUserId: 'ASSIGNEE' } as const)[field]
  return filterOptionCounts.value.get(`${apiField}:${value ?? '__NULL__'}`)
    ?? tableItems.value.filter(item => item[field] === value).length
}

async function loadFilterOptions(): Promise<void> {
  if (filterOptionsLoading.value) return
  filterOptionsLoading.value = true
  const fields = [ListProjectWorkItemFilterOptionsFieldEnum.Assignee,
    ListProjectWorkItemFilterOptionsFieldEnum.Status,
    ListProjectWorkItemFilterOptionsFieldEnum.Priority,
    ListProjectWorkItemFilterOptionsFieldEnum.Content]
  try {
    const base = listRequest(null)
    const { view: _view, limit: _limit, ...context } = base
    void _view; void _limit
    const pages = await Promise.all(fields.map(field => workItemsApi.listProjectWorkItemFilterOptions({
      ...context, projectId: projectId.value, field, limit: 100,
    }, { signal: activeController?.signal ?? null })))
    const next = new Map<string, number>()
    pages.forEach((page, index) => page.items.forEach(option => next.set(`${fields[index]}:${option.value}`, option.count)))
    filterOptionCounts.value = next
  } catch (reason) {
    if (!(reason instanceof DOMException && reason.name === 'AbortError'))
      error.value = await toApiProblem(reason)
  } finally { filterOptionsLoading.value = false }
}

async function loadMembers(requestedProjectId: string, revision: number): Promise<void> {
  const loaded: ProjectMember[] = []
  let page = 0
  let totalPages = 1
  while (page < totalPages) {
    const result = await projectsApi.listProjectMembers({
      projectId: requestedProjectId,
      status: ProjectMembershipStatusFilter.All,
      page,
      size: 100,
    })
    if (revision !== loadRevision) return
    loaded.push(...result.items)
    totalPages = result.totalPages
    page += 1
  }
  members.value = loaded
}

function listRequest(cursor?: string | null) {
  return {
    projectId: projectId.value, limit: 25,
    view: ContentViewType.Table,
    ...(cursor ? { cursor } : {}),
    ...(searchInput.value.trim() ? { q: searchInput.value.trim() } : {}),
    ...(filters.statuses.size ? { status: filters.statuses } : {}),
    ...(filters.priorities.size ? { priority: filters.priorities } : {}),
    ...(filters.assignees.size ? { assigneeUserId: filters.assignees } : {}),
    ...(filters.contents.size ? { contentId: filters.contents } : {}),
    ...(filters.dueRange[0] ? { dueFrom: filters.dueRange[0] } : {}),
    ...(filters.dueRange[1] ? { dueTo: filters.dueRange[1] } : {}),
    ...(filters.updatedAfter ? { updatedAfter: filters.updatedAfter } : {}),
    ...(sortRules.value.length ? { sort: sortRules.value.map(item => `${item.field},${item.direction}`) } : {}),
  }
}

async function loadTable(cursor: string | null = null, append = false, revision = loadRevision): Promise<void> {
  if (tableLoading.value) return
  tableLoading.value = true
  loadingMoreError.value = undefined
  try {
    const result = await workItemsApi.listProjectWorkItems(listRequest(cursor), { signal: activeController?.signal ?? null })
    if (revision !== loadRevision) return
    const merged = append ? [...tableItems.value, ...result.items] : result.items
    tableItems.value = [...new Map(merged.map(item => [item.id, item])).values()]
    tableNextCursor.value = result.nextCursor
    await nextTick(markDraggableRows)
  } catch (reason) {
    if (revision === loadRevision) {
      const problem = await toApiProblem(reason)
      if (append) loadingMoreError.value = problem; else error.value = problem
    }
  } finally {
    if (revision === loadRevision) tableLoading.value = false
  }
}

async function loadLane(statusCode: string, cursor: string | null = null, revision = loadRevision): Promise<void> {
  const state = lane(statusCode)
  state.loading = true
  delete state.error
  try {
    const result = await workItemsApi.listProjectWorkItems({
      projectId: projectId.value,
      view: ContentViewType.Kanban,
      status: new Set([statusCode]),
      limit: 25,
      ...(cursor ? { cursor } : {}),
      ...(searchInput.value.trim() ? { q: searchInput.value.trim() } : {}),
      ...(filters.assignees.size ? { assigneeUserId: filters.assignees } : {}),
      ...(filters.priorities.size ? { priority: filters.priorities } : {}),
      ...(filters.contents.size ? { contentId: filters.contents } : {}),
      ...(filters.dueRange[0] ? { dueFrom: filters.dueRange[0] } : {}),
      ...(filters.dueRange[1] ? { dueTo: filters.dueRange[1] } : {}),
      ...(filters.updatedAfter ? { updatedAfter: filters.updatedAfter } : {}),
    }, { signal: activeController?.signal ?? null })
    if (revision !== loadRevision) return
    const merged = cursor ? [...state.items, ...result.items] : result.items
    state.items = [...new Map(merged.map(item => [item.id, item])).values()]
    state.nextCursor = result.nextCursor
    await nextTick(bindKanbanSentinels)
  } catch (reason) {
    if (revision === loadRevision) state.error = await toApiProblem(reason)
  } finally {
    if (revision === loadRevision) state.loading = false
  }
}

async function loadKanban(revision = loadRevision): Promise<void> {
  Object.keys(lanes).forEach(key => delete lanes[key])
  await Promise.all(workflowStatuses.value.map(status => loadLane(status.statusCode, null, revision)))
  await nextTick(bindKanbanSentinels)
}

function bindKanbanSentinels(): void {
  kanbanObserver?.disconnect()
  document.querySelectorAll<HTMLElement>('.lane-cursor-sentinel').forEach(element => kanbanObserver?.observe(element))
}

async function refreshCurrentView(): Promise<void> {
  if (selectedView.value === 'kanban') await loadKanban()
  else await loadTable(null, false)
}

async function loadWorkspace(): Promise<void> {
  const revision = ++loadRevision
  activeController?.abort()
  activeController = new AbortController()
  const requestedProjectId = projectId.value
  loading.value = true
  error.value = undefined
  project.value = undefined
  catalog.value = undefined
  tableItems.value = []
  tableNextCursor.value = null
  members.value = []
  Object.keys(lanes).forEach(key => delete lanes[key])
  closeQuick()
  try {
    const [nextProject, nextCatalog] = await Promise.all([
      projectsApi.getProject({ projectId: requestedProjectId }),
      contentsApi.listProjectContents({ projectId: requestedProjectId }),
    ])
    if (revision !== loadRevision) return
    project.value = nextProject
    catalog.value = nextCatalog
    await Promise.all([
      loadMembers(requestedProjectId, revision),
      selectedView.value === 'kanban' ? loadKanban(revision) : loadTable(null, false, revision),
    ])
  } catch (reason) {
    if (revision === loadRevision) error.value = await toApiProblem(reason)
  } finally {
    if (revision === loadRevision) loading.value = false
  }
}

async function changeView(view: ProjectView): Promise<void> {
  if (view === selectedView.value) return
  await router.push({ query: { ...route.query, view } })
}

function openQuick(): void {
  if (!canCreate.value) return
  quickOpen.value = true
  if (!quickContentId.value && activeContents.value.length === 1) quickContentId.value = activeContents.value[0]!.id
  void nextTick(() => quickTitleInput.value?.focus())
}

function closeQuick(): void {
  quickOpen.value = false
  quickContentId.value = ''
  quickTitle.value = ''
}

async function createQuick(continueAdding: boolean): Promise<void> {
  if (quickCreating.value || !quickContentId.value || !quickTitle.value.trim()) return
  const csrf = readCsrfToken()
  if (!csrf) {
    error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  quickCreating.value = true
  error.value = undefined
  try {
    const created = await workItemsApi.createWorkItem({
      contentId: quickContentId.value,
      xXSRFTOKEN: csrf,
      idempotencyKey: globalThis.crypto.randomUUID(),
      workItemCreateRequest: {
        title: quickTitle.value.trim(),
        priority: null,
        assigneeUserId: null,
        description: null,
        notes: null,
        timelineStartDate: null,
        timelineEndDate: null,
        dueDate: null,
      },
    })
    ElMessage.success(`已创建 ${created.itemNo}`)
    if (continueAdding) {
      quickTitle.value = ''
      await nextTick()
      quickTitleInput.value?.focus()
    } else closeQuick()
    await refreshCurrentView()
  } catch (reason) {
    error.value = await toApiProblem(reason)
    await nextTick()
    quickTitleInput.value?.focus()
  } finally {
    quickCreating.value = false
  }
}

function onQuickKeydown(rawEvent: Event | KeyboardEvent): void {
  const event = rawEvent as KeyboardEvent
  if (event.key !== 'Enter') return
  event.preventDefault()
  void createQuick(event.shiftKey)
}

function onDocumentPointerDown(event: PointerEvent): void {
  if (!quickOpen.value || quickCreating.value || !quickTitle.value.trim()) return
  if (quickRow.value?.contains(event.target as Node)) return
  void createQuick(false)
}

async function openDetail(item: ProjectWorkItemListItem, tab: 'details' | 'discussion'): Promise<void> {
  detailOpen.value = true
  detailTab.value = tab
  detailLoading.value = true
  detail.value = undefined
  try {
    detail.value = await workItemsApi.getWorkItem({ workItemId: item.id })
  } catch (reason) {
    error.value = await toApiProblem(reason)
    detailOpen.value = false
  } finally {
    detailLoading.value = false
  }
}

function transitionFor(item: ProjectWorkItemListItem, statusCode: string): WorkItemTransitionOption | undefined {
  return item.capabilities.availableTransitions.find(option => option.toStatus === statusCode)
}

async function dropInto(statusCode: string): Promise<void> {
  const item = dragging.value
  dragging.value = undefined
  if (!item || item.statusCode === statusCode) return
  const transition = transitionFor(item, statusCode)
  if (!item.capabilities.canMoveInKanban || !transition) {
    ElMessage.warning('该工作项不能迁移到目标状态。')
    return
  }
  let resolution: string | null = null
  if (transition.requiresResolution) {
    try {
      const answer = await ElMessageBox.prompt('该状态迁移需要填写说明。', `迁移到${transition.displayName}`, {
        inputType: 'textarea',
        inputValidator: value => Boolean(value.trim()) || '请输入迁移说明',
        confirmButtonText: '确认迁移',
        cancelButtonText: '取消',
      })
      resolution = answer.value.trim()
    } catch {
      return
    }
  }
  const csrf = readCsrfToken()
  if (!csrf) {
    error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  try {
    const updated = await workItemsApi.transitionWorkItem({
      workItemId: item.id,
      xXSRFTOKEN: csrf,
      ifMatch: item.etag,
      idempotencyKey: globalThis.crypto.randomUUID(),
      workItemTransitionRequest: { toStatus: statusCode, resolution },
    })
    replaceLightItem(item.id, updated)
    if (selectedView.value === 'kanban') await loadKanban()
    ElMessage.success(`已迁移到${transition.displayName}`)
  } catch (reason) {
    error.value = await toApiProblem(reason)
    await refreshCurrentView()
  }
}

async function transitionItem(item: ProjectWorkItemListItem, statusCode: string): Promise<void> {
  dragging.value = item
  await dropInto(statusCode)
}

function replaceLightItem(id: string, detail: WorkItemDetail): void {
  const apply = (item: ProjectWorkItemListItem): ProjectWorkItemListItem => item.id !== id ? item : {
    ...item, statusCode: detail.statusCode, statusCategory: detail.statusCategory,
    priority: detail.priority,
    assigneeUserId: detail.assigneeUserId, assigneeDisplayName: detail.assigneeDisplayName,
    dueDate: detail.dueDate, updatedAt: detail.updatedAt, rowVersion: detail.rowVersion, etag: detail.etag,
    capabilities: detail.capabilities,
  }
  tableItems.value = tableItems.value.map(apply)
  Object.values(lanes).forEach(state => { state.items = state.items.map(apply) })
}

async function patchCell(item: ProjectWorkItemListItem, field: 'assignee' | 'priority' | 'dueDate', value: string | Date | null): Promise<boolean> {
  const key = `${item.id}:${field}`
  if (editingCell.value) return false
  const csrf = readCsrfToken()
  if (!csrf) { error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return false }
  editingCell.value = key
  try {
    const common = { workItemId: item.id, xXSRFTOKEN: csrf, ifMatch: item.etag, idempotencyKey: globalThis.crypto.randomUUID() }
    const updated = field === 'assignee'
      ? await workItemsApi.patchWorkItemAssignee({ ...common, workItemAssigneePatchRequest: { assigneeUserId: value as string | null } })
      : field === 'priority'
        ? await workItemsApi.patchWorkItemPriority({ ...common, workItemPriorityPatchRequest: { priority: value as WorkItemPriority | null } })
        : await workItemsApi.patchWorkItemDueDate({ ...common, workItemDueDatePatchRequest: { dueDate: value as Date | null } })
    replaceLightItem(item.id, updated)
    return true
  } catch (reason) {
    error.value = await toApiProblem(reason)
    await loadTable(null, false)
    return false
  } finally { editingCell.value = '' }
}

function setSortCount(count: number): void {
  const next = sortRules.value.slice(0, count)
  while (next.length < count) next.push({ field: 'UPDATED_AT', direction: 'DESC' })
  sortRules.value = next
  void syncUrl()
}

function persistTablePrefs(): void {
  localStorage.setItem(tablePrefsKey.value, JSON.stringify({ version: TABLE_PREFS_VERSION, widths: columnWidths, hidden: [...hiddenColumns.value] }))
}

function loadTablePrefs(): void {
  try {
    const parsed = JSON.parse(localStorage.getItem(tablePrefsKey.value) ?? '{}') as { version?: number; widths?: Partial<Record<ColumnKey, number>>; hidden?: ColumnKey[] }
    if (parsed.version !== TABLE_PREFS_VERSION) return
    columns.forEach(column => {
      const value = parsed.widths?.[column.key]
      if (typeof value === 'number') columnWidths[column.key] = Math.max(column.minWidth, value)
    })
    hiddenColumns.value = new Set((parsed.hidden ?? []).filter(key => key !== 'title'))
  } catch { /* 忽略损坏的本地视图偏好 */ }
}

function onHeaderDragEnd(newWidth: number, _oldWidth: number, column: { label: string }): void {
  const config = columns.find(item => item.label === column.label)
  if (!config) return
  columnWidths[config.key] = Math.max(config.minWidth, Math.round(newWidth))
  persistTablePrefs()
}

function toggleColumn(key: ColumnKey, checked: boolean): void {
  if (key === 'title') return
  const next = new Set(hiddenColumns.value)
  if (checked) next.delete(key); else next.add(key)
  hiddenColumns.value = next
  persistTablePrefs()
}

function markDraggableRows(): void {
  const rows = tableRef.value?.$el?.querySelectorAll('.el-table__body-wrapper tbody tr') as NodeListOf<HTMLElement> | undefined
  rows?.forEach((row, index) => { row.draggable = Boolean(tableItems.value[index]?.capabilities.canMoveInProjectOrder) })
}

function tableRowClassName({ rowIndex }: { rowIndex: number }): string {
  const classes = ['work-item-table-row']
  if (tableDropIndex.value === rowIndex) classes.push('work-item-table-row--drop-before')
  if (tableDropIndex.value === tableItems.value.length && rowIndex === tableItems.value.length - 1)
    classes.push('work-item-table-row--drop-after')
  return classes.join(' ')
}

function captureTablePositions(): Map<string, number> {
  const rows = tableRef.value?.$el?.querySelectorAll('.el-table__body-wrapper tbody tr') as NodeListOf<HTMLElement> | undefined
  return new Map(tableItems.value.map((item, index) => [item.id, rows?.[index]?.getBoundingClientRect().top ?? 0]))
}

function animateTableReorder(previous: Map<string, number>): void {
  const rows = tableRef.value?.$el?.querySelectorAll('.el-table__body-wrapper tbody tr') as NodeListOf<HTMLElement> | undefined
  tableItems.value.forEach((item, index) => {
    const row = rows?.[index]
    const before = previous.get(item.id)
    if (!row || before === undefined) return
    const delta = before - row.getBoundingClientRect().top
    if (Math.abs(delta) < 1) return
    row.animate([{ transform: `translateY(${delta}px)` }, { transform: 'translateY(0)' }], {
      duration: 140,
      easing: 'cubic-bezier(.2, 0, 0, 1)',
    })
  })
}

function rowIndexFromEvent(event: DragEvent): number {
  const row = (event.target as HTMLElement | null)?.closest('tbody tr')
  if (!row) return -1
  return [...row.parentElement!.children].indexOf(row)
}

function onTableDragStart(event: DragEvent): void {
  if ((event.target as HTMLElement | null)?.closest('button,input,.el-input,.el-select,.el-popover')) { event.preventDefault(); return }
  if (hasExplicitSort.value) {
    event.preventDefault(); sortRules.value = []; void syncUrl(); ElMessage.info('已清除排序，请在列表恢复手工顺序后再次拖动。'); return
  }
  const index = rowIndexFromEvent(event)
  const item = tableItems.value[index]
  if (!item?.capabilities.canMoveInProjectOrder) { event.preventDefault(); return }
  tableDragging.value = item
  event.dataTransfer?.setData('text/plain', item.id)
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    const source = (event.target as HTMLElement).closest('tbody tr') as HTMLElement | null
    if (source) {
      const ghost = source.cloneNode(true) as HTMLElement
      ghost.className = `${source.className} work-item-drag-ghost`
      ghost.style.width = `${source.getBoundingClientRect().width}px`
      document.body.appendChild(ghost)
      event.dataTransfer.setDragImage(ghost, 24, 18)
      window.setTimeout(() => ghost.remove())
    }
  }
}

function onTableDragOver(event: DragEvent): void {
  if (!tableDragging.value) return
  const index = rowIndexFromEvent(event)
  if (index >= 0) {
    const row = (event.target as HTMLElement).closest('tbody tr')!
    const rect = row.getBoundingClientRect()
    tableDropIndex.value = index + (event.clientY > rect.top + rect.height / 2 ? 1 : 0)
  }
  if (event.clientY >= window.innerHeight - 48) {
    window.scrollBy({ top: 28, behavior: 'smooth' })
    if (tableNextCursor.value && !tableLoading.value) void loadTable(tableNextCursor.value, true)
  }
}

async function onTableDrop(event: DragEvent): Promise<void> {
  event.preventDefault()
  const item = tableDragging.value
  let target = tableDropIndex.value
  tableDragging.value = undefined; tableDropIndex.value = undefined
  if (!item || target === undefined) return
  const original = [...tableItems.value]
  const from = original.findIndex(candidate => candidate.id === item.id)
  if (from < 0) return
  if (target === original.length && tableNextCursor.value) {
    ElMessage.info('正在加载更远的工作项，请稍后继续拖动。'); await loadTable(tableNextCursor.value, true); return
  }
  const reordered = [...original]
  reordered.splice(from, 1)
  if (from < target) target -= 1
  target = Math.max(0, Math.min(target, reordered.length))
  reordered.splice(target, 0, item)
  if (reordered.map(row => row.id).join() === original.map(row => row.id).join()) return
  const previousPositions = captureTablePositions()
  tableItems.value = reordered
  await nextTick()
  animateTableReorder(previousPositions)
  const csrf = readCsrfToken()
  if (!csrf) { tableItems.value = original; error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  try {
    const updated = await workItemsApi.moveProjectWorkItemOrder({
      projectId: projectId.value, workItemId: item.id, xXSRFTOKEN: csrf, ifMatch: item.etag,
      idempotencyKey: globalThis.crypto.randomUUID(),
      projectWorkItemOrderMoveRequest: {
        previousVisibleWorkItemId: reordered[target - 1]?.id ?? null,
        nextVisibleWorkItemId: reordered[target + 1]?.id ?? null,
      },
    })
    replaceLightItem(item.id, updated)
    ElMessage.success('工作项顺序已更新')
  } catch (reason) {
    tableItems.value = original
    error.value = await toApiProblem(reason)
    await loadTable(null, false)
  }
}

function clearFilters(): void {
  filters.assignees = new Set(); filters.statuses = new Set(); filters.priorities = new Set(); filters.contents = new Set()
  filters.dueRange = []; filters.updatedAfter = null
  void syncUrl()
}

function resetCurrentData(): void {
  const revision = ++loadRevision
  activeController?.abort(); activeController = new AbortController()
  error.value = undefined; tableItems.value = []; tableNextCursor.value = null
  Object.keys(lanes).forEach(key => delete lanes[key])
  if (selectedView.value === 'kanban') void loadKanban(revision); else void loadTable(null, false, revision)
}

function onDueDateChange(item: ProjectWorkItemListItem, value: unknown): void {
  void patchCell(item, 'dueDate', value instanceof Date ? value : null)
}

watch(projectId, () => { applyRouteState(); void loadWorkspace() }, { immediate: true })
watch(() => JSON.stringify(route.query), () => {
  if (!project.value) return
  applyRouteState(); resetCurrentData()
})
watch(assigneeSearch, scheduleMemberSearch)

onMounted(() => {
  loadTablePrefs()
  document.addEventListener('pointerdown', onDocumentPointerDown)
  tableObserver = new IntersectionObserver(entries => {
    if (entries.some(entry => entry.isIntersecting) && tableNextCursor.value && !tableLoading.value) void loadTable(tableNextCursor.value, true)
  }, { rootMargin: '320px 0px' })
  kanbanObserver = new IntersectionObserver(entries => {
    entries.filter(entry => entry.isIntersecting).forEach(entry => {
      const status = (entry.target as HTMLElement).dataset.status
      if (status && lane(status).nextCursor && !lane(status).loading)
        void loadLane(status, lane(status).nextCursor)
    })
  }, { rootMargin: '240px' })
  watch(tableSentinel, (next, previous) => { if (previous) tableObserver?.unobserve(previous); if (next) tableObserver?.observe(next) }, { immediate: true })
})
onBeforeUnmount(() => {
  activeController?.abort(); if (searchTimer) window.clearTimeout(searchTimer)
  if (memberSearchTimer) window.clearTimeout(memberSearchTimer)
  tableObserver?.disconnect(); kanbanObserver?.disconnect()
  document.removeEventListener('pointerdown', onDocumentPointerDown)
})
</script>

<template>
  <div
    v-loading="loading"
    class="project-view-stack"
  >
    <inline-problem
      v-if="error"
      :problem="error"
    />
    <template v-if="project">
      <project-workspace-header
        section="overview"
        :project="project"
      />

      <section class="work-items-home">
        <div class="monday-view-header">
          <div
            class="view-tabs"
            role="tablist"
            aria-label="项目工作项视图"
          >
            <button
              :class="{ active: selectedView === 'table' }"
              role="tab"
              @click="changeView('table')"
            >
              <svg
                width="14"
                height="14"
                viewBox="0 0 16 16"
                fill="currentColor"
                class="tab-icon"
              >
                <path d="M1 2.5A1.5 1.5 0 0 1 2.5 1h11A1.5 1.5 0 0 1 15 2.5v11a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 1 13.5v-11zM2.5 2a.5.5 0 0 0-.5.5V5h12V2.5a.5.5 0 0 0-.5-.5h-11zM14 6H2v7.5a.5.5 0 0 0 .5.5H6V6h8zm-7 8h4.5a.5.5 0 0 0 .5-.5V6H7v8z" />
              </svg>
              <span>表格</span>
            </button>
            <button
              :class="{ active: selectedView === 'kanban' }"
              role="tab"
              @click="changeView('kanban')"
            >
              <svg
                width="14"
                height="14"
                viewBox="0 0 16 16"
                fill="currentColor"
                class="tab-icon"
              >
                <path d="M1 2.5A1.5 1.5 0 0 1 2.5 1h3A1.5 1.5 0 0 1 7 2.5v11A1.5 1.5 0 0 1 5.5 15h-3A1.5 1.5 0 0 1 1 13.5v-11zM2.5 2a.5.5 0 0 0-.5.5v11a.5.5 0 0 0 .5.5h3a.5.5 0 0 0 .5-.5v-11a.5.5 0 0 0-.5-.5h-3zm7-1A1.5 1.5 0 0 0 8 2.5v7A1.5 1.5 0 0 0 9.5 11h3A1.5 1.5 0 0 0 14 9.5v-7A1.5 1.5 0 0 0 12.5 1h-3zm-.5 1.5a.5.5 0 0 1 .5-.5h3a.5.5 0 0 1 .5.5v7a.5.5 0 0 1-.5.5h-3a.5.5 0 0 1-.5-.5v-7z" />
              </svg>
              <span>看板</span>
            </button>
          </div>
        </div>

        <div class="work-items-toolbar" aria-label="工作项工具栏">
          <div class="toolbar-search" :class="{ 'toolbar-search--expanded': searchExpanded }">
            <el-input
              v-if="searchExpanded"
              v-model="searchInput"
              clearable
              autofocus
              placeholder="搜索工作项名称或编号"
              @input="scheduleSearch"
              @clear="() => syncUrl()"
            >
              <template #prefix><el-icon><search /></el-icon></template>
            </el-input>
            <button v-else class="toolbar-button" @click="searchExpanded = true">
              <el-icon><search /></el-icon><span>Search</span>
            </button>
          </div>

          <el-popover placement="bottom-start" :width="360" trigger="click" popper-class="work-items-popover" @show="loadFilterOptions">
            <template #reference>
              <button class="toolbar-button" :class="{ active: filters.assignees.size }">
                <el-icon><user /></el-icon><span>处理人</span>
                <span v-if="filters.assignees.size" class="toolbar-count">{{ filters.assignees.size }}</span>
              </button>
            </template>
            <div class="popover-stack">
              <el-input v-model="assigneeSearch" clearable autofocus placeholder="搜索项目成员" />
              <button
                v-for="member in filteredMembers"
                :key="member.userId"
                class="popover-option"
                @click="toggleSet(filters.assignees, member.userId, !filters.assignees.has(member.userId))"
              >
                <el-checkbox :model-value="filters.assignees.has(member.userId)" @click.stop />
                <yp-assignee :user-id="member.userId" :display-name="member.displayName" />
                <span class="option-count">{{ countBy('assigneeUserId', member.userId) }}</span>
              </button>
            </div>
          </el-popover>

          <el-popover placement="bottom-start" :width="560" trigger="click" popper-class="work-items-popover work-items-filter-popover" @show="loadFilterOptions">
            <template #reference>
              <button class="toolbar-button" :class="{ active: filters.statuses.size || filters.priorities.size || filters.contents.size || filters.dueRange.length }">
                <el-icon><filter-icon /></el-icon><span>筛选</span>
              </button>
            </template>
            <div class="filter-popover">
              <header><strong>快速筛选</strong><button class="text-button" @click="clearFilters">清除全部</button></header>
              <div class="filter-columns">
                <section>
                  <h4>工作项名称</h4>
                  <el-input v-model="searchInput" clearable placeholder="名称或编号" @input="scheduleSearch" />
                </section>
                <section>
                  <h4>状态</h4>
                  <button v-for="status in workflowStatuses" :key="status.statusCode" class="filter-value" @click="toggleSet(filters.statuses, status.statusCode, !filters.statuses.has(status.statusCode))">
                    <el-checkbox :model-value="filters.statuses.has(status.statusCode)" @click.stop />
                    <span>{{ status.displayName }}</span><small>{{ countBy('statusCode', status.statusCode) }}</small>
                  </button>
                </section>
                <section>
                  <h4>优先级</h4>
                  <button v-for="priority in priorityOptions" :key="priority" class="filter-value" @click="toggleSet(filters.priorities, priority, !filters.priorities.has(priority))">
                    <el-checkbox :model-value="filters.priorities.has(priority)" @click.stop />
                    <span>{{ getPriorityPresentation(priority).label }}</span><small>{{ countBy('priority', priority) }}</small>
                  </button>
                </section>
                <section>
                  <h4>工作项类别</h4>
                  <button v-for="content in catalog?.items ?? []" :key="content.id" class="filter-value" @click="toggleSet(filters.contents, content.id, !filters.contents.has(content.id))">
                    <el-checkbox :model-value="filters.contents.has(content.id)" @click.stop />
                    <span>{{ content.name }}</span><small>{{ countBy('contentId', content.id) }}</small>
                  </button>
                </section>
              </div>
              <div class="filter-dates">
                <el-date-picker v-model="filters.dueRange" type="daterange" start-placeholder="截止日期从" end-placeholder="截止日期到" @change="syncUrl" />
                <el-date-picker v-model="filters.updatedAfter" type="date" placeholder="最后更新时间晚于" @change="syncUrl" />
              </div>
            </div>
          </el-popover>

          <el-popover placement="bottom-start" :width="420" trigger="click" :disabled="selectedView === 'kanban'" popper-class="work-items-popover">
            <template #reference>
              <button class="toolbar-button" :disabled="selectedView === 'kanban'" :class="{ active: sortRules.length }">
                <el-icon><sort /></el-icon><span>排序</span>
              </button>
            </template>
            <div class="sort-popover">
              <header><strong>排序方式</strong><button class="text-button" @click="sortRules = []; syncUrl()">清除</button></header>
              <div v-for="(rule, index) in sortRules" :key="index" class="sort-rule">
                <el-select v-model="rule.field" @change="syncUrl">
                  <el-option label="工作项名称" value="TITLE" /><el-option label="处理人" value="ASSIGNEE" />
                  <el-option label="状态" value="STATUS" /><el-option label="优先级" value="PRIORITY" />
                  <el-option label="截止日期" value="DUE_DATE" /><el-option label="最后更新时间" value="UPDATED_AT" />
                </el-select>
                <el-select v-model="rule.direction" @change="syncUrl"><el-option label="升序" value="ASC" /><el-option label="降序" value="DESC" /></el-select>
              </div>
              <button v-if="sortRules.length < 3" class="popover-add" @click="setSortCount(sortRules.length + 1)">+ 新增排序</button>
            </div>
          </el-popover>

          <el-popover placement="bottom-start" :width="320" trigger="click" :disabled="selectedView === 'kanban'" popper-class="work-items-popover">
            <template #reference>
              <button class="toolbar-button" :disabled="selectedView === 'kanban'">
                <el-icon><hide /></el-icon><span>隐藏</span>
              </button>
            </template>
            <div class="popover-stack">
              <strong>显示列</strong>
              <label v-for="column in columns" :key="column.key" class="column-option">
                <el-checkbox :model-value="!hiddenColumns.has(column.key)" :disabled="column.key === 'title'" @change="checked => toggleColumn(column.key, Boolean(checked))" />
                <span>{{ column.label }}</span>
              </label>
            </div>
          </el-popover>
        </div>

        <div
          v-if="selectedView === 'table'"
          v-loading="tableLoading"
          class="table-surface monday-table-surface"
        >
          <div
            class="monday-table-wrapper"
            @dragstart.capture="onTableDragStart"
            @dragover.prevent="onTableDragOver"
            @drop.prevent="onTableDrop"
            @dragend="tableDragging = undefined; tableDropIndex = undefined"
          >
            <el-table
              ref="tableRef"
              :data="tableItems"
              :row-class-name="tableRowClassName"
              row-key="id"
              class="monday-table"
              :style="{ minWidth: `${tableMinWidth}px` }"
              empty-text="当前项目暂无工作项"
              border
              @header-dragend="onHeaderDragEnd"
            >
              <el-table-column
                label="工作项名称"
                :width="columnWidths.title"
                resizable
              >
                <template #default="scope">
                  <div class="title-cell">
                    <button
                      class="work-item-link"
                      @click="openDetail(scope.row as ProjectWorkItemListItem, 'details')"
                    >
                      <span class="work-item-title-text">{{ (scope.row as ProjectWorkItemListItem).title }}</span>
                      <small class="work-item-code-text">{{ (scope.row as ProjectWorkItemListItem).itemNo }}</small>
                    </button>
                    <button
                      class="monday-discussion-btn"
                      aria-label="打开协作讨论"
                      title="打开协作讨论"
                      @click.stop="openDetail(scope.row as ProjectWorkItemListItem, 'discussion')"
                    >
                      <svg
                        width="17"
                        height="17"
                        viewBox="0 0 24 24"
                        fill="none"
                        class="discussion-bubble-icon"
                      >
                        <path
                          d="M12 21C16.9706 21 21 16.9706 21 12C21 7.02944 16.9706 3 12 3C7.02944 3 3 7.02944 3 12C3 13.8214 3.54139 15.5165 4.4741 16.9366L3.25 21L7.54583 19.8665C8.89531 20.5902 10.4079 21 12 21Z"
                          stroke="currentColor"
                          stroke-width="1.6"
                          stroke-linecap="round"
                          stroke-linejoin="round"
                        />
                        <path
                          d="M12 8.5V15.5M8.5 12H15.5"
                          stroke="currentColor"
                          stroke-width="1.6"
                          stroke-linecap="round"
                        />
                      </svg>
                    </button>
                  </div>
                </template>
              </el-table-column>

              <el-table-column
                v-if="!hiddenColumns.has('assignee')"
                label="处理人"
                :width="columnWidths.assignee"
                align="center"
                resizable
              >
                <template #default="scope">
                  <el-popover placement="bottom" :width="360" trigger="click" popper-class="work-items-popover" @show="assigneeSearch = ''">
                    <template #reference>
                      <button class="cell-editor-trigger monday-cell-centered" :disabled="Boolean(editingCell)">
                        <yp-assignee :user-id="(scope.row as ProjectWorkItemListItem).assigneeUserId" :display-name="(scope.row as ProjectWorkItemListItem).assigneeDisplayName" :show-name="false" size="table" />
                      </button>
                    </template>
                    <div class="popover-stack">
                      <el-input v-model="assigneeSearch" autofocus clearable placeholder="搜索项目成员" />
                      <button class="popover-option" @click="patchCell(scope.row as ProjectWorkItemListItem, 'assignee', null)"><span class="empty-avatar">—</span><span>清空处理人</span></button>
                      <button v-for="member in filteredMembers" :key="member.userId" class="popover-option" @click="patchCell(scope.row as ProjectWorkItemListItem, 'assignee', member.userId)">
                        <yp-assignee :user-id="member.userId" :display-name="member.displayName" />
                      </button>
                    </div>
                  </el-popover>
                </template>
              </el-table-column>

              <el-table-column
                v-if="!hiddenColumns.has('status')"
                label="状态"
                :width="columnWidths.status"
                align="center"
                class-name="monday-block-column"
                resizable
              >
                <template #default="scope">
                  <el-popover placement="bottom" :width="220" trigger="click" popper-class="work-items-popover status-popover">
                    <template #reference>
                      <button class="monday-status-cell status-chip cell-editor-trigger" :class="`monday-status-cell--${getStatusTone((scope.row as ProjectWorkItemListItem).statusCode)}`" :disabled="Boolean(editingCell)">
                        <span>{{ statusLabel((scope.row as ProjectWorkItemListItem).statusCode) }}</span>
                      </button>
                    </template>
                    <div class="label-options">
                      <button
                        v-for="status in workflowStatuses"
                        :key="status.statusCode"
                        class="status-option"
                        :class="`monday-status-cell--${getStatusTone(status.statusCode)}`"
                        :disabled="status.statusCode !== (scope.row as ProjectWorkItemListItem).statusCode && !transitionFor(scope.row as ProjectWorkItemListItem, status.statusCode)"
                        @click="transitionItem(scope.row as ProjectWorkItemListItem, status.statusCode)"
                      >{{ status.displayName }}</button>
                    </div>
                  </el-popover>
                </template>
              </el-table-column>

              <el-table-column
                v-if="!hiddenColumns.has('priority')"
                label="优先级"
                :width="columnWidths.priority"
                align="center"
                class-name="monday-block-column"
                resizable
              >
                <template #default="scope">
                  <el-popover placement="bottom" :width="220" trigger="click" popper-class="work-items-popover priority-popover">
                    <template #reference>
                      <button class="monday-priority-cell cell-editor-trigger" :class="`monday-priority-cell--${getPriorityPresentation((scope.row as ProjectWorkItemListItem).priority).tone}`" :disabled="Boolean(editingCell)">
                        <span>{{ getPriorityPresentation((scope.row as ProjectWorkItemListItem).priority).label }}</span>
                      </button>
                    </template>
                    <div class="label-options">
                      <button v-for="priority in priorityOptions" :key="priority" class="priority-option" :class="`monday-priority-cell--${getPriorityPresentation(priority).tone}`" @click="patchCell(scope.row as ProjectWorkItemListItem, 'priority', priority)">{{ getPriorityPresentation(priority).label }}</button>
                      <button class="priority-option monday-priority-cell--empty" @click="patchCell(scope.row as ProjectWorkItemListItem, 'priority', null)">清空</button>
                    </div>
                  </el-popover>
                </template>
              </el-table-column>

              <el-table-column
                v-if="!hiddenColumns.has('content')"
                label="工作项类别"
                :width="columnWidths.content"
                align="center"
                resizable
              >
                <template #default="scope">
                  <span class="monday-content-label">{{ (scope.row as ProjectWorkItemListItem).contentName }}</span>
                </template>
              </el-table-column>

              <el-table-column
                v-if="!hiddenColumns.has('dueDate')"
                label="截止日期"
                :width="columnWidths.dueDate"
                align="center"
                resizable
              >
                <template #default="scope">
                  <el-popover placement="bottom" :width="300" trigger="click" popper-class="work-items-popover date-popover">
                    <template #reference><button class="monday-due-date-cell cell-editor-trigger" :disabled="Boolean(editingCell)">
                    <span
                      v-if="isOverdue((scope.row as ProjectWorkItemListItem).dueDate, (scope.row as ProjectWorkItemListItem).statusCode)"
                      class="monday-overdue-badge"
                      title="已超出截止时间"
                    >
                      <svg
                        width="15"
                        height="15"
                        viewBox="0 0 16 16"
                        fill="none"
                      >
                        <circle
                          cx="8"
                          cy="8"
                          r="7"
                          class="overdue-circle"
                        />
                        <path
                          d="M8 4.2V8.5M8 11.2V11.8"
                          class="overdue-exclamation"
                          stroke-width="1.6"
                          stroke-linecap="round"
                        />
                      </svg>
                    </span>
                    <span
                      class="due-date-text"
                      :class="{ 'due-date-text--overdue': isOverdue((scope.row as ProjectWorkItemListItem).dueDate, (scope.row as ProjectWorkItemListItem).statusCode) }"
                    >
                      {{ formatDate((scope.row as ProjectWorkItemListItem).dueDate) }}
                    </span>
                    </button></template>
                    <div class="date-editor">
                      <div><el-button @click="patchCell(scope.row as ProjectWorkItemListItem, 'dueDate', new Date())">Today</el-button><el-button text @click="patchCell(scope.row as ProjectWorkItemListItem, 'dueDate', null)">清空</el-button></div>
                      <el-date-picker :model-value="(scope.row as ProjectWorkItemListItem).dueDate" type="date" placeholder="选择截止日期" @change="onDueDateChange(scope.row as ProjectWorkItemListItem, $event)" />
                    </div>
                  </el-popover>
                </template>
              </el-table-column>

              <el-table-column
                v-if="!hiddenColumns.has('updatedAt')"
                label="最后更新时间"
                :width="columnWidths.updatedAt"
                align="center"
                resizable
              >
                <template #default="scope">
                  <span class="monday-timestamp">{{ formatTime((scope.row as ProjectWorkItemListItem).updatedAt) }}</span>
                </template>
              </el-table-column>
            </el-table>

            <div
              v-if="quickOpen"
              ref="quickRow"
              class="quick-row monday-quick-row"
              :style="quickGridStyle"
            >
              <el-input
                ref="quickTitleInput"
                v-model="quickTitle"
                class="quick-title-field"
                maxlength="300"
                :disabled="quickCreating"
                placeholder="输入工作项名称；Enter 创建，Shift+Enter 创建后继续"
                @keydown="onQuickKeydown"
              />
              <el-select
                v-model="quickContentId"
                class="quick-content-field"
                :style="{ gridColumn: quickContentColumn }"
                :disabled="quickCreating"
                filterable
                placeholder="选择工作项类别"
              >
                <el-option
                  v-for="item in activeContents"
                  :key="item.id"
                  :label="item.name"
                  :value="item.id"
                />
              </el-select>
              <el-button
                class="quick-submit"
                :style="{ gridColumn: quickSubmitColumn }"
                type="primary"
                :loading="quickCreating"
                :disabled="!quickContentId || !quickTitle.trim()"
                @click="createQuick(false)"
              >
                添加
              </el-button>
            </div>
            <button
              v-else
              class="quick-add monday-quick-add"
              :disabled="!canCreate"
              @click="openQuick"
            >
              <el-icon><plus /></el-icon>
              <span>添加工作项</span>
            </button>
            <div ref="tableSentinel" class="cursor-sentinel" aria-hidden="true" />
            <div v-if="tableLoading && tableItems.length" class="incremental-state">正在加载更多工作项…</div>
            <div v-else-if="loadingMoreError" class="incremental-state incremental-state--error">
              <span>加载更多失败</span><el-button text @click="loadTable(tableNextCursor, true)">重试</el-button>
            </div>
            <div v-else-if="!tableNextCursor && tableItems.length" class="incremental-state">已加载全部工作项</div>
          </div>
        </div>

        <div
          v-else
          class="kanban-board"
        >
          <section
            v-for="status in workflowStatuses"
            :key="status.statusCode"
            v-loading="lane(status.statusCode).loading"
            class="kanban-lane"
            @dragover.prevent
            @drop.prevent="dropInto(status.statusCode)"
          >
            <header><strong>{{ status.displayName }}</strong><span>{{ lane(status.statusCode).items.length }}{{ lane(status.statusCode).nextCursor ? '+' : '' }}</span></header>
            <inline-problem
              v-if="lane(status.statusCode).error"
              :problem="lane(status.statusCode).error!"
            />
            <button
              v-for="item in lane(status.statusCode).items"
              :key="item.id"
              class="kanban-card"
              draggable="true"
              @dragstart="dragging = item"
              @dragend="dragging = undefined"
              @click="openDetail(item, 'details')"
            >
              <small>{{ contentName(item.contentId) }} · {{ item.itemNo }}</small>
              <strong>{{ item.title }}</strong>
              <span><yp-priority-badge :priority="item.priority" /></span>
            </button>
            <div
              v-if="lane(status.statusCode).nextCursor"
              class="lane-cursor-sentinel incremental-state"
              :data-status="status.statusCode"
            >{{ lane(status.statusCode).loading ? '正在加载…' : '继续滚动以加载更多' }}</div>
          </section>
        </div>
      </section>
    </template>

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
          <div class="detail-heading">
            <small>{{ contentName(detail.contentId) }} · {{ detail.itemNo }}</small>
            <h2>{{ detail.title }}</h2>
          </div>
          <work-item-detail-panel
            v-model="detailTab"
            :work-item-id="detail.id"
            :members="activeMembers"
            :can-publish="canPublishDiscussion"
            :read-only-reason="discussionReadOnlyReason"
          >
            <template #details>
              <dl class="detail-list">
                <div><dt>状态</dt><dd>{{ statusLabel(detail.statusCode) }}</dd></div>
                <div><dt>优先级</dt><dd><yp-priority-badge :priority="detail.priority" /></dd></div>
                <div>
                  <dt>处理人</dt><dd>
                    <yp-assignee
                      :user-id="detail.assigneeUserId"
                      :display-name="detail.assigneeDisplayName"
                    />
                  </dd>
                </div>
                <div><dt>截止日期</dt><dd>{{ formatDate(detail.dueDate) }}</dd></div>
                <div><dt>最后更新时间</dt><dd>{{ formatTime(detail.updatedAt) }}</dd></div>
              </dl>
              <p
                v-if="detail.description"
                class="detail-copy"
              >
                {{ detail.description }}
              </p>
              <lazy-attachment-panel
                :owner-type="AttachmentOwnerType.WorkItem"
                :owner-id="detail.id"
                :can-upload="detail.capabilities.canEditFields"
              />
            </template>
          </work-item-detail-panel>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.work-items-home {
  min-width: 0;
  max-width: 100%;
  border: 0;
  border-radius: var(--yp-radius-md);
  background: transparent;
}

.monday-view-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--yp-space-4);
  border-bottom: 1px solid var(--yp-border-subtle);
}

.project-view-stack,
.table-surface {
  min-width: 0;
  max-width: 100%;
}

.work-items-toolbar {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: var(--yp-space-2);
  margin: calc(-1 * var(--yp-space-1)) 0 var(--yp-space-4);
  overflow-x: auto;
}

.toolbar-button {
  display: inline-flex;
  height: 36px;
  flex: 0 0 auto;
  align-items: center;
  gap: 7px;
  padding: 0 var(--yp-space-3);
  border: 0;
  border-radius: var(--yp-radius-sm);
  color: var(--yp-text-primary);
  background: transparent;
  font: inherit;
  cursor: pointer;
  transition: background var(--yp-motion-fast) var(--yp-ease-standard), color var(--yp-motion-fast) var(--yp-ease-standard);
}

.toolbar-button:hover:not(:disabled), .toolbar-button.active { background: var(--yp-bg-hover); color: var(--yp-action-primary); }
.toolbar-button:disabled { color: var(--yp-text-disabled); cursor: not-allowed; }
.toolbar-count { min-width: 18px; padding: 1px 5px; border-radius: var(--yp-radius-pill); color: var(--yp-priority-foreground); background: var(--yp-action-primary); font-size: 11px; }
.toolbar-search { width: 94px; flex: 0 0 auto; transition: width 100ms cubic-bezier(0, 0, .35, 1); }
.toolbar-search > .toolbar-button { width: 100%; }
.toolbar-search--expanded { width: min(360px, 40vw); }

.popover-stack, .sort-popover, .filter-popover, .date-editor { display: grid; gap: var(--yp-space-3); }
.popover-option, .filter-value, .text-button, .popover-add {
  display: flex; align-items: center; gap: var(--yp-space-2); width: 100%; padding: 7px 8px; border: 0;
  border-radius: var(--yp-radius-sm); color: var(--yp-text-primary); background: transparent; text-align: left; cursor: pointer;
}
.popover-option:hover, .filter-value:hover, .popover-add:hover { background: var(--yp-bg-hover); }
.option-count, .filter-value small { margin-left: auto; color: var(--yp-text-muted); }
.empty-avatar { display: inline-grid; width: 28px; height: 28px; place-items: center; border: 1px dashed var(--yp-border-default); border-radius: 50%; }
.filter-popover > header, .sort-popover > header { display: flex; align-items: center; justify-content: space-between; }
.text-button { width: auto; color: var(--yp-action-primary); }
.filter-columns { display: grid; grid-template-columns: repeat(4, minmax(120px, 1fr)); gap: var(--yp-space-4); overflow-x: auto; }
.filter-columns section { min-width: 0; }
.filter-columns h4 { margin: 0 0 var(--yp-space-2); color: var(--yp-text-secondary); }
.filter-dates { display: flex; gap: var(--yp-space-3); padding-top: var(--yp-space-3); border-top: 1px solid var(--yp-border-subtle); }
.sort-rule { display: grid; grid-template-columns: 1fr 130px; gap: var(--yp-space-2); }
.column-option { display: flex; align-items: center; gap: var(--yp-space-2); padding: 5px 0; }

:global(.work-items-popover.el-zoom-in-top-enter-active),
:global(.work-items-popover.el-zoom-in-top-leave-active) {
  transition: opacity 100ms var(--yp-ease-standard), transform 100ms var(--yp-ease-standard);
}

:global(.work-items-popover.el-zoom-in-top-enter-from),
:global(.work-items-popover.el-zoom-in-top-leave-to) {
  opacity: 0;
  transform: translateY(-4px) scale(.98);
}

.view-tabs {
  display: flex;
  gap: var(--yp-space-2);
}

.view-tabs button {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px var(--yp-space-4);
  border: 0;
  border-bottom: 2px solid transparent;
  color: var(--yp-text-secondary);
  background: transparent;
  font-family: var(--yp-font-family);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: color var(--yp-motion-fast) var(--yp-ease-standard), border-color var(--yp-motion-fast) var(--yp-ease-standard);
}

.view-tabs button:hover {
  color: var(--yp-text-primary);
}

.view-tabs button.active {
  border-bottom-color: var(--yp-action-primary);
  color: var(--yp-action-primary);
  font-weight: 600;
}

.tab-icon {
  flex: 0 0 14px;
  opacity: 0.85;
}

.monday-table-wrapper {
  width: 100%;
  max-width: 100%;
  border: 1px solid var(--yp-border-subtle);
  border-left: 0;
  border-radius: 0 var(--yp-radius-md) var(--yp-radius-md) 0;
  background: var(--yp-bg-surface);
  box-shadow: 0 1px 3px color-mix(in srgb, var(--yp-text-primary) 3%, transparent);
  overflow-x: auto;
  overflow-y: hidden;
  overscroll-behavior-x: contain;
}

.table-surface {
  min-height: 240px;
}

/* Monday Table 核心样式与网格微调 */
:deep(.monday-table.el-table) {
  --el-table-border-color: var(--yp-border-subtle);
  --el-table-header-bg-color: var(--yp-monday-header-bg);
  --el-table-header-text-color: var(--yp-text-secondary);
  --el-table-row-hover-bg-color: var(--yp-bg-sunken);
  --el-table-tr-bg-color: var(--yp-bg-surface);
  color: var(--yp-text-primary);
  font-size: 13px;
  width: max-content;
  max-width: none;
  border-left: 5px solid color-mix(in srgb, var(--yp-action-primary) 55%, var(--yp-bg-surface));
}

:deep(.monday-table .el-table__header th.el-table__cell) {
  height: 38px;
  padding: 0;
  border-right: 1px solid var(--yp-monday-grid-border);
  border-bottom: 1px solid var(--yp-monday-grid-border);
  background: var(--yp-monday-header-bg);
  font-size: 13px;
  font-weight: 500;
  color: var(--yp-text-secondary);
}

:deep(.monday-table .el-table__header th.el-table__cell:last-child) {
  border-right: 0;
}

:deep(.monday-table .el-table__body td.el-table__cell) {
  height: 40px;
  padding: 0;
  border-right: 1px solid var(--yp-monday-grid-border);
  border-bottom: 1px solid var(--yp-monday-grid-border);
}

:deep(.monday-table .el-table__body td.el-table__cell:last-child) {
  border-right: 0;
}

:deep(.monday-table .el-table__cell > .cell) {
  padding: 0 var(--yp-space-3);
  line-height: 1.4;
}

:deep(.monday-table td.monday-block-column > .cell) {
  padding: 0;
  height: 40px;
}

.title-cell {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--yp-space-2);
}

.work-item-link {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
  padding: 0;
  border: 0;
  color: var(--yp-text-primary);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: color var(--yp-motion-fast) var(--yp-ease-standard);
}

.work-item-link:hover .work-item-title-text {
  color: var(--yp-action-primary);
}

.work-item-title-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
  font-size: 13.5px;
}

.work-item-code-text {
  color: var(--yp-text-muted);
  font-size: 11px;
}

/* Monday 讨论气泡按钮 */
.monday-discussion-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  padding: 0;
  border: 0;
  border-radius: var(--yp-radius-pill);
  color: var(--yp-text-muted);
  background: transparent;
  cursor: pointer;
  transition: color var(--yp-motion-fast) var(--yp-ease-standard),
              background var(--yp-motion-fast) var(--yp-ease-standard),
              transform var(--yp-motion-fast) var(--yp-ease-standard);
}

.monday-discussion-btn:hover {
  color: var(--yp-action-primary);
  background: var(--yp-bg-selected);
  transform: scale(1.05);
}

.discussion-bubble-icon {
  width: 17px;
  height: 17px;
}

.monday-cell-centered {
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 状态色块与优先级色块基础样式 */
.monday-status-cell,
.monday-priority-cell {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  min-height: 40px;
  padding: 0 var(--yp-space-2);
  font-size: 13px;
  font-weight: 600;
  text-align: center;
  user-select: none;
  cursor: pointer;
  box-sizing: border-box;
  overflow: hidden;
  transition: filter var(--yp-motion-fast) var(--yp-ease-standard);
}

.monday-status-cell { border: 0; font-family: inherit; }

.monday-status-cell:hover,
.monday-priority-cell:hover {
  filter: brightness(0.95);
}

/* 纸质向内翻折角 (沿折痕线轴对称翻折到左下方的深色三角，直角朝向内侧) */
.monday-status-cell::before,
.monday-priority-cell::before {
  content: '';
  position: absolute;
  top: 0;
  right: 0;
  width: 10px;
  height: 10px;
  background: color-mix(in srgb, var(--yp-text-primary) 30%, transparent);
  clip-path: polygon(0 0, 0 100%, 100% 100%);
  filter: drop-shadow(-1px 1px 1px color-mix(in srgb, var(--yp-text-primary) 24%, transparent));
  z-index: 2;
  pointer-events: none;
  opacity: 0;
  transform: translate(2px, -2px) scale(0.85);
  transform-origin: top right;
  transition: opacity 200ms var(--yp-ease-standard), transform 200ms var(--yp-ease-standard);
}

/* 翻折后露出的右上角空白缺角 (沿折痕线轴对称的右上半区背景色三角) */
.monday-status-cell::after,
.monday-priority-cell::after {
  content: '';
  position: absolute;
  top: 0;
  right: 0;
  width: 10px;
  height: 10px;
  background: var(--yp-bg-surface);
  clip-path: polygon(0 0, 100% 0, 100% 100%);
  z-index: 3;
  pointer-events: none;
  opacity: 0;
  transform: translate(2px, -2px) scale(0.85);
  transform-origin: top right;
  transition: opacity 200ms var(--yp-ease-standard), transform 200ms var(--yp-ease-standard);
}

/* 鼠标悬停延时触发并放慢展开动画 */
.monday-status-cell:hover::before,
.monday-priority-cell:hover::before,
.monday-status-cell:hover::after,
.monday-priority-cell:hover::after {
  opacity: 1;
  transform: translate(0, 0) scale(1);
  transition: opacity 320ms cubic-bezier(0.2, 0, 0, 1) 140ms, transform 320ms cubic-bezier(0.2, 0, 0, 1) 140ms;
}

.monday-status-cell--green { background: var(--yp-status-green); color: var(--yp-status-green-foreground); }
.monday-status-cell--yellow { background: var(--yp-status-orange); color: var(--yp-status-orange-foreground); }
.monday-status-cell--red { background: var(--yp-status-red); color: var(--yp-status-red-foreground); }
.monday-status-cell--blue { background: var(--yp-status-blue); color: var(--yp-status-blue-foreground); }
.monday-status-cell--gray { background: var(--yp-status-gray); color: var(--yp-status-gray-foreground); }
.monday-status-cell--neutral { background: var(--yp-border-strong); color: var(--yp-text-primary); }

.monday-priority-cell--urgent { background: var(--yp-priority-urgent); color: var(--yp-priority-foreground); }
.monday-priority-cell--high { background: var(--yp-priority-high); color: var(--yp-priority-foreground); }
.monday-priority-cell--medium { background: var(--yp-priority-medium); color: var(--yp-priority-foreground); }
.monday-priority-cell--low { background: var(--yp-priority-low); color: var(--yp-priority-foreground); }
.monday-priority-cell--empty { background: var(--yp-priority-empty); color: var(--yp-priority-empty-foreground); }

.monday-content-label {
  color: var(--yp-text-secondary);
  font-size: 13px;
}

/* 截止日期与超期感叹号警告 */
.monday-due-date-cell {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
}

.monday-overdue-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.overdue-circle {
  fill: var(--yp-status-red);
}

.overdue-exclamation {
  stroke: var(--yp-text-inverse);
}

.due-date-text {
  font-size: 13px;
  color: var(--yp-text-primary);
}

.due-date-text--overdue {
  color: var(--yp-status-red);
  font-weight: 500;
}

.monday-timestamp {
  font-size: 12.5px;
  color: var(--yp-text-muted);
}

/* 快速新增 */
.monday-quick-add {
  display: flex;
  width: 100%;
  align-items: center;
  gap: var(--yp-space-2);
  height: 38px;
  padding: 0 var(--yp-space-4);
  border: 0;
  border-top: 1px solid var(--yp-border-subtle);
  border-left: 5px solid color-mix(in srgb, var(--yp-action-primary) 30%, var(--yp-bg-surface));
  color: var(--yp-text-secondary);
  background: transparent;
  font-size: 13px;
  cursor: pointer;
  transition: color var(--yp-motion-fast) var(--yp-ease-standard), background var(--yp-motion-fast) var(--yp-ease-standard);
}

.monday-quick-add:hover:not(:disabled) {
  color: var(--yp-action-primary);
  background: var(--yp-bg-hover);
}

.monday-quick-add:disabled {
  cursor: not-allowed;
  opacity: .55;
}

.monday-quick-row {
  display: grid;
  min-width: max-content;
  gap: var(--yp-space-2);
  padding: var(--yp-space-2) var(--yp-space-3);
  border-top: 1px solid var(--yp-border-subtle);
  border-left: 5px solid color-mix(in srgb, var(--yp-action-primary) 30%, var(--yp-bg-surface));
  background: var(--yp-bg-sunken);
}

.cell-editor-trigger { padding: 0; border: 0; font: inherit; cursor: pointer; }
.cell-editor-trigger:not(.monday-status-cell):not(.monday-priority-cell) { color: inherit; background: transparent; }
.cell-editor-trigger:disabled { cursor: wait; }
.label-options { display: grid; gap: var(--yp-space-2); }
.status-option, .priority-option { min-height: 38px; padding: 6px 12px; border: 0; border-radius: 2px; font: inherit; cursor: pointer; transition: transform var(--yp-motion-fast) var(--yp-ease-standard), filter var(--yp-motion-fast) var(--yp-ease-standard); }
.status-option:hover:not(:disabled), .priority-option:hover { filter: brightness(.94); transform: translateY(-1px); }
.status-option:disabled { opacity: .4; cursor: not-allowed; }
.date-editor { padding: var(--yp-space-1); }
.cursor-sentinel { height: 1px; }
.incremental-state { display: flex; min-height: 36px; align-items: center; justify-content: center; gap: var(--yp-space-2); color: var(--yp-text-muted); font-size: 12px; }
.incremental-state--error { color: var(--yp-status-red); }
:deep(.monday-table .el-table__body tr[draggable='true']) { cursor: grab; transition: transform var(--yp-motion-fast) var(--yp-ease-standard), box-shadow var(--yp-motion-fast) var(--yp-ease-standard); }
:deep(.monday-table .el-table__body tr[draggable='true']:active) { cursor: grabbing; }
:deep(.monday-table .work-item-table-row--drop-before td) { box-shadow: inset 0 2px 0 var(--yp-action-primary); }
:deep(.monday-table .work-item-table-row--drop-after td) { box-shadow: inset 0 -2px 0 var(--yp-action-primary); }
.work-item-drag-ghost { position: fixed; top: -10000px; left: -10000px; z-index: 9999; opacity: .9; pointer-events: none; box-shadow: var(--yp-shadow-lg); }

.quick-title-field { grid-column: 1; }
.quick-content-field { grid-column: 5; }
.quick-submit { grid-column: 7; justify-self: center; height: 32px; }

.table-pagination {
  justify-content: flex-end;
  padding: var(--yp-space-4) 0;
}

.kanban-board {
  display: grid;
  grid-auto-columns: minmax(280px, 1fr);
  grid-auto-flow: column;
  gap: var(--yp-space-4);
  overflow-x: auto;
  padding: var(--yp-space-4) 0;
  background: transparent;
}

.kanban-lane {
  min-height: 320px;
  padding: var(--yp-space-3);
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-surface);
}

.kanban-lane header {
  display: flex;
  justify-content: space-between;
  padding: var(--yp-space-2);
}

.kanban-lane header span {
  color: var(--yp-text-muted);
}

.kanban-card {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: var(--yp-space-2);
  margin-top: var(--yp-space-3);
  padding: var(--yp-space-4);
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
  color: var(--yp-text-primary);
  background: var(--yp-bg-surface);
  box-shadow: var(--yp-shadow-card);
  text-align: left;
  cursor: grab;
}

.kanban-card:hover {
  border-color: var(--yp-border-strong);
}

.detail-heading h2 {
  margin: var(--yp-space-1) 0 var(--yp-space-4);
}

.detail-heading small {
  color: var(--yp-text-muted);
}

.detail-list {
  display: grid;
  gap: var(--yp-space-3);
  margin: 0;
}

.detail-list div {
  display: grid;
  grid-template-columns: 120px 1fr;
  gap: var(--yp-space-3);
}

.detail-list dt {
  color: var(--yp-text-muted);
}

.detail-list dd {
  margin: 0;
}

.detail-copy {
  margin-top: var(--yp-space-5);
  white-space: pre-wrap;
}

@media (max-width: 720px) {
  .monday-quick-row {
    grid-template-columns: 1fr;
    min-width: 0;
  }
  .quick-title-field, .quick-content-field, .quick-submit {
    grid-column: 1;
  }
  .quick-submit {
    justify-self: stretch;
  }
}
</style>
