<script setup lang="ts">
import './workItemCompactTable.css'
import { useWorkItemEdits } from './useWorkItemEdits'
import { isWorkItemViewControl } from './workItemViewControls'
import { vBrandLoading as vLoading } from '../../brand/loading'
import { onTimeTrackingChanged } from '../../composables/useTimeTracker'
import WorkItemTimerCell from './WorkItemTimerCell.vue'
import WorkItemDiscussionIcon from './WorkItemDiscussionIcon.vue'
import WorkItemRowActions from './WorkItemRowActions.vue'
import WorkItemNameCell from './WorkItemNameCell.vue'
import WorkItemDraftCell from './WorkItemDraftCell.vue'
import { useWorkItemInlineCreate } from './useWorkItemInlineCreate'
import WorkItemUpdatedCell from './WorkItemUpdatedCell.vue'
import { Filter as FilterIcon, Hide, Search, Sort, User } from '@element-plus/icons-vue'
import {
  TimeTrackingState,
  WorkItemViewType,
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
  ElMessage,
  ElMessageBox,
  ElOption as ElOptionRaw,
  ElPopover,
  ElSelect as ElSelectRaw,
  ElTable,
  ElTableColumn,
} from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch, type CSSProperties, type DefineComponent } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter, type LocationQuery } from 'vue-router'
import type { WorkItemTableSource } from './workItemTableSource'
import { contentsApi, projectsApi, workItemsApi } from '../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import WorkItemDetailPanel from '../../components/collaboration/WorkItemDetailPanel.vue'
import MondayColumnQuickSort from './MondayColumnQuickSort.vue'
import ProjectWorkItemSubitemsTable, {
  type ProjectWorkItemSubitemSortRule,
} from './ProjectWorkItemSubitemsTable.vue'
import ProjectWorkspaceHeader from './ProjectWorkspaceHeader.vue'
import WorkItemLabelPopoverContent from './WorkItemLabelPopoverContent.vue'
import WorkItemContentPopoverContent from './WorkItemContentPopoverContent.vue'
import WorkItemDueDateCell from './WorkItemDueDateCell.vue'
import type { DueDateValue } from './workItemDueDate'
import { workItemLabelColorValue } from './workItemLabelColors'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpPriorityBadge from '../../components/yp/YpPriorityBadge.vue'
import WorkItemGroupingPopover from './WorkItemGroupingPopover.vue'
import { useWorkItemGrouping, isGroupDisplayRow, type WorkItemGroupDisplayRow } from './useWorkItemGrouping'
import { EMPTY_GROUP, type GroupField, type WorkItemGroup } from './workItemGrouping'
import { useWorkItemGroupCreate } from './useWorkItemGroupCreate'
import { useSession } from '../../composables/useSession'
import { useWorkItemDueClock } from './useWorkItemDueClock'
import { companyDate } from './workItemDueDate'

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
  reloadRequested?: boolean
  error?: ApiProblem
  sortRules: ProjectWorkItemSubitemSortRule[]
}

interface LabelPopoverContentHandle {
  resetEditor: () => void
}

const props = defineProps<{
  embeddedProjectId?: string
  preferenceScope?: string
  source?: WorkItemTableSource
  refreshKey?: number
}>()
const emit = defineEmits<{ changed: [] }>()
const embedded = computed(() => Boolean(props.embeddedProjectId))
const pageRoute = useRoute()
const embeddedQuery = ref<LocationQuery>({})
const route = reactive({
  get params() { return embedded.value ? { projectId: props.embeddedProjectId } : pageRoute.params },
  get query() { return embedded.value ? embeddedQuery.value : pageRoute.query },
})
const tableSource: WorkItemTableSource = {
  listProjectWorkItems: (request, init) => (props.source ?? workItemsApi).listProjectWorkItems(request, init),
  listProjectWorkItemFilterOptions: (request, init) => (props.source ?? workItemsApi).listProjectWorkItemFilterOptions(request, init),
  listWorkItemSubitems: (request, init) => init === undefined
    ? (props.source ?? workItemsApi).listWorkItemSubitems(request)
    : (props.source ?? workItemsApi).listWorkItemSubitems(request, init),
}
async function setViewQuery(query: LocationQuery, replace = false) {
  if (embedded.value) {
    embeddedQuery.value = query
    const preferences = { ...query }
    delete preferences.workItemId
    try { localStorage.setItem(`${props.preferenceScope}:query`, JSON.stringify(preferences)) } catch { /* Keep the current view usable. */ }
  } else if (replace) await router.replace({ query })
  else await router.push({ query })
}
function notifyChanged() { if (embedded.value) emit('changed') }
function visibleSubitemCount(item: ProjectWorkItemListItem) { return props.source?.subitemCount?.(item.id) ?? item.subitemCount }
const router = useRouter()
const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent
const projectId = computed(() => String(route.params.projectId))
function openSmallTimer() { window.dispatchEvent(new Event('yumpoo:open-timer')) }
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
const lanes = reactive<Record<string, KanbanLane>>({})
const subitems = reactive<Record<string, SubitemState>>({})
const expandedSubitemIds = ref<string[]>([])
const subitemSelections = reactive<Record<string, Set<string>>>({})
const subitemTableHandles = new Map<string, { openQuick: () => void; canClose: () => boolean }>()
const restoringArchive = ref(false)
const quickOpen = ref(false)
const quickTitle = ref('')
const quickCreating = ref(false)
const quickRow = ref<HTMLElement>()
const quickTitleInput = ref<InstanceType<typeof ElInput>>()
const detailOpen = ref(false)
const detailPanel = ref<InstanceType<typeof WorkItemDetailPanel>>()
let leavingDraft: Promise<boolean> | undefined
let detailGeneration = 0

async function beforeDraftLeave(): Promise<boolean> {
  if (detailPanel.value?.busy) {
    ElMessage.info('内容正在保存，请稍候再离开。')
    return false
  }
  if (!detailPanel.value?.hasDraft) return true
  if (leavingDraft) return leavingDraft
  leavingDraft = ElMessageBox.confirm('离开将丢弃尚未保存的描述或讨论草稿。', '放弃草稿', {
    confirmButtonText: '放弃草稿', cancelButtonText: '继续编写', type: 'warning',
  }).then(() => { detailPanel.value?.discardDraft(); return true }, () => false)
  try { return await leavingDraft } finally { leavingDraft = undefined }
}

async function beforeDetailClose(done: () => void): Promise<void> {
  if (await beforeDraftLeave()) done()
}

onBeforeRouteLeave(() => embedded.value ? canClose() : beforeDraftLeave())
onBeforeRouteUpdate((to, from) => to.params.projectId !== from.params.projectId
  || to.query.workItemId !== from.query.workItemId ? beforeDraftLeave() : true)
