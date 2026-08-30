<script setup lang="ts">
import { Filter as FilterIcon, Hide, Search, Sort, User } from '@element-plus/icons-vue'
import {
  ContentStatus,
  AttachmentOwnerType,
  ContentViewType,
  ProjectActorAccess,
  ProjectLifecycle,
  ProjectMembershipStatus,
  ProjectMembershipStatusFilter,
  ListProjectWorkItemFilterOptionsFieldEnum,
  readCsrfToken,
  type ProjectContentCatalog,
  type ProjectDetail,
  type ProjectMember,
  type WorkItemDetail,
  type ProjectWorkItemListItem,
  type WorkItemTransitionOption,
  type WorkItemLabelCatalog,
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
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch, type CSSProperties, type DefineComponent } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { contentsApi, projectsApi, workItemsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import WorkItemDetailPanel from '../../components/collaboration/WorkItemDetailPanel.vue'
import LazyAttachmentPanel from '../../components/collaboration/LazyAttachmentPanel.vue'
import MondayColumnQuickSort from '../../components/projects/MondayColumnQuickSort.vue'
import ProjectWorkItemSubitemsTable, {
  type ProjectWorkItemSubitemSortRule,
} from '../../components/projects/ProjectWorkItemSubitemsTable.vue'
import ProjectWorkspaceHeader from '../../components/projects/ProjectWorkspaceHeader.vue'
import WorkItemLabelPopoverContent from '../../components/projects/WorkItemLabelPopoverContent.vue'
import { workItemLabelColorValue } from '../../components/projects/workItemLabelColors'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpPriorityBadge from '../../components/yp/YpPriorityBadge.vue'

type ProjectView = 'table' | 'kanban'

interface KanbanLane {
  items: ProjectWorkItemListItem[]
  nextCursor: string | null
  loading: boolean
  error?: ApiProblem
}

interface SubitemState {
  items: ProjectWorkItemListItem[]
  loading: boolean
  loaded: boolean
  error?: ApiProblem
  sortRules: ProjectWorkItemSubitemSortRule[]
}

interface LabelPopoverContentHandle {
  resetEditor: () => void
}

const route = useRoute()
const router = useRouter()
const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent
const projectId = computed(() => String(route.params.projectId))
const selectedView = computed<ProjectView>(() => route.query.view === 'kanban' ? 'kanban' : 'table')
const project = ref<ProjectDetail>()
const catalog = ref<ProjectContentCatalog>()
const labelCatalog = ref<WorkItemLabelCatalog>()
const members = ref<ProjectMember[]>([])
const tableItems = ref<ProjectWorkItemListItem[]>([])
const tableNextCursor = ref<string | null>(null)
const loading = ref(false)
const tableLoading = ref(false)
const tableSorting = ref(false)
const error = ref<ApiProblem>()
const vLoading = ElLoading.directive
const lanes = reactive<Record<string, KanbanLane>>({})
const subitems = reactive<Record<string, SubitemState>>({})
const expandedSubitemIds = ref<string[]>([])
const subitemSelections = reactive<Record<string, Set<string>>>({})
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
const tableDraggingIndex = ref<number>(-1)
const tableDropIndex = ref<number>()
const columnDraggingKey = ref<MovableColumnKey>()
const columnDraggingIndex = ref(-1)
const columnDropIndex = ref<number>()
const columnResizingKey = ref<MovableColumnKey>()
const selectedWorkItemIds = ref(new Set<string>())
const tableRef = ref<{
  $el: HTMLElement
  doLayout: () => void
  toggleRowExpansion: (row: ProjectWorkItemListItem, expanded?: boolean) => void
}>()
const tableSentinel = ref<HTMLElement>()
const horizontalPageScrollbar = ref<HTMLElement>()
const verticalPageScrollbar = ref<HTMLElement>()
const horizontalOverflow = ref(false)
const horizontalScrollExtent = ref(1)
const verticalScrollExtent = ref(1)
const pageScrollbarLeft = ref(0)
let tableDragPreview: HTMLElement | undefined
let tableColumnDragPreview: HTMLElement | undefined
let tableScrollElement: HTMLElement | undefined
let pageScrollbarSyncQueued = false
let responsiveTableLayoutFrame: number | undefined
let projectPageResizeObserver: ResizeObserver | undefined
let tableDragPointerOffset = { x: 0, y: 0 }
let tableColumnDragPointerOffset = { x: 0, y: 0 }
let tablePointerCandidate: {
  pointerId: number
  row: HTMLElement
  item: ProjectWorkItemListItem
  index: number
  startX: number
  startY: number
} | undefined
let tableColumnPointerCandidate: {
  pointerId: number
  key: MovableColumnKey
  header: HTMLTableCellElement
  headerRects: Array<{ left: number; width: number }>
  index: number
  startX: number
  startY: number
} | undefined
let tableColumnResizeCandidate: {
  pointerId: number
  key: MovableColumnKey
  minWidth: number
  startWidth: number
  startX: number
} | undefined
let suppressTableClick = false
let suppressTableClickTimer: number | undefined
const loadingMoreError = ref<ApiProblem>()
const editingCell = ref('')
const assigneeSearch = ref('')
const assigneeMatches = ref<ProjectMember[]>()
const filterOptionCounts = ref(new Map<string, number>())
const filterOptionsLoading = ref(false)
const labelPopoverContentRefs = new Map<string, LabelPopoverContentHandle>()
const searchExpanded = ref(Boolean(route.query.q))
const searchInput = ref(String(route.query.q ?? ''))
const selectedRowId = ref<string | undefined>(route.query.workItemId ? String(route.query.workItemId) : undefined)
const selectedCellKey = ref<string | undefined>(route.query.workItemId ? `${route.query.workItemId}:title` : undefined)
const TABLE_SELECTION_COLUMN_WIDTH = 48
const TABLE_EXPAND_COLUMN_WIDTH = 1
const TABLE_ADD_COLUMN_MIN_WIDTH = 96
const DRAWER_MIN_WIDTH = 440
const DRAWER_VIEWPORT_GUTTER = 60
const drawerWidth = ref(560)
const isResizingDrawer = ref(false)
const pageScrollbarRight = computed(() => detailOpen.value ? drawerWidth.value : 0)
const horizontalPageScrollbarStyle = computed<CSSProperties>(() => ({
  left: `${pageScrollbarLeft.value}px`,
  right: `${pageScrollbarRight.value}px`,
}))
const verticalPageScrollbarStyle = computed<CSSProperties>(() => ({
  right: `${pageScrollbarRight.value}px`,
}))

function onDrawerResizePointerDown(event: PointerEvent): void {
  event.preventDefault()
  event.stopPropagation()
  isResizingDrawer.value = true
  const startX = event.clientX
  const startWidth = drawerWidth.value

  const onPointerMove = (e: PointerEvent) => {
    const delta = startX - e.clientX
    const maxWidth = Math.max(DRAWER_MIN_WIDTH, window.innerWidth - DRAWER_VIEWPORT_GUTTER)
    const nextWidth = Math.max(DRAWER_MIN_WIDTH, Math.min(maxWidth, startWidth + delta))
    drawerWidth.value = nextWidth
  }

  const onPointerUp = () => {
    isResizingDrawer.value = false
    window.removeEventListener('pointermove', onPointerMove)
    window.removeEventListener('pointerup', onPointerUp)
    window.removeEventListener('pointercancel', onPointerUp)
  }

  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('pointercancel', onPointerUp)
}

function selectCell(rowId: string, cellKey: string): void {
  selectedRowId.value = rowId
  selectedCellKey.value = `${rowId}:${cellKey}`
}

function tableCellClassName({
  row,
  column,
}: {
  row: ProjectWorkItemListItem
  column: { columnKey?: string }
}): string {
  if (!column.columnKey || column.columnKey === 'title') return ''
  return selectedCellKey.value === `${row.id}:${column.columnKey}` ? 'monday-cell--selected' : ''
}

function syncProjectPageScrollLayout(): void {
  document.body.classList.toggle('yp-work-items-drawer-open', detailOpen.value)
  if (detailOpen.value) {
    document.body.style.setProperty('--yp-work-items-drawer-width', `${drawerWidth.value}px`)
  } else {
    document.body.style.removeProperty('--yp-work-items-drawer-width')
  }
  scheduleResponsiveTableLayout()
}

function resolveTableScrollElement(): HTMLElement | undefined {
  return tableRef.value?.$el?.querySelector<HTMLElement>('.el-scrollbar__wrap') ?? undefined
}

function syncPageScrollbarPositions(): void {
  if (!tableScrollElement) return
  const horizontal = horizontalPageScrollbar.value
  const vertical = verticalPageScrollbar.value
  if (horizontal && Math.abs(horizontal.scrollLeft - tableScrollElement.scrollLeft) > 0.5) {
    horizontal.scrollLeft = tableScrollElement.scrollLeft
  }
  if (vertical && Math.abs(vertical.scrollTop - tableScrollElement.scrollTop) > 0.5) {
    vertical.scrollTop = tableScrollElement.scrollTop
  }
}

function syncTableHeaderScrollPosition(): void {
  if (!tableScrollElement) return
  const headerWrapper = tableRef.value?.$el?.querySelector<HTMLElement>('.el-table__header-wrapper')
  if (headerWrapper && Math.abs(headerWrapper.scrollLeft - tableScrollElement.scrollLeft) > 0.5) {
    headerWrapper.scrollLeft = tableScrollElement.scrollLeft
  }
}

function syncSubitemFixedColumnScrollPosition(): void {
  tableRef.value?.$el?.style.setProperty(
    '--work-item-table-scroll-left',
    `${tableScrollElement?.scrollLeft ?? 0}px`,
  )
}

function onTableScroll(): void {
  syncSubitemFixedColumnScrollPosition()
  syncTableHeaderScrollPosition()
  syncPageScrollbarPositions()
}

function bindTableScrollElement(next: HTMLElement | undefined): void {
  if (tableScrollElement === next) return
  tableScrollElement?.removeEventListener('scroll', onTableScroll)
  tableScrollElement = next
  tableScrollElement?.addEventListener('scroll', onTableScroll, { passive: true })
  syncSubitemFixedColumnScrollPosition()
}

function syncPageScrollbars(): void {
  bindTableScrollElement(resolveTableScrollElement())

  const contextNavigation = document.querySelector<HTMLElement>('.app-shell--workspace .context-navigation')
  const appMain = document.querySelector<HTMLElement>('.app-shell--workspace .app-main')
  const contextRect = contextNavigation?.getBoundingClientRect()
  const appMainRect = appMain?.getBoundingClientRect()
  const contextVisible = Boolean(contextNavigation && contextRect && contextRect.width > 0
    && getComputedStyle(contextNavigation).display !== 'none')
  pageScrollbarLeft.value = Math.max(0, Math.round(contextVisible ? contextRect!.right : (appMainRect?.left ?? 0)))

  const horizontal = horizontalPageScrollbar.value
  const vertical = verticalPageScrollbar.value
  if (!tableScrollElement || !horizontal || !vertical) return

  const nextHorizontalOverflow = tableScrollElement.scrollWidth - tableScrollElement.clientWidth > 1
  const horizontalOverflowChanged = horizontalOverflow.value !== nextHorizontalOverflow
  horizontalOverflow.value = nextHorizontalOverflow

  horizontalScrollExtent.value = Math.max(
    horizontal.clientWidth,
    horizontal.clientWidth + tableScrollElement.scrollWidth - tableScrollElement.clientWidth,
  )
  verticalScrollExtent.value = Math.max(
    vertical.clientHeight,
    vertical.clientHeight + tableScrollElement.scrollHeight - tableScrollElement.clientHeight,
  )
  void nextTick(syncPageScrollbarPositions)
  if (horizontalOverflowChanged) void nextTick(schedulePageScrollbarSync)
}

function schedulePageScrollbarSync(): void {
  if (pageScrollbarSyncQueued) return
  pageScrollbarSyncQueued = true
  void nextTick(() => {
    pageScrollbarSyncQueued = false
    syncPageScrollbars()
  })
}

function syncResponsiveTableLayout(): void {
  tableRef.value?.doLayout()
  schedulePageScrollbarSync()
}

function scheduleResponsiveTableLayout(): void {
  if (responsiveTableLayoutFrame !== undefined) return
  responsiveTableLayoutFrame = window.requestAnimationFrame(() => {
    responsiveTableLayoutFrame = undefined
    syncResponsiveTableLayout()
  })
}

function flushResponsiveTableLayout(): void {
  if (responsiveTableLayoutFrame !== undefined) {
    window.cancelAnimationFrame(responsiveTableLayoutFrame)
    responsiveTableLayoutFrame = undefined
  }
  syncResponsiveTableLayout()
}

function observeProjectPageResizeTargets(): void {
  projectPageResizeObserver?.disconnect()
  if (typeof ResizeObserver === 'undefined') return
  projectPageResizeObserver = new ResizeObserver(scheduleResponsiveTableLayout)
  const appMain = document.querySelector<HTMLElement>('.app-shell--workspace .app-main')
  if (appMain) projectPageResizeObserver.observe(appMain)
  if (tableRef.value?.$el) projectPageResizeObserver.observe(tableRef.value.$el)
}

function onHorizontalPageScroll(): void {
  if (!tableScrollElement || !horizontalPageScrollbar.value) return
  if (Math.abs(tableScrollElement.scrollLeft - horizontalPageScrollbar.value.scrollLeft) > 0.5) {
    tableScrollElement.scrollLeft = horizontalPageScrollbar.value.scrollLeft
  }
  syncSubitemFixedColumnScrollPosition()
  syncTableHeaderScrollPosition()
}

function onVerticalPageScroll(): void {
  if (!tableScrollElement || !verticalPageScrollbar.value) return
  if (Math.abs(tableScrollElement.scrollTop - verticalPageScrollbar.value.scrollTop) > 0.5) {
    tableScrollElement.scrollTop = verticalPageScrollbar.value.scrollTop
  }
}
const filters = reactive({
  assignees: new Set<string>(), statuses: new Set<string>(), priorities: new Set<string>(),
  contents: new Set<string>(), dueRange: [] as Date[], updatedAfter: null as Date | null,
})
interface SortRule { field: string; direction: 'ASC' | 'DESC' }
const sortRules = ref<SortRule[]>([])
const savingSortOrder = ref(false)
type ColumnKey = 'title' | 'assignee' | 'status' | 'priority' | 'content' | 'dueDate' | 'updatedAt'
type MovableColumnKey = Exclude<ColumnKey, 'title'>
const columns: Array<{ key: ColumnKey; label: string; defaultWidth: number; minWidth: number }> = [
  { key: 'title', label: '工作项名称', defaultWidth: 320, minWidth: 220 },
  { key: 'assignee', label: '处理人', defaultWidth: 90, minWidth: 72 },
  { key: 'status', label: '状态', defaultWidth: 130, minWidth: 96 },
  { key: 'priority', label: '优先级', defaultWidth: 120, minWidth: 90 },
  { key: 'content', label: '工作项类别', defaultWidth: 150, minWidth: 110 },
  { key: 'dueDate', label: '截止日期', defaultWidth: 140, minWidth: 112 },
  { key: 'updatedAt', label: '最后更新时间', defaultWidth: 170, minWidth: 135 },
]
const sortFieldByColumn: Record<ColumnKey, string> = {
  title: 'TITLE',
  assignee: 'ASSIGNEE',
  status: 'STATUS',
  priority: 'PRIORITY',
  content: 'CONTENT',
  dueDate: 'DUE_DATE',
  updatedAt: 'UPDATED_AT',
}
const columnByKey = new Map(columns.map(column => [column.key, column]))
const defaultMovableColumnOrder = columns.filter(column => column.key !== 'title').map(column => column.key as MovableColumnKey)
const movableColumnOrder = ref<MovableColumnKey[]>([...defaultMovableColumnOrder])
const subitemMovableColumnOrder = ref<MovableColumnKey[]>([...defaultMovableColumnOrder])
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
const orderedColumns = computed(() => [
  columnByKey.get('title')!,
  ...movableColumnOrder.value.map(key => columnByKey.get(key)!),
])
const orderedSubitemColumns = computed(() => [
  columnByKey.get('title')!,
  ...subitemMovableColumnOrder.value.map(key => columnByKey.get(key)!),
])
const visibleColumns = computed(() => orderedColumns.value.filter(item => item.key === 'title' || !hiddenColumns.value.has(item.key)))
const visibleSubitemColumns = computed(() => orderedSubitemColumns.value.filter(item => item.key === 'title' || !hiddenColumns.value.has(item.key)))
const movableVisibleColumns = computed(() => visibleColumns.value.filter(item => item.key !== 'title'))
const tableColumnStructureKey = computed(() => movableColumnOrder.value.join('|'))
const quickGridStyle = computed(() => ({
  gridTemplateColumns: [`${TABLE_EXPAND_COLUMN_WIDTH}px`, `${TABLE_SELECTION_COLUMN_WIDTH}px`,
    ...visibleColumns.value.map(item => `${columnWidths[item.key]}px`), `${TABLE_ADD_COLUMN_MIN_WIDTH}px`].join(' '),
}))
const quickContentColumn = computed(() => Math.max(4, visibleColumns.value.findIndex(item => item.key === 'content') + 3))
const quickSubmitColumn = computed(() => visibleColumns.value.length + 3)
const hasExplicitSort = computed(() => sortRules.value.length > 0)
const filteredMembers = computed(() => {
  const query = assigneeSearch.value.trim().toLocaleLowerCase()
  return query
    ? (assigneeMatches.value ?? activeMembers.value.filter(item => item.displayName.toLocaleLowerCase().includes(query)))
    : activeMembers.value
})

const contentsById = computed(() => new Map((catalog.value?.items ?? []).map(item => [item.id, item])))
const activeContents = computed(() => (catalog.value?.items ?? []).filter(item => item.status === ContentStatus.Active))
const workflowStatuses = computed(() => [...(labelCatalog.value?.statuses ?? [])]
  .sort((left, right) => left.sortOrder - right.sortOrder)
  .map(status => ({ ...status, statusCode: status.code })))
const priorityOptions = computed(() => [...(labelCatalog.value?.priorities ?? [])]
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

function subitemState(parentId: string): SubitemState {
  if (!subitems[parentId]) subitems[parentId] = {
    items: [], loading: false, loaded: false, sortRules: [],
  }
  return subitems[parentId]
}

async function loadSubitems(parentId: string, force = false): Promise<void> {
  const state = subitemState(parentId)
  if (state.loading || (state.loaded && !force)) return
  state.loading = true
  delete state.error
  try {
    const result = await workItemsApi.listWorkItemSubitems({
      parentWorkItemId: parentId,
      ...(state.sortRules.length
        ? { sort: state.sortRules.map(rule => `${rule.field},${rule.direction}`) }
        : {}),
    })
    state.items = result.items
    state.loaded = true
  } catch (reason) {
    state.error = await toApiProblem(reason)
  } finally {
    state.loading = false
    schedulePageScrollbarSync()
  }
}

function onTableExpandChange(row: ProjectWorkItemListItem,
  expanded: ProjectWorkItemListItem[] | boolean): void {
  const expandedRows = Array.isArray(expanded) ? expanded : expanded
    ? [...tableItems.value.filter(item => expandedSubitemIds.value.includes(item.id)), row]
    : tableItems.value.filter(item => item.id !== row.id
      && expandedSubitemIds.value.includes(item.id))
  expandedSubitemIds.value = [...new Set(expandedRows.map(item => item.id))]
  if (expandedRows.some(item => item.id === row.id)) void loadSubitems(row.id)
  schedulePageScrollbarSync()
}

function toggleSubitems(row: ProjectWorkItemListItem): void {
  tableRef.value?.toggleRowExpansion(row, !expandedSubitemIds.value.includes(row.id))
}

function onSubitemSortChange(parentId: string, rules: ProjectWorkItemSubitemSortRule[]): void {
  const state = subitemState(parentId)
  state.sortRules = rules.slice(0, 3)
  state.loaded = false
  void loadSubitems(parentId, true)
}

function bumpSubitemCount(parentId: string, delta: number): void {
  const apply = (item: ProjectWorkItemListItem): ProjectWorkItemListItem => item.id === parentId
    ? { ...item, subitemCount: Math.max(0, item.subitemCount + delta) }
    : item
  tableItems.value = tableItems.value.map(apply)
  Object.values(lanes).forEach(state => { state.items = state.items.map(apply) })
}

function onSubitemCreated(parent: ProjectWorkItemListItem): void {
  bumpSubitemCount(parent.id, 1)
  const state = subitemState(parent.id)
  state.loaded = false
  void loadSubitems(parent.id, true)
  ElMessage.success('子项已创建')
}

function onSubitemSelectionChange(parentId: string, rows: ProjectWorkItemListItem[]): void {
  subitemSelections[parentId] = new Set(rows.map(row => row.id))
}

function moveSubitemColumn(source: string, target: string, placement: 'before' | 'after' = 'before'): void {
  if (source === 'title' || target === 'title' || source === target) return
  const sourceKey = source as MovableColumnKey
  const targetKey = target as MovableColumnKey
  const next = subitemMovableColumnOrder.value.filter(key => key !== sourceKey)
  const targetIndex = next.indexOf(targetKey)
  if (targetIndex < 0) return
  next.splice(targetIndex + (placement === 'after' ? 1 : 0), 0, sourceKey)
  subitemMovableColumnOrder.value = next
  persistTablePrefs()
}

function contentName(contentId: string): string {
  return contentsById.value.get(contentId)?.name ?? '未知 Content'
}

function statusLabel(statusCode: string): string {
  return workflowStatuses.value.find(item => item.statusCode === statusCode)?.displayName ?? statusCode
}

function labelCellStyle(colorToken?: string): CSSProperties {
  return {
    ...(colorToken ? { backgroundColor: workItemLabelColorValue(colorToken) } : {}),
    color: 'var(--yp-text-inverse)',
  }
}

function getStatusCellStyle(statusCode: string): CSSProperties {
  return labelCellStyle(workflowStatuses.value.find(item => item.statusCode === statusCode)?.colorToken)
}

function getPriorityCellStyle(priority: string | null): CSSProperties {
  return labelCellStyle(priorityOptions.value.find(item => item.code === priority)?.colorToken)
}

function getStatusTone(statusCode: string): string {
  const option = workflowStatuses.value.find(item => item.statusCode === statusCode)
  const colorTone: Record<string, string> = {
    GREEN: 'green', TEAL: 'green', BLUE: 'blue', INDIGO: 'blue', PURPLE: 'blue',
    MAGENTA: 'red', RED: 'red', ORANGE: 'yellow', AMBER: 'yellow', LIME: 'green',
    CYAN: 'blue', GRAY: 'gray',
  }
  if (option?.colorToken) return colorTone[option.colorToken] ?? 'gray'
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
  const option = priorityOptions.value.find(item => item.code === priority)
  const tokenTone: Record<string, string> = {
    RED: 'urgent', MAGENTA: 'urgent', ORANGE: 'high', AMBER: 'high',
    GREEN: 'low', LIME: 'low', TEAL: 'medium', CYAN: 'medium', BLUE: 'low',
    INDIGO: 'medium', PURPLE: 'medium', GRAY: 'empty',
  }
  if (option) return { label: option.displayName, tone: tokenTone[option.colorToken] ?? 'empty' }
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
  if (option?.statusCategory === 'DONE' || option?.statusCategory === 'CANCELED') {
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
  filters.priorities = new Set(queryValues('priority'))
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

function routeQuerySignature(includeSort: boolean): string {
  return JSON.stringify(Object.fromEntries(Object.entries(route.query)
    .filter(([key]) => key !== 'workItemId' && (includeSort || key !== 'sort'))
    .sort(([left], [right]) => left.localeCompare(right))))
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
  else if (set === filters.priorities) filters.priorities = next as Set<string>
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
  if (tableLoading.value || tableSorting.value) return
  tableLoading.value = true
  loadingMoreError.value = undefined
  try {
    const result = await workItemsApi.listProjectWorkItems(listRequest(cursor), { signal: activeController?.signal ?? null })
    if (revision !== loadRevision) return
    const merged = append ? [...tableItems.value, ...result.items] : result.items
    tableItems.value = [...new Map(merged.map(item => [item.id, item])).values()]
    tableNextCursor.value = result.nextCursor
    await nextTick()
  } catch (reason) {
    if (revision === loadRevision) {
      const problem = await toApiProblem(reason)
      if (append) loadingMoreError.value = problem; else error.value = problem
    }
  } finally {
    if (revision === loadRevision) tableLoading.value = false
  }
}

async function reloadSortedTableInPlace(): Promise<void> {
  const revision = ++loadRevision
  activeController?.abort()
  const controller = new AbortController()
  activeController = controller
  tableLoading.value = false
  tableSorting.value = true
  loadingMoreError.value = undefined
  error.value = undefined
  const minimumItemCount = Math.max(1, tableItems.value.length)
  const loaded = new Map<string, ProjectWorkItemListItem>()
  let cursor: string | null = null
  let nextCursor: string | null = null

  try {
    while (true) {
      const result = await workItemsApi.listProjectWorkItems(
        listRequest(cursor),
        { signal: controller.signal },
      )
      if (revision !== loadRevision) return
      result.items.forEach(item => loaded.set(item.id, item))
      nextCursor = result.nextCursor
      if (!nextCursor || loaded.size >= minimumItemCount || nextCursor === cursor) break
      cursor = nextCursor
    }

    tableItems.value = [...loaded.values()]
    tableNextCursor.value = nextCursor
    await nextTick()
  } catch (reason) {
    if (revision === loadRevision && !(reason instanceof DOMException && reason.name === 'AbortError')) {
      error.value = await toApiProblem(reason)
    }
  } finally {
    if (revision === loadRevision) tableSorting.value = false
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
  labelCatalog.value = undefined
  selectedWorkItemIds.value = new Set()
  tableItems.value = []
  tableNextCursor.value = null
  members.value = []
  Object.keys(lanes).forEach(key => delete lanes[key])
  closeQuick()
  try {
    const [nextProject, nextCatalog, nextLabels] = await Promise.all([
      projectsApi.getProject({ projectId: requestedProjectId }),
      contentsApi.listProjectContents({ projectId: requestedProjectId }),
      workItemsApi.getProjectWorkItemLabels({ projectId: requestedProjectId }),
    ])
    if (revision !== loadRevision) return
    project.value = nextProject
    catalog.value = nextCatalog
    labelCatalog.value = nextLabels
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

async function loadDetail(workItemId: string, tab: 'details' | 'discussion' = 'details'): Promise<void> {
  detailOpen.value = true
  detailTab.value = tab
  detailLoading.value = true
  detail.value = undefined
  try {
    detail.value = await workItemsApi.getWorkItem({ workItemId })
  } catch (reason) {
    error.value = await toApiProblem(reason)
    detailOpen.value = false
    await closeDetailRoute()
  } finally {
    detailLoading.value = false
  }
}

async function openDetail(item: ProjectWorkItemListItem, tab: 'details' | 'discussion'): Promise<void> {
  selectCell(item.id, tab === 'details' ? 'title' : 'discussion')
  detailTab.value = tab
  if (String(route.query.workItemId ?? '') === item.id) {
    await loadDetail(item.id, tab)
    return
  }
  await router.push({ query: { ...route.query, workItemId: item.id } })
}

async function closeDetailRoute(): Promise<void> {
  if (!route.query.workItemId) return
  const next = { ...route.query }
  delete next.workItemId
  await router.push({ query: next })
}

function onDetailModelValue(value: boolean): void {
  detailOpen.value = value
  if (!value) void closeDetailRoute()
}

function onLabelsUpdated(next: WorkItemLabelCatalog): void {
  labelCatalog.value = next
}

function labelPopoverKey(itemId: string, kind: 'status' | 'priority'): string {
  return `${itemId}:${kind}`
}

function setLabelPopoverContentRef(key: string, value: unknown): void {
  const instance = value as LabelPopoverContentHandle | null
  if (instance?.resetEditor) labelPopoverContentRefs.set(key, instance)
  else labelPopoverContentRefs.delete(key)
}

function resetLabelPopoverContent(itemId: string, kind: 'status' | 'priority'): void {
  labelPopoverContentRefs.get(labelPopoverKey(itemId, kind))?.resetEditor()
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

function replaceLightItem(id: string, updatedDetail: WorkItemDetail): void {
  const apply = (item: ProjectWorkItemListItem): ProjectWorkItemListItem => item.id !== id ? item : {
    ...item, statusCode: updatedDetail.statusCode, statusCategory: updatedDetail.statusCategory,
    priority: updatedDetail.priority,
    assigneeUserId: updatedDetail.assigneeUserId,
    assigneeDisplayName: updatedDetail.assigneeDisplayName,
    dueDate: updatedDetail.dueDate, updatedAt: updatedDetail.updatedAt,
    rowVersion: updatedDetail.rowVersion, etag: updatedDetail.etag,
    capabilities: updatedDetail.capabilities,
  }
  tableItems.value = tableItems.value.map(apply)
  Object.values(lanes).forEach(state => { state.items = state.items.map(apply) })
  Object.values(subitems).forEach(state => { state.items = state.items.map(apply) })
  if (detail.value?.id === id) detail.value = { ...detail.value, ...updatedDetail }
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
        ? await workItemsApi.patchWorkItemPriority({ ...common, workItemPriorityPatchRequest: { priority: value as string | null } })
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

function sortDirectionForColumn(key: ColumnKey): 'ASC' | 'DESC' | undefined {
  return sortRules.value.find(rule => rule.field === sortFieldByColumn[key])?.direction
}

function applyColumnQuickSort(key: ColumnKey): void {
  if (savingSortOrder.value) return
  const field = sortFieldByColumn[key]
  const index = sortRules.value.findIndex(rule => rule.field === field)
  const next = sortRules.value.map(rule => ({ ...rule }))
  if (index >= 0) {
    const current = next[index]!
    next[index] = { ...current, direction: current.direction === 'ASC' ? 'DESC' : 'ASC' }
  } else if (next.length < 3) {
    next.push({ field, direction: 'ASC' })
  } else {
    next[next.length - 1] = { field, direction: 'ASC' }
  }
  sortRules.value = next
  void syncUrl()
}

function clearColumnSort(key: ColumnKey): void {
  if (savingSortOrder.value) return
  const field = sortFieldByColumn[key]
  sortRules.value = sortRules.value.filter(rule => rule.field !== field)
  void syncUrl()
}

function clearAllSorts(): void {
  if (savingSortOrder.value) return
  sortRules.value = []
  void syncUrl()
}

async function loadAllSortedWorkItems(): Promise<boolean> {
  while (tableNextCursor.value) {
    const cursor = tableNextCursor.value
    await loadTable(cursor, true)
    if (loadingMoreError.value || tableNextCursor.value === cursor) return false
  }
  return true
}

async function saveSortedWorkItemOrder(): Promise<void> {
  if (savingSortOrder.value || !sortRules.value.length) return
  if (tableLoading.value || tableSorting.value) {
    ElMessage.info('排序结果仍在加载，请稍后再保存工作项顺序')
    return
  }
  savingSortOrder.value = true
  try {
    if (!await loadAllSortedWorkItems()) {
      ElMessage.error('无法加载完整排序结果，暂未保存工作项顺序')
      return
    }
    const ordered = [...tableItems.value]
    if (ordered.some(item => !item.capabilities.canMoveInProjectOrder)) {
      ElMessage.warning('当前结果中包含不可调整顺序的工作项')
      return
    }
    const csrf = readCsrfToken()
    if (!csrf) {
      error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
      return
    }
    for (let index = 1; index < ordered.length; index += 1) {
      const item = tableItems.value.find(candidate => candidate.id === ordered[index]!.id) ?? ordered[index]!
      const updated = await workItemsApi.moveProjectWorkItemOrder({
        projectId: projectId.value,
        workItemId: item.id,
        xXSRFTOKEN: csrf,
        ifMatch: item.etag,
        idempotencyKey: globalThis.crypto.randomUUID(),
        projectWorkItemOrderMoveRequest: {
          previousVisibleWorkItemId: ordered[index - 1]!.id,
          nextVisibleWorkItemId: null,
        },
      })
      replaceLightItem(item.id, updated)
    }
    sortRules.value = []
    await syncUrl()
    ElMessage.success(`已保存 ${ordered.length} 个工作项的当前顺序`)
  } catch (reason) {
    error.value = await toApiProblem(reason)
    await loadTable(null, false)
  } finally {
    savingSortOrder.value = false
  }
}

function persistTablePrefs(): void {
  localStorage.setItem(tablePrefsKey.value, JSON.stringify({
    version: TABLE_PREFS_VERSION,
    widths: columnWidths,
    hidden: [...hiddenColumns.value],
    order: movableColumnOrder.value,
    subitemOrder: subitemMovableColumnOrder.value,
  }))
}

function loadTablePrefs(): void {
  try {
    const parsed = JSON.parse(localStorage.getItem(tablePrefsKey.value) ?? '{}') as {
      version?: number
      widths?: Partial<Record<ColumnKey, number>>
      hidden?: ColumnKey[]
      order?: ColumnKey[]
      subitemOrder?: ColumnKey[]
    }
    if (parsed.version !== TABLE_PREFS_VERSION) return
    columns.forEach(column => {
      const value = parsed.widths?.[column.key]
      if (typeof value === 'number') columnWidths[column.key] = Math.max(column.minWidth, value)
    })
    hiddenColumns.value = new Set((parsed.hidden ?? []).filter(key => key !== 'title'))
    const savedOrder = (parsed.order ?? []).filter((key): key is MovableColumnKey => key !== 'title' && defaultMovableColumnOrder.includes(key as MovableColumnKey))
    movableColumnOrder.value = [
      ...new Set(savedOrder),
      ...defaultMovableColumnOrder.filter(key => !savedOrder.includes(key)),
    ]
    const savedSubitemOrder = (parsed.subitemOrder ?? []).filter((key): key is MovableColumnKey => key !== 'title' && defaultMovableColumnOrder.includes(key as MovableColumnKey))
    subitemMovableColumnOrder.value = [
      ...new Set(savedSubitemOrder),
      ...defaultMovableColumnOrder.filter(key => !savedSubitemOrder.includes(key)),
    ]
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

const TABLE_ROW_HEIGHT = 36
const TABLE_DRAG_TILT_DEGREES = 1
const TABLE_DRAG_POINTER_THRESHOLD = 5
const TABLE_COLUMN_RESIZE_HANDLE_WIDTH = 8

function removeTableDragPreview(): void {
  tableDragPreview?.remove()
  tableDragPreview = undefined
  tableDragPointerOffset = { x: 0, y: 0 }
}

function moveTableDragPreview(clientX: number, clientY: number): void {
  if (!tableDragPreview || (clientX === 0 && clientY === 0)) return
  tableDragPreview.style.left = `${Math.round(clientX - tableDragPointerOffset.x)}px`
  tableDragPreview.style.top = `${Math.round(clientY - tableDragPointerOffset.y)}px`
}

function createTableDragPreview(source: HTMLElement, clientX: number, clientY: number): HTMLElement {
  removeTableDragPreview()
  const rect = source.getBoundingClientRect()
  const width = Math.max(rect.width, source.offsetWidth, 1)
  const height = Math.max(rect.height, source.offsetHeight, TABLE_ROW_HEIGHT)
  tableDragPointerOffset = {
    x: Math.min(Math.max(clientX - rect.left, 0), width),
    y: Math.min(Math.max(clientY - rect.top, 0), height),
  }

  const preview = document.createElement('div')
  preview.className = 'work-item-drag-preview'
  preview.setAttribute('aria-hidden', 'true')
  preview.style.width = `${width}px`
  preview.style.height = `${height}px`
  preview.style.transform = `rotate(${TABLE_DRAG_TILT_DEGREES}deg)`
  preview.style.transformOrigin = `${tableDragPointerOffset.x}px ${tableDragPointerOffset.y}px`

  const previewTable = document.createElement('table')
  previewTable.className = 'work-item-drag-preview__table'
  previewTable.style.width = `${width}px`
  const previewBody = document.createElement('tbody')
  const previewRow = source.cloneNode(true) as HTMLTableRowElement
  previewRow.classList.remove('work-item-table-row--dragging')
  previewRow.classList.add('work-item-drag-preview__row')
  previewRow.removeAttribute('draggable')
  previewRow.removeAttribute('style')
  previewRow.querySelectorAll('[id]').forEach(element => element.removeAttribute('id'))
  previewRow.querySelectorAll<HTMLElement>('button,input,select,textarea,[tabindex]').forEach(element => {
    element.tabIndex = -1
  })

  const sourceCells = source.querySelectorAll('td')
  const previewCells = previewRow.querySelectorAll<HTMLElement>('td')
  sourceCells.forEach((cell, index) => {
    const previewCell = previewCells[index]
    if (!previewCell) return
    const cellWidth = cell.getBoundingClientRect().width
    previewCell.style.width = `${cellWidth}px`
    previewCell.style.minWidth = `${cellWidth}px`
    previewCell.style.maxWidth = `${cellWidth}px`
    previewCell.style.boxSizing = 'border-box'
  })

  previewBody.appendChild(previewRow)
  previewTable.appendChild(previewBody)
  preview.appendChild(previewTable)
  document.body.appendChild(preview)
  tableDragPreview = preview
  moveTableDragPreview(clientX, clientY)
  return preview
}

function resetTableDragState(): void {
  removeTableDragPreview()
  tableDragging.value = undefined
  tableDraggingIndex.value = -1
  tableDropIndex.value = undefined
}

function removeTableColumnDragPreview(): void {
  tableColumnDragPreview?.remove()
  tableColumnDragPreview = undefined
  tableColumnDragPointerOffset = { x: 0, y: 0 }
}

function moveTableColumnDragPreview(clientX: number, clientY: number): void {
  if (!tableColumnDragPreview || (clientX === 0 && clientY === 0)) return
  tableColumnDragPreview.style.left = `${Math.round(clientX - tableColumnDragPointerOffset.x)}px`
  tableColumnDragPreview.style.top = `${Math.round(clientY - tableColumnDragPointerOffset.y)}px`
}

function sanitizeColumnDragPreview(root: HTMLElement): void {
  root.removeAttribute('id')
  root.removeAttribute('style')
  root.querySelectorAll('[id]').forEach(element => element.removeAttribute('id'))
  root.querySelectorAll<HTMLElement>('button,input,select,textarea,[tabindex]').forEach(element => {
    element.tabIndex = -1
  })
}

function createTableColumnDragPreview(header: HTMLTableCellElement, clientX: number, clientY: number): HTMLElement {
  removeTableColumnDragPreview()
  const headerRect = header.getBoundingClientRect()
  const width = Math.max(headerRect.width, header.offsetWidth, 1)
  const tableRect = tableRef.value?.$el?.getBoundingClientRect()
  const height = Math.max(headerRect.height, Math.min(tableRect?.height ?? 420, window.innerHeight - 24))
  tableColumnDragPointerOffset = {
    x: Math.min(Math.max(clientX - headerRect.left, 0), width),
    y: Math.min(Math.max(clientY - headerRect.top, 0), height),
  }

  const preview = document.createElement('div')
  preview.className = 'work-item-column-drag-preview'
  preview.setAttribute('aria-hidden', 'true')
  preview.style.width = `${width}px`
  preview.style.height = `${height}px`
  preview.style.transform = `rotate(${TABLE_DRAG_TILT_DEGREES}deg)`
  preview.style.transformOrigin = `${tableColumnDragPointerOffset.x}px ${tableColumnDragPointerOffset.y}px`

  const previewTable = document.createElement('table')
  previewTable.className = 'work-item-column-drag-preview__table'
  previewTable.style.width = `${width}px`
  const previewHead = document.createElement('thead')
  const previewHeadRow = document.createElement('tr')
  const previewHeader = header.cloneNode(true) as HTMLTableCellElement
  sanitizeColumnDragPreview(previewHeader)
  previewHeader.classList.add('work-item-column-drag-preview__header')
  previewHeader.style.width = `${width}px`
  previewHeadRow.appendChild(previewHeader)
  previewHead.appendChild(previewHeadRow)
  previewTable.appendChild(previewHead)

  const headerIndex = header.parentElement ? [...header.parentElement.children].indexOf(header) : -1
  const previewBody = document.createElement('tbody')
  const sourceRows = tableRef.value?.$el?.querySelectorAll<HTMLTableRowElement>(
    '.el-table__body-wrapper tbody tr.work-item-table-row',
  ) ?? []
  sourceRows.forEach(sourceRow => {
    const sourceCell = sourceRow.children[headerIndex]
    if (!(sourceCell instanceof HTMLTableCellElement)) return
    const previewRow = document.createElement('tr')
    const previewCell = sourceCell.cloneNode(true) as HTMLTableCellElement
    sanitizeColumnDragPreview(previewCell)
    previewCell.classList.add('work-item-column-drag-preview__cell')
    previewCell.style.width = `${width}px`
    previewRow.appendChild(previewCell)
    previewBody.appendChild(previewRow)
  })
  previewTable.appendChild(previewBody)
  preview.appendChild(previewTable)
  document.body.appendChild(preview)
  tableColumnDragPreview = preview
  moveTableColumnDragPreview(clientX, clientY)
  return preview
}

function resetTableColumnDragState(): void {
  removeTableColumnDragPreview()
  columnDraggingKey.value = undefined
  columnDraggingIndex.value = -1
  columnDropIndex.value = undefined
}

function tableColumnDragStyle(columnKey?: string): CSSProperties {
  const draggedKey = columnDraggingKey.value
  const dropIndex = columnDropIndex.value
  const from = columnDraggingIndex.value
  if (!draggedKey || dropIndex === undefined || from < 0 || !columnKey || columnKey === 'title') return {}
  const index = movableVisibleColumns.value.findIndex(column => column.key === columnKey)
  if (index < 0) return {}
  if (columnKey === draggedKey) return { opacity: 0, pointerEvents: 'none' }

  const draggedWidth = columnWidths[draggedKey]
  if (from < dropIndex && index > from && index < dropIndex) {
    return { transform: `translateX(-${draggedWidth}px)` }
  }
  if (from > dropIndex && index >= dropIndex && index < from) {
    return { transform: `translateX(${draggedWidth}px)` }
  }
  return { transform: 'translateX(0px)' }
}

function tableCellStyle({ column }: { column: { columnKey?: string } }): CSSProperties {
  return tableColumnDragStyle(column.columnKey)
}

function tableHeaderCellStyle({ column }: { column: { columnKey?: string } }): CSSProperties {
  return tableColumnDragStyle(column.columnKey)
}

function movableHeaderCells(): HTMLTableCellElement[] {
  return [...(tableRef.value?.$el?.querySelectorAll<HTMLTableCellElement>('.el-table__header-wrapper th.monday-movable-column-header') ?? [])]
}

function updateTableColumnDropTarget(clientX: number): void {
  const headerRects = tableColumnPointerCandidate?.headerRects ?? movableHeaderCells().map(header => {
    const rect = header.getBoundingClientRect()
    return { left: rect.left, width: rect.width }
  })
  let target = headerRects.findIndex(rect => clientX < rect.left + rect.width / 2)
  if (target < 0) target = headerRects.length
  columnDropIndex.value = target
}

function clearTableColumnPointerTracking(): void {
  tableColumnPointerCandidate = undefined
  window.removeEventListener('pointermove', onTableColumnPointerMove, true)
  window.removeEventListener('pointerup', onTableColumnPointerUp, true)
  window.removeEventListener('pointercancel', onTableColumnPointerCancel, true)
}

function clearTableColumnResizeTracking(): void {
  tableColumnResizeCandidate = undefined
  columnResizingKey.value = undefined
  window.removeEventListener('pointermove', onTableColumnResizePointerMove, true)
  window.removeEventListener('pointerup', onTableColumnResizePointerUp, true)
  window.removeEventListener('pointercancel', onTableColumnResizePointerCancel, true)
  document.body.style.removeProperty('cursor')
  document.body.style.removeProperty('user-select')
}

function applyTableColumnResize(clientX: number): void {
  const candidate = tableColumnResizeCandidate
  if (!candidate) return
  const nextWidth = Math.max(
    candidate.minWidth,
    Math.round(candidate.startWidth + clientX - candidate.startX),
  )
  if (columnWidths[candidate.key] === nextWidth) return
  columnWidths[candidate.key] = nextWidth
  scheduleResponsiveTableLayout()
}

function onTableColumnResizePointerDown(event: PointerEvent, handle: HTMLElement): void {
  const columnKey = handle.dataset.columnKey as ColumnKey | undefined
  const config = columnKey ? columnByKey.get(columnKey) : undefined
  const header = handle.closest<HTMLTableCellElement>('th.monday-movable-column-header')
  if (!columnKey || !config || columnKey === 'title' || !header) return
  const key = columnKey as MovableColumnKey

  clearTableColumnPointerTracking()
  clearTableColumnResizeTracking()
  const renderedWidth = header.getBoundingClientRect().width
  tableColumnResizeCandidate = {
    pointerId: event.pointerId,
    key,
    minWidth: config.minWidth,
    startWidth: renderedWidth > 0 ? renderedWidth : columnWidths[key],
    startX: event.clientX,
  }
  columnResizingKey.value = key
  event.preventDefault()
  event.stopPropagation()
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  window.addEventListener('pointermove', onTableColumnResizePointerMove, { capture: true, passive: false })
  window.addEventListener('pointerup', onTableColumnResizePointerUp, true)
  window.addEventListener('pointercancel', onTableColumnResizePointerCancel, true)
}

function onTableColumnResizePointerMove(event: PointerEvent): void {
  if (!tableColumnResizeCandidate || tableColumnResizeCandidate.pointerId !== event.pointerId) return
  event.preventDefault()
  event.stopPropagation()
  applyTableColumnResize(event.clientX)
}

function onTableColumnResizePointerUp(event: PointerEvent): void {
  if (!tableColumnResizeCandidate || tableColumnResizeCandidate.pointerId !== event.pointerId) return
  event.preventDefault()
  event.stopPropagation()
  applyTableColumnResize(event.clientX)
  clearTableColumnResizeTracking()
  void nextTick(flushResponsiveTableLayout)
  persistTablePrefs()
  suppressClickAfterTableDrag()
}

function onTableColumnResizePointerCancel(event: PointerEvent): void {
  if (!tableColumnResizeCandidate || tableColumnResizeCandidate.pointerId !== event.pointerId) return
  clearTableColumnResizeTracking()
  void nextTick(flushResponsiveTableLayout)
  persistTablePrefs()
  suppressClickAfterTableDrag()
}

function onTableColumnPointerDown(event: PointerEvent): void {
  if (!event.isPrimary || event.button !== 0 || tableDragging.value || columnDraggingKey.value || columnResizingKey.value) return
  const target = event.target as HTMLElement | null
  const resizeHandle = target?.closest<HTMLElement>('.monday-column-resize-handle')
  if (resizeHandle) {
    onTableColumnResizePointerDown(event, resizeHandle)
    return
  }
  if (target?.closest('.sort-by-column')) return
  const header = target?.closest<HTMLTableCellElement>('th.monday-movable-column-header')
  if (!header) return
  const rect = header.getBoundingClientRect()
  if (rect.width > 0 && rect.right - event.clientX < TABLE_COLUMN_RESIZE_HANDLE_WIDTH) return
  const headers = movableHeaderCells()
  const index = headers.indexOf(header)
  const key = movableVisibleColumns.value[index]?.key as MovableColumnKey | undefined
  if (!key) return

  clearTableColumnPointerTracking()
  tableColumnPointerCandidate = {
    pointerId: event.pointerId,
    key,
    header,
    headerRects: headers.map(candidate => {
      const candidateRect = candidate.getBoundingClientRect()
      return { left: candidateRect.left, width: candidateRect.width }
    }),
    index,
    startX: event.clientX,
    startY: event.clientY,
  }
  window.addEventListener('pointermove', onTableColumnPointerMove, { capture: true, passive: false })
  window.addEventListener('pointerup', onTableColumnPointerUp, true)
  window.addEventListener('pointercancel', onTableColumnPointerCancel, true)
}

function onTableColumnPointerMove(event: PointerEvent): void {
  const candidate = tableColumnPointerCandidate
  if (!candidate || candidate.pointerId !== event.pointerId) return
  if (!columnDraggingKey.value) {
    const distance = Math.hypot(event.clientX - candidate.startX, event.clientY - candidate.startY)
    if (distance < TABLE_DRAG_POINTER_THRESHOLD) return
    createTableColumnDragPreview(candidate.header, candidate.startX, candidate.startY)
    columnDraggingKey.value = candidate.key
    columnDraggingIndex.value = candidate.index
    columnDropIndex.value = candidate.index
    suppressClickAfterTableDrag()
  }
  event.preventDefault()
  moveTableColumnDragPreview(event.clientX, event.clientY)
  updateTableColumnDropTarget(event.clientX)
}

function commitTableColumnDrop(): void {
  const draggedKey = columnDraggingKey.value
  const from = columnDraggingIndex.value
  let target = columnDropIndex.value
  resetTableColumnDragState()
  if (!draggedKey || from < 0 || target === undefined) return
  const visibleKeys = movableVisibleColumns.value.map(column => column.key as MovableColumnKey)
  const currentVisibleIndex = visibleKeys.indexOf(draggedKey)
  if (currentVisibleIndex < 0) return
  const remainingVisible = visibleKeys.filter(key => key !== draggedKey)
  if (currentVisibleIndex < target) target -= 1
  target = Math.max(0, Math.min(target, remainingVisible.length))
  if (target === currentVisibleIndex) return

  const nextOrder = movableColumnOrder.value.filter(key => key !== draggedKey)
  const beforeKey = remainingVisible[target]
  if (beforeKey) {
    nextOrder.splice(nextOrder.indexOf(beforeKey), 0, draggedKey)
  } else {
    const lastVisibleKey = remainingVisible.at(-1)
    const insertionIndex = lastVisibleKey ? nextOrder.indexOf(lastVisibleKey) + 1 : nextOrder.length
    nextOrder.splice(insertionIndex, 0, draggedKey)
  }
  movableColumnOrder.value = nextOrder
  persistTablePrefs()
  schedulePageScrollbarSync()
}

function onTableColumnPointerUp(event: PointerEvent): void {
  const candidate = tableColumnPointerCandidate
  if (!candidate || candidate.pointerId !== event.pointerId) return
  const dragged = Boolean(columnDraggingKey.value)
  clearTableColumnPointerTracking()
  if (!dragged) return
  event.preventDefault()
  event.stopPropagation()
  suppressClickAfterTableDrag()
  commitTableColumnDrop()
}

function onTableColumnPointerCancel(event: PointerEvent): void {
  if (!tableColumnPointerCandidate || tableColumnPointerCandidate.pointerId !== event.pointerId) return
  const dragged = Boolean(columnDraggingKey.value)
  clearTableColumnPointerTracking()
  if (dragged) suppressClickAfterTableDrag()
  resetTableColumnDragState()
}

function onTableSurfacePointerDown(event: PointerEvent): void {
  if ((event.target as HTMLElement | null)?.closest('.subitem-table-shell')) return
  onTableColumnPointerDown(event)
  if (!tableColumnPointerCandidate && !tableColumnResizeCandidate) onTablePointerDown(event)
}

function isRowSelected(rowId: string): boolean {
  if (selectedWorkItemIds.value.has(rowId)) return true
  if (Object.values(subitemSelections).some(ids => ids.has(rowId))) return true
  if (selectedRowId.value === rowId) return true
  if (detailOpen.value && detail.value?.id === rowId) return true
  return false
}

function onTableSelectionChange(rows: ProjectWorkItemListItem[]): void {
  selectedWorkItemIds.value = new Set(rows.map(row => row.id))
}

function tableRowClassName({ row }: { row: ProjectWorkItemListItem; rowIndex: number }): string {
  const classes = ['work-item-table-row']
  if (row.capabilities.canMoveInProjectOrder) classes.push('work-item-table-row--movable')
  if (tableDragging.value?.id === row.id) classes.push('work-item-table-row--dragging')
  if (tableSorting.value) classes.push('work-item-table-row--sorting')
  if (isRowSelected(row.id)) classes.push('work-item-table-row--selected')
  return classes.join(' ')
}

function tableRowStyle({ rowIndex }: { row: ProjectWorkItemListItem; rowIndex: number }): CSSProperties {
  if (!tableDragging.value || tableDraggingIndex.value < 0 || tableDropIndex.value === undefined) {
    return {}
  }
  const from = tableDraggingIndex.value
  const to = tableDropIndex.value
  let offset = 0

  if (rowIndex === from) {
    return { opacity: 0, pointerEvents: 'none' }
  } else if (from < to && rowIndex > from && rowIndex < to) {
    offset = -TABLE_ROW_HEIGHT
  } else if (from > to && rowIndex >= to && rowIndex < from) {
    offset = TABLE_ROW_HEIGHT
  }

  if (offset === 0) {
    return { transform: 'translateY(0px)' }
  }
  return { transform: `translateY(${offset}px)` }
}

function captureTablePositions(): Map<string, number> {
  const rows = tableRef.value?.$el?.querySelectorAll(
    '.el-table__body-wrapper tbody tr.work-item-table-row',
  ) as NodeListOf<HTMLElement> | undefined
  return new Map(tableItems.value.map((item, index) => [item.id, rows?.[index]?.getBoundingClientRect().top ?? 0]))
}

function animateTableReorder(previous: Map<string, number>): void {
  const rows = tableRef.value?.$el?.querySelectorAll(
    '.el-table__body-wrapper tbody tr.work-item-table-row',
  ) as NodeListOf<HTMLElement> | undefined
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

function rowIndexFromTarget(target: EventTarget | null): number {
  const row = (target as HTMLElement | null)?.closest('tr.work-item-table-row')
  if (!row) return -1
  return [...row.parentElement!.children].indexOf(row)
}

function clearTablePointerTracking(): void {
  tablePointerCandidate = undefined
  window.removeEventListener('pointermove', onTablePointerMove, true)
  window.removeEventListener('pointerup', onTablePointerUp, true)
  window.removeEventListener('pointercancel', onTablePointerCancel, true)
}

function suppressClickAfterTableDrag(): void {
  suppressTableClick = true
  if (suppressTableClickTimer) window.clearTimeout(suppressTableClickTimer)
  suppressTableClickTimer = window.setTimeout(() => {
    suppressTableClick = false
    suppressTableClickTimer = undefined
  }, 50)
}

function onTableClickCapture(event: MouseEvent): void {
  if ((event.target as HTMLElement | null)?.closest('.subitem-table-shell')) return
  if (!suppressTableClick) return
  event.preventDefault()
  event.stopPropagation()
  suppressTableClick = false
  if (suppressTableClickTimer) window.clearTimeout(suppressTableClickTimer)
  suppressTableClickTimer = undefined
}

function updateTableDropTarget(clientY: number): void {
  const rows = [...(tableRef.value?.$el?.querySelectorAll<HTMLElement>(
    '.el-table__body-wrapper tbody tr.work-item-table-row',
  ) ?? [])]
  const hasMeasuredRows = rows.some(row => row.getBoundingClientRect().height > 0)
  if (hasMeasuredRows) {
    const target = rows.findIndex(row => clientY < row.getBoundingClientRect().top
      + row.getBoundingClientRect().height / 2)
    tableDropIndex.value = target < 0 ? tableItems.value.length : target
  } else {
    const bodyWrapper = tableRef.value?.$el?.querySelector('.el-table__body-wrapper') as HTMLElement | null
    if (bodyWrapper && bodyWrapper.getBoundingClientRect().height > 0) {
      const rect = bodyWrapper.getBoundingClientRect()
      const scrollTop = resolveTableScrollElement()?.scrollTop ?? bodyWrapper.scrollTop
      const relativeY = clientY - rect.top + scrollTop
      tableDropIndex.value = Math.max(0, Math.min(tableItems.value.length,
        Math.round(relativeY / TABLE_ROW_HEIGHT)))
    }
  }
  if (clientY >= window.innerHeight - 48) {
    window.scrollBy({ top: 28, behavior: 'smooth' })
    if (tableNextCursor.value && !tableLoading.value && !tableSorting.value) void loadTable(tableNextCursor.value, true)
  } else if (clientY <= 48) {
    window.scrollBy({ top: -28, behavior: 'smooth' })
  }
}

function onTablePointerDown(event: PointerEvent): void {
  if (!event.isPrimary || event.button !== 0 || tableDragging.value) return
  const target = event.target as HTMLElement | null
  if (target?.closest('.subitem-expand-button, .monday-subitems-counter-component')) return
  const dragArea = target?.closest('.work-item-link, .monday-selection-column')
  if (!dragArea) return
  const index = rowIndexFromTarget(target)
  const item = tableItems.value[index]
  const row = target?.closest('tr.work-item-table-row') as HTMLElement | null
  if (!row || !item?.capabilities.canMoveInProjectOrder) return

  clearTablePointerTracking()
  tablePointerCandidate = {
    pointerId: event.pointerId,
    row,
    item,
    index,
    startX: event.clientX,
    startY: event.clientY,
  }
  window.addEventListener('pointermove', onTablePointerMove, { capture: true, passive: false })
  window.addEventListener('pointerup', onTablePointerUp, true)
  window.addEventListener('pointercancel', onTablePointerCancel, true)
}

function onTablePointerMove(event: PointerEvent): void {
  const candidate = tablePointerCandidate
  if (!candidate || candidate.pointerId !== event.pointerId) return

  if (!tableDragging.value) {
    const distance = Math.hypot(event.clientX - candidate.startX, event.clientY - candidate.startY)
    if (distance < TABLE_DRAG_POINTER_THRESHOLD) return
    if (hasExplicitSort.value) {
      event.preventDefault()
      suppressClickAfterTableDrag()
      clearTablePointerTracking()
      sortRules.value = []
      void syncUrl()
      ElMessage.info('已清除排序，请在列表恢复手工顺序后再次拖动。')
      return
    }
    createTableDragPreview(candidate.row, candidate.startX, candidate.startY)
    tableDragging.value = candidate.item
    tableDraggingIndex.value = candidate.index
    tableDropIndex.value = candidate.index
    suppressClickAfterTableDrag()
  }

  event.preventDefault()
  moveTableDragPreview(event.clientX, event.clientY)
  updateTableDropTarget(event.clientY)
}

function onTablePointerUp(event: PointerEvent): void {
  const candidate = tablePointerCandidate
  if (!candidate || candidate.pointerId !== event.pointerId) return
  const dragged = Boolean(tableDragging.value)
  clearTablePointerTracking()
  if (!dragged) return
  event.preventDefault()
  event.stopPropagation()
  suppressClickAfterTableDrag()
  void commitTableDrop()
}

function onTablePointerCancel(event: PointerEvent): void {
  if (!tablePointerCandidate || tablePointerCandidate.pointerId !== event.pointerId) return
  const dragged = Boolean(tableDragging.value)
  clearTablePointerTracking()
  if (dragged) suppressClickAfterTableDrag()
  resetTableDragState()
}

async function commitTableDrop(): Promise<void> {
  const item = tableDragging.value
  let target = tableDropIndex.value
  resetTableDragState()
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
  selectedWorkItemIds.value = new Set()
  error.value = undefined; tableItems.value = []; tableNextCursor.value = null
  Object.keys(lanes).forEach(key => delete lanes[key])
  Object.keys(subitems).forEach(key => delete subitems[key])
  Object.keys(subitemSelections).forEach(key => delete subitemSelections[key])
  expandedSubitemIds.value = []
  if (selectedView.value === 'kanban') void loadKanban(revision); else void loadTable(null, false, revision)
}

function apiDate(value: string | null): Date | null {
  return value ? new Date(`${value}T00:00:00.000Z`) : null
}

function todayValue(): string {
  const today = new Date()
  const year = today.getFullYear()
  const month = String(today.getMonth() + 1).padStart(2, '0')
  const day = String(today.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function onDueDateChange(item: ProjectWorkItemListItem, value: string | null): void {
  void patchCell(item, 'dueDate', apiDate(value))
}

watch(projectId, () => { applyRouteState(); void loadWorkspace() }, { immediate: true })
watch([() => routeQuerySignature(true), () => routeQuerySignature(false)], ([, context], [, previousContext]) => {
  if (!project.value) return
  const sortOnly = selectedView.value === 'table' && context === previousContext
  applyRouteState()
  if (sortOnly) void reloadSortedTableInPlace()
  else resetCurrentData()
})
watch(() => route.query.workItemId, value => {
  const workItemId = Array.isArray(value) ? value[0] : value
  if (workItemId) {
    selectedRowId.value = String(workItemId)
    if (!selectedCellKey.value || !selectedCellKey.value.startsWith(`${workItemId}:`)) {
      selectedCellKey.value = `${workItemId}:title`
    }
    void loadDetail(String(workItemId), detailTab.value)
  } else {
    detailOpen.value = false
    detail.value = undefined
  }
}, { immediate: true })
watch(assigneeSearch, scheduleMemberSearch)
watch([detailOpen, drawerWidth], syncProjectPageScrollLayout, { flush: 'post' })
watch(tableRef, () => {
  observeProjectPageResizeTargets()
  scheduleResponsiveTableLayout()
}, { flush: 'post' })
watch([
  tableRef,
  () => selectedView.value,
  () => tableItems.value.length,
  () => quickOpen.value,
  () => tableLoading.value,
  () => loadingMoreError.value,
  () => visibleColumns.value.map(item => `${item.key}:${columnWidths[item.key]}`).join('|'),
], schedulePageScrollbarSync, { flush: 'post' })

onMounted(() => {
  document.body.classList.add('yp-project-overview-scroll')
  syncProjectPageScrollLayout()
  observeProjectPageResizeTargets()
  window.addEventListener('resize', scheduleResponsiveTableLayout)
  loadTablePrefs()
  document.addEventListener('pointerdown', onDocumentPointerDown)
  tableObserver = new IntersectionObserver(entries => {
    if (entries.some(entry => entry.isIntersecting) && tableNextCursor.value && !tableLoading.value && !tableSorting.value)
      void loadTable(tableNextCursor.value, true)
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
  if (suppressTableClickTimer) window.clearTimeout(suppressTableClickTimer)
  if (responsiveTableLayoutFrame !== undefined) window.cancelAnimationFrame(responsiveTableLayoutFrame)
  projectPageResizeObserver?.disconnect()
  tableObserver?.disconnect(); kanbanObserver?.disconnect()
  document.removeEventListener('pointerdown', onDocumentPointerDown)
  window.removeEventListener('resize', scheduleResponsiveTableLayout)
  bindTableScrollElement(undefined)
  document.body.classList.remove('yp-project-overview-scroll', 'yp-work-items-drawer-open')
  document.body.style.removeProperty('--yp-work-items-drawer-width')
  clearTablePointerTracking()
  clearTableColumnPointerTracking()
  clearTableColumnResizeTracking()
  removeTableDragPreview()
  removeTableColumnDragPreview()
})
</script>

<template>
  <div
    v-loading="loading"
    class="project-view-stack project-overview-stack"
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
                  <button v-for="priority in priorityOptions" :key="priority.code" class="filter-value" @click="toggleSet(filters.priorities, priority.code, !filters.priorities.has(priority.code))">
                    <el-checkbox :model-value="filters.priorities.has(priority.code)" @click.stop />
                    <span>{{ priority.displayName }}</span><small>{{ countBy('priority', priority.code) }}</small>
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

          <el-popover placement="bottom-start" :width="420" trigger="click" :disabled="selectedView === 'kanban' || savingSortOrder" popper-class="work-items-popover">
            <template #reference>
              <button class="toolbar-button" :disabled="selectedView === 'kanban' || savingSortOrder" :class="{ active: sortRules.length }">
                <el-icon><sort /></el-icon><span>排序<span v-if="sortRules.length"> / {{ sortRules.length }}</span></span>
              </button>
            </template>
            <div class="sort-popover">
              <header><strong>排序方式</strong><button class="text-button" @click="clearAllSorts">清除</button></header>
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
          :aria-busy="tableLoading || tableSorting"
          class="table-surface monday-table-surface"
        >
          <div
            class="monday-table-wrapper"
            @pointerdown.capture="onTableSurfacePointerDown"
            @click.capture="onTableClickCapture"
          >
            <el-table
              :key="tableColumnStructureKey"
              ref="tableRef"
              :data="tableItems"
              :fit="true"
              :expand-row-keys="expandedSubitemIds"
              :row-class-name="tableRowClassName"
              :row-style="tableRowStyle"
              :cell-class-name="tableCellClassName"
              :cell-style="tableCellStyle"
              :header-cell-style="tableHeaderCellStyle"
              row-key="id"
              class="monday-table"
              height="100%"
              empty-text="当前项目暂无工作项"
              border
              @header-dragend="onHeaderDragEnd"
              @selection-change="onTableSelectionChange"
              @expand-change="onTableExpandChange"
            >
              <el-table-column type="expand" :width="TABLE_EXPAND_COLUMN_WIDTH" fixed class-name="monday-expand-column">
                <template #default="scope">
                  <project-work-item-subitems-table
                    v-if="expandedSubitemIds.includes((scope.row as ProjectWorkItemListItem).id)"
                    :project-id="projectId"
                    :parent="scope.row as ProjectWorkItemListItem"
                    :items="subitemState((scope.row as ProjectWorkItemListItem).id).items"
                    :loading="subitemState((scope.row as ProjectWorkItemListItem).id).loading"
                    :error="subitemState((scope.row as ProjectWorkItemListItem).id).error"
                    :sort-rules="subitemState((scope.row as ProjectWorkItemListItem).id).sortRules"
                    :columns="visibleSubitemColumns"
                    :column-widths="columnWidths"
                    :active-contents="activeContents"
                    :members="members"
                    :workflow-statuses="workflowStatuses"
                    :priority-options="priorityOptions"
                    :label-catalog="labelCatalog"
                    :can-create="canCreate"
                    :editing-cell="Boolean(editingCell)"
                    @retry="loadSubitems((scope.row as ProjectWorkItemListItem).id, true)"
                    @sort-change="onSubitemSortChange((scope.row as ProjectWorkItemListItem).id, $event)"
                    @created="onSubitemCreated"
                    @updated="replaceLightItem"
                    @open-detail="openDetail"
                    @patch="patchCell"
                    @transition="transitionItem"
                    @selection-change="onSubitemSelectionChange"
                    @header-resize="onHeaderDragEnd"
                    @move-column="moveSubitemColumn"
                  />
                </template>
              </el-table-column>
              <el-table-column
                type="selection"
                :width="TABLE_SELECTION_COLUMN_WIDTH"
                fixed
                reserve-selection
                class-name="monday-selection-column"
                label-class-name="monday-selection-column"
              />
              <el-table-column
                label="工作项名称"
                column-key="title"
                :min-width="columnWidths.title"
                header-align="center"
                class-name="monday-title-column"
                label-class-name="monday-title-column monday-sortable-column-header"
                fixed
                resizable
              >
                <template #header>
                  <monday-column-quick-sort
                    label="工作项名称"
                    :direction="sortDirectionForColumn('title')"
                    :saving="savingSortOrder"
                    @sort="applyColumnQuickSort('title')"
                    @clear="clearColumnSort('title')"
                    @save="saveSortedWorkItemOrder"
                  />
                  <span
                    class="monday-column-resize-handle monday-title-column-resize-handle"
                    data-column-key="title"
                    aria-hidden="true"
                  />
                </template>
                <template #default="scope">
                  <div class="title-cell">
                    <div
                      class="work-item-link"
                      :class="{ 'monday-cell--selected': selectedCellKey === `${(scope.row as ProjectWorkItemListItem).id}:title` }"
                      tabindex="0"
                      role="button"
                      @click.stop="openDetail(scope.row as ProjectWorkItemListItem, 'details')"
                    >
                      <button
                        class="subitem-expand-button"
                        :class="{
                          'subitem-expand-button--has-subitems': (scope.row as ProjectWorkItemListItem).subitemCount > 0,
                          'subitem-expand-button--empty': (scope.row as ProjectWorkItemListItem).subitemCount === 0,
                        }"
                        type="button"
                        :aria-label="expandedSubitemIds.includes((scope.row as ProjectWorkItemListItem).id) ? '收起子项' : '展开子项'"
                        :aria-expanded="expandedSubitemIds.includes((scope.row as ProjectWorkItemListItem).id)"
                        @click.stop="toggleSubitems(scope.row as ProjectWorkItemListItem)"
                      >
                        <svg
                          viewBox="0 0 20 20"
                          fill="currentColor"
                          width="16"
                          height="16"
                          aria-hidden="true"
                          class="icon_35ca7030fb monday-expand-icon"
                          data-testid="icon"
                          data-vibe="Icon"
                        >
                          <path
                            fill="currentColor"
                            d="M12.76 10.56a.77.77 0 0 0 0-1.116L8.397 5.233a.84.84 0 0 0-1.157 0 .77.77 0 0 0 0 1.116l3.785 3.653-3.785 3.652a.77.77 0 0 0 0 1.117.84.84 0 0 0 1.157 0l4.363-4.211Z"
                          />
                        </svg>
                      </button>
                      <span class="work-item-title-text">{{ (scope.row as ProjectWorkItemListItem).title }}</span>
                      <div
                        v-if="(scope.row as ProjectWorkItemListItem).subitemCount > 0"
                        data-testid="clickable"
                        tabindex="0"
                        role="button"
                        :aria-label="`${(scope.row as ProjectWorkItemListItem).subitemCount} Subitems`"
                        :aria-expanded="expandedSubitemIds.includes((scope.row as ProjectWorkItemListItem).id)"
                        class="clickable_b3ab95e8e9 monday-subitems-counter-component name-cell-component__subitems-counter disableTextSelection_fae179dda6"
                        @click.stop="toggleSubitems(scope.row as ProjectWorkItemListItem)"
                      >
                        <div class="monday-subitems-counter-component__subitems-count">{{ (scope.row as ProjectWorkItemListItem).subitemCount }}</div>
                      </div>
                    </div>
                    <button
                      class="monday-discussion-btn"
                      :class="{ 'monday-cell--selected': selectedCellKey === `${(scope.row as ProjectWorkItemListItem).id}:discussion` }"
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
                v-for="column in movableVisibleColumns"
                :key="column.key"
                :label="column.label"
                :column-key="column.key"
                :width="columnWidths[column.key]"
                align="center"
                :class-name="`monday-movable-column monday-column--${column.key}${column.key === 'status' || column.key === 'priority' ? ' monday-block-column' : ''}`"
                :label-class-name="`monday-movable-column-header monday-sortable-column-header monday-column-header--${column.key}${columnResizingKey === column.key ? ' monday-column-resizing' : ''}`"
                resizable
              >
                <template #header>
                  <monday-column-quick-sort
                    :label="column.label"
                    :direction="sortDirectionForColumn(column.key)"
                    :saving="savingSortOrder"
                    @sort="applyColumnQuickSort(column.key)"
                    @clear="clearColumnSort(column.key)"
                    @save="saveSortedWorkItemOrder"
                  />
                  <span
                    class="monday-column-resize-handle"
                    :data-column-key="column.key"
                    aria-hidden="true"
                  />
                </template>
                <template #default="scope">
                  <template v-if="column.key === 'assignee'">
                    <el-popover placement="bottom" :width="360" trigger="click" popper-class="work-items-popover" @show="assigneeSearch = ''">
                      <template #reference>
                        <button
                          class="cell-editor-trigger monday-cell-centered"
                          :disabled="Boolean(editingCell)"
                          @click.stop="selectCell((scope.row as ProjectWorkItemListItem).id, 'assignee')"
                        >
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

                  <template v-else-if="column.key === 'status'">
                    <el-popover
                      placement="bottom"
                      width="auto"
                      trigger="click"
                      popper-class="work-items-label-popover status-popover"
                      @hide="resetLabelPopoverContent((scope.row as ProjectWorkItemListItem).id, 'status')"
                    >
                      <template #reference>
                        <button
                          class="monday-status-cell status-chip cell-editor-trigger"
                          :class="`monday-status-cell--${getStatusTone((scope.row as ProjectWorkItemListItem).statusCode)}`"
                          :style="getStatusCellStyle((scope.row as ProjectWorkItemListItem).statusCode)"
                          :disabled="Boolean(editingCell)"
                          @click.stop="selectCell((scope.row as ProjectWorkItemListItem).id, 'status')"
                        >
                          <span>{{ statusLabel((scope.row as ProjectWorkItemListItem).statusCode) }}</span>
                        </button>
                      </template>
                      <work-item-label-popover-content
                        :ref="element => setLabelPopoverContentRef(labelPopoverKey((scope.row as ProjectWorkItemListItem).id, 'status'), element)"
                        kind="status"
                        :project-id="projectId"
                        :catalog="labelCatalog"
                        :workflow-statuses="workflowStatuses.filter(item => item.active || item.statusCode === (scope.row as ProjectWorkItemListItem).statusCode)"
                        :current-value="(scope.row as ProjectWorkItemListItem).statusCode"
                        :can-manage="Boolean(labelCatalog?.canManage)"
                        :available-transitions="(scope.row as ProjectWorkItemListItem).capabilities.availableTransitions"
                        @select-status="transitionItem(scope.row as ProjectWorkItemListItem, $event)"
                        @updated="onLabelsUpdated"
                      />
                    </el-popover>
                  </template>

                  <template v-else-if="column.key === 'priority'">
                    <el-popover
                      placement="bottom"
                      width="auto"
                      trigger="click"
                      popper-class="work-items-label-popover priority-popover"
                      @hide="resetLabelPopoverContent((scope.row as ProjectWorkItemListItem).id, 'priority')"
                    >
                      <template #reference>
                        <button
                          class="monday-priority-cell cell-editor-trigger"
                          :class="`monday-priority-cell--${getPriorityPresentation((scope.row as ProjectWorkItemListItem).priority).tone}`"
                          :style="getPriorityCellStyle((scope.row as ProjectWorkItemListItem).priority)"
                          :disabled="Boolean(editingCell)"
                          @click.stop="selectCell((scope.row as ProjectWorkItemListItem).id, 'priority')"
                        >
                          <span>{{ getPriorityPresentation((scope.row as ProjectWorkItemListItem).priority).label }}</span>
                        </button>
                      </template>
                      <work-item-label-popover-content
                        :ref="element => setLabelPopoverContentRef(labelPopoverKey((scope.row as ProjectWorkItemListItem).id, 'priority'), element)"
                        kind="priority"
                        :project-id="projectId"
                        :catalog="labelCatalog"
                        :priority-options="priorityOptions.filter(item => item.active)"
                        :current-value="(scope.row as ProjectWorkItemListItem).priority"
                        :can-manage="Boolean(labelCatalog?.canManage)"
                        @select-priority="patchCell(scope.row as ProjectWorkItemListItem, 'priority', $event)"
                        @updated="onLabelsUpdated"
                      />
                    </el-popover>
                  </template>

                  <span v-else-if="column.key === 'content'" class="monday-content-label">{{ (scope.row as ProjectWorkItemListItem).contentName }}</span>

                  <template v-else-if="column.key === 'dueDate'">
                    <el-popover placement="bottom" :width="300" trigger="click" popper-class="work-items-popover date-popover">
                      <template #reference>
                        <button
                          class="monday-due-date-cell cell-editor-trigger"
                          :disabled="Boolean(editingCell)"
                          @click.stop="selectCell((scope.row as ProjectWorkItemListItem).id, 'dueDate')"
                        >
                          <span
                            v-if="isOverdue((scope.row as ProjectWorkItemListItem).dueDate, (scope.row as ProjectWorkItemListItem).statusCode)"
                            class="monday-overdue-badge"
                            title="已超出截止时间"
                          >
                            <svg width="15" height="15" viewBox="0 0 16 16" fill="none">
                              <circle cx="8" cy="8" r="7" class="overdue-circle" />
                              <path d="M8 4.2V8.5M8 11.2V11.8" class="overdue-exclamation" stroke-width="1.6" stroke-linecap="round" />
                            </svg>
                          </span>
                          <span
                            class="due-date-text"
                            :class="{ 'due-date-text--overdue': isOverdue((scope.row as ProjectWorkItemListItem).dueDate, (scope.row as ProjectWorkItemListItem).statusCode) }"
                          >
                            {{ formatDate((scope.row as ProjectWorkItemListItem).dueDate) }}
                          </span>
                        </button>
                      </template>
                      <div class="date-editor">
                        <div><el-button @click="onDueDateChange(scope.row as ProjectWorkItemListItem, todayValue())">Today</el-button><el-button text @click="onDueDateChange(scope.row as ProjectWorkItemListItem, null)">清空</el-button></div>
                        <el-date-picker :model-value="(scope.row as ProjectWorkItemListItem).dueDate ? formatDate((scope.row as ProjectWorkItemListItem).dueDate) : null" type="date" value-format="YYYY-MM-DD" placeholder="选择截止日期" @update:model-value="onDueDateChange(scope.row as ProjectWorkItemListItem, $event as string | null)" />
                      </div>
                    </el-popover>
                  </template>

                  <span v-else-if="column.key === 'updatedAt'" class="monday-timestamp">{{ formatTime((scope.row as ProjectWorkItemListItem).updatedAt) }}</span>
                </template>
              </el-table-column>

              <el-table-column
                label="添加列"
                column-key="add-column"
                :min-width="TABLE_ADD_COLUMN_MIN_WIDTH"
                :resizable="false"
                header-align="left"
                class-name="monday-add-column"
                label-class-name="monday-add-column-header"
              >
                <template #header>
                  <button
                    type="button"
                    class="monday-add-column-icon"
                    aria-label="添加列（功能预留）"
                    title="添加列（功能预留）"
                  >
                    <svg
                      viewBox="0 0 20 20"
                      fill="currentColor"
                      width="18"
                      height="18"
                      aria-hidden="true"
                    >
                      <path
                        d="M10 2.25C10.4142 2.25 10.75 2.58579 10.75 3V9.25H17C17.4142 9.25 17.75 9.58579 17.75 10C17.75 10.4142 17.4142 10.75 17 10.75H10.75V17C10.75 17.4142 10.4142 17.75 10 17.75C9.58579 17.75 9.25 17.4142 9.25 17V10.75H3C2.58579 10.75 2.25 10.4142 2.25 10C2.25 9.58579 2.58579 9.25 3 9.25H9.25V3C9.25 2.58579 9.58579 2.25 10 2.25Z"
                        fill-rule="evenodd"
                        clip-rule="evenodd"
                      />
                    </svg>
                  </button>
                </template>
              </el-table-column>

              <template #append>
                <div
                  v-if="quickOpen"
                  ref="quickRow"
                  class="quick-row monday-quick-row"
                  :style="quickGridStyle"
                >
                  <span class="monday-quick-checkbox" aria-hidden="true" />
                  <el-input
                    ref="quickTitleInput"
                    v-model="quickTitle"
                    class="quick-title-field"
                    maxlength="300"
                    :disabled="quickCreating"
                    placeholder="添加工作项"
                    aria-label="工作项名称；Enter 创建，Shift+Enter 创建后继续"
                    @keydown="onQuickKeydown"
                  />
                  <el-select
                    v-model="quickContentId"
                    class="quick-content-field"
                    :style="{ gridColumn: quickContentColumn }"
                    :disabled="quickCreating"
                    filterable
                    placeholder="Content"
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
                  :style="quickGridStyle"
                  :disabled="!canCreate"
                  @click="openQuick"
                >
                  <span class="monday-quick-checkbox" aria-hidden="true" />
                  <span class="monday-quick-add__field">添加工作项</span>
                </button>
                <div ref="tableSentinel" class="cursor-sentinel" aria-hidden="true" />
                <div v-if="tableLoading && tableItems.length" class="incremental-state">正在加载更多工作项…</div>
                <div v-else-if="loadingMoreError" class="incremental-state incremental-state--error">
                  <span>加载更多失败</span><el-button text @click="loadTable(tableNextCursor, true)">重试</el-button>
                </div>
              </template>
            </el-table>
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
            @drop.prevent="status.active && dropInto(status.statusCode)"
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
      :model-value="detailOpen"
      :modal="false"
      :modal-penetrable="true"
      append-to-body
      title="工作项详情"
      header-class="work-items-detail-drawer__header"
      modal-class="work-items-drawer-overlay"
      class="work-items-detail-drawer"
      :size="`${drawerWidth}px`"
      @update:model-value="onDetailModelValue"
    >
      <div
        class="drawer-resize-handle"
        :class="{ 'drawer-resize-handle--resizing': isResizingDrawer }"
        title="拖动调整抽屉宽度"
        @pointerdown="onDrawerResizePointerDown"
      >
        <div class="drawer-resize-grip" aria-hidden="true">
          <svg width="6" height="18" viewBox="0 0 6 18" fill="currentColor">
            <circle cx="1.5" cy="2" r="1.1" />
            <circle cx="4.5" cy="2" r="1.1" />
            <circle cx="1.5" cy="6.5" r="1.1" />
            <circle cx="4.5" cy="6.5" r="1.1" />
            <circle cx="1.5" cy="11" r="1.1" />
            <circle cx="4.5" cy="11" r="1.1" />
            <circle cx="1.5" cy="15.5" r="1.1" />
            <circle cx="4.5" cy="15.5" r="1.1" />
          </svg>
        </div>
      </div>
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

    <teleport to="body">
      <div
        v-if="project && selectedView === 'table'"
        v-show="horizontalOverflow"
        ref="horizontalPageScrollbar"
        class="project-table-scrollbar project-table-scrollbar--horizontal"
        :style="horizontalPageScrollbarStyle"
        aria-label="横向滚动工作项表格"
        tabindex="0"
        @scroll.passive="onHorizontalPageScroll"
      >
        <div
          class="project-table-scrollbar__horizontal-spacer"
          :style="{ width: `${horizontalScrollExtent}px` }"
          aria-hidden="true"
        />
      </div>
      <div
        v-if="project && selectedView === 'table'"
        ref="verticalPageScrollbar"
        class="project-table-scrollbar project-table-scrollbar--vertical"
        :style="verticalPageScrollbarStyle"
        aria-label="纵向滚动工作项表格"
        tabindex="0"
        @scroll.passive="onVerticalPageScroll"
      >
        <div
          class="project-table-scrollbar__vertical-spacer"
          :style="{ height: `${verticalScrollExtent}px` }"
          aria-hidden="true"
        />
      </div>
    </teleport>
  </div>
</template>

<style scoped>
.work-items-home {
  display: flex;
  min-width: 0;
  min-height: 0;
  max-width: 100%;
  flex: 1 1 0;
  flex-direction: column;
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

.project-overview-stack {
  display: flex;
  height: 100%;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
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

.monday-table-surface {
  display: flex;
  min-width: 0;
  min-height: 240px;
  flex: 1 1 0;
  border: 0 !important;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  overflow: visible !important;
  padding-top: 14px;
}

.monday-table-wrapper {
  display: flex;
  width: 100%;
  min-width: 0;
  min-height: 0;
  max-width: 100%;
  flex: 1 1 auto;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  overflow: visible !important;
}

/* Monday Table 核心样式与网格微调 */
:deep(.monday-table.el-table),
:deep(.monday-table .el-table__inner-wrapper),
:deep(.monday-table .el-table__header),
:deep(.monday-table .el-table__header thead),
:deep(.monday-table .el-table__header tr),
:deep(.monday-table .el-table__header th.el-table__cell),
:deep(.monday-table .el-table__header th.el-table__cell > .cell),
:deep(.monday-table .el-table-fixed-column--left),
:deep(.monday-table th.monday-sortable-column-header),
:deep(.monday-table th.monday-sortable-column-header > .cell) {
  overflow: visible !important;
}

:deep(.monday-table.el-table) {
  --work-item-table-row-height: 36px;
  --work-item-table-header-height: 38px;
  --work-item-sort-overflow-space: 20px;
  --work-item-group-accent: rgb(87, 155, 252);
  --work-item-hierarchy-indent: 40px;
  --work-item-hierarchy-gap: 14px;
  --work-item-hierarchy-line-width: 1px;
  --work-item-hierarchy-bar-width: 6px;
  --work-item-hierarchy-bar-center: 3px;
  --work-item-hierarchy-corner-radius: var(--work-item-hierarchy-bar-width);
  --work-item-hierarchy-spine-offset: calc(
    var(--work-item-hierarchy-bar-center) - (var(--work-item-hierarchy-line-width) / 2)
  );
  --work-item-column-resize-idle: rgb(208, 212, 228);
  --work-item-column-resize-accent: rgb(87, 155, 252);
  --work-item-table-cell-bg: var(--yp-bg-surface);
  --work-item-quick-add-accent: rgb(87, 155, 252);
  --el-table-border-color: var(--yp-monday-grid-border);
  --el-table-header-bg-color: var(--work-item-table-cell-bg);
  --el-table-header-text-color: var(--yp-text-secondary);
  --el-table-row-hover-bg-color: var(--yp-bg-sunken);
  --el-table-tr-bg-color: var(--work-item-table-cell-bg);
  color: var(--yp-text-primary);
  font-size: 13px;
  width: 100%;
  max-width: 100%;
  border: none;
  border-top-left-radius: var(--yp-radius-md);
}

:deep(.monday-table .el-scrollbar__wrap) {
  overscroll-behavior: contain;
}

:deep(.monday-table .el-scrollbar__bar) {
  display: none !important;
}

.project-table-scrollbar {
  position: fixed;
  z-index: 1999;
  background: var(--yp-bg-sunken);
  scrollbar-color: var(--yp-border-strong) var(--yp-bg-sunken);
  scrollbar-width: thin;
  overscroll-behavior: contain;
}

.project-table-scrollbar::-webkit-scrollbar {
  width: 10px;
  height: 10px;
}

.project-table-scrollbar::-webkit-scrollbar-track {
  background: var(--yp-bg-sunken);
}

.project-table-scrollbar::-webkit-scrollbar-thumb {
  border: 2px solid var(--yp-bg-sunken);
  border-radius: var(--yp-radius-pill);
  background: var(--yp-border-strong);
}

.project-table-scrollbar::-webkit-scrollbar-thumb:hover {
  background: var(--yp-text-secondary);
}

.project-table-scrollbar--horizontal {
  bottom: 0;
  height: 12px;
  overflow-x: scroll;
  overflow-y: hidden;
}

.project-table-scrollbar--vertical {
  top: 0;
  bottom: 0;
  width: 12px;
  overflow-x: hidden;
  overflow-y: scroll;
}

.project-table-scrollbar__horizontal-spacer {
  height: 1px;
}

.project-table-scrollbar__vertical-spacer {
  width: 1px;
}

:deep(.monday-table .el-table__body tr.work-item-table-row--selected td.el-table__cell) {
  background-color: var(--yp-bg-selected) !important;
}

:deep(.monday-table.el-table--border::after),
:deep(.monday-table.el-table--border::before),
:deep(.monday-table .el-table__inner-wrapper::before),
:deep(.monday-table.el-table--border .el-table__inner-wrapper::after) {
  display: none;
}

:deep(.monday-table .el-table__border-left-patch) {
  display: none;
}

:deep(.monday-table.el-table > .el-table__inner-wrapper > .el-table__header-wrapper) {
  position: relative;
  z-index: 6;
  margin-top: calc(-1 * var(--work-item-sort-overflow-space));
  padding-top: var(--work-item-sort-overflow-space);
  overflow: hidden !important;
}

/* 顶部安全区会增加 headerWrapper 的测量高度，这里保持数据区可视高度不变。 */
:deep(.monday-table.el-table > .el-table__inner-wrapper > .el-table__body-wrapper) {
  height: calc(100% - var(--work-item-table-header-height)) !important;
}

:deep(.monday-table .el-table__header th.el-table__cell) {
  height: var(--work-item-table-header-height);
  padding: 0;
  border-top: 1px solid var(--yp-monday-grid-border);
  border-right: 1px solid var(--yp-monday-grid-border);
  border-bottom: 1px solid var(--yp-monday-grid-border);
  background: var(--work-item-table-cell-bg);
  font-size: 13px;
  font-weight: 500;
  color: var(--yp-text-secondary);
}

:deep(.monday-table .el-table__header th.el-table__cell:hover),
:deep(.monday-table .el-table__header th.el-table__cell:focus-within) {
  background: var(--work-item-table-cell-bg);
}

:deep(.monday-table .el-table__header th.monday-sortable-column-header:hover),
:deep(.monday-table .el-table__header th.monday-sortable-column-header:focus-within) {
  background: var(--yp-bg-sunken);
}

:deep(.monday-table th.monday-sortable-column-header) {
  position: relative;
  z-index: 4;
}

:deep(.monday-table .el-table__header th.el-table-fixed-column--left) {
  z-index: 10;
}

:deep(.monday-table th.monday-sortable-column-header:hover),
:deep(.monday-table th.monday-sortable-column-header:focus-within),
:deep(.monday-table th.monday-sortable-column-header:has(.sort-by-column--active)) {
  z-index: 8;
}

:deep(.monday-table .el-table__header th.el-table-fixed-column--left:hover),
:deep(.monday-table .el-table__header th.el-table-fixed-column--left:focus-within),
:deep(.monday-table .el-table__header th.el-table-fixed-column--left:has(.sort-by-column--active)) {
  z-index: 12;
}

:deep(.monday-table .monday-expand-column) {
  width: 1px;
  padding: 0 !important;
  border: 0 !important;
  background: transparent !important;
}

:deep(.monday-table .monday-expand-column > .cell) {
  display: none;
}

:deep(.monday-table .el-table__header th.monday-selection-column),
:deep(.monday-table .el-table__body td.monday-selection-column) {
  position: relative;
  border-left: 0;
}

:deep(.monday-table .el-table__header th.monday-selection-column)::before,
:deep(.monday-table .el-table__body td.monday-selection-column)::before {
  position: absolute;
  z-index: 2;
  left: -1px;
  width: var(--work-item-hierarchy-bar-width);
  background: var(--work-item-group-accent);
  content: '';
  pointer-events: none;
}

:deep(.monday-table .el-table__header th.monday-selection-column)::before {
  top: 0;
  bottom: -1px;
  border-radius: var(--work-item-hierarchy-corner-radius) 0 0;
}

:deep(.monday-table .el-table__body td.monday-selection-column)::before {
  top: -1px;
  bottom: -1px;
}

:deep(.monday-table .el-table__header th.monday-selection-column) {
  border-top: 0;
  border-top-left-radius: 0;
  background-image: linear-gradient(var(--yp-monday-grid-border), var(--yp-monday-grid-border));
  background-position: var(--work-item-hierarchy-bar-width) top;
  background-repeat: no-repeat;
  background-size: calc(100% - var(--work-item-hierarchy-bar-width)) 1px;
}

:deep(.monday-table .el-table__header th.el-table__cell:last-child) {
  border-right: 0;
  border-top-right-radius: 0;
}

:deep(.monday-table .el-table__body td.el-table__cell) {
  height: var(--work-item-table-row-height);
  padding: 0;
  border-right: 1px solid var(--yp-monday-grid-border);
  border-bottom: 1px solid var(--yp-monday-grid-border);
}

:deep(.monday-table .el-table__body td.el-table__cell:last-child) {
  border-right: 0;
}

:deep(.monday-table th.monday-add-column-header),
:deep(.monday-table td.monday-add-column) {
  border-left: 1px solid var(--yp-monday-grid-border) !important;
  border-right: 0 !important;
}

:deep(.monday-table th.monday-add-column-header) {
  position: relative;
  border-top-right-radius: 0;
}

:deep(.monday-table .el-table__header th.el-table__cell:has(+ th.monday-add-column-header)),
:deep(.monday-table .el-table__body td.el-table__cell:has(+ td.monday-add-column)) {
  border-right: 0;
}

:deep(.monday-table th.monday-add-column-header > .cell) {
  display: flex;
  height: 100%;
  align-items: center;
  justify-content: flex-start;
  padding: 0 0 0 10px;
}

.monday-add-column-icon {
  display: inline-flex;
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 0;
  border-radius: var(--yp-radius-sm, 4px);
  background: transparent;
  color: var(--yp-text-secondary);
  cursor: pointer;
  user-select: none;
  transition: color var(--yp-motion-fast) var(--yp-ease-standard),
              background-color var(--yp-motion-fast) var(--yp-ease-standard);
}

.monday-add-column-icon:hover {
  background: var(--yp-bg-hover);
  color: var(--yp-text-primary);
}

.monday-add-column-icon:focus-visible {
  outline: 2px solid var(--yp-action-primary);
  outline-offset: -2px;
}

:deep(.monday-table .monday-selection-column > .cell) {
  display: flex;
  height: 100%;
  align-items: center;
  justify-content: center;
  padding: 0 0 0 6px;
  box-sizing: border-box;
}

:deep(.monday-table .monday-selection-column .el-checkbox) {
  display: inline-flex;
  width: 100%;
  height: 100%;
  align-items: center;
  justify-content: center;
  margin: 0;
}

:deep(.monday-table .work-item-table-row--movable .monday-selection-column),
:deep(.monday-table .work-item-table-row--movable .monday-selection-column .el-checkbox),
:deep(.monday-table .work-item-table-row--movable .monday-selection-column .el-checkbox__input) {
  cursor: grab;
  user-select: none;
  touch-action: none;
}

:deep(.monday-table .work-item-table-row--movable .monday-selection-column:active),
:deep(.monday-table .work-item-table-row--movable .monday-selection-column:active .el-checkbox),
:deep(.monday-table .work-item-table-row--movable .monday-selection-column:active .el-checkbox__input) {
  cursor: grabbing;
}

:deep(.monday-table .monday-selection-column .el-checkbox__inner) {
  width: 16px;
  height: 16px;
  border-color: var(--yp-border-strong);
  border-radius: 2px;
  background: var(--yp-bg-surface);
}

:deep(.monday-table .monday-selection-column .el-checkbox__input.is-checked .el-checkbox__inner),
:deep(.monday-table .monday-selection-column .el-checkbox__input.is-indeterminate .el-checkbox__inner) {
  border-color: var(--yp-action-primary);
  background: var(--yp-action-primary);
}

:deep(.monday-table .monday-selection-column .el-checkbox__inner::after) {
  display: none;
}

:deep(.monday-table .monday-selection-column .el-checkbox__input.is-checked .el-checkbox__inner::after) {
  display: block;
  top: 1px;
  left: 1px;
  width: 14px;
  height: 14px;
  border: 0;
  background: var(--yp-status-blue-foreground);
  clip-path: polygon(8% 49%, 20% 36%, 42% 57%, 78% 20%, 91% 33%, 43% 79%);
  transform: none;
}

:deep(.monday-table .monday-selection-column .el-checkbox__input.is-indeterminate .el-checkbox__inner::before) {
  top: 7px;
  right: 3px;
  left: 3px;
  height: 2px;
}

:deep(.monday-table .el-table__body tr) {
  position: relative;
  transition: transform 180ms cubic-bezier(0.2, 0, 0, 1);
  will-change: transform;
}

:deep(.monday-table .el-table__body tr.work-item-table-row--sorting) {
  transition: none !important;
  will-change: auto;
}

:deep(.monday-table th.monday-movable-column-header),
:deep(.monday-table td.monday-movable-column) {
  transition: transform 180ms cubic-bezier(0.2, 0, 0, 1), opacity 120ms ease;
  will-change: transform;
}

:deep(.monday-table th.monday-movable-column-header),
:deep(.monday-table th.monday-movable-column-header > .cell) {
  cursor: grab;
  user-select: none;
  touch-action: none;
}

:deep(.monday-table th.monday-movable-column-header:active),
:deep(.monday-table th.monday-movable-column-header:active > .cell) {
  cursor: grabbing;
}

.monday-column-resize-handle {
  position: absolute;
  z-index: 20;
  top: 0;
  right: 0;
  bottom: 0;
  width: 8px;
  overflow: hidden;
  cursor: col-resize;
}

:deep(.monday-table th.monday-movable-column-header .monday-column-resize-handle)::before,
:deep(.monday-table th.monday-title-column .monday-title-column-resize-handle)::before {
  position: absolute;
  top: 0;
  right: -3px;
  bottom: 0;
  width: 6px;
  border-radius: 16px;
  background: var(--work-item-column-resize-idle);
  content: '';
  opacity: 0;
  pointer-events: none;
  transition: background 100ms ease, opacity 100ms ease;
}

.monday-title-column-resize-handle {
  overflow: visible;
}

:deep(.monday-table th.monday-title-column .monday-title-column-resize-handle)::before {
  clip-path: inset(0 0 0 50%);
}

:deep(.monday-table th.monday-movable-column-header:hover .monday-column-resize-handle)::before {
  opacity: 1;
}

:deep(.monday-table th.monday-title-column:hover .monday-title-column-resize-handle)::before {
  opacity: 1;
}

:deep(.monday-table th.monday-movable-column-header:has(.monday-column-resize-handle:hover) .monday-column-resize-handle)::before,
:deep(.monday-table th.monday-movable-column-header.noclick .monday-column-resize-handle)::before,
:deep(.monday-table th.monday-movable-column-header.monday-column-resizing .monday-column-resize-handle)::before {
  background: var(--work-item-column-resize-accent);
  opacity: 1;
}

:deep(.monday-table th.monday-title-column:has(.monday-title-column-resize-handle:hover) .monday-title-column-resize-handle)::before,
:deep(.monday-table th.monday-title-column.noclick .monday-title-column-resize-handle)::before {
  background: var(--work-item-column-resize-accent);
  opacity: 1;
}

:deep(.monday-table .el-table__cell > .cell) {
  padding: 0 var(--yp-space-3);
  line-height: 1.4;
}

:deep(.monday-table td.monday-block-column > .cell),
:deep(.monday-table td.monday-title-column > .cell) {
  padding: 0;
  height: var(--work-item-table-row-height);
}

.title-cell {
  display: flex;
  align-items: stretch;
  height: var(--work-item-table-row-height);
  width: 100%;
}

.work-item-link {
  display: flex;
  align-items: center;
  min-width: 0;
  flex: 1 1 auto;
  justify-content: flex-start;
  padding: 0 var(--yp-space-2) 0 var(--yp-space-2);
  border: 0;
  color: var(--yp-text-primary);
  background: transparent;
  text-align: left;
  cursor: default;
  position: relative;
  box-sizing: border-box;
  transition: color var(--yp-motion-fast) var(--yp-ease-standard);
}

:deep(.monday-table .work-item-table-row--movable) .work-item-link {
  cursor: grab;
  user-select: none;
  touch-action: none;
}

:deep(.monday-table .work-item-table-row--movable) .work-item-link:active {
  cursor: grabbing;
}

.work-item-link:focus,
.work-item-link:active {
  outline: none;
}

.work-item-title-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
  font-size: 13.5px;
  color: var(--yp-text-primary);
}

/* Monday 讨论气泡按钮与分割线 */
.monday-discussion-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 100%;
  flex: 0 0 40px;
  padding: 0;
  border: 0;
  border-left: 1px solid var(--yp-monday-grid-border);
  border-radius: 0;
  color: var(--yp-text-muted);
  background: transparent;
  cursor: pointer;
  transition: color var(--yp-motion-fast) var(--yp-ease-standard),
              background var(--yp-motion-fast) var(--yp-ease-standard);
}

.monday-discussion-btn:hover {
  color: var(--yp-action-primary);
  background: var(--yp-bg-hover);
}

.discussion-bubble-icon {
  width: 17px;
  height: 17px;
}

.monday-cell-centered {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  min-height: var(--work-item-table-row-height);
  box-sizing: border-box;
}

:deep(.monday-table .el-table__body td.el-table__cell.monday-cell--selected) {
  position: relative;
  z-index: 4;
}

.work-item-link.monday-cell--selected,
.monday-discussion-btn.monday-cell--selected {
  position: relative;
  z-index: 4;
}

:deep(.monday-table .el-table__body td.el-table__cell.monday-cell--selected::after),
.work-item-link.monday-cell--selected::after,
.monday-discussion-btn.monday-cell--selected::after {
  position: absolute;
  z-index: 8;
  inset: 0;
  border: 0.5px solid var(--yp-action-primary);
  box-sizing: border-box;
  content: '';
  pointer-events: none;
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
  min-height: var(--work-item-table-row-height);
  padding: 0 var(--yp-space-2);
  font-size: 13px;
  font-weight: 600;
  text-align: center;
  user-select: none;
  cursor: pointer;
  box-sizing: border-box;
  overflow: hidden;
  transition: opacity var(--yp-motion-fast) var(--yp-ease-standard);
}

.monday-status-cell { border: 0; font-family: inherit; }

.monday-status-cell:hover,
.monday-priority-cell:hover {
  opacity: 0.7;
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
  width: 100%;
  height: 100%;
  min-height: var(--work-item-table-row-height);
  box-sizing: border-box;
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
  position: relative;
  display: grid;
  width: 100%;
  min-width: max-content;
  align-items: center;
  height: var(--work-item-table-row-height);
  padding: 0;
  border: 0;
  color: var(--yp-text-secondary);
  background: transparent;
  font-size: 13px;
  cursor: pointer;
  transition: color var(--yp-motion-fast) var(--yp-ease-standard);
}

.monday-quick-add__field {
  display: flex;
  height: 26px;
  grid-column: 3;
  align-items: center;
  box-sizing: border-box;
  padding: 0 8px;
  border: 1px solid transparent;
  border-radius: var(--yp-radius-sm, 4px);
  text-align: left;
  transition: border-color var(--yp-motion-fast) var(--yp-ease-standard),
              background-color var(--yp-motion-fast) var(--yp-ease-standard),
              color var(--yp-motion-fast) var(--yp-ease-standard);
}

.monday-quick-checkbox {
  width: 16px;
  height: 16px;
  grid-column: 2;
  align-self: center;
  justify-self: center;
  box-sizing: border-box;
  border: 1px solid color-mix(in srgb, var(--yp-border-strong) 50%, transparent);
  border-radius: 2px;
  background: var(--yp-bg-surface);
  transform: translateX(2px);
  pointer-events: none;
}

.monday-quick-add:hover:not(:disabled) .monday-quick-add__field {
  border-color: var(--yp-border-strong, var(--yp-border-default));
  background: var(--yp-bg-surface);
  color: var(--yp-text-primary);
}

.monday-quick-add:focus-visible { outline: none; }

.monday-quick-add:focus-visible .monday-quick-add__field {
  border-color: var(--yp-action-primary);
  box-shadow: 0 0 0 1px var(--yp-action-primary);
}

.monday-quick-add:disabled {
  cursor: not-allowed;
  opacity: .55;
}

.monday-quick-row {
  --work-item-quick-control-height: 26px;
  position: relative;
  display: grid;
  height: var(--work-item-table-row-height);
  min-width: max-content;
  gap: 0;
  align-items: center;
  box-sizing: border-box;
  padding: 0;
  background: transparent;
  transition: background-color var(--yp-motion-fast) var(--yp-ease-standard);
}

.monday-quick-row:focus-within { background: var(--yp-bg-selected); }

.monday-quick-add,
.monday-quick-row {
  border-bottom: 1px solid var(--yp-monday-grid-border);
  box-sizing: border-box;
}

.monday-quick-add::before,
.monday-quick-row::before {
  position: absolute;
  z-index: 2;
  top: -1px;
  bottom: -1px;
  left: 0;
  width: 6px;
  border-radius: 0 0 0 6px;
  background: var(--work-item-quick-add-accent);
  content: '';
  opacity: .5;
  pointer-events: none;
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
:deep(.monday-table .work-item-table-row--dragging td) {
  pointer-events: none;
}
:deep(.monday-table .work-item-table-row--dragging) {
  opacity: 0 !important;
  transition: none !important;
}

.quick-title-field,
.quick-content-field {
  align-self: center;
  box-sizing: border-box;
  min-width: 0;
  padding: 0 4px;
}
.quick-title-field { grid-column: 3; padding-left: 8px; }
.quick-content-field { grid-column: 5; }
.quick-submit {
  justify-self: center;
  width: 64px;
  height: var(--work-item-quick-control-height);
  padding: 0 12px;
}
:deep(.monday-quick-row .el-input__wrapper),
:deep(.monday-quick-row .el-select__wrapper) {
  height: var(--work-item-quick-control-height);
  min-height: var(--work-item-quick-control-height);
}
:deep(.monday-quick-row .el-input__wrapper.is-focus),
:deep(.monday-quick-row .el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px var(--yp-border-default) inset !important;
}
:deep(.monday-quick-row .el-input__wrapper:has(input:focus-visible)),
:deep(.monday-quick-row .el-select__wrapper:has(input:focus-visible)) {
  outline: none !important;
  outline-offset: 0;
}

.monday-subitems-counter-component {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  margin-left: 8px;
  flex: 0 0 auto;
  border-radius: var(--yp-radius-sm, 4px);
  background-color: var(--yp-bg-sunken);
  color: var(--yp-text-secondary);
  font-size: 12px;
  font-weight: 500;
  line-height: 1;
  cursor: pointer;
  user-select: none;
  box-sizing: border-box;
  transition: background-color var(--yp-motion-fast) var(--yp-ease-standard),
              color var(--yp-motion-fast) var(--yp-ease-standard);
}

.monday-subitems-counter-component:hover {
  background-color: var(--yp-bg-hover);
  color: var(--yp-text-primary);
}

.monday-subitems-counter-component__subitems-count {
  display: inline-block;
  line-height: 1;
  text-align: center;
}

:deep(.monday-table td.monday-expand-column) {
  padding: 0;
  text-align: center;
}

:deep(.monday-table td.monday-expand-column .cell) {
  padding: 0;
  justify-content: center;
}

:deep(.monday-table .el-table__expand-icon) {
  display: none;
}

.subitem-expand-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  min-width: 20px;
  flex: 0 0 20px;
  margin-right: 6px;
  padding: 0;
  border: 0;
  border-radius: var(--yp-radius-sm, 4px);
  background: transparent;
  cursor: pointer;
  box-sizing: border-box;
  transition: background var(--yp-motion-fast) var(--yp-ease-standard),
              color var(--yp-motion-fast) var(--yp-ease-standard),
              opacity var(--yp-motion-fast) var(--yp-ease-standard);
}

.subitem-expand-button:hover,
.subitem-expand-button:focus-visible {
  background: var(--yp-bg-hover);
}

.subitem-expand-button svg {
  display: block;
  width: 16px;
  height: 16px;
  transform-origin: center;
  transition: transform 120ms ease;
}

.subitem-expand-button[aria-expanded="true"] svg {
  transform: rotate(90deg);
}

/* 没有子工作项：平时隐藏占位，hover 时出现浅色展开按钮 */
.subitem-expand-button--empty {
  opacity: 0;
  color: var(--yp-text-placeholder);
  pointer-events: none;
}

:deep(.monday-table .work-item-table-row:hover) .subitem-expand-button--empty,
.work-item-link:hover .subitem-expand-button--empty,
.subitem-expand-button--empty:focus-visible {
  opacity: 1;
  pointer-events: auto;
}

.subitem-expand-button--empty:hover {
  color: var(--yp-text-secondary);
}

/* 已有子工作项：常驻深色展开按钮 */
.subitem-expand-button--has-subitems {
  opacity: 1;
  color: var(--yp-text-primary);
  pointer-events: auto;
}

.subitem-expand-button--has-subitems:hover {
  color: var(--yp-text-primary);
}

:deep(.monday-table .el-table__expanded-cell) {
  position: relative;
  padding: var(--work-item-hierarchy-gap) 0 !important;
  border-left: 0;
  background: var(--yp-bg-surface);
}

:deep(.monday-table .el-table__expanded-cell)::before {
  position: absolute;
  z-index: 4;
  top: -1px;
  bottom: -1px;
  left: var(--work-item-hierarchy-spine-offset);
  width: var(--work-item-hierarchy-line-width);
  border-radius: var(--work-item-hierarchy-line-width);
  background: var(--work-item-group-accent);
  content: '';
  pointer-events: none;
}

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

</style>

<style>
.work-item-drag-preview,
.work-item-column-drag-preview {
  position: fixed;
  z-index: 100000;
  pointer-events: none;
  overflow: hidden;
  border: 1px solid var(--yp-border-strong);
  border-radius: 4px;
  background: linear-gradient(180deg, var(--yp-bg-raised) 0%, var(--yp-bg-sunken) 100%);
  box-shadow:
    var(--yp-shadow-overlay),
    0 7px 16px color-mix(in srgb, var(--yp-text-primary) 20%, transparent),
    inset 0 1px 0 color-mix(in srgb, var(--yp-bg-surface) 96%, transparent),
    inset 0 -1px 0 color-mix(in srgb, var(--yp-text-primary) 18%, transparent);
  filter: grayscale(1);
  opacity: 0.96;
  cursor: grabbing;
  user-select: none;
  will-change: left, top, transform;
}

.work-item-drag-preview::after,
.work-item-column-drag-preview::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(
    90deg,
    color-mix(in srgb, var(--yp-bg-surface) 17%, transparent),
    color-mix(in srgb, var(--yp-text-primary) 10%, transparent)
  );
  pointer-events: none;
}

.work-item-drag-preview::after {
  box-shadow: inset 6px 0 0 rgb(87, 155, 252);
}

.work-item-column-drag-preview::after {
  box-shadow: inset 6px 0 0 var(--yp-text-muted);
}

.work-item-drag-preview__table {
  height: 100%;
  table-layout: fixed;
  border-spacing: 0;
  border-collapse: collapse;
  color: var(--yp-text-secondary);
  background: var(--yp-bg-sunken);
}

.work-item-drag-preview__row,
.work-item-drag-preview__row > td {
  height: 36px;
}

.work-item-drag-preview__row > td {
  padding: 0;
  overflow: hidden;
  color: var(--yp-text-secondary) !important;
  background: var(--yp-bg-sunken) !important;
  border-right: 1px solid var(--yp-border-default) !important;
  border-bottom: 1px solid var(--yp-border-strong) !important;
  box-sizing: border-box;
}

.work-item-drag-preview__row > td:nth-child(even) {
  background: var(--yp-bg-hover) !important;
}

.work-item-column-drag-preview__table {
  width: 100%;
  table-layout: fixed;
  border-spacing: 0;
  border-collapse: collapse;
  color: var(--yp-text-primary);
  background: var(--yp-bg-surface);
}

.work-item-column-drag-preview {
  filter: none;
  opacity: 1;
}

.work-item-column-drag-preview::after {
  background: transparent;
  box-shadow: none;
}

.work-item-column-drag-preview__header {
  height: 38px;
  padding: 0 var(--yp-space-2);
  overflow: hidden;
  color: var(--yp-text-secondary) !important;
  background: var(--yp-monday-header-bg) !important;
  border-right: 1px solid var(--yp-monday-grid-border) !important;
  border-bottom: 1px solid var(--yp-monday-grid-border) !important;
  box-sizing: border-box;
  text-align: center;
}

.work-item-column-drag-preview__cell {
  height: 36px;
  padding: 0;
  overflow: hidden;
  color: var(--yp-text-primary) !important;
  background: var(--yp-bg-surface) !important;
  border-right: 1px solid var(--yp-monday-grid-border) !important;
  border-bottom: 1px solid var(--yp-monday-grid-border) !important;
  box-sizing: border-box;
  text-align: center;
}

.work-item-drag-preview *,
.work-item-column-drag-preview * {
  pointer-events: none !important;
  cursor: grabbing !important;
}

/* 抽屉无蒙版交互穿透 */
.work-items-drawer-overlay {
  pointer-events: none !important;
  background: transparent !important;
}

.work-items-drawer-overlay .el-drawer,
.work-items-detail-drawer {
  pointer-events: auto !important;
  box-shadow: -4px 0 24px color-mix(in srgb, var(--yp-text-primary) 12%, transparent) !important;
  overflow: visible !important;
}

/* 抽屉左侧拖动手柄 */
.drawer-resize-handle {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 20px;
  cursor: ew-resize;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: center;
  user-select: none;
  touch-action: none;
}

.drawer-resize-handle::before {
  content: '';
  position: absolute;
  inset: 0 10px 0 0;
  background: var(--yp-bg-sunken);
  box-shadow: -2px 0 8px color-mix(in srgb, var(--yp-text-primary) 10%, transparent);
  opacity: 0;
  transition: opacity var(--yp-motion-fast) var(--yp-ease-standard);
}

.drawer-resize-grip {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 12px;
  height: 32px;
  border-radius: 4px;
  color: var(--yp-text-secondary);
  background: var(--yp-bg-sunken);
  border: 1px solid var(--yp-monday-grid-border);
  box-shadow: 0 1px 4px color-mix(in srgb, var(--yp-text-primary) 10%, transparent);
  opacity: 0;
  transform: scale(.92);
  transition: opacity var(--yp-motion-fast) var(--yp-ease-standard),
              transform var(--yp-motion-fast) var(--yp-ease-standard);
}

.drawer-resize-handle:hover::before,
.drawer-resize-handle--resizing::before,
.drawer-resize-handle:hover .drawer-resize-grip,
.drawer-resize-handle--resizing .drawer-resize-grip {
  opacity: 1;
}

.drawer-resize-handle:hover .drawer-resize-grip,
.drawer-resize-handle--resizing .drawer-resize-grip {
  transform: scale(1);
}
</style>