const detailLoading = ref(false)
const detail = ref<WorkItemDetail>()
const detailTab = ref<'details' | 'discussion' | 'relations' | 'activity'>('details')
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
  toggleRowSelection: (row: ProjectWorkItemListItem, selected?: boolean) => void
}>()
const tableSentinel = ref<HTMLElement>()
const horizontalPageScrollbar = ref<HTMLElement>()
const verticalPageScrollbar = ref<HTMLElement>()
const horizontalOverflow = ref(false)
const horizontalScrollExtent = ref(1)
const verticalScrollExtent = ref(1)
const pageScrollbarLeft = ref(0)
const pageScrollbarTop = ref(0)
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
  key: ColumnKey
  minWidth: number
  startWidth: number
  startX: number
} | undefined
let suppressTableClick = false
let suppressTableClickTimer: number | undefined
const loadingMoreError = ref<ApiProblem>()
const editingCell = ref('')
const editingNames = ref(new Set<string>())
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
const TABLE_MENU_COLUMN_WIDTH = 32
const TABLE_ADD_COLUMN_MIN_WIDTH = 96
const DRAWER_MIN_WIDTH = 480
const DRAWER_VIEWPORT_GUTTER = 60
const drawerWidth = ref(560)
const isResizingDrawer = ref(false)
const horizontalPageScrollbarStyle = computed<CSSProperties>(() => ({
  left: `${pageScrollbarLeft.value}px`,
  right: 'calc(var(--yp-work-items-drawer-inset, 0px) + 12px)',
}))
const verticalPageScrollbarStyle = computed<CSSProperties>(() => ({
  top: `${pageScrollbarTop.value}px`,
  bottom: horizontalOverflow.value ? '12px' : '0px',
  right: 'var(--yp-work-items-drawer-inset, 0px)',
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
    if (nextWidth === drawerWidth.value) return
    drawerWidth.value = nextWidth
    if (!embedded.value) {
      document.body.style.setProperty('--yp-work-items-drawer-width', `${nextWidth}px`)
      document.body.style.setProperty('--yp-work-items-drawer-inset', `${nextWidth}px`)
    }
  }

  const onPointerUp = () => {
    isResizingDrawer.value = false
    scheduleResponsiveTableLayout()
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
  column: { property?: string }
}): string {
  if (!column.property || column.property === 'title') return ''
  return selectedCellKey.value === `${row.id}:${column.property}` ? 'monday-cell--selected' : ''
}

function syncProjectPageScrollLayout(): void {
  if (embedded.value) { scheduleResponsiveTableLayout(); return }
  document.body.classList.toggle('yp-work-items-drawer-open', detailOpen.value)
  if (detailOpen.value) {
    document.body.style.setProperty('--yp-work-items-drawer-width', `${drawerWidth.value}px`)
  }
  document.body.style.setProperty('--yp-work-items-drawer-inset', detailOpen.value ? `${drawerWidth.value}px` : '0px')
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
  if (embedded.value) return

  const contextNavigation = document.querySelector<HTMLElement>('.app-shell--workspace .context-navigation')
  const appMain = document.querySelector<HTMLElement>('.app-shell--workspace .app-main')
  const contextRect = contextNavigation?.getBoundingClientRect()
  const appMainRect = appMain?.getBoundingClientRect()
  const contextVisible = Boolean(contextNavigation && contextRect && contextRect.width > 0
    && getComputedStyle(contextNavigation).display !== 'none')
  pageScrollbarLeft.value = Math.max(0, Math.round(contextVisible ? contextRect!.right : (appMainRect?.left ?? 0)))

  pageScrollbarTop.value = Math.max(0, Math.round(tableScrollElement?.getBoundingClientRect().top ?? 0))
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
  if (horizontalPageScrollbar.value) projectPageResizeObserver.observe(horizontalPageScrollbar.value)
  if (verticalPageScrollbar.value) projectPageResizeObserver.observe(verticalPageScrollbar.value)
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
  timeState: '' as '' | TimeTrackingState, timeMin: '', timeMax: '',
  contents: new Set<string>(), dueRange: [] as Date[], updatedAfter: null as Date | null,
})
interface SortRule { field: string; direction: 'ASC' | 'DESC' }
const sortRules = ref<SortRule[]>([])
const savingSortOrder = ref(false)
const stopTimerUpdates = onTimeTrackingChanged(() => {
  if (embedded.value) return
  if (sortRules.value.some(rule => rule.field === 'TIME_TRACKING') || filters.timeState || filters.timeMin !== '' || filters.timeMax !== '') void loadTable()
})
onBeforeUnmount(stopTimerUpdates)
type ColumnKey = 'title' | 'assignee' | 'status' | 'priority' | 'content' | 'dueDate' | 'timeTracking' | 'updatedAt'
type MovableColumnKey = Exclude<ColumnKey, 'title'>
const columns: Array<{ key: ColumnKey; label: string; defaultWidth: number; minWidth: number }> = [
  { key: 'title', label: '工作项名称', defaultWidth: 320, minWidth: 220 },
  { key: 'assignee', label: '处理人', defaultWidth: 90, minWidth: 72 },
  { key: 'status', label: '状态', defaultWidth: 96, minWidth: 96 },
  { key: 'priority', label: '优先级', defaultWidth: 90, minWidth: 90 },
  { key: 'content', label: '工作项类别', defaultWidth: 110, minWidth: 110 },
  { key: 'dueDate', label: '截止日期', defaultWidth: 140, minWidth: 112 },
  { key: 'timeTracking', label: '时长追踪', defaultWidth: 140, minWidth: 120 },
  { key: 'updatedAt', label: '最后更新时间', defaultWidth: 170, minWidth: 135 },
]
const sortFieldByColumn: Record<ColumnKey, string> = {
  title: 'TITLE',
  assignee: 'ASSIGNEE',
  status: 'STATUS',
  priority: 'PRIORITY',
  content: 'CONTENT',
  dueDate: 'DUE_DATE',
  timeTracking: 'TIME_TRACKING', updatedAt: 'UPDATED_AT',
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
const projectTablePrefsKey = `yumpoo:project-work-items:table:v${TABLE_PREFS_VERSION}`
const tablePrefsKey = computed(() => props.preferenceScope ? `${props.preferenceScope}:columns` : projectTablePrefsKey)
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
const quickGridStyle = computed(() => ({
  '--work-item-menu-column-width': `${TABLE_MENU_COLUMN_WIDTH}px`,
  gridTemplateColumns: [`${TABLE_MENU_COLUMN_WIDTH}px`, `${TABLE_EXPAND_COLUMN_WIDTH}px`, `${TABLE_SELECTION_COLUMN_WIDTH}px`,
    ...visibleColumns.value.map(item => `${columnWidths[item.key]}px`), `${TABLE_ADD_COLUMN_MIN_WIDTH}px`].join(' '),
}))
const hasExplicitSort = computed(() => sortRules.value.length > 0)
const filteredMembers = computed(() => {
  const query = assigneeSearch.value.trim().toLocaleLowerCase()
  return query
    ? (assigneeMatches.value ?? activeMembers.value.filter(item => item.displayName.toLocaleLowerCase().includes(query)))
    : activeMembers.value
})

const contentsById = computed(() => new Map((catalog.value?.items ?? []).map(item => [item.id, item])))
const activeContents = computed(() => (catalog.value?.items ?? []).filter(item => item.active))
const defaultContentId = computed(() => activeContents.value[0]?.id)
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
const { draft: inlineDraft, rows: displayWorkItemRows, isDraft, start: createBelow, save: saveInlineDraft, cancel: cancelInlineDraft } = useWorkItemInlineCreate({
  contextId: () => projectId.value,
  items: () => tableItems.value,
  canCreate: () => canCreate.value && !hasExplicitSort.value && !editingCell.value && !tableSorting.value,
  content: () => activeContents.value[0],
  status: () => workflowStatuses.value.find(status => status.active && status.statusCode === 'NOT_STARTED'),
  created: (created, anchorId) => {
    const next = [...tableItems.value]
    next.splice(next.findIndex(item => item.id === anchorId) + 1, 0, { ...created, subitemCount: 0, discussionCount: 0 })
    tableItems.value = next
    notifyChanged()
    void reloadSortedTableInPlace()
  },
})
const session = useSession()
const groupingClock = useWorkItemDueClock()
const projectGroupingPrefsKey = () => `yumpoo:project-work-items:grouping:v1:${session.authentication.value?.company.id ?? 'unknown'}:${session.authentication.value?.user.id ?? 'unknown'}:${projectId.value}`
const grouping = useWorkItemGrouping({
  source: () => tableSource,
  fallbackPreferenceKey: projectGroupingPrefsKey,
  items: tableItems,
  preferenceKey: () => props.preferenceScope ? `${props.preferenceScope}:grouping` : projectGroupingPrefsKey(),
  request: () => listRequest(null),
  sources: () => ({ members: members.value, statuses: labelCatalog.value?.statuses ?? [],
    priorities: labelCatalog.value?.priorities ?? [], contents: catalog.value?.items ?? [] }),
  today: () => companyDate(groupingClock.value, session.authentication.value?.company.timezone ?? 'Asia/Shanghai'),
  ready: () => Boolean(project.value && selectedView.value === 'table'),
  restore: () => reloadSortedTableInPlace(selectedRowId.value),
  protectedIds: () => new Set([...expandedSubitemIds.value, ...selectedWorkItemIds.value,
    ...[selectedRowId.value, inlineDraft.value?.anchorId].filter((id): id is string => Boolean(id))]),
})
const { active: grouped, field: groupingField, order: groupingOrder, showEmpty: showEmptyGroups,
  groups: workItemGroups, countsReady: groupCountsReady, error: groupingError, loading: groupsLoading } = grouping
const groupingSwitching = ref(false)
function groupCreateDisabledReason(group: WorkItemGroup): string {
  if (!canCreate.value) return '当前无法添加工作项'
  if (group.key === EMPTY_GROUP) return ''
  switch (groupingField.value) {
    case 'CONTENT': return activeContents.value.some(item => item.id === group.key) ? '' : '此类别已停用'
    case 'STATUS': return workflowStatuses.value.some(item => item.code === group.key && item.active) ? '' : '此状态已停用'
    case 'PRIORITY': return priorityOptions.value.some(item => item.code === group.key && item.active) ? '' : '此优先级已停用'
    case 'ASSIGNEE': return activeMembers.value.some(item => item.userId === group.key) ? '' : '此处理人已不在项目中'
    case 'DUE_DATE': return group.from && group.to && group.from > group.to ? '当前日期下此分组没有可用日期' : ''
    default: return ''
  }
}
const groupCreate = useWorkItemGroupCreate({
  projectId: () => projectId.value, field: () => groupingField.value, contentId: () => defaultContentId.value,
  disabledReason: groupCreateDisabledReason, changed: () => { notifyChanged(); return reloadSortedTableInPlace() },
})
type TableDisplayRow = ProjectWorkItemListItem | WorkItemGroupDisplayRow
function groupKeyForRow(row: TableDisplayRow): string {
  if (isGroupDisplayRow(row)) return row.group.key
  const anchor = isDraft(row) ? tableItems.value.find(item => item.id === inlineDraft.value?.anchorId) : undefined
  return grouping.keyOf(anchor ?? row)
}
const displayTableItems = computed<TableDisplayRow[]>(() => {
  const withSubitems = (items: ProjectWorkItemListItem[], group: WorkItemGroup): TableDisplayRow[] => items.flatMap(item =>
    expandedSubitemIds.value.includes(item.id)
      ? [item, { id: `subitems:${item.id}`, groupRowKind: 'subitems', group, parent: item } as WorkItemGroupDisplayRow] : [item])
  if (!grouped.value) return withSubitems(displayWorkItemRows.value, { key: '', label: '', color: 'rgb(87, 155, 252)', rank: 0, count: 0 })
  const result: TableDisplayRow[] = []
  const rows = new Map<string, ProjectWorkItemListItem[]>()
  for (const item of displayWorkItemRows.value) {
    const key = groupKeyForRow(item)
    if (!rows.has(key)) rows.set(key, [])
    rows.get(key)!.push(item)
  }
  workItemGroups.value.forEach((group, index) => {
    const add = (kind: WorkItemGroupDisplayRow['groupRowKind']) => result.push({ id: `group:${groupingField.value}:${group.key}:${kind}`, groupRowKind: kind, group })
    if (index) add('spacer')
    add('heading'); add('columns')
    result.push(...withSubitems(rows.get(group.key) ?? [], group))
    add('load')
    add('add')
  })
  return result
})
const groupSentinels = new Map<string, HTMLElement>()
const groupHeadings = new Map<string, HTMLElement>()
let groupObserver: IntersectionObserver | undefined
function setGroupSentinel(key: string, element: unknown, heading = false): void {
  const targets = heading ? groupHeadings : groupSentinels
  const previous = targets.get(key)
  if (previous === element) return
  if (previous) groupObserver?.unobserve(previous)
  if (element instanceof HTMLElement) {
    element.dataset.groupKey = key
    targets.set(key, element)
    groupObserver?.observe(element)
  } else targets.delete(key)
}
function loadVisibleGroups(): void {
  if (!grouped.value || !groupCountsReady.value) return
  const root = resolveTableScrollElement()?.getBoundingClientRect()
  groupSentinels.forEach((element, key) => {
    if (grouping.isCollapsed(key) || grouping.page(key).error) return
    const rect = element.getBoundingClientRect()
    const start = groupHeadings.get(key)?.getBoundingClientRect()
    const groupTop = grouping.page(key).loaded ? rect.top : start?.top ?? rect.top
    if (rect.height > 0 && rect.bottom >= (root?.top ?? 0) - 240 && groupTop <= (root?.bottom ?? window.innerHeight) + 240)
      void grouping.load(key)
  })
}
function groupSpan({ row, columnIndex }: { row: TableDisplayRow; columnIndex: number }): { rowspan: number; colspan: number } | undefined {
  if (!isGroupDisplayRow(row) || row.groupRowKind === 'columns') return
  const first = ['spacer', 'subitems', 'add'].includes(row.groupRowKind) ? 0 : 2
  if (columnIndex < first) return
  return { rowspan: columnIndex === first ? 1 : 0, colspan: columnIndex === first ? visibleColumns.value.length + 4 - first : 0 }
}
function groupRowClasses(context: { row: TableDisplayRow; rowIndex: number }): string {
  const { row } = context
  if (!grouped.value && !isGroupDisplayRow(row)) return tableRowClassName({ ...context, row })
  const key = groupKeyForRow(row)
  const hidden = grouping.isCollapsed(key) ? ' work-item-group-collapsed' : ''
  if (isGroupDisplayRow(row)) return `work-item-group-${row.groupRowKind}${hidden}`
  return `${tableRowClassName({ ...context, row })}${hidden}`
}
function groupRowStyles(context: { row: TableDisplayRow; rowIndex: number }): CSSProperties {
  const { row } = context
  if (!grouped.value && !isGroupDisplayRow(row)) return tableRowStyle({ row, rowIndex: tableItems.value.findIndex(item => item.id === row.id) })
  const key = groupKeyForRow(row)
  const group = workItemGroups.value.find(candidate => candidate.key === key)
  const style: CSSProperties = { '--work-item-group-accent': group?.color }
  if (isGroupDisplayRow(row)) return style
  return { ...style, ...tableRowStyle({ row, rowIndex: tableItems.value.findIndex(item => item.id === row.id) }) }
}
function groupCellClasses(context: { row: TableDisplayRow; column: { property?: string } }): string {
  if (isGroupDisplayRow(context.row) && context.row.groupRowKind === 'subitems') return 'el-table__expanded-cell'
  if (isGroupDisplayRow(context.row)) return context.row.groupRowKind === 'columns'
    ? `work-item-group-column-header monday-sortable-column-header${context.column.property && context.column.property !== 'title' ? ' monday-movable-column-header' : ''}` : ''
  return tableCellClassName({ ...context, row: context.row })
}
function groupSelection(group: WorkItemGroup): { checked: boolean; mixed: boolean } {
  const items = grouping.page(group.key).items
  const count = items.filter(item => selectedWorkItemIds.value.has(item.id)).length
  return { checked: items.length > 0 && count === items.length, mixed: count > 0 && count < items.length }
}
function selectGroupRows(group: WorkItemGroup, checked: boolean): void {
  grouping.page(group.key).items.forEach(item => tableRef.value?.toggleRowSelection(item, checked))
}
async function changeGrouping(field: GroupField | ''): Promise<void> {
  loadRevision++
  activeController?.abort()
  activeController = new AbortController()
  tableLoading.value = false; tableSorting.value = false
  clearTablePointerTracking(); resetTableDragState()
  groupingSwitching.value = true
  try {
    await grouping.setField(field)
    scheduleResponsiveTableLayout()
    await nextTick()
  } finally { groupingSwitching.value = false }
}
const canPublishDiscussion = computed(() => Boolean(project.value
  && project.value.lifecycle !== ProjectLifecycle.Archived
  && detail.value?.capabilities?.canDiscuss
  && (project.value.actorAccess === ProjectActorAccess.Owner
    || project.value.actorAccess === ProjectActorAccess.Member)))
const discussionReadOnlyReason = computed(() => {
  if (detail.value?.archived) return '工作项已归档，恢复后可继续编辑和讨论。'
  if (project.value?.lifecycle === ProjectLifecycle.Archived) return 'Project 已归档，工作项讨论仅可查看。'
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
  if (state.loading) {
    if (force) state.reloadRequested = true
    return
  }
  if (state.loaded && !force) return
  state.loading = true
  delete state.error
  try {
    const result = await tableSource.listWorkItemSubitems({
      parentWorkItemId: parentId,
      ...(state.sortRules.length
        ? { sort: state.sortRules.map(rule => `${rule.field},${rule.direction}`) }
        : {}),
    })
    if (subitems[parentId] !== state) return
    state.items = result.items
    state.loaded = true
  } catch (reason) {
    state.error = await toApiProblem(reason)
  } finally {
    state.loading = false
    schedulePageScrollbarSync()
    if (state.reloadRequested && subitems[parentId] === state) {
      delete state.reloadRequested
      await loadSubitems(parentId, true)
    }
  }
}

function onTableExpandChange(row: TableDisplayRow,
  expanded: TableDisplayRow[] | boolean): void {
  if (isGroupDisplayRow(row)) return
  const expandedRows = Array.isArray(expanded) ? expanded.filter(item => !isGroupDisplayRow(item)) : expanded
    ? [...tableItems.value.filter(item => expandedSubitemIds.value.includes(item.id)), row]
    : tableItems.value.filter(item => item.id !== row.id
      && expandedSubitemIds.value.includes(item.id))
  expandedSubitemIds.value = [...new Set(expandedRows.map(item => item.id))]
  if (expandedRows.some(item => item.id === row.id)) void loadSubitems(row.id)
  schedulePageScrollbarSync()
}

function toggleSubitems(row: ProjectWorkItemListItem): void {
  onTableExpandChange(row, !expandedSubitemIds.value.includes(row.id))
}

function setSubitemTableHandle(id: string, handle: unknown): void {
  if (handle) subitemTableHandles.set(id, handle as { openQuick: () => void; canClose: () => boolean })
  else subitemTableHandles.delete(id)
}

async function addSubitem(row: ProjectWorkItemListItem): Promise<void> {
  if (!canCreate.value) return
  onTableExpandChange(row, true)
  await nextTick()
  subitemTableHandles.get(row.id)?.openQuick()
}

async function beforeRowRemove(row: ProjectWorkItemListItem): Promise<boolean> {
  return detailOpen.value && detail.value?.id === row.id ? beforeDraftLeave() : true
}

async function onRowRemoved(row: ProjectWorkItemListItem): Promise<void> {
  selectedWorkItemIds.value.delete(row.id)
  Object.values(subitemSelections).forEach(selection => selection.delete(row.id))
  delete subitemSelections[row.id]
  expandedSubitemIds.value = expandedSubitemIds.value.filter(id => id !== row.id)
  if (detail.value?.id === row.id) {
    detailOpen.value = false
    await closeDetailRoute()
  }
}

async function restoreArchivedItem(): Promise<void> {
  if (!detail.value?.archived || !detail.value.capabilities.canDelete || restoringArchive.value) return
  const csrf = readCsrfToken()
  if (!csrf) { error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  restoringArchive.value = true
  const restoringItem = detail.value
  try {
    const restored = await workItemsApi.unarchiveWorkItem({ workItemId: restoringItem.id, xXSRFTOKEN: csrf,
      ifMatch: restoringItem.etag, idempotencyKey: crypto.randomUUID() })
    if (detail.value?.id === restoringItem.id) detail.value = restored
    await onRelationsChanged([restoringItem.id, ...expandedSubitemIds.value])
    ElMessage.success('工作项已恢复')
  } catch (reason) {
    error.value = await toApiProblem(reason)
    if (isProblemStatus(error.value, 409) || isProblemStatus(error.value, 412)) {
      try {
        const latest = await workItemsApi.getWorkItem({ workItemId: restoringItem.id })
        if (detail.value?.id === restoringItem.id) detail.value = latest
      } catch { /* Keep the original mutation error visible. */ }
    }
  }
  finally { restoringArchive.value = false }
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
  notifyChanged()
  bumpSubitemCount(parent.id, 1)
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
  return contentsById.value.get(contentId)?.name ?? '未知类别'
}

function contentLabel(item: Pick<ProjectWorkItemListItem, 'contentId' | 'contentName' | 'contentColorToken'>) {
  return contentsById.value.get(item.contentId) ?? { name: item.contentName, colorToken: item.contentColorToken }
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
  if (!priority) return { label: '-', tone: 'empty' }
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

function formatDate(value: Date | string | null): string {
  return value ? new Date(value).toISOString().slice(0, 10) : '—'
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
  filters.timeState = Object.values(TimeTrackingState).includes(String(route.query.timeState) as TimeTrackingState) ? String(route.query.timeState) as TimeTrackingState : ''
  filters.timeMin = String(route.query.timeMin ?? '')
  filters.timeMax = String(route.query.timeMax ?? '')
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
  for (const key of ['timeState', 'timeMin', 'timeMax'] as const) {
    if (filters[key] !== '') next[key] = filters[key]
    else delete next[key]
  }
  const sortValue = sortRules.value.map(item => `${item.field},${item.direction}`).join(';')
  if (sortValue) next.sort = sortValue
  else delete next.sort
  await setViewQuery(Object.fromEntries(Object.entries(next).filter(([, value]) => value !== undefined)) as LocationQuery, true)
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
    const pages = await Promise.all(fields.map(field => tableSource.listProjectWorkItemFilterOptions({
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
    view: WorkItemViewType.Table,
    ...(cursor ? { cursor } : {}),
    ...(searchInput.value.trim() ? { q: searchInput.value.trim() } : {}),
    ...(filters.statuses.size ? { status: filters.statuses } : {}),
    ...(filters.priorities.size ? { priority: filters.priorities } : {}),
    ...(filters.assignees.size ? { assigneeUserId: filters.assignees } : {}),
    ...(filters.contents.size ? { contentId: filters.contents } : {}),
    ...(filters.dueRange[0] ? { dueFrom: filters.dueRange[0] } : {}),
    ...(filters.dueRange[1] ? { dueTo: filters.dueRange[1] } : {}),
    ...(filters.timeState ? { timeTrackingState: filters.timeState } : {}),
    ...(filters.timeMin !== '' ? { timeTrackingMinMs: Number(filters.timeMin) * 1000 } : {}),
    ...(filters.timeMax !== '' ? { timeTrackingMaxMs: Number(filters.timeMax) * 1000 } : {}),
    ...(filters.updatedAfter ? { updatedAfter: filters.updatedAfter } : {}),
    ...(sortRules.value.length ? { sort: sortRules.value.map(item => `${item.field},${item.direction}`) } : {}),
  }
}

async function loadTable(cursor: string | null = null, append = false, revision = loadRevision): Promise<void> {
  if (grouped.value && selectedView.value === 'table') { await grouping.refresh(); return }
  if (tableLoading.value || tableSorting.value) return
  tableLoading.value = true
  loadingMoreError.value = undefined
  try {
    const result = await tableSource.listProjectWorkItems(listRequest(cursor), { signal: activeController?.signal ?? null })
    if (revision !== loadRevision) return
    const merged = append ? [...tableItems.value, ...result.items] : result.items
    tableItems.value = [...new Map(merged.map(item => [item.id, item])).values()]
    tableNextCursor.value = result.nextCursor
    await nextTick()
  } catch (reason) {
    if (revision === loadRevision) {
      const problem = await toApiProblem(reason)
      if (append && problem.kind === 'response' && JSON.stringify(problem.error).includes('TIME_TRACKING_CURSOR_EXPIRED')) {
        tableLoading.value = false
        ElMessage.info('计时记录已变化，已重新加载列表。')
        await loadTable()
      } else if (append) loadingMoreError.value = problem; else error.value = problem
    }
  } finally {
    if (revision === loadRevision) tableLoading.value = false
  }
}

async function reloadSortedTableInPlace(revealWorkItemId?: string): Promise<void> {
  if (grouped.value && selectedView.value === 'table') { await grouping.refresh(); return }
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
      const result = await tableSource.listProjectWorkItems(
        listRequest(cursor),
        { signal: controller.signal },
      )
      if (revision !== loadRevision) return
      result.items.forEach(item => loaded.set(item.id, item))
      nextCursor = result.nextCursor
      if (!nextCursor || nextCursor === cursor
        || (loaded.size >= minimumItemCount && (!revealWorkItemId || loaded.has(revealWorkItemId))
          && expandedSubitemIds.value.every(id => loaded.has(id))
          && (!inlineDraft.value?.anchorId || loaded.has(inlineDraft.value.anchorId)))) break
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
    const result = await tableSource.listProjectWorkItems({
      projectId: projectId.value,
      view: WorkItemViewType.Kanban,
      status: new Set([statusCode]),
      limit: 25,
      ...(cursor ? { cursor } : {}),
      ...(searchInput.value.trim() ? { q: searchInput.value.trim() } : {}),
      ...(filters.assignees.size ? { assigneeUserId: filters.assignees } : {}),
      ...(filters.priorities.size ? { priority: filters.priorities } : {}),
      ...(filters.contents.size ? { contentId: filters.contents } : {}),
      ...(filters.dueRange[0] ? { dueFrom: filters.dueRange[0] } : {}),
      ...(filters.dueRange[1] ? { dueTo: filters.dueRange[1] } : {}),
      ...(filters.timeState ? { timeTrackingState: filters.timeState } : {}),
    ...(filters.timeMin !== '' ? { timeTrackingMinMs: Number(filters.timeMin) * 1000 } : {}),
    ...(filters.timeMax !== '' ? { timeTrackingMaxMs: Number(filters.timeMax) * 1000 } : {}),
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
  grouping.stop()
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
  await setViewQuery({ ...route.query, view })
}

function openQuick(): void {
  if (!canCreate.value) return
  quickOpen.value = true
  void nextTick(() => quickTitleInput.value?.focus())
}

function closeQuick(): void {
  quickOpen.value = false
  quickTitle.value = ''
}

async function createQuick(continueAdding: boolean): Promise<void> {
  if (quickCreating.value || !defaultContentId.value || !quickTitle.value.trim()) return
  const csrf = readCsrfToken()
  if (!csrf) {
    error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  quickCreating.value = true
  error.value = undefined
  try {
    const created = await workItemsApi.createWorkItem({
      projectId: projectId.value,
      xXSRFTOKEN: csrf,
      idempotencyKey: globalThis.crypto.randomUUID(),
      workItemCreateRequest: {
        contentId: defaultContentId.value,
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
    notifyChanged()
    ElMessage.success(`已创建 ${created.itemNo}`)
    if (continueAdding) {
      quickTitle.value = ''
      await nextTick()
      quickTitleInput.value?.focus()
    } else closeQuick()
    if (selectedView.value === 'table') await reloadSortedTableInPlace()
    else await refreshCurrentView()
  } catch (reason) {
    error.value = await toApiProblem(reason)
    await nextTick()
    quickTitleInput.value?.focus()
  } finally {
    quickCreating.value = false
    if (continueAdding && quickOpen.value) {
      await nextTick()
      quickTitleInput.value?.focus()
    }
  }
}

function onQuickKeydown(rawEvent: Event | KeyboardEvent): void {
  const event = rawEvent as KeyboardEvent
  if (event.isComposing || event.keyCode === 229) return
  if (event.key === 'Escape') { closeQuick(); return }
  if (event.key !== 'Enter') return
  event.preventDefault()
  void createQuick(event.shiftKey)
}

function onDocumentPointerDown(event: PointerEvent): void {
  if (isWorkItemViewControl(event.target)) return
  if (grouped.value || !quickOpen.value || quickCreating.value) return
  if (quickRow.value?.contains(event.target as Node)) return
  closeQuick()
}

async function loadDetail(workItemId: string, tab: 'details' | 'discussion' | 'relations' | 'activity' = 'details'): Promise<void> {
  const current = ++detailGeneration
  detailOpen.value = true
  detailTab.value = tab
  detailLoading.value = true
  detail.value = undefined
  try {
    const loaded = await workItemsApi.getWorkItem({ workItemId })
    if (current === detailGeneration) detail.value = loaded
  } catch (reason) {
    const failure = await toApiProblem(reason)
    if (current !== detailGeneration) return
    error.value = failure
    detailOpen.value = false
    await closeDetailRoute()
  } finally {
    if (current === detailGeneration) detailLoading.value = false
  }
}

async function openRelatedWorkItem(target: { workItemId: string, projectId: string }): Promise<void> {
  if (target.projectId === projectId.value) {
    if (!await beforeDraftLeave()) return
    await loadDetail(target.workItemId)
    return
  }
  await router.push({
    name: 'project-overview',
    params: { projectId: target.projectId },
    query: { workItemId: target.workItemId },
  })
}

async function onDiscussionChanged(workItemId: string): Promise<void> {
  notifyChanged()
  const parents = Object.keys(subitems).filter(id => subitems[id]!.items.some(item => item.id === workItemId))
  parents.forEach(id => { subitems[id]!.loaded = false })
  await Promise.all([
    selectedView.value === 'table' ? reloadSortedTableInPlace() : refreshCurrentView(),
    ...parents.filter(id => expandedSubitemIds.value.includes(id)).map(id => loadSubitems(id, true)),
  ])
}

async function onRelationsChanged(affectedWorkItemIds: string[]): Promise<void> {
  notifyChanged()
  for (const id of affectedWorkItemIds) {
    if (subitems[id]) subitems[id].loaded = false
  }
  if (selectedView.value === 'table') await reloadSortedTableInPlace()
  else await refreshCurrentView()
  await Promise.all(expandedSubitemIds.value
    .filter(id => affectedWorkItemIds.includes(id))
    .map(id => loadSubitems(id, true)))
}

async function tableEdgeItems(_item: ProjectWorkItemListItem, edge: 'top' | 'bottom'): Promise<ProjectWorkItemListItem[]> {
  const rows: ProjectWorkItemListItem[] = []
  let cursor: string | null = null
  const seen = new Set<string>()
  do {
    const result = await tableSource.listProjectWorkItems(listRequest(cursor))
    rows.push(...result.items)
    cursor = result.nextCursor
    if (cursor && seen.has(cursor)) throw new Error('工作项游标重复，请刷新后重试。')
    if (cursor) seen.add(cursor)
  } while (cursor && edge === 'bottom')
  return rows
}
async function onRowMoved(item: ProjectWorkItemListItem, parentId?: string): Promise<void> {
  selectCell(item.id, 'title')
  if (parentId) await loadSubitems(parentId, true)
  else await reloadSortedTableInPlace(item.id)
  await nextTick()
  tableRef.value?.$el.querySelector<HTMLElement>('.work-item-name-cell.monday-cell--selected')
    ?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })
}

async function openDetail(item: ProjectWorkItemListItem, tab: 'details' | 'discussion' | 'activity'): Promise<void> {
  if (detailOpen.value && detail.value?.id === item.id && detailTab.value === tab) return
  if (!await beforeDraftLeave()) return
  selectCell(item.id, tab === 'details' ? 'title' : tab === 'activity' ? 'updatedAt' : 'discussion')
  detailTab.value = tab
  if (String(route.query.workItemId ?? '') === item.id) {
    await loadDetail(item.id, tab)
    return
  }
  await setViewQuery({ ...route.query, workItemId: item.id })
}

async function closeDetailRoute(): Promise<void> {
  if (!route.query.workItemId) return
  const next = { ...route.query }
  delete next.workItemId
  await setViewQuery(next)
}

function onDetailModelValue(value: boolean): void {
  detailOpen.value = value
  if (!value) {
    if (!embedded.value) document.body.style.removeProperty('--yp-work-items-drawer-width')
    void closeDetailRoute()
  }
}

function onLabelsUpdated(next: WorkItemLabelCatalog): void {
  notifyChanged()
  labelCatalog.value = next
}

function onContentsUpdated(next: ProjectContentCatalog): void {
  notifyChanged()
  catalog.value = next
}

function labelPopoverKey(itemId: string, kind: 'status' | 'priority' | 'content'): string {
  return `${itemId}:${kind}`
}

function setLabelPopoverContentRef(key: string, value: unknown): void {
  const instance = value as LabelPopoverContentHandle | null
  if (instance?.resetEditor) labelPopoverContentRefs.set(key, instance)
  else labelPopoverContentRefs.delete(key)
}

function resetLabelPopoverContent(itemId: string, kind: 'status' | 'priority' | 'content'): void {
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
  notifyChanged()
  const apply = (item: ProjectWorkItemListItem): ProjectWorkItemListItem => {
    if (item.id !== id) return item
    const updated = {
      ...item, title: updatedDetail.title, contentId: updatedDetail.contentId, contentName: updatedDetail.contentName,
      contentColorToken: updatedDetail.contentColorToken,
      statusCode: updatedDetail.statusCode, statusCategory: updatedDetail.statusCategory,
      priority: updatedDetail.priority,
      assigneeUserId: updatedDetail.assigneeUserId,
      assigneeDisplayName: updatedDetail.assigneeDisplayName,
      dueDate: updatedDetail.dueDate, dueTime: updatedDetail.dueTime ?? null,
      completedAt: updatedDetail.completedAt ?? null, updatedAt: updatedDetail.updatedAt,
      ...(updatedDetail.updatedByUserId ? { updatedByUserId: updatedDetail.updatedByUserId } : {}),
      ...(updatedDetail.updatedByDisplayName ? { updatedByDisplayName: updatedDetail.updatedByDisplayName } : {}),
      rowVersion: updatedDetail.rowVersion, etag: updatedDetail.etag,
      capabilities: updatedDetail.capabilities,
    }
    if (!updatedDetail.updatedByUserId) delete updated.updatedByUserId
    if (!updatedDetail.updatedByDisplayName) delete updated.updatedByDisplayName
    return updated
  }
  tableItems.value = tableItems.value.map(apply)
  Object.values(lanes).forEach(state => { state.items = state.items.map(apply) })
  Object.values(subitems).forEach(state => { state.items = state.items.map(apply) })
  if (detail.value?.id === id) detail.value = { ...detail.value, ...updatedDetail }
  if (grouped.value && tableItems.value.some(item => item.id === id)) void grouping.changed()
}

const cellEdits = useWorkItemEdits({ updated: replaceLightItem, failed: async problem => { error.value = problem; notifyChanged(); await loadTable(null, false) } })
async function patchCell(item: ProjectWorkItemListItem, field: 'assignee' | 'priority' | 'dueDate' | 'content', value: string | Date | null, dueTime?: string | null): Promise<boolean> {
  if (editingCell.value) return false
  editingCell.value = item.id + ':' + field
  try {
    const updated = await cellEdits.patch(item, field, value, dueTime)
    if (updated && field === 'content') catalog.value = await contentsApi.listProjectContents({ projectId: projectId.value })
    return updated
  } catch (reason) { error.value = await toApiProblem(reason); return false }
  finally { editingCell.value = '' }
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
  if (grouped.value) return
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
    const parsed = JSON.parse(localStorage.getItem(tablePrefsKey.value) ?? (embedded.value ? localStorage.getItem(projectTablePrefsKey) : null) ?? '{}') as {
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

  const draggedWidth = tableColumnPointerCandidate?.headerRects[from]?.width || columnWidths[draggedKey]
  if (from < dropIndex && index > from && index < dropIndex) {
    return { transform: `translateX(-${draggedWidth}px)` }
  }
  if (from > dropIndex && index >= dropIndex && index < from) {
    return { transform: `translateX(${draggedWidth}px)` }
  }
  return { transform: 'translateX(0px)' }
}

function tableCellStyle({ column }: { column: { property?: string } }): CSSProperties {
  return tableColumnDragStyle(column.property)
}

function tableHeaderCellStyle({ column }: { column: { property?: string } }): CSSProperties {
  return tableColumnDragStyle(column.property)
}

function movableHeaderCells(source?: HTMLTableCellElement): HTMLTableCellElement[] {
  if (grouped.value) {
    const row = source?.parentElement ?? tableRef.value?.$el?.querySelector('.work-item-group-columns:not(.work-item-group-collapsed)')
    return [...(row?.querySelectorAll<HTMLTableCellElement>('td.monday-movable-column-header') ?? [])]
  }
  return [...(tableRef.value?.$el?.querySelectorAll<HTMLTableCellElement>(
    ':scope > .el-table__inner-wrapper > .el-table__header-wrapper th.monday-movable-column-header',
  ) ?? [])]
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
  const header = handle.closest<HTMLTableCellElement>(grouped.value ? '.work-item-group-column-header' : '.monday-movable-column-header')
  if (!columnKey || !config || (columnKey === 'title' && !grouped.value) || !header) return
  const key = columnKey

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
  if (key !== 'title') columnResizingKey.value = key
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
  const header = target?.closest<HTMLTableCellElement>('.monday-movable-column-header')
  if (!header) return
  const rect = header.getBoundingClientRect()
  if (rect.width > 0 && rect.right - event.clientX < TABLE_COLUMN_RESIZE_HANDLE_WIDTH) return
  const headers = movableHeaderCells(header)
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
  if ((event.target as Element | null)?.closest('.timer-cell')) return
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

function onTableSelectionChange(rows: TableDisplayRow[]): void {
  selectedWorkItemIds.value = new Set(rows.filter(row => !isGroupDisplayRow(row) && !isDraft(row)).map(row => row.id))
}

function tableRowClassName({ row }: { row: ProjectWorkItemListItem; rowIndex: number }): string {
  const classes = ['work-item-table-row']
  if (isDraft(row)) classes.push('work-item-draft-row')
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
  return new Map([...(rows ?? [])].flatMap(row => {
    const id = row.querySelector<HTMLElement>('[data-work-item-id]')?.dataset.workItemId
    return id ? [[id, row.getBoundingClientRect().top] as const] : []
  }))
}

function animateTableReorder(previous: Map<string, number>): void {
  const rows = tableRef.value?.$el?.querySelectorAll(
    '.el-table__body-wrapper tbody tr.work-item-table-row',
  ) as NodeListOf<HTMLElement> | undefined
  rows?.forEach(row => {
    const id = row.querySelector<HTMLElement>('[data-work-item-id]')?.dataset.workItemId
    const before = id ? previous.get(id) : undefined
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
  const id = row.querySelector<HTMLElement>('[data-work-item-id]')?.dataset.workItemId
  return tableItems.value.findIndex(item => item.id === id)
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
  if ((event.target as Element | null)?.closest('.timer-cell')) return
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
  if (grouped.value && tableDragging.value) {
    const key = grouping.keyOf(tableDragging.value)
    const groupRows = rows.filter(row => {
      const id = row.querySelector<HTMLElement>('[data-work-item-id]')?.dataset.workItemId
      return grouping.page(key).items.some(item => item.id === id) && row.getBoundingClientRect().height > 0
    })
    const first = groupRows[0]?.getBoundingClientRect()
    const last = groupRows.at(-1)?.getBoundingClientRect()
    if (!first || !last || clientY < first.top - 16 || clientY > last.bottom + 16) {
      tableDropIndex.value = undefined
      return
    }
    const row = groupRows.find(candidate => clientY < candidate.getBoundingClientRect().top + candidate.getBoundingClientRect().height / 2)
    tableDropIndex.value = row ? rowIndexFromTarget(row) : rowIndexFromTarget(groupRows.at(-1)!) + 1
    return
  }
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
  if (!event.isPrimary || event.button !== 0 || tableDragging.value || inlineDraft.value) return
  const target = event.target as HTMLElement | null
  if (target?.closest('.subitem-expand-button, .monday-subitems-counter-component, .work-item-name-cell--editing, .work-item-detail-button')) return
  const dragArea = target?.closest('.work-item-link, .monday-selection-column')
  if (!dragArea) return
  const index = rowIndexFromTarget(target)
  const item = tableItems.value[index]
  const row = target?.closest('tr.work-item-table-row') as HTMLElement | null
  if (!row || !item?.capabilities.canMoveInProjectOrder) return
  if (grouped.value && (!groupCountsReady.value || !grouping.page(grouping.keyOf(item)).loaded)) return

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
  const groupKey = grouped.value ? grouping.keyOf(item) : undefined
  const original = groupKey === undefined ? [...tableItems.value] : [...grouping.page(groupKey).items]
  if (groupKey !== undefined) {
    const offset = tableItems.value.findIndex(candidate => candidate.id === original[0]?.id)
    target -= offset
    if (offset < 0 || target < 0 || target > original.length) return
  }
  const applyOrder = (items: ProjectWorkItemListItem[]) => {
    if (groupKey === undefined) tableItems.value = items
    else grouping.replaceOrder(groupKey, items)
  }
  const from = original.findIndex(candidate => candidate.id === item.id)
  if (from < 0) return
  if (target === original.length && (groupKey === undefined ? tableNextCursor.value : grouping.page(groupKey).nextCursor)) {
    ElMessage.info('正在加载更远的工作项，请稍后继续拖动。')
    if (groupKey === undefined) await loadTable(tableNextCursor.value, true)
    else await grouping.load(groupKey)
    return
  }
  const reordered = [...original]
  reordered.splice(from, 1)
  if (from < target) target -= 1
  target = Math.max(0, Math.min(target, reordered.length))
  reordered.splice(target, 0, item)
  if (reordered.map(row => row.id).join() === original.map(row => row.id).join()) return
  const previousPositions = captureTablePositions()
  applyOrder(reordered)
  await nextTick()
  animateTableReorder(previousPositions)
  const csrf = readCsrfToken()
  if (!csrf) { applyOrder(original); error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
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
    applyOrder(original)
    error.value = await toApiProblem(reason)
    await loadTable(null, false)
  }
}

function clearFilters(): void {
  filters.assignees = new Set(); filters.statuses = new Set(); filters.priorities = new Set(); filters.contents = new Set()
  filters.timeState = ''; filters.timeMin = ''; filters.timeMax = ''
  filters.dueRange = []; filters.updatedAfter = null
  void syncUrl()
}

function resetCurrentData(): void {
  grouping.stop()
  if (grouped.value && selectedView.value === 'table') {
    loadRevision++; activeController?.abort(); tableLoading.value = false; tableSorting.value = false
    void grouping.refresh()
    return
  }
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

function onDueDateChange(item: ProjectWorkItemListItem, value: string | null, dueTime?: string | null): void {
  void patchCell(item, 'dueDate', apiDate(value), dueTime)
}

function onDeadlineChange(item: ProjectWorkItemListItem, value: DueDateValue): void {
  onDueDateChange(item, value.dueDate, value.dueTime)
}

watch(projectId, () => {
  if (embedded.value) {
    try { embeddedQuery.value = JSON.parse(localStorage.getItem(`${props.preferenceScope}:query`) ?? '{}') as LocationQuery } catch { embeddedQuery.value = {} }
  }
  applyRouteState(); void loadWorkspace()
}, { immediate: true })
watch(() => props.refreshKey, async () => {
  if (!project.value) return
  await reloadSortedTableInPlace()
  await Promise.all(expandedSubitemIds.value.map(id => loadSubitems(id, true)))
})
async function canClose(): Promise<boolean> {
  if (editingCell.value || editingNames.value.size || quickCreating.value || quickTitle.value.trim() || inlineDraft.value
      || workItemGroups.value.some(group => { const draft = groupCreate.draft(group); return draft.saving || draft.title.trim() })
      || [...subitemTableHandles.values()].some(handle => handle.canClose?.() === false)) {
    ElMessage.info('请先完成或取消当前工作项编辑。')
    return false
  }
  return beforeDraftLeave()
}
defineExpose({ canClose })
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
    detailGeneration++
    detailOpen.value = false
    detail.value = undefined
  }
}, { immediate: true })
watch(assigneeSearch, scheduleMemberSearch)
watch(detailOpen, syncProjectPageScrollLayout, { flush: 'post' })
watch(isResizingDrawer, value => { if (!embedded.value) document.body.classList.toggle('yp-work-items-drawer-resizing', value) }, { flush: 'sync' })
watch(tableRef, () => {
  observeProjectPageResizeTargets()
  scheduleResponsiveTableLayout()
}, { flush: 'post' })
watch([displayTableItems, () => [...grouping.collapsed.value].join('|'), groupCountsReady], () => {
  schedulePageScrollbarSync()
  void nextTick(loadVisibleGroups)
}, { flush: 'post' })
watch(displayTableItems, () => {
  if (!grouped.value && !groupingSwitching.value) return
  const scroll = resolveTableScrollElement()
  if (!scroll) return
  const top = scroll.getBoundingClientRect().top
  const cells = [...scroll.querySelectorAll<HTMLElement>('[data-work-item-id]')]
  const anchor = cells.find(cell => cell.getBoundingClientRect().height > 0 && cell.getBoundingClientRect().bottom > top)
  const id = anchor?.dataset.workItemId
  const position = anchor?.getBoundingClientRect().top
  const left = scroll.scrollLeft
  const atTop = scroll.scrollTop < 1
  void nextTick(() => {
    scroll.scrollLeft = left
    const next = [...scroll.querySelectorAll<HTMLElement>('[data-work-item-id]')].find(cell => cell.dataset.workItemId === id)
    if (atTop) scroll.scrollTop = 0
    else if (next && next.getBoundingClientRect().height && position !== undefined)
      scroll.scrollTop += next.getBoundingClientRect().top - position
  })
}, { flush: 'pre' })
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
  if (!embedded.value) document.body.classList.add('yp-project-overview-scroll')
  syncProjectPageScrollLayout()
  observeProjectPageResizeTargets()
  window.addEventListener('resize', scheduleResponsiveTableLayout)
  loadTablePrefs()
  document.addEventListener('pointerdown', onDocumentPointerDown)
  tableObserver = new IntersectionObserver(entries => {
    if (!grouped.value && entries.some(entry => entry.isIntersecting) && tableNextCursor.value && !tableLoading.value && !tableSorting.value)
      void loadTable(tableNextCursor.value, true)
  }, { rootMargin: '320px 0px' })
  groupObserver = new IntersectionObserver(entries => {
    if (entries.some(entry => entry.isIntersecting)) loadVisibleGroups()
  }, { rootMargin: '240px 0px' })
  groupSentinels.forEach(element => groupObserver?.observe(element))
  groupHeadings.forEach(element => groupObserver?.observe(element))
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
  groupObserver?.disconnect()
  activeController?.abort(); if (searchTimer) window.clearTimeout(searchTimer)
  if (memberSearchTimer) window.clearTimeout(memberSearchTimer)
  if (suppressTableClickTimer) window.clearTimeout(suppressTableClickTimer)
  if (responsiveTableLayoutFrame !== undefined) window.cancelAnimationFrame(responsiveTableLayoutFrame)
  projectPageResizeObserver?.disconnect()
  tableObserver?.disconnect(); kanbanObserver?.disconnect()
  document.removeEventListener('pointerdown', onDocumentPointerDown)
  window.removeEventListener('resize', scheduleResponsiveTableLayout)
  bindTableScrollElement(undefined)
  if (!embedded.value) {
    document.body.classList.remove('yp-project-overview-scroll', 'yp-work-items-drawer-open', 'yp-work-items-drawer-resizing')
    document.body.style.removeProperty('--yp-work-items-drawer-width')
    document.body.style.removeProperty('--yp-work-items-drawer-inset')
  }
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
    :class="{ 'work-items-embedded': embedded }"
  >
    <inline-problem
      v-if="error"
      :problem="error"
    />
    <template v-if="project">
      <project-workspace-header
        v-if="!embedded"
        section="overview"
        :project="project"
      />

      <section class="work-items-home">
        <div v-if="!embedded" class="monday-view-header">
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

        <div class="work-items-toolbar" aria-label="工作项工具栏" data-work-item-view-control>
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

          <el-popover placement="bottom-start" :width="360" trigger="click" popper-class="work-items-popover work-item-view-control" @show="loadFilterOptions">
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
              <button class="toolbar-button" :class="{ active: filters.statuses.size || filters.priorities.size || filters.contents.size || filters.dueRange.length || filters.timeState || filters.timeMin || filters.timeMax || filters.updatedAfter }">
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
                  <button v-for="status in workflowStatuses" :key="status.statusCode" class="filter-value" :aria-pressed="filters.statuses.has(status.statusCode)" @click="toggleSet(filters.statuses, status.statusCode, !filters.statuses.has(status.statusCode))">
                    <span class="filter-checkbox" :class="{ checked: filters.statuses.has(status.statusCode) }" aria-hidden="true" />
                    <span>{{ status.displayName }}</span><small>{{ countBy('statusCode', status.statusCode) }}</small>
                  </button>
                </section>
                <section>
                  <h4>优先级</h4>
                  <button v-for="priority in priorityOptions" :key="priority.code" class="filter-value" :aria-pressed="filters.priorities.has(priority.code)" @click="toggleSet(filters.priorities, priority.code, !filters.priorities.has(priority.code))">
                    <span class="filter-checkbox" :class="{ checked: filters.priorities.has(priority.code) }" aria-hidden="true" />
                    <span>{{ priority.displayName }}</span><small>{{ countBy('priority', priority.code) }}</small>
                  </button>
                </section>
                <section>
                  <h4>工作项类别</h4>
                  <button v-for="content in catalog?.items ?? []" :key="content.id" class="filter-value" :aria-pressed="filters.contents.has(content.id)" @click="toggleSet(filters.contents, content.id, !filters.contents.has(content.id))">
                    <span class="filter-checkbox" :class="{ checked: filters.contents.has(content.id) }" aria-hidden="true" />
                    <span>{{ content.name }}</span><small>{{ countBy('contentId', content.id) }}</small>
                  </button>
                </section>
              </div>
              <div class="filter-dates">
                <div class="filter-field">
                  <span class="filter-field-label">截止日期</span>
                  <el-date-picker v-model="filters.dueRange" type="daterange" start-placeholder="开始日期" end-placeholder="结束日期" @change="syncUrl" />
                </div>
                <div class="filter-field">
                  <span class="filter-field-label">最后更新时间</span>
                  <el-date-picker v-model="filters.updatedAfter" type="date" aria-label="最后更新时间晚于" placeholder="选择日期之后" @change="syncUrl" />
                </div>
                <div class="filter-field">
                  <span class="filter-field-label">计时状态</span>
                  <el-select v-model="filters.timeState" aria-label="计时状态" placeholder="全部" @change="syncUrl()">
                    <el-option value="" label="全部" /><el-option value="RUNNING" label="计时中" /><el-option value="STOPPED" label="已停止" /><el-option value="EMPTY" label="无记录" />
                  </el-select>
                </div>
                <div class="filter-field">
                  <span class="filter-field-label">累计时长（秒）</span>
                  <div class="filter-duration-range">
                    <el-input v-model="filters.timeMin" aria-label="最少累计秒数" type="number" min="0" placeholder="最少" @change="syncUrl()" />
                    <span aria-hidden="true">—</span>
                    <el-input v-model="filters.timeMax" aria-label="最多累计秒数" type="number" min="0" placeholder="最多" @change="syncUrl()" />
                  </div>
                </div>
              </div>
            </div>
          </el-popover>

          <el-popover placement="bottom-start" :width="420" trigger="click" :disabled="selectedView === 'kanban' || savingSortOrder" popper-class="work-items-popover work-item-view-control">
            <template #reference>
              <button class="toolbar-button" :disabled="selectedView === 'kanban' || savingSortOrder" :class="{ active: sortRules.length }">
                <el-icon><sort /></el-icon><span>排序<span v-if="sortRules.length"> / {{ sortRules.length }}</span></span>
              </button>
            </template>
            <div class="sort-popover">
              <header><strong>排序方式</strong><button class="text-button" @click="clearAllSorts">清除</button></header>
              <div v-for="(rule, index) in sortRules" :key="index" class="sort-rule">
                <el-select v-model="rule.field" popper-class="work-item-view-control" @change="syncUrl">
                  <el-option label="工作项名称" value="TITLE" /><el-option label="处理人" value="ASSIGNEE" />
                  <el-option label="状态" value="STATUS" /><el-option label="优先级" value="PRIORITY" />
                  <el-option label="截止日期" value="DUE_DATE" /><el-option label="累计计时" value="TIME_TRACKING" /><el-option label="最后更新时间" value="UPDATED_AT" />
                </el-select>
                <el-select v-model="rule.direction" popper-class="work-item-view-control" @change="syncUrl"><el-option label="升序" value="ASC" /><el-option label="降序" value="DESC" /></el-select>
              </div>
              <button v-if="sortRules.length < 3" class="popover-add" @click="setSortCount(sortRules.length + 1)">+ 新增排序</button>
            </div>
          </el-popover>

          <el-popover placement="bottom-start" :width="320" trigger="click" :disabled="selectedView === 'kanban'" popper-class="work-items-popover work-item-view-control">
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
          <work-item-grouping-popover :field="groupingField" :order="groupingOrder" :show-empty="showEmptyGroups"
            :disabled="selectedView === 'kanban' || savingSortOrder"
            @field="changeGrouping" @order="groupingOrder = $event" @show-empty="showEmptyGroups = $event" />
          <button class="toolbar-button" @click="openSmallTimer">◷ 小计时器</button>
        </div>

        <div
          v-if="selectedView === 'table'"
          v-loading="tableLoading && !tableItems.length && !grouped"
          :aria-busy="tableLoading || tableSorting || groupsLoading"
          class="table-surface monday-table-surface"
        >
          <div
            class="monday-table-wrapper"
            @pointerdown.capture="onTableSurfacePointerDown"
            @click.capture="onTableClickCapture"
          >
            <el-table
              ref="tableRef"
              :data="displayTableItems"
              :fit="true"
              :row-class-name="groupRowClasses"
              :row-style="groupRowStyles"
              :cell-class-name="groupCellClasses"
              :span-method="groupSpan"
              :show-header="!grouped"
              :cell-style="tableCellStyle"
              :header-cell-style="tableHeaderCellStyle"
              row-key="id"
              class="monday-table work-item-table-base"
              :class="{ 'monday-table--column-dragging': columnDraggingKey, 'monday-table--empty': !displayTableItems.length, 'monday-table--grouped': grouped }"
              height="100%"
              empty-text="当前项目暂无工作项"
              border
              @header-dragend="onHeaderDragEnd"
              @selection-change="onTableSelectionChange"
            >
              <el-table-column
                :width="TABLE_MENU_COLUMN_WIDTH"
                fixed
                class-name="work-item-menu-column"
                label-class-name="work-item-menu-column"
              >
                <template #default="scope">
                  <div v-if="isGroupDisplayRow(scope.row) && scope.row.groupRowKind === 'add'" class="work-item-group-create">
                    <div v-if="groupCreate.draft(scope.row.group).open" class="quick-row monday-quick-row work-item-group-quick" :style="quickGridStyle">
                      <span class="monday-quick-checkbox" aria-hidden="true" />
                      <el-input :ref="value => groupCreate.setInput((scope.row as WorkItemGroupDisplayRow).group, value)"
                        v-model="groupCreate.draft(scope.row.group).title" class="quick-title-field monday-quick-add__field"
                        maxlength="300" :disabled="groupCreate.draft(scope.row.group).saving || Boolean(groupCreate.draft(scope.row.group).request)"
                        placeholder="添加工作项" :aria-label="`添加工作项到${scope.row.group.label}；Enter 创建，Shift+Enter 连续添加`"
                        @keydown="groupCreate.keydown(scope.row.group, $event)" />
                      <div class="quick-controls">
                        <el-button class="quick-submit" size="small" type="primary" :loading="groupCreate.draft(scope.row.group).saving"
                          :disabled="!groupCreate.draft(scope.row.group).title.trim() || Boolean(groupCreateDisabledReason(scope.row.group))"
                          @click="groupCreate.save(scope.row.group)">
                          {{ groupCreate.draft(scope.row.group).error ? '重试' : '添加' }}
                        </el-button>
                        <span class="quick-hint">Enter 新增 · Shift+Enter 连续添加</span>
                      </div>
                      <span class="work-item-group-add-mask" aria-hidden="true" />
                    </div>
                    <button v-else class="quick-add monday-quick-add work-item-group-quick" :style="quickGridStyle"
                      :disabled="Boolean(groupCreateDisabledReason(scope.row.group))" :title="groupCreateDisabledReason(scope.row.group) || undefined"
                      :aria-label="`添加工作项到${scope.row.group.label}`" @click="groupCreate.open(scope.row.group)">
                      <span class="monday-quick-checkbox" aria-hidden="true" />
                      <span class="monday-quick-add__field">添加工作项</span>
                      <span class="work-item-group-add-mask" aria-hidden="true" />
                    </button>
                    <inline-problem v-if="groupCreate.draft(scope.row.group).error" class="work-item-group-create-error" :problem="groupCreate.draft(scope.row.group).error!" />
                  </div>
                  <project-work-item-subitems-table
                    v-if="isGroupDisplayRow(scope.row) && scope.row.groupRowKind === 'subitems'"
                    :style="grouped ? { '--work-item-group-accent': workItemGroups.find(group => group.key === groupKeyForRow(scope.row.parent as ProjectWorkItemListItem))?.color } : undefined"
                    :ref="value => setSubitemTableHandle(scope.row.parent!.id, value)"
                    :project-id="projectId"
                    :selected-cell-key="selectedCellKey"
                    :parent="scope.row.parent as ProjectWorkItemListItem"
                    :items="subitemState((scope.row.parent as ProjectWorkItemListItem).id).items"
                    :loading="subitemState((scope.row.parent as ProjectWorkItemListItem).id).loading && !subitemState((scope.row.parent as ProjectWorkItemListItem).id).loaded"
                    :error="subitemState((scope.row.parent as ProjectWorkItemListItem).id).error"
                    :sort-rules="subitemState((scope.row.parent as ProjectWorkItemListItem).id).sortRules"
                    :columns="visibleSubitemColumns"
                    :column-widths="columnWidths"
                    :active-contents="activeContents"
                    :content-catalog="catalog"
                    :members="members"
                    :workflow-statuses="workflowStatuses"
                    :priority-options="priorityOptions"
                    :label-catalog="labelCatalog"
                    :can-create="canCreate"
                    :editing-cell="Boolean(editingCell)"
                    :before-remove="beforeRowRemove"
                    @row-changed="onRelationsChanged"
                    @row-moved="onRowMoved"
                    @select-cell="selectCell"
                    @row-removed="onRowRemoved"
                    @retry="loadSubitems((scope.row.parent as ProjectWorkItemListItem).id, true)"
                    @sort-change="onSubitemSortChange((scope.row.parent as ProjectWorkItemListItem).id, $event)"
                    :strict-version="embedded"
                    @timer-changed="notifyChanged"
                    @created="onSubitemCreated"
                    @updated="replaceLightItem"
                    @open-detail="openDetail"
                    @patch="patchCell"
                    @due-date-change="onDeadlineChange"
                    @contents-updated="onContentsUpdated"
                    @labels-updated="onLabelsUpdated"
                    @transition="transitionItem"
                    @selection-change="onSubitemSelectionChange"
                    @header-resize="onHeaderDragEnd"
                    @move-column="moveSubitemColumn"
                  />
                  <work-item-row-actions
                    v-if="!isGroupDisplayRow(scope.row) && !isDraft(scope.row as ProjectWorkItemListItem)"
                    :item="scope.row as ProjectWorkItemListItem"
                    :can-create="canCreate"
                    :sorted="hasExplicitSort"
                    :order-items="grouped ? grouping.edgeItems : embedded ? tableEdgeItems : undefined"
                    :disabled="Boolean(editingCell) || tableSorting || savingSortOrder || (grouped && groupsLoading)"
                    :before-remove="() => beforeRowRemove(scope.row as ProjectWorkItemListItem)"
                    @open="openDetail($event, 'details')"
                    @add-subitem="addSubitem"
                    @create-below="createBelow"
                    @moved="onRowMoved"
                    @changed="onRelationsChanged"
                    @removed="onRowRemoved"
                  />
                </template>
              </el-table-column>
              <el-table-column :width="TABLE_EXPAND_COLUMN_WIDTH" fixed class-name="monday-expand-column" />
              <el-table-column
                type="selection"
                :selectable="(row: TableDisplayRow) => !isGroupDisplayRow(row) && !isDraft(row)"
                :width="TABLE_SELECTION_COLUMN_WIDTH"
                fixed
                reserve-selection
                class-name="monday-selection-column"
                label-class-name="monday-selection-column"
              >
                <template #default="scope">
                  <template v-if="isGroupDisplayRow(scope.row)">
                    <button v-if="scope.row.groupRowKind === 'heading'" type="button" class="work-item-group-toggle"
                      data-work-item-view-control
                      :ref="value => setGroupSentinel((scope.row as WorkItemGroupDisplayRow).group.key, value, true)"
                      :aria-expanded="!grouping.isCollapsed(scope.row.group.key)"
                      :aria-label="`${grouping.isCollapsed(scope.row.group.key) ? '展开' : '收起'}分组：${scope.row.group.label}`"
                      @click.stop="grouping.toggle(scope.row.group.key)">
                      <svg viewBox="0 0 20 20" width="16" height="16" fill="currentColor" aria-hidden="true" :class="{ expanded: !grouping.isCollapsed(scope.row.group.key) }">
                        <path d="M12.76 10.56a.77.77 0 0 0 0-1.116L8.397 5.233a.84.84 0 0 0-1.157 0 .77.77 0 0 0 0 1.116l3.785 3.653-3.785 3.652a.77.77 0 0 0 0 1.117.84.84 0 0 0 1.157 0l4.363-4.211Z" />
                      </svg>
                      <span class="work-item-group-name">{{ scope.row.group.label }}</span>
                      <small>{{ groupCountsReady ? scope.row.group.count : '…' }} 个工作项</small>
                    </button>
                    <el-checkbox v-else-if="scope.row.groupRowKind === 'columns'" :model-value="groupSelection(scope.row.group).checked"
                      :indeterminate="groupSelection(scope.row.group).mixed" :disabled="!grouping.page(scope.row.group.key).items.length"
                      :aria-label="`选择${scope.row.group.label}已加载工作项`" @change="selectGroupRows(scope.row.group, Boolean($event))" />
                    <div v-else-if="scope.row.groupRowKind === 'load'" :ref="element => isGroupDisplayRow(scope.row) && setGroupSentinel(scope.row.group.key, element)"
                      class="work-item-group-load" :class="{ 'work-item-group-load--complete': scope.row.group.count > 0 && grouping.page(scope.row.group.key).loaded && !grouping.page(scope.row.group.key).nextCursor && !grouping.page(scope.row.group.key).error }">
                      <template v-if="grouping.page(scope.row.group.key).error">
                        <span>此分组加载失败</span><el-button text size="small" @click="grouping.load(scope.row.group.key)">重试</el-button>
                      </template>
                      <span v-else-if="scope.row.group.count === 0 && groupCountsReady">暂无工作项</span>
                      <button v-else-if="!grouping.page(scope.row.group.key).loaded || grouping.page(scope.row.group.key).nextCursor"
                        class="text-button" :disabled="!groupCountsReady || grouping.page(scope.row.group.key).loading" @click="grouping.load(scope.row.group.key)">
                        {{ grouping.page(scope.row.group.key).loading || !groupCountsReady ? '正在加载…' : '加载更多工作项' }}
                      </button>
                    </div>
                  </template>
                </template>
              </el-table-column>
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
                  <template v-if="isGroupDisplayRow(scope.row)">
                    <monday-column-quick-sort v-if="scope.row.groupRowKind === 'columns'" label="工作项名称"
                      :direction="sortDirectionForColumn('title')" :saving="tableSorting" :allow-save="false"
                      @sort="applyColumnQuickSort('title')" @clear="clearColumnSort('title')" />
                    <span class="monday-column-resize-handle monday-title-column-resize-handle" data-column-key="title" aria-hidden="true" />
                  </template>
                  <div v-else class="title-cell" :data-work-item-id="scope.row.id">
                    <work-item-name-cell
                      class="work-item-link"
                      :item="scope.row as ProjectWorkItemListItem"
                      :strict-version="embedded"
                      :selected="selectedCellKey === `${scope.row.id}:title`"
                      :disabled="Boolean(editingCell)"
                      :create="isDraft(scope.row as ProjectWorkItemListItem) ? saveInlineDraft : undefined"
                      @open="openDetail(scope.row as ProjectWorkItemListItem, 'details')"
                      @updated="replaceLightItem"
                      @editing="active => { if (active) { editingNames.add(scope.row.id); selectCell(scope.row.id, 'title') } else editingNames.delete(scope.row.id) }"
                      @cancel="cancelInlineDraft"
                    >
                      <template #prefix>
                        <button
                          v-if="!isDraft(scope.row as ProjectWorkItemListItem)"
                          class="subitem-expand-button"
                          :class="{
                            'subitem-expand-button--has-subitems': visibleSubitemCount(scope.row as ProjectWorkItemListItem) > 0,
                            'subitem-expand-button--empty': visibleSubitemCount(scope.row as ProjectWorkItemListItem) === 0,
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
                        <span
                          v-else
                          class="draft-title-indent"
                          aria-hidden="true"
                        />
                      </template>
                      <template #suffix>
                        <span v-if="source?.isContextRow?.(scope.row.id)" class="work-item-context-label" title="此父项用于展示命中的子项，不计入图表数量">父项上下文</span>
                        <div
                          v-if="visibleSubitemCount(scope.row as ProjectWorkItemListItem) > 0"
                          data-testid="clickable"
                          tabindex="0"
                          role="button"
                          :aria-label="`${visibleSubitemCount(scope.row as ProjectWorkItemListItem)} Subitems`"
                          :aria-expanded="expandedSubitemIds.includes((scope.row as ProjectWorkItemListItem).id)"
                          class="clickable_b3ab95e8e9 monday-subitems-counter-component name-cell-component__subitems-counter disableTextSelection_fae179dda6"
                          @click.stop="toggleSubitems(scope.row as ProjectWorkItemListItem)"
                        >
                          <div class="monday-subitems-counter-component__subitems-count">{{ visibleSubitemCount(scope.row as ProjectWorkItemListItem) }}</div>
                        </div>
                      </template>
                    </work-item-name-cell>
                    <button
                      class="monday-discussion-btn"
                      :disabled="isDraft(scope.row as ProjectWorkItemListItem)"
                      :class="{ 'monday-cell--selected': selectedCellKey === `${(scope.row as ProjectWorkItemListItem).id}:discussion` }"
                      :aria-label="(scope.row as ProjectWorkItemListItem).discussionCount ? `打开协作讨论，${(scope.row as ProjectWorkItemListItem).discussionCount}条讨论` : '打开协作讨论'"
                      title="打开协作讨论"
                      @click.stop="openDetail(scope.row as ProjectWorkItemListItem, 'discussion')"
                    >
                      <WorkItemDiscussionIcon :count="(scope.row as ProjectWorkItemListItem).discussionCount" />
                    </button>
                  </div>
                </template>
              </el-table-column>

              <!-- 按位置复用列；prop 会响应字段变化，column-key 只在 Element Plus 初始化列时读取。 -->
              <el-table-column
                v-for="(column, columnIndex) in movableVisibleColumns"
                :key="columnIndex"
                :label="column.label"
                :prop="column.key"
                :width="columnWidths[column.key]"
                align="center"
                :class-name="`monday-movable-column monday-column--${column.key}${column.key === 'status' || column.key === 'priority' || column.key === 'content' ? ' monday-block-column' : ''}`"
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
                  <template v-if="isGroupDisplayRow(scope.row)">
                    <monday-column-quick-sort v-if="scope.row.groupRowKind === 'columns'" :label="column.label"
                      :direction="sortDirectionForColumn(column.key)" :saving="tableSorting" :allow-save="false"
                      @sort="applyColumnQuickSort(column.key)" @clear="clearColumnSort(column.key)" />
                    <span class="monday-column-resize-handle" :data-column-key="column.key" aria-hidden="true" />
                  </template>
                  <work-item-draft-cell
                    v-else-if="isDraft(scope.row as ProjectWorkItemListItem)"
                    :item="scope.row as ProjectWorkItemListItem"
                    :column="column.key"
                    :status-label="workflowStatuses.find(status => status.statusCode === scope.row.statusCode)?.displayName ?? '—'"
                    :status-color="workflowStatuses.find(status => status.statusCode === scope.row.statusCode)?.colorToken"
                  />
                  <template v-else-if="column.key === 'assignee'">
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

                  <template v-else-if="column.key === 'content'">
                    <el-popover
                      placement="bottom"
                      width="auto"
                      trigger="click"
                      popper-class="work-items-label-popover content-popover"
                      @hide="resetLabelPopoverContent((scope.row as ProjectWorkItemListItem).id, 'content')"
                    >
                      <template #reference>
                        <button
                          class="monday-content-label cell-editor-trigger"
                          :style="labelCellStyle(contentLabel(scope.row as ProjectWorkItemListItem).colorToken)"
                          :disabled="Boolean(editingCell)"
                          @click.stop="selectCell((scope.row as ProjectWorkItemListItem).id, 'content')"
                        >
                          <span>{{ contentLabel(scope.row as ProjectWorkItemListItem).name || '—' }}</span>
                        </button>
                      </template>
                      <work-item-content-popover-content
                        :ref="element => setLabelPopoverContentRef(labelPopoverKey((scope.row as ProjectWorkItemListItem).id, 'content'), element)"
                        :project-id="projectId"
                        :catalog="catalog"
                        :current-value="(scope.row as ProjectWorkItemListItem).contentId"
                        :can-manage="Boolean(catalog?.canManage)"
                        @select="patchCell(scope.row as ProjectWorkItemListItem, 'content', $event)"
                        @updated="onContentsUpdated"
                      />
                    </el-popover>
                  </template>

                  <template v-else-if="column.key === 'dueDate'">
                    <work-item-due-date-cell
                      :item="scope.row as ProjectWorkItemListItem"
                      :can-edit="(scope.row as ProjectWorkItemListItem).capabilities.canEditFields"
                      :busy="Boolean(editingCell)"
                      @select="selectCell((scope.row as ProjectWorkItemListItem).id, 'dueDate')"
                      @change="onDeadlineChange(scope.row as ProjectWorkItemListItem, $event)"
                    />
                  </template>

                  <WorkItemTimerCell v-else-if="column.key === 'timeTracking'" :item="scope.row as ProjectWorkItemListItem" :project-id="projectId" @changed="notifyChanged" />
                  <WorkItemUpdatedCell
                    v-else-if="column.key === 'updatedAt'"
                    :item="scope.row as ProjectWorkItemListItem"
                    @open-activity="openDetail(scope.row as ProjectWorkItemListItem, 'activity')"
                  />
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
                <template #default="scope">
                  <button v-if="isGroupDisplayRow(scope.row) && scope.row.groupRowKind === 'columns'" type="button"
                    class="monday-add-column-icon" aria-label="添加列（功能预留）" title="添加列（功能预留）">
                    <svg viewBox="0 0 20 20" width="18" height="18" fill="none" aria-hidden="true"><path d="M10 3v14M3 10h14" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" /></svg>
                  </button>
                </template>
              </el-table-column>

              <template #append>
                <inline-problem v-if="groupingError" :problem="groupingError" />
                <button v-if="groupingError" class="text-button" @click="grouping.refresh()">重试加载分组</button>
                <div
                  v-if="!grouped && quickOpen"
                  ref="quickRow"
                  class="quick-row monday-quick-row"
                  :style="quickGridStyle"
                >
                  <span class="monday-quick-checkbox" aria-hidden="true" />
                  <el-input
                    ref="quickTitleInput"
                    v-model="quickTitle"
                    class="quick-title-field monday-quick-add__field"
                    maxlength="300"
                    :disabled="quickCreating"
                    placeholder="添加工作项"
                    aria-label="工作项名称；Enter 创建，Shift+Enter 创建后继续"
                    @keydown="onQuickKeydown"
                  />
                  <div class="quick-controls">
                    <el-button
                      class="quick-submit"
                      size="small"
                      type="primary"
                      :loading="quickCreating"
                      :disabled="!defaultContentId || !quickTitle.trim()"
                      @click="createQuick(false)"
                    >
                      添加
                    </el-button>
                    <span class="quick-hint">Enter 新增 · Shift+Enter 连续添加</span>
                  </div>
                </div>
                <button
                  v-else-if="!grouped"
                  class="quick-add monday-quick-add"
                  :style="quickGridStyle"
                  :disabled="!canCreate"
                  @click="openQuick"
                >
                  <span class="monday-quick-checkbox" aria-hidden="true" />
                  <span class="monday-quick-add__field">添加工作项</span>
                </button>
                <div ref="tableSentinel" class="cursor-sentinel" aria-hidden="true" />
                <div v-if="!grouped && tableLoading && tableItems.length" class="incremental-state">正在加载更多工作项…</div>
                <div v-else-if="!grouped && loadingMoreError" class="incremental-state incremental-state--error">
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
      :before-close="beforeDetailClose"
      :modal="false"
      :modal-penetrable="true"
      append-to-body
      title="工作项详情"
      header-class="work-items-detail-drawer__header"
      modal-class="work-items-drawer-overlay"
      :class="['work-items-detail-drawer', { 'work-items-detail-drawer--resizing': isResizingDrawer }]"
      :size="`${drawerWidth}px`"
      @close="detailOpen = false"
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
          <div
            v-if="detail.archived"
            class="work-item-archive-notice"
          >
            <span>此工作项已归档</span>
            <el-button
              v-if="detail.capabilities.canDelete"
              :loading="restoringArchive"
              @click="restoreArchivedItem"
            >
              恢复工作项
            </el-button>
          </div>
          <div class="detail-heading">
            <small>{{ contentName(detail.contentId) }} · {{ detail.itemNo }}</small>
            <h2>{{ detail.title }}</h2>
          </div>
          <work-item-detail-panel
            ref="detailPanel"
            v-model="detailTab"
            :detail="detail"
            :members="activeMembers"
            :can-publish="canPublishDiscussion"
            :read-only-reason="discussionReadOnlyReason"
            @relations-changed="onRelationsChanged"
            @discussion-changed="onDiscussionChanged"
            @description-updated="replaceLightItem($event.id, $event)"
            @open-work-item="openRelatedWorkItem"
          />
        </template>
      </div>
    </el-drawer>

    <teleport v-if="!embedded" to="body">
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
.work-item-group-toggle {
  display: flex; align-items: center; gap: 10px; width: min(540px, calc(100vw - 200px));
  height: 38px; padding: 0 14px 0 10px; border: 0; background: transparent;
  color: var(--yp-text-primary); text-align: left; cursor: pointer;
}
.work-item-group-toggle svg { flex: none; transition: transform 140ms ease; }
.work-item-group-toggle svg.expanded { transform: rotate(90deg); }
.work-item-group-name { color: var(--work-item-group-accent); font-size: 16px; font-weight: 600; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.work-item-group-toggle small { flex: none; font-size: 12px; color: var(--yp-text-secondary); font-weight: 400; }
.work-item-group-load { display: flex; gap: 8px; align-items: center; min-height: 36px; padding: 0 16px; color: var(--yp-text-secondary); font-size: 12px; }
.work-item-group-load--complete { min-height: 0; height: 0; padding: 0; }
:deep(.monday-table--grouped.el-table > .el-table__inner-wrapper > .el-table__body-wrapper) { height: 100% !important; }
:deep(.monday-table--grouped > .el-table__inner-wrapper > .el-table__body-wrapper > .el-scrollbar > .el-scrollbar__wrap > .el-scrollbar__view > table > tbody > tr.work-item-group-collapsed:not(.work-item-group-heading):not(.work-item-group-spacer)),
:deep(.monday-table--grouped tr.work-item-table-row.work-item-group-collapsed + tr:has(> .el-table__expanded-cell)) { display: none; }
:deep(.monday-table--grouped tr.work-item-group-heading > td.el-table__cell),
:deep(.monday-table--grouped tr.work-item-group-spacer > td.el-table__cell) { border: 0; background: var(--yp-bg-surface) !important; padding: 0; }
:deep(.monday-table--grouped tr.work-item-group-heading > td > .cell) { padding: 0; height: 38px; }
:deep(.monday-table--grouped tr.work-item-group-heading > .monday-selection-column),
:deep(.monday-table--grouped tr.work-item-group-load > .monday-selection-column) { position: relative !important; left: auto !important; }
:deep(.monday-table--grouped tr.work-item-group-heading > .monday-selection-column)::before,
:deep(.monday-table--grouped tr.work-item-group-load > .monday-selection-column)::before,
.work-item-group-toggle, .work-item-group-load { transform: translateX(var(--work-item-table-scroll-left, 0px)); }
:deep(.monday-table--grouped tr.work-item-group-heading > td > .cell),
:deep(.monday-table--grouped tr.work-item-group-load > td > .cell) { justify-content: flex-start !important; }
:deep(.monday-table--grouped tr.work-item-group-heading:not(.work-item-group-collapsed) > .monday-selection-column)::before { display: none; }
:deep(.monday-table--grouped tr.work-item-group-spacer > td.el-table__cell),
:deep(.monday-table--grouped tr.work-item-group-spacer > td > .cell) { height: 24px; line-height: 0; padding: 0; }
:deep(.monday-table--grouped tr.work-item-group-columns > td.el-table__cell:not(.work-item-menu-column):not(.monday-expand-column)) {
  height: var(--work-item-table-header-height); padding: 0;
  border-top: 1px solid var(--yp-monday-grid-border); background: var(--work-item-table-cell-bg);
  color: var(--yp-text-secondary); font-size: 13px; font-weight: 500;
}
:deep(.monday-table--grouped tr.work-item-group-columns > .monday-selection-column)::before { top: 0; border-top-left-radius: var(--work-item-hierarchy-corner-radius); }
:deep(.monday-table--grouped tr.work-item-group-columns > .monday-selection-column) { border-top-left-radius: var(--work-item-hierarchy-corner-radius); }
:deep(.monday-table--grouped tr.work-item-group-columns .monday-column-quick-sort) { height: var(--work-item-table-header-height); }
:deep(.monday-table--grouped tr.work-item-group-columns > td > .cell) { padding: 0; overflow: visible; }
:deep(.monday-table--grouped tr.work-item-group-columns td.monday-movable-column-header) { position: relative; cursor: grab; touch-action: none; user-select: none; }
:deep(.monday-table--grouped tr.work-item-group-columns td.monday-movable-column-header > .cell) { overflow: visible; }
:deep(.monday-table--grouped tr.work-item-group-load > td.el-table__cell) { padding: 0; height: auto; }
:deep(.monday-table--grouped tr.work-item-group-load > td > .cell) { min-height: 0 !important; height: auto !important; }
:deep(.monday-table--grouped tr.work-item-group-load > td > .cell) { padding: 0; }
:deep(.monday-table--grouped tr.work-item-group-load:has(.work-item-group-load--complete) > td) { border-bottom: 0; }
:deep(.monday-table--grouped tr.work-item-group-add > td.work-item-menu-column) {
  position: relative !important; left: auto !important; height: auto; padding: 0; border: 0;
  background: var(--yp-bg-surface) !important;
}
:deep(.monday-table--grouped tr.work-item-group-add > td > .cell) {
  display: block; height: auto !important; min-height: 0 !important; padding: 0; overflow: visible;
}
.work-item-group-create { width: 100%; }
.work-item-group-quick { --work-item-quick-add-accent: var(--work-item-group-accent); }
.monday-quick-add.work-item-group-quick::before,
.monday-quick-row.work-item-group-quick::before { opacity: 1; }
.work-item-group-quick .monday-quick-add__field,
.work-item-group-quick .quick-controls { position: relative; z-index: 4; }
.work-item-group-add-mask {
  position: absolute; z-index: 3; top: 0; right: 0; bottom: -1px; left: var(--work-item-quick-start);
  border-bottom-left-radius: var(--work-item-hierarchy-corner-radius);
  background: color-mix(in srgb, var(--yp-bg-surface) 50%, transparent); pointer-events: none;
}
.work-item-group-create-error {
  margin: 0 12px 0 calc(81px + var(--work-item-table-scroll-left, 0px));
  max-width: min(540px, calc(100vw - 220px)); white-space: normal;
}
:deep(.monday-table--grouped tr.work-item-group-heading.work-item-group-collapsed > .monday-selection-column) {
  border: 1px solid var(--yp-monday-grid-border); border-radius: var(--work-item-hierarchy-corner-radius);
  background: var(--work-item-table-cell-bg) !important;
}
:deep(.monday-table--grouped tr.work-item-group-heading.work-item-group-collapsed > .monday-selection-column)::before {
  top: -1px; bottom: -1px; border-radius: var(--work-item-hierarchy-corner-radius) 0 0 var(--work-item-hierarchy-corner-radius);
}
@media (prefers-reduced-motion: reduce) { .work-item-group-toggle svg { transition: none; } }
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

.project-overview-stack:has(.monday-table-surface) {
  margin-left: -32px;
  padding-left: 32px;
  width: calc(100% + 32px);
  max-width: calc(100% + 32px);
  box-sizing: border-box;
}

.work-items-toolbar {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: var(--yp-space-2);
  margin: calc(-1 * var(--yp-space-1)) 0 var(--yp-space-4);
  overflow-x: auto;
}

.toolbar-button, :deep(.grouping-toolbar-button) {
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

.toolbar-button:hover:not(:disabled), .toolbar-button.active, :deep(.grouping-toolbar-button:hover:not(:disabled)), :deep(.grouping-toolbar-button.active) { background: var(--yp-bg-hover); color: var(--yp-action-primary); }
.toolbar-button:disabled, :deep(.grouping-toolbar-button:disabled) { color: var(--yp-text-disabled); cursor: not-allowed; }
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
.filter-popover { min-width: 0; max-height: min(720px, calc(100dvh - 96px)); overflow-y: auto; overflow-x: hidden; }
.filter-columns { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--yp-space-4); min-width: 0; }
.filter-columns section:first-child { grid-column: 1 / -1; }
.filter-columns section { min-width: 0; }
.filter-columns h4 { margin: 0 0 var(--yp-space-2); color: var(--yp-text-secondary); }
.filter-value { min-width: 0; }
.filter-value > span { min-width: 0; overflow-wrap: anywhere; }
.filter-checkbox { flex: 0 0 auto; width: 14px; height: 14px; border: 1px solid var(--yp-border-default); border-radius: 3px; display: inline-grid; place-items: center; box-sizing: border-box; }
.filter-checkbox.checked { background: var(--yp-action-primary); border-color: var(--yp-action-primary); color: white; }
.filter-checkbox.checked::after { content: "✓"; font-size: 11px; line-height: 1; }
.filter-value small { flex: 0 0 auto; }
.filter-dates { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--yp-space-4); min-width: 0; padding-top: var(--yp-space-4); border-top: 1px solid var(--yp-border-subtle); }
.filter-field { display: grid; gap: var(--yp-space-2); min-width: 0; }
.filter-field-label { color: var(--yp-text-secondary); font-weight: 500; }
.filter-field :deep(.el-date-editor), .filter-field :deep(.el-select), .filter-field :deep(.el-input) { width: 100%; min-width: 0; box-sizing: border-box; }
.filter-duration-range { display: grid; grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr); align-items: center; gap: var(--yp-space-2); min-width: 0; }
:global(.work-items-filter-popover.el-popover) { width: min(680px, calc(100vw - 32px)) !important; box-sizing: border-box; }
@media (max-width: 560px) {
  .filter-columns { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .filter-dates { grid-template-columns: minmax(0, 1fr); }
}
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
  margin-left: -32px;
  width: calc(100% + 32px);
  max-width: none;
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

:deep(.monday-table .el-table__body tr.work-item-table-row--selected > td.el-table__cell:not(.work-item-menu-column):not(.monday-expand-column)) {
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
:deep(.monday-table > .el-table__inner-wrapper > .el-table__header-wrapper th.el-table-fixed-column--left)::after {
  content: '';
  position: absolute;
  inset: calc(-1 * var(--work-item-sort-overflow-space) - 1px) 0 auto;
  height: var(--work-item-sort-overflow-space);
  background: var(--yp-bg-surface);
  pointer-events: auto;
}
:deep(.monday-table .el-table__header th.el-table-fixed-column--left > .cell) {
  position: relative;
  z-index: 1;
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

:deep(.monday-table th.monday-movable-column-header:not(.subitem-movable-column-header)),
:deep(.monday-table td.monday-movable-column:not(.subitem-movable-column)) {
  transition: none;
}

:deep(.monday-table--column-dragging th.monday-movable-column-header:not(.subitem-movable-column-header)),
:deep(.monday-table--column-dragging td.monday-movable-column:not(.subitem-movable-column)) {
  transition: transform 180ms cubic-bezier(0.2, 0, 0, 1);
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

:deep(.monday-table td.monday-column--updatedAt > .cell) {
  --work-item-updated-cell-padding: calc(var(--yp-space-3) + 4px);
  padding: 0;
  height: var(--work-item-table-row-height);
}

:deep(.monday-table td.monday-column--timeTracking > .cell),
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

:deep(.monday-table .work-item-table-row--movable) .work-item-link:not(.work-item-name-cell--editing) {
  cursor: grab;
  user-select: none;
  touch-action: none;
}

:deep(.monday-table .work-item-table-row--movable) .work-item-link:not(.work-item-name-cell--editing):active {
  cursor: grabbing;
}

.work-item-link:focus,
.work-item-link:active {
  outline: none;
}

.draft-title-indent { flex: 0 0 24px; }

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

:deep(.monday-table .el-table__body td.el-table-fixed-column--left) {
  z-index: 5;
}

.work-item-link.monday-cell--selected,
.monday-discussion-btn.monday-cell--selected {
  position: relative;
  z-index: 4;
}

:deep(.monday-table .el-table__body td.el-table__cell.monday-cell--selected::after),
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
  display: flex;
  width: calc(100% - 48px);
  height: 26px;
  min-width: 0;
  margin: 5px 24px;
  padding: 0 16px;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 999px;
  box-sizing: border-box;
  color: var(--yp-text-inverse);
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.monday-content-label span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: color var(--yp-motion-fast) var(--yp-ease-standard);
}

.monday-quick-add__field {
  display: flex;
  height: 26px;
  grid-column: 4;
  align-items: center;
  box-sizing: border-box;
  padding: 0 8px 0 32px;
  border: 1px solid transparent;
  border-radius: var(--yp-radius-sm, 4px);
  font: inherit;
  font-size: 13px;
  text-align: left;
  transition: border-color var(--yp-motion-fast) var(--yp-ease-standard),
              background-color var(--yp-motion-fast) var(--yp-ease-standard),
              color var(--yp-motion-fast) var(--yp-ease-standard);
}

.monday-quick-checkbox {
  width: 16px;
  height: 16px;
  grid-column: 3;
  align-self: center;
  justify-self: center;
  box-sizing: border-box;
  border: 1px solid color-mix(in srgb, var(--yp-border-strong) 50%, transparent);
  border-radius: 2px;
  background: var(--yp-bg-surface);
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

.monday-quick-row:focus-within {
  background: linear-gradient(to right, transparent var(--work-item-quick-start), var(--yp-bg-selected) var(--work-item-quick-start));
}

.monday-quick-add,
.monday-quick-row {
  --work-item-quick-start: calc(var(--work-item-menu-column-width) + var(--work-item-table-scroll-left, 0px));
  border-bottom: 1px solid transparent;
  box-sizing: border-box;
}

.monday-quick-add__field,
.monday-quick-checkbox,
.quick-controls {
  transform: translateX(var(--work-item-table-scroll-left, 0px));
}

.monday-quick-add::after,
.monday-quick-row::after {
  position: absolute;
  right: 0;
  bottom: -1px;
  left: var(--work-item-quick-start);
  height: 1px;
  background: var(--yp-monday-grid-border);
  content: '';
  pointer-events: none;
}

.monday-quick-add::before,
.monday-quick-row::before {
  position: absolute;
  z-index: 2;
  top: -1px;
  bottom: -1px;
  left: var(--work-item-quick-start);
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

:deep(.monday-table--empty .el-table__empty-block) { display: none; }

.quick-controls {
  grid-column: 5 / -1;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 8px;
}
.quick-hint { color: var(--yp-text-muted); font-size: 12px; white-space: nowrap; }
.quick-title-field {
  width: 100%;
  min-width: 0;
  margin: 0;
  outline: none;
  border-color: var(--yp-action-primary);
  background: var(--yp-bg-surface);
  color: var(--yp-text-primary);
}
.quick-title-field :deep(.el-input__wrapper) {
  height: 100%;
  min-height: 0;
  padding: 0;
  border-radius: 0;
  outline: none;
  background: transparent;
  box-shadow: none;
}
.quick-title-field :deep(.el-input__inner) { height: 100%; min-height: 0; color: inherit; font: inherit; }
.quick-title-field :deep(.el-input__inner)::placeholder { color: var(--yp-text-secondary); opacity: 1; }
.monday-quick-row .quick-submit {
  justify-self: center;
  width: 48px;
  flex-shrink: 0;
  height: var(--work-item-quick-control-height);
  min-height: var(--work-item-quick-control-height);
  padding: 0 8px;
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
  border-bottom-color: transparent !important;
  background: var(--yp-bg-surface);
}

:deep(.monday-table .el-table__expanded-cell > .cell) {
  padding: 0;
}

:deep(.monday-table .el-table__expanded-cell)::after {
  position: absolute;
  right: 0;
  bottom: -1px;
  left: calc(32px + var(--work-item-table-scroll-left, 0px));
  height: 1px;
  background: var(--yp-monday-grid-border);
  content: '';
  pointer-events: none;
}

:deep(.monday-table .el-table__expanded-cell)::before {
  position: absolute;
  z-index: 4;
  top: -1px;
  bottom: -1px;
  left: calc(32px + var(--work-item-hierarchy-spine-offset));
  width: var(--work-item-hierarchy-line-width);
  border-radius: var(--work-item-hierarchy-line-width);
  background: var(--work-item-group-accent);
  content: '';
  pointer-events: none;
  transform: translateX(var(--work-item-table-scroll-left, 0px));
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

.work-item-archive-notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  margin-bottom: 16px;
  border-radius: 6px;
  background: var(--yp-bg-sunken);
  color: var(--yp-text-secondary);
}

.detail-heading small {
  color: var(--yp-text-muted);
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
  --el-transition-duration: var(--yp-work-items-drawer-duration, 300ms);
  pointer-events: none !important;
  background: transparent !important;
}

.work-items-drawer-overlay .el-drawer,
.work-items-detail-drawer {
  transform: translateX(calc(var(--yp-work-items-drawer-width, 560px) - var(--yp-work-items-drawer-inset, 0px))) !important;
  transition: none !important;
  pointer-events: auto !important;
  box-shadow: -4px 0 24px color-mix(in srgb, var(--yp-text-primary) 12%, transparent) !important;
  overflow: visible !important;
}

.work-items-detail-drawer--resizing {
  transition: none !important;
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

<style scoped>
.work-items-embedded { min-height: 0; height: auto; flex: 1 1 0; gap: 0; }
.work-items-embedded .work-items-home { min-height: 0; }
.work-items-embedded .monday-table-surface { min-height: 0; }
.work-items-embedded .work-items-toolbar { margin: 0; flex: none; }
.work-items-embedded :deep(.el-scrollbar__bar) { display: block !important; }
.work-items-embedded :deep(.el-scrollbar__wrap) { overscroll-behavior: auto; }
.work-item-context-label { font-size: 11px; white-space: nowrap; color: var(--yp-text-secondary); padding: 0 5px; }
</style>
