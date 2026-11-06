<script setup lang="ts">
import YpNotice from '../yp/YpNotice.vue'
import { usePageLoad } from '../../brand/page-progress'

import { projectWritable } from './projectStatus'
import './workItemCompactTable.css'
import './workItemDragPreview.css'
import { useTableRowDrag } from './useTableRowDrag'
import { useTableColumnDrag, type BuiltInColumnKey, type MovableColumnKey } from './useTableColumnDrag'
import { useWorkItemEdits, type WorkItemPatchField, type WorkItemPatchValue } from './useWorkItemEdits'
import { isWorkItemViewControl } from './workItemViewControls'
import { vBrandLoading as vLoading } from '../../brand/loading'
import { onTimeTrackingChanged, useTimeTracker } from '../../composables/useTimeTracker'
import { onProjectWorkItemsChanged } from '../../composables/workItemChanges'
import WorkItemTimerCell from './WorkItemTimerCell.vue'
import { usePageOnboarding } from '../../onboarding/useOnboarding'
import WorkItemDiscussionIcon from './WorkItemDiscussionIcon.vue'
import WorkItemColumnVisibilityMenu from './WorkItemColumnVisibilityMenu.vue'
import { createWorkItemColumnFlip, measureWorkItemColumns, visibleWorkItemColumnCells } from './workItemColumnFlip'
import WorkItemRowActions from './WorkItemRowActions.vue'
import WorkItemBatchBar from './WorkItemBatchBar.vue'
import WorkItemImportDialog from './import/WorkItemImportDialog.vue'
import WorkItemArchivedDrawer from './WorkItemArchivedDrawer.vue'
import WorkItemParentPicker from './WorkItemParentPicker.vue'
import { useWorkItemBatchActions, type WorkItemSelection } from './useWorkItemBatchActions'
import { useWorkItemCellPopover } from './useWorkItemCellPopover'
import WorkItemBatchEditScope from './WorkItemBatchEditScope.vue'
import { dueDateBatchEdit, workItemBatchEditScopeKey, type WorkItemBatchAssigneeMode, type WorkItemBatchEdit } from './workItemBatchEdit'
import { moveWorkItemOrder, workItemOrderSiblings } from './workItemOrder'
import WorkItemNameCell from './WorkItemNameCell.vue'
import WorkItemDraftCell from './WorkItemDraftCell.vue'
import { useWorkItemInlineCreate } from './useWorkItemInlineCreate'
import WorkItemUpdatedCell from './WorkItemUpdatedCell.vue'
import WorkItemTableSkeleton from './WorkItemTableSkeleton.vue'
import WorkItemKanbanSkeleton from './WorkItemKanbanSkeleton.vue'
import WorkItemKanbanBoard, { type KanbanBoardStatus } from './WorkItemKanbanBoard.vue'
import WorkItemKanbanCard, { type KanbanCardField } from './WorkItemKanbanCard.vue'
import { ArrowDown, Box, Hide, Plus, Search, Sort, Upload } from '@element-plus/icons-vue'
import {
  HideCompletedEffect,
  ListProjectWorkItemFilterOptionsFieldEnum,
  WorkItemViewType,
  WorkItemRelationRole,
  WorkItemRelationType,
  ProjectActorAccess,
  ProjectLifecycle,
  ProjectMembershipStatus,
  ProjectMembershipStatusFilter,
  readCsrfToken,
  type ProjectContentCatalog,
  type ProjectDetail,
  type ProjectMember,
  type WorkItemDetail,
  type ProjectWorkItemListItem,
  type WorkItemTransitionOption,
  type WorkItemLabelCatalog,
  type WorkItemTableGrouping,
} from '@yumpoo/api-client'
import {
  ElButton,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElDrawer,
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
import { computed, nextTick, onBeforeUnmount, onMounted, provide, reactive, ref, watch, type CSSProperties, type DefineComponent } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter, type LocationQuery } from 'vue-router'
import type { WorkItemTableSource } from './workItemTableSource'
import { contentsApi, projectsApi, workItemsApi } from '../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import WorkItemDetailPanel from '../../components/collaboration/WorkItemDetailPanel.vue'
import WorkItemColumnHeader from './WorkItemColumnHeader.vue'
import WorkItemColumnExpandButton from './WorkItemColumnExpandButton.vue'
import WorkItemColumnSettingsPopover from './WorkItemColumnSettingsPopover.vue'
import { COLLAPSED_COLUMN_WIDTH, type ColumnMenuAction, type ColumnMenuState } from './workItemColumnMenu'
import ProjectWorkItemSubitemsTable, {
  type ProjectWorkItemSubitemSortRule,
} from './ProjectWorkItemSubitemsTable.vue'
import ProjectWorkspaceHeader from './ProjectWorkspaceHeader.vue'
import WorkItemLabelPopoverContent from './WorkItemLabelPopoverContent.vue'
import WorkItemContentPopoverContent from './WorkItemContentPopoverContent.vue'
import WorkItemDueDateCell from './WorkItemDueDateCell.vue'
import type { DueDateValue } from './workItemDueDate'
import { useProjectTableScrollLayout } from './useProjectTableScrollLayout'
import { useWorkItemPresentation } from './workItemPresentation'
import WorkItemFilterPopover from './WorkItemFilterPopover.vue'
import WorkItemAssigneeFilterPopover from './WorkItemAssigneeFilterPopover.vue'
import { useWorkItemFilters } from './useWorkItemFilters'
import WorkItemAssigneePicker from './WorkItemAssigneePicker.vue'
import { workItemAssignees } from './workItemAssignees'
import YpAssigneeStack from '../yp/YpAssigneeStack.vue'
import WorkItemGroupingPopover from './WorkItemGroupingPopover.vue'
import WorkItemTableSettingsMenu from './WorkItemTableSettingsMenu.vue'
import WorkItemCompletedMenu from './WorkItemCompletedMenu.vue'
import { useHideCompleted } from '../../composables/useHideCompleted'
import { useWorkItemTableSettings } from './useWorkItemTableSettings'
import { applyDefaultStatus } from './workItemDefaultStatus'
import { DEFAULT_VALUE_COLUMN_KEYS, isCompletedStatus, heightOption, resolveWorkItemCreateDefaults, type DefaultValueColumnKey,
  type TableSettingsCatalog, evaluateWorkItemColoring, type ColoringColumnKey, type WorkItemColoring } from './workItemTableSettings'
import { useWorkItemGrouping, isGroupDisplayRow, type WorkItemGroupDisplayRow } from './useWorkItemGrouping'
import { EMPTY_GROUP, type GroupField, type WorkItemGroup } from './workItemGrouping'
import { useWorkItemGroupCreate } from './useWorkItemGroupCreate'
import { useSession } from '../../composables/useSession'
import { useMinuteClock } from '../../composables/useMinuteClock'
import { apiDate, companyDate, dueDateKey } from './workItemDueDate'
import ConnectColumnAddButton from './connect/ConnectColumnAddButton.vue'
import ConnectTableColumnHeader from './connect/ConnectTableColumnHeader.vue'
import ConnectColumnDeleteDialog from './connect/ConnectColumnDeleteDialog.vue'
import ConnectTableCell from './connect/ConnectTableCell.vue'
import ConnectedItemCard from './connect/ConnectedItemCard.vue'
import { CONNECT_DRAFT_KEY, isConnectColumnKey, type ConnectColumnKey } from './connect/connectColumnKeys'
import ConnectKanbanConnections from './connect/ConnectKanbanConnections.vue'
import { useConnectTable } from './connect/useConnectTable'

const pageLoad = usePageLoad()
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
  pending?: Promise<void>
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
    delete preferences.tab
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
const timerEnabled = useTimeTracker().timerEnabled
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
const error = ref<ApiProblem>()
const tableSorting = ref(false)
const lanes = reactive<Record<string, KanbanLane>>({})
const subitems = reactive<Record<string, SubitemState>>({})
const expandedSubitemIds = ref<string[]>([])
const subitemSelections = reactive<Record<string, Set<string>>>({})
type SubitemTableHandle = Pick<InstanceType<typeof ProjectWorkItemSubitemsTable>, 'openQuick' | 'canClose' | 'clearSelection' | 'deselect'>
const subitemTableHandles = new Map<string, SubitemTableHandle>()
const restoringArchive = ref(false)
const quickOpen = ref(false)
const quickTitle = ref('')
const quickCreating = ref(false)
const creatingTop = ref(false)
const importOpen = ref(false)
const archivedOpen = ref(false)
/** Row whose name cell opens for editing as soon as it renders: the item the toolbar button just created. */
const autoEditId = ref('')
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

onBeforeRouteLeave(() => canClose())
onBeforeRouteUpdate((to, from) => to.params.projectId !== from.params.projectId
  ? canClose() : to.query.workItemId !== from.query.workItemId ? beforeDraftLeave() : true)
const detailLoading = ref(false)
const detail = ref<WorkItemDetail>()
const detailTab = ref<'details' | 'discussion' | 'activity'>('details')
const dragging = ref<ProjectWorkItemListItem>()
const selectedWorkItemIds = ref(new Set<string>())
const tableRef = ref<{
  $el: HTMLElement
  doLayout: () => void
  clearSelection: () => void
  toggleRowSelection: (row: ProjectWorkItemListItem, selected?: boolean) => void
}>()
const tableSentinel = ref<HTMLElement>()
const loadingMoreError = ref<ApiProblem>()
const editingCell = ref('')
const createDisabled = computed(() => creatingTop.value || Boolean(editingCell.value) || tableSorting.value)
const editingNames = ref(new Set<string>())
const cellPopoverBusy = reactive<Record<string, boolean>>({})
const { openCell, setCellOpen, closeCell } = useWorkItemCellPopover()
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
  column: { property?: string; columnKey?: string }
}): string {
  const classes: string[] = []
  if (column.property && column.property !== 'title' && selectedCellKey.value === `${row.id}:${column.property}`)
    classes.push('monday-cell--selected')
  if (cellColoring(row, column)) classes.push('work-item-cell--colored')
  return classes.join(' ')
}

interface SortRule { field: string; direction: 'ASC' | 'DESC' }
const sortRules = ref<SortRule[]>([])
const savingSortOrder = ref(false)
const stopTimerUpdates = onTimeTrackingChanged(() => {
  if (embedded.value) return
  if (sortRules.value.some(rule => rule.field === 'TIME_TRACKING')) void loadTable()
})
onBeforeUnmount(stopTimerUpdates)
// The desktop quick panel changes statuses and creates items in its own window.
onBeforeUnmount(onProjectWorkItemsChanged(() => projectId.value,
  () => void (selectedView.value === 'table' ? reloadSortedTableInPlace() : refreshCurrentView())))
type ColumnKey = BuiltInColumnKey | ConnectColumnKey
const columns: Array<{ key: BuiltInColumnKey; label: string; defaultWidth: number; minWidth: number }> = [
  { key: 'title', label: '工作项名称', defaultWidth: 320, minWidth: 220 },
  { key: 'assignee', label: '处理人', defaultWidth: 90, minWidth: 72 },
  { key: 'status', label: '状态', defaultWidth: 96, minWidth: 96 },
  { key: 'priority', label: '优先级', defaultWidth: 90, minWidth: 90 },
  { key: 'content', label: '工作项类别', defaultWidth: 110, minWidth: 110 },
  { key: 'dueDate', label: '截止日期', defaultWidth: 140, minWidth: 112 },
  { key: 'timeTracking', label: '时长追踪', defaultWidth: 140, minWidth: 120 },
  { key: 'updatedAt', label: '最后更新时间', defaultWidth: 170, minWidth: 135 },
]
const sortFieldByColumn: Record<BuiltInColumnKey, string> = {
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
const columnWidths = reactive<Record<BuiltInColumnKey, number>>(Object.fromEntries(columns.map(item => [item.key, item.defaultWidth])) as Record<BuiltInColumnKey, number>)
const hiddenColumns = ref(new Set<BuiltInColumnKey>())
let loadRevision = 0
let searchTimer: number | undefined
let tableObserver: IntersectionObserver | undefined
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
const connect = reactive(useConnectTable({
  projectId, project, enabled: () => !embedded.value,
  rows: () => selectedView.value === 'kanban' ? Object.values(lanes).flatMap(state => state.items)
    : [...tableItems.value, ...Object.values(subitems).flatMap(state => state.items)],
  table: () => tableRef.value?.$el,
}))
const builtInVisibleColumns = computed(() => orderedColumns.value.filter(item => item.key === 'title' || !hiddenColumns.value.has(item.key)))
const visibleColumns = computed(() => [...builtInVisibleColumns.value, ...connect.visibleColumns])
const visibleSubitemColumns = computed(() => orderedSubitemColumns.value.filter(item => item.key === 'title' || !hiddenColumns.value.has(item.key)))
const movableVisibleColumns = computed(() => builtInVisibleColumns.value.filter(item => item.key !== 'title'))
const allHiddenColumns = computed(() => new Set<ColumnKey>([...hiddenColumns.value, ...connect.hidden]))
const restorableColumns = computed(() => [...columns, ...connect.columns].filter(column => allHiddenColumns.value.has(column.key)))
const collapsedColumns = ref(new Set<ColumnKey>())
const columnSettingsKind = ref<'status' | 'priority' | 'content'>()
const columnSettingsAnchor = ref<HTMLElement>()
const groupFieldByColumn: Partial<Record<BuiltInColumnKey, GroupField>> = {
  assignee: 'ASSIGNEE', status: 'STATUS', priority: 'PRIORITY', content: 'CONTENT', dueDate: 'DUE_DATE',
}
function isColumnCollapsed(key: ColumnKey): boolean { return collapsedColumns.value.has(key) }
// 替换原 columnWidth：折叠列在表格与添加行网格中统一使用折叠宽度
function columnWidth(key: ColumnKey): number {
  if (isColumnCollapsed(key)) return COLLAPSED_COLUMN_WIDTH
  return isConnectColumnKey(key) ? connect.width(key) : columnWidths[key]
}
function columnMenuState(column: { key: BuiltInColumnKey; label: string }): ColumnMenuState {
  const labels = column.key === 'status' || column.key === 'priority' ? labelCatalog.value
    : column.key === 'content' ? catalog.value : undefined
  const groupField = groupFieldByColumn[column.key]
  return {
    label: column.label,
    settings: column.key === 'timeTracking' ? 'timer' : !labels ? 'unavailable' : labels.canManage ? 'editable' : 'readonly',
    sortDirection: sortDirectionForColumn(column.key),
    sortDisabled: savingSortOrder.value,
    collapsible: column.key !== 'title',
    groupField,
    groupedByThis: Boolean(groupField) && groupingField.value === groupField,
    groupDisabled: savingSortOrder.value,
  }
}
function onColumnMenuAction(key: BuiltInColumnKey, action: ColumnMenuAction, anchor?: HTMLElement): void {
  if (action.type === 'settings') {
    if (anchor && (key === 'status' || key === 'priority' || key === 'content')) {
      columnSettingsAnchor.value = anchor
      columnSettingsKind.value = key
    }
  } else if (action.type === 'filter') openColumnFilter(key)
  else if (action.type === 'sort') setColumnSort(key, action.direction)
  else if (action.type === 'collapse') void toggleColumnCollapsed(key)
  else {
    const field = groupFieldByColumn[key]
    if (field) void changeGrouping(groupingField.value === field ? '' : field)
  }
}
function openColumnFilter(key: BuiltInColumnKey): void {
  // 等列菜单关闭后再打开工具栏弹层，避免同一次点击被弹层判定为外部点击而立即关闭。
  window.setTimeout(() => {
    if (key === 'title') { searchExpanded.value = true; return }
    void loadFilterOptions()
    if (key === 'assignee') assigneeFilterOpen.value = true
    else filterPopoverOpen.value = true
  }, 0)
}
function setColumnSort(key: BuiltInColumnKey, direction: 'ASC' | 'DESC' | null): void {
  if (savingSortOrder.value) return
  if (!direction) { clearColumnSort(key); return }
  const field = sortFieldByColumn[key]
  const next = sortRules.value.map(rule => ({ ...rule }))
  const index = next.findIndex(rule => rule.field === field)
  if (index >= 0) next[index] = { field, direction }
  else if (next.length < 3) next.push({ field, direction })
  else next[next.length - 1] = { field, direction }
  sortRules.value = next
  void syncUrl()
}
async function toggleColumnCollapsed(key: ColumnKey): Promise<void> {
  if (key === 'title') return
  const snapshot = columnFlip.capture()
  const next = new Set(collapsedColumns.value)
  if (next.has(key)) next.delete(key); else next.add(key)
  collapsedColumns.value = next
  persistTablePrefs()
  await nextTick()
  flushResponsiveTableLayout()
  columnFlip.play(snapshot)
}
function showHiddenColumn(key: string) { if (isConnectColumnKey(key) || columnByKey.has(key as BuiltInColumnKey)) void toggleColumn(key as ColumnKey, true) }
const QUICK_CHECKBOX_SIZE = 16
const quickGridStyle = computed(() => {
  const titleLeft = TABLE_MENU_COLUMN_WIDTH + TABLE_EXPAND_COLUMN_WIDTH + TABLE_SELECTION_COLUMN_WIDTH
  return {
    '--work-item-menu-column-width': `${TABLE_MENU_COLUMN_WIDTH}px`,
    '--work-item-quick-checkbox-left': `${TABLE_MENU_COLUMN_WIDTH + TABLE_EXPAND_COLUMN_WIDTH + (TABLE_SELECTION_COLUMN_WIDTH - QUICK_CHECKBOX_SIZE) / 2}px`,
    '--work-item-quick-title-left': `${titleLeft}px`,
    '--work-item-quick-controls-left': `${titleLeft + columnWidths.title}px`,
    gridTemplateColumns: [`${TABLE_MENU_COLUMN_WIDTH}px`, `${TABLE_EXPAND_COLUMN_WIDTH}px`, `${TABLE_SELECTION_COLUMN_WIDTH}px`,
      ...visibleColumns.value.map(item => `${columnWidth(item.key)}px`), `${TABLE_ADD_COLUMN_MIN_WIDTH}px`].join(' '),
  }
})
const TABLE_LEAD_WIDTHS = [TABLE_MENU_COLUMN_WIDTH, TABLE_EXPAND_COLUMN_WIDTH, TABLE_SELECTION_COLUMN_WIDTH]
const PAGE_SKELETON_TOOLBAR = [104, 72, 64, 60, 56, 88]
const skeletonColumns = computed(() => visibleColumns.value.map(item => ({ key: item.key, width: columnWidth(item.key), collapsed: isColumnCollapsed(item.key) })))
const skeletonRowHeight = computed(() => heightOption(tableViewSettings.value.rowHeight).row)
const hasExplicitSort = computed(() => sortRules.value.length > 0)

const contentsById = computed(() => new Map((catalog.value?.items ?? []).map(item => [item.id, item])))
const activeContents = computed(() => (catalog.value?.items ?? []).filter(item => item.active))
const workflowStatuses = computed(() => [...(labelCatalog.value?.statuses ?? [])]
  .sort((left, right) => left.sortOrder - right.sortOrder)
  .map(status => ({ ...status, statusCode: status.code })))
const priorityOptions = computed(() => [...(labelCatalog.value?.priorities ?? [])]
  .sort((left, right) => left.sortOrder - right.sortOrder))
const { contentName, contentLabel, statusLabel, labelCellStyle, getStatusCellStyle, getPriorityCellStyle,
  getStatusTone, getPriorityPresentation, formatDate } = useWorkItemPresentation({ contentsById, workflowStatuses, priorityOptions })
const activeMembers = computed(() => members.value.filter(item => item.membershipStatus === ProjectMembershipStatus.Active))
const filter = useWorkItemFilters({
  route, projectId, searchInput, activeMembers, tableItems, tableSource, error, listRequest, syncUrl,
  getRevision: () => loadRevision, getSignal: () => activeController?.signal ?? null,
})
const { filters, assigneeFilterOpen, filterPopoverOpen, explicitFilterActive, loadFilterOptions, invalidateFilterOptions, filterRequest } = filter
const canCreate = computed(() => Boolean(project.value
  && projectWritable(project.value)
  && activeContents.value.length
  && (project.value.actorAccess === ProjectActorAccess.Owner
    || project.value.actorAccess === ProjectActorAccess.Member)))
const canRestoreArchived = computed(() => Boolean(project.value && projectWritable(project.value)
  && (project.value.actorAccess === ProjectActorAccess.Owner || project.value.actorAccess === ProjectActorAccess.Member)))
const { draft: inlineDraft, rows: displayWorkItemRows, isDraft, start: createBelow, save: saveInlineDraft, cancel: cancelInlineDraft } = useWorkItemInlineCreate({
  contextId: () => projectId.value,
  items: () => tableItems.value,
  canCreate: () => canCreate.value && !hasExplicitSort.value && !editingCell.value && !tableSorting.value,
  content: () => activeContents.value.find(item => item.id === defaultContentId.value),
  status: () => workflowStatuses.value.find(status => status.active && status.statusCode === (createDefaults.value.statusCode ?? 'NOT_STARTED')),
  defaults: () => createDefaults.value,
  created: (created, anchorId) => {
    const next = [...tableItems.value]
    next.splice(next.findIndex(item => item.id === anchorId) + 1, 0, { ...created, subitemCount: 0, discussionCount: 0 })
    tableItems.value = next
    notifyChanged()
    void reloadSortedTableInPlace()
  },
})
const session = useSession()
const groupingClock = useMinuteClock()
const { settings: tableViewSettings, state: tableSettingsState, update: updateTableSettings, load: reloadTableSettings,
  saveState: tableSettingsSaveState, saveError: tableSettingsSaveError, retrySave: retrySaveTableSettings }
  = useWorkItemTableSettings(() => projectId.value)
const companyToday = computed(() => companyDate(groupingClock.value, session.authentication.value?.company.timezone ?? 'Asia/Shanghai'))
const createDefaults = computed(() => resolveWorkItemCreateDefaults(tableViewSettings.value.defaultValues, {
  memberIds: new Set(activeMembers.value.map(item => item.userId)),
  statusCodes: new Set(workflowStatuses.value.filter(item => item.active).map(item => item.code)),
  priorityCodes: new Set(priorityOptions.value.filter(item => item.active).map(item => item.code)),
  contentIds: new Set(activeContents.value.map(item => item.id)),
  today: companyToday.value,
}))
const defaultContentId = computed(() => createDefaults.value.contentId ?? activeContents.value[0]?.id)
const tableSettingsCatalog = computed<TableSettingsCatalog>(() => ({
  members: activeMembers.value, statuses: workflowStatuses.value, priorities: priorityOptions.value,
  contents: catalog.value?.items ?? [],
}))
const defaultValueColumns = computed(() => movableColumnOrder.value
  .filter((key): key is DefaultValueColumnKey => (DEFAULT_VALUE_COLUMN_KEYS as readonly string[]).includes(key))
  .map(key => ({ key, label: columnByKey.get(key)!.label })))
// 冻结前 N 列：按当前可见列顺序（内置列在前、连接列在后），新建连接列草稿不参与。
const pinnableColumns = computed(() => visibleColumns.value.filter(column => column.key !== 'title' && column.key !== CONNECT_DRAFT_KEY))
const pinnedColumnKeys = computed(() => new Set<string>(pinnableColumns.value
  .slice(0, tableViewSettings.value.pinnedColumnCount).map(column => column.key)))
const tablePinColumns = computed(() => [columnByKey.get('title')!, ...pinnableColumns.value]
  .map(column => ({ key: column.key, label: column.label, width: columnWidth(column.key) })))
// 子项表独立冻结前 N 列：按子项列自己的顺序（内置列在前、连接列在后）。
const subitemPinnableColumns = computed(() => [...visibleSubitemColumns.value, ...connect.visibleColumns]
  .filter(column => column.key !== 'title' && column.key !== CONNECT_DRAFT_KEY))
const subitemPinnedColumnKeys = computed(() => new Set<string>(subitemPinnableColumns.value
  .slice(0, tableViewSettings.value.subitemPinnedColumnCount ?? 0).map(column => column.key)))
const subitemPinColumns = computed(() => [columnByKey.get('title')!, ...subitemPinnableColumns.value]
  .map(column => ({ key: column.key, label: column.label, width: columnWidth(column.key) })))
const tableRowHeight = computed(() => heightOption(tableViewSettings.value.rowHeight).row)
const tableHeightClasses = computed(() => [
  `monday-table--header-${tableViewSettings.value.headerHeight.toLowerCase()}`,
  `monday-table--row-${tableViewSettings.value.rowHeight.toLowerCase()}`,
])
const PIN_SCROLL_RESERVE = 200
function tablePinWidthBudget(): number {
  const width = resolveTableScrollElement()?.clientWidth ?? 0
  return width > 0
    ? Math.max(1, width - TABLE_MENU_COLUMN_WIDTH - TABLE_EXPAND_COLUMN_WIDTH - TABLE_SELECTION_COLUMN_WIDTH - PIN_SCROLL_RESERVE)
    : 0
}
/** 子项表前导为 72px 菜单列与 48px 勾选列。 */
const SUBITEM_LEAD_WIDTH = 72 + TABLE_SELECTION_COLUMN_WIDTH
function subitemPinWidthBudget(): number {
  const width = resolveTableScrollElement()?.clientWidth ?? 0
  return width > 0 ? Math.max(1, width - SUBITEM_LEAD_WIDTH - PIN_SCROLL_RESERVE) : 0
}
watch(() => [[...pinnedColumnKeys.value].join('|'), tableViewSettings.value.headerHeight, tableViewSettings.value.rowHeight].join(':'),
  () => void nextTick(flushResponsiveTableLayout))
const projectGroupingPrefsKey = () => `yumpoo:project-work-items:grouping:v1:${session.authentication.value?.company.id ?? 'unknown'}:${session.authentication.value?.user.id ?? 'unknown'}:${projectId.value}`
const grouping = useWorkItemGrouping({
  source: () => tableSource,
  items: tableItems,
  preferenceKey: () => props.preferenceScope ? `${props.preferenceScope}:grouping` : projectGroupingPrefsKey(),
  stored: () => tableSettingsState.value === 'ready' ? tableViewSettings.value.grouping : undefined,
  ...(props.preferenceScope ? {} : { save: (value: WorkItemTableGrouping) => updateTableSettings({ grouping: value }) }),
  request: () => listRequest(null),
  sources: () => ({ members: members.value, statuses: labelCatalog.value?.statuses ?? [],
    priorities: labelCatalog.value?.priorities ?? [], contents: catalog.value?.items ?? [] }),
  today: () => companyDate(groupingClock.value, session.authentication.value?.company.timezone ?? 'Asia/Shanghai'),
  ready: () => Boolean(project.value && selectedView.value === 'table'),
  restore: () => reloadSortedTableInPlace(selectedRowId.value),
  protectedIds: () => new Set([...expandedIds.value, ...selectedWorkItemIds.value,
    ...[selectedRowId.value, inlineDraft.value?.anchorId].filter((id): id is string => Boolean(id))]),
  filtering: () => explicitFilterActive.value,
})
const { active: grouped, field: groupingField, order: groupingOrder, showEmpty: showEmptyGroups,
  groups: workItemGroups, countsReady: groupCountsReady, error: groupingError, loading: groupsLoading } = grouping
const expandedSubitemsKey = () => props.preferenceScope ? `${props.preferenceScope}:expanded-subitems`
  : `yumpoo:project-work-items:expanded-subitems:v1:${session.authentication.value?.company.id ?? 'unknown'}:${session.authentication.value?.user.id ?? 'unknown'}:${projectId.value}`
// 子项展开按设备保存，只在收起或删除时移除；不在当前数据里的 ID 保留但不生效，只保存最近展开的 100 项。
const MAX_SAVED_EXPANDED = 100
watch(expandedSubitemsKey, key => {
  try {
    const saved = JSON.parse(localStorage.getItem(key) ?? '[]') as unknown
    expandedSubitemIds.value = Array.isArray(saved) ? saved.filter((id): id is string => typeof id === 'string') : []
  } catch { expandedSubitemIds.value = [] }
}, { immediate: true, flush: 'sync' })
watch(expandedSubitemIds, ids => {
  try { localStorage.setItem(expandedSubitemsKey(), JSON.stringify(ids.slice(-MAX_SAVED_EXPANDED))) } catch { /* 本地存储不可用时只在当前页面保留展开。 */ }
})
/** 筛选生效时：含命中子项的主行默认展开；其间的手动展开收起只记在这里，筛选变化时清空，不写入本机展开记录。 */
const filterToggledIds = ref(new Set<string>())
const expandedIds = computed(() => explicitFilterActive.value
  ? tableItems.value.filter(item => Boolean(item.matchedSubitemCount) !== filterToggledIds.value.has(item.id)).map(item => item.id)
  : expandedSubitemIds.value)
const visibleExpandedIds = computed(() => {
  const loaded = new Set(tableItems.value.map(item => item.id))
  return expandedIds.value.filter(id => loaded.has(id))
})
watch(() => visibleExpandedIds.value.join('|'), () => visibleExpandedIds.value.forEach(id => void loadSubitems(id)))
const { hideCompleted, hideCompletedEffect } = useHideCompleted()
function isCompletedRow(item: ProjectWorkItemListItem): boolean {
  return hideCompleted.value && isCompletedStatus(tableViewSettings.value.completedStatusCodes, item.statusCode, item.statusCategory)
}
/** 「不显示」：主表只请求本人完成定义以外的状态；看板与仪表板内嵌表格不受影响。 */
const unfinishedStatusCodes = computed(() => !embedded.value && hideCompleted.value
  && hideCompletedEffect.value === HideCompletedEffect.Hide
  ? workflowStatuses.value.filter(status => !isCompletedStatus(tableViewSettings.value.completedStatusCodes,
    status.code, status.statusCategory)).map(status => status.code)
  : null)
watch(() => unfinishedStatusCodes.value?.join(','), () => void resetCurrentData())
const groupingSwitching = ref(false)
const { horizontalPageScrollbar, verticalPageScrollbar, horizontalOverflow, horizontalScrollExtent, verticalScrollExtent,
  pageScrollbarLeft, horizontalPageScrollbarStyle, verticalPageScrollbarStyle, onHorizontalPageScroll, onVerticalPageScroll,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars -- ProjectWorkItems.detail.spec.ts 通过组件实例同步滚动条。
  syncPageScrollbars,
  resolveTableScrollElement, schedulePageScrollbarSync, scheduleResponsiveTableLayout, flushResponsiveTableLayout,
} = useProjectTableScrollLayout({
  tableRef, tableSentinel, embedded, detailOpen, drawerWidth, selectedView, grouped, groupingSwitching,
  tableLoading, tableSorting, error, loadingMoreError, tableNextCursor, loadTable,
})
const onboardingReady = computed(() => !embedded.value && Boolean(project.value) && !loading.value && !tableLoading.value && !error.value
  && (selectedView.value === 'kanban'
    ? Object.values(lanes).every(state => !state.loading && !state.error)
    : !grouped.value || (groupCountsReady.value && !groupsLoading.value && !groupingError.value)))
const onboarding = usePageOnboarding(() => onboardingReady.value)
watch(onboardingReady, ready => {
  if (ready) void onboarding.ready()
}, { flush: 'post' })
function groupCreateDisabledReason(group: WorkItemGroup): string {
  if (!canCreate.value) return '当前无法添加工作项'
  if (group.key === EMPTY_GROUP) return ''
  switch (groupingField.value) {
    case 'CONTENT': return activeContents.value.some(item => item.id === group.key) ? '' : '此类别已停用'
    case 'STATUS': return workflowStatuses.value.some(item => item.code === group.key && item.active) ? '' : '此状态已停用'
    case 'PRIORITY': return priorityOptions.value.some(item => item.code === group.key && item.active) ? '' : '此优先级已停用'
    case 'ASSIGNEE': return group.key.split(',').every(id => activeMembers.value.some(item => item.userId === id)) ? '' : '此分组中有处理人已不在项目中'
    case 'DUE_DATE': return group.from && group.to && group.from > group.to ? '当前日期下此分组没有可用日期' : ''
    default: return ''
  }
}
const groupCreate = useWorkItemGroupCreate({
  defaults: () => createDefaults.value,
  projectId: () => projectId.value, field: () => groupingField.value, contentId: () => defaultContentId.value,
  disabledReason: groupCreateDisabledReason, changed: () => { notifyChanged(); return reloadSortedTableInPlace() },
})
type TableDisplayRow = ProjectWorkItemListItem | WorkItemGroupDisplayRow
function groupKeyForRow(row: TableDisplayRow): string {
  if (isGroupDisplayRow(row)) return row.group.key
  const anchor = isDraft(row) ? tableItems.value.find(item => item.id === inlineDraft.value?.anchorId) : undefined
  return grouping.keyOf(anchor ?? row)
}
const tableInitialLoading = computed(() => (grouped.value ? groupsLoading.value : tableLoading.value) && !displayTableItems.value.length)
function isGroupPageLoading(group: { key: string }): boolean {
  const page = grouping.page(group.key)
  return !page.error && (!groupCountsReady.value || page.loading)
}
const displayTableItems = computed<TableDisplayRow[]>(() => {
  const withSubitems = (items: ProjectWorkItemListItem[], group: WorkItemGroup): TableDisplayRow[] => items.flatMap(item =>
    expandedIds.value.includes(item.id)
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
function groupCellClasses(context: { row: TableDisplayRow; column: { property?: string; columnKey?: string } }): string {
  if (isGroupDisplayRow(context.row) && context.row.groupRowKind === 'subitems') return 'el-table__expanded-cell'
  if (isGroupDisplayRow(context.row) && context.row.groupRowKind === 'columns' && isConnectColumnKey(context.column.property ?? '')) return 'work-item-group-column-header monday-connect-column-header'
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
  clearSelection()
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
  && projectWritable(project.value)
  && detail.value?.capabilities?.canDiscuss
  && (project.value.actorAccess === ProjectActorAccess.Owner
    || project.value.actorAccess === ProjectActorAccess.Member)))
const discussionReadOnlyReason = computed(() => {
  if (detail.value?.archived) return '工作项已归档，恢复后可继续编辑和讨论。'
  if (project.value?.lifecycle === ProjectLifecycle.Archived) return 'Project 已归档，工作项讨论仅可查看。'
  if (project.value?.endedAt) return '项目已结束，工作项讨论仅可查看。'
  if (!canPublishDiscussion.value) return '当前角色没有发布讨论的权限。'
  return undefined
})

function currentBatchItem(id: string): ProjectWorkItemListItem | undefined {
  return tableItems.value.find(item => item.id === id)
    ?? Object.values(subitems).flatMap(state => state.items).find(item => item.id === id)
}
function clearSelection(): void {
  tableRef.value?.clearSelection()
  subitemTableHandles.forEach(handle => handle.clearSelection())
  selectedWorkItemIds.value = new Set()
  Object.keys(subitemSelections).forEach(id => delete subitemSelections[id])
}
const batchSelections = computed<WorkItemSelection[]>(() => [
  ...tableItems.value.filter(item => selectedWorkItemIds.value.has(item.id)).map(item => ({ id: item.id })),
  ...Object.entries(subitemSelections).flatMap(([parentId, ids]) => (subitems[parentId]?.items ?? [])
    .filter(item => ids.has(item.id)).map(item => ({ id: item.id, parentId }))),
])
const batchSelectedIds = computed(() => new Set(batchSelections.value.map(selection => selection.id)))
const batch = useWorkItemBatchActions({
  contextId: () => projectId.value,
  selection: () => batchSelections.value,
  resolve: currentBatchItem,
  canCreate: () => canCreate.value,
  sorted: parentId => parentId ? Boolean(subitems[parentId]?.sortRules.length) : hasExplicitSort.value,
  duplicateOptions: () => {
    if (!catalog.value || !labelCatalog.value) throw new Error('工作项目录尚未加载，请稍后重试。')
    return { catalog: catalog.value, labels: labelCatalog.value, members: members.value }
  },
  scopeKey: (selection, item) => selection.parentId ? 'parent:' + selection.parentId
    : grouped.value ? 'group:' + groupKeyForRow(item) : 'project:' + item.projectId,
  orderItems: (item, edge) => {
    const parentId = Object.keys(subitems).find(id => subitems[id]!.items.some(child => child.id === item.id))
    return workItemOrderSiblings(item, edge, parentId,
      parentId ? async () => (await tableSource.listWorkItemSubitems({ parentWorkItemId: parentId })).items
        : grouped.value ? grouping.edgeItems : embedded.value ? tableEdgeItems : undefined)
  },
  beforeRemove: async items => !detailOpen.value || !items.some(item => item.id === detail.value?.id
    || subitems[item.id]?.items.some(child => child.id === detail.value?.id)) || await beforeDraftLeave(),
  deselect: async (selection, action) => {
    const item = currentBatchItem(selection.id)
    if (!item) return
    if (selection.parentId) {
      subitemTableHandles.get(selection.parentId)?.deselect(item)
      subitemSelections[selection.parentId]?.delete(item.id)
    } else {
      tableRef.value?.toggleRowSelection(item, false)
      selectedWorkItemIds.value.delete(item.id)
    }
    if (action === 'archive' || action === 'delete') await onRowRemoved(item, true)
  },
  refresh: onRelationsChanged,
  updated: (id, detail) => replaceLightItem(id, detail, false),
})
const { busy: batchBusy, stopping: batchStopping, progressLabel: batchProgressLabel, count: selectedCount,
  subitemCount: selectedSubitemCount, selected: selectedBatchRows, convertibleItems: batchConvertibleItems,
  canDelete: batchCanDelete, moveDisabled: batchMoveDisabled, choosingParent: batchChoosingParent } = batch
/** 编辑已勾选的行时同步修改全部勾选项；弹层顶部的复选框可改为只修改当前行。 */
const batchEditSync = ref(true)
function batchEditCount(id: string): number {
  return batchSelectedIds.value.size > 1 && batchSelectedIds.value.has(id) ? batchSelectedIds.value.size : 0
}
function batchEditTargets(id: string): WorkItemSelection[] | undefined {
  return batchEditSync.value && batchEditCount(id) ? batchSelections.value : undefined
}
function batchClearDueDateLabel(id: string): string | undefined {
  const count = batchEditCount(id)
  return count ? `清空已选 ${count} 项的截止日期` : undefined
}
provide(workItemBatchEditScopeKey, { count: batchEditCount, sync: batchEditSync })
async function runBatchEdit(edit: WorkItemBatchEdit, targets: WorkItemSelection[]): Promise<boolean> {
  closeCell()
  const result = await batch.edit(edit, targets)
  try {
    if (edit.field === 'content' && result?.succeeded.length) catalog.value = await contentsApi.listProjectContents({ projectId: projectId.value })
  } catch (reason) { error.value = await toApiProblem(reason) }
  return Boolean(result && !result.failed.length && !result.skipped.length && !result.remaining)
}
function applyBatchAssignees(item: ProjectWorkItemListItem, mode: WorkItemBatchAssigneeMode, userIds: string[]): void {
  const targets = batchEditTargets(item.id)
  if (targets) void runBatchEdit({ field: 'assignees', mode, userIds }, targets)
  else closeCell()
}
function duplicateRow(item: ProjectWorkItemListItem, parentId?: string): void {
  void batch.run('duplicate', [{ id: item.id, parentId }])
}

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
    return state.pending
  }
  if (state.loaded && !force) return
  state.loading = true
  delete state.error
  state.pending = (async () => {
    try {
      const result = await tableSource.listWorkItemSubitems({
        parentWorkItemId: parentId,
        ...filterRequest(),
        ...(filters.statuses.size ? { status: filters.statuses } : {}),
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
  })()
  return state.pending
}

function onTableExpandChange(row: TableDisplayRow,
  expanded: TableDisplayRow[] | boolean): void {
  if (isGroupDisplayRow(row)) return
  const open = Array.isArray(expanded) ? expanded.includes(row) : expanded
  if (explicitFilterActive.value) {
    const next = new Set(filterToggledIds.value)
    if (open === Boolean(row.matchedSubitemCount)) next.delete(row.id); else next.add(row.id)
    filterToggledIds.value = next
  } else {
    expandedSubitemIds.value = open ? [...new Set([...expandedSubitemIds.value, row.id])]
      : expandedSubitemIds.value.filter(id => id !== row.id)
  }
  if (open) void loadSubitems(row.id)
  schedulePageScrollbarSync()
}

function toggleSubitems(row: ProjectWorkItemListItem): void {
  onTableExpandChange(row, !expandedIds.value.includes(row.id))
}

function setSubitemTableHandle(id: string, handle: unknown): void {
  if (handle) subitemTableHandles.set(id, handle as SubitemTableHandle)
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

async function onRowRemoved(row: ProjectWorkItemListItem, preserveChildSelection = false): Promise<void> {
  void connect.refreshCells([row.id])
  selectedWorkItemIds.value.delete(row.id)
  Object.values(subitemSelections).forEach(selection => selection.delete(row.id))
  if (!preserveChildSelection) delete subitemSelections[row.id]
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
    void connect.refreshCells([restoringItem.id])
    await onRelationsChanged([restoringItem.id, ...visibleExpandedIds.value])
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

/** 抽屉里点标题：关闭抽屉，用现有详情抽屉打开（详情里同样可以恢复）。 */
async function openArchived(workItemId: string): Promise<void> {
  if (!await beforeDraftLeave()) return
  archivedOpen.value = false
  await setViewQuery({ ...route.query, workItemId })
}

async function onArchivedRestored(ids: string[]): Promise<void> {
  void connect.refreshCells(ids)
  await onRelationsChanged([...ids, ...visibleExpandedIds.value])
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
    .filter(([key]) => key !== 'workItemId' && key !== 'tab' && (includeSort || key !== 'sort'))
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
  await setViewQuery(Object.fromEntries(Object.entries(next).filter(([, value]) => value !== undefined)) as LocationQuery, true)
}

function scheduleSearch(): void {
  if (searchTimer) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => void syncUrl(), 300)
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
    ...filterRequest(),
    ...(filters.statuses.size ? { status: filters.statuses }
      : unfinishedStatusCodes.value && !searchInput.value.trim() ? { status: new Set(unfinishedStatusCodes.value) } : {}),
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
          && visibleExpandedIds.value.every(id => loaded.has(id))
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
      ...filterRequest(),
    }, { signal: activeController?.signal ?? null })
    if (revision !== loadRevision) return
    const merged = cursor ? [...state.items, ...result.items] : result.items
    state.items = [...new Map(merged.map(item => [item.id, item])).values()]
    state.nextCursor = result.nextCursor
  } catch (reason) {
    if (revision === loadRevision) state.error = await toApiProblem(reason)
  } finally {
    if (revision === loadRevision) state.loading = false
  }
}

async function loadKanban(revision = loadRevision): Promise<void> {
  Object.keys(lanes).forEach(key => delete lanes[key])
  await Promise.all([...kanbanLaneStatuses.value.map(status => loadLane(status.statusCode, null, revision)), loadKanbanCounts(revision)])
}

// 看板：卡片字段跟随「隐藏」列设置；列头数量与分组计数同源，按当前筛选统计根工作项。
const KANBAN_CARD_FIELDS: readonly string[] = ['content', 'status', 'priority', 'dueDate', 'timeTracking']
/** 隐藏已完成开启时（任一效果），看板不显示也不加载被本人视为已完成的状态列；仪表板内嵌看板不受影响。 */
const kanbanLaneStatuses = computed<KanbanBoardStatus[]>(() => !embedded.value && hideCompleted.value
  ? workflowStatuses.value.filter(status => !isCompletedStatus(tableViewSettings.value.completedStatusCodes,
    status.statusCode, status.statusCategory))
  : workflowStatuses.value)
watch(() => kanbanLaneStatuses.value.map(status => status.statusCode).join('|'), () => {
  if (selectedView.value !== 'kanban' || !project.value) return
  kanbanLaneStatuses.value.filter(status => !lanes[status.statusCode]).forEach(status => void loadLane(status.statusCode))
})
const kanbanCardFields = computed(() => movableVisibleColumns.value.map(column => column.key)
  .filter((key): key is KanbanCardField => KANBAN_CARD_FIELDS.includes(key)))
const kanbanCounts = ref<Map<string, number>>()
const kanbanExpandedIds = ref(new Set<string>())

async function loadKanbanCounts(revision = loadRevision): Promise<void> {
  kanbanCounts.value = undefined
  const counts = new Map<string, number>()
  let cursor: string | null = null
  try {
    do {
      const page = await tableSource.listProjectWorkItemFilterOptions({ projectId: projectId.value, ...filterRequest(),
        field: ListProjectWorkItemFilterOptionsFieldEnum.Status, limit: 100, ...(cursor ? { cursor } : {}) },
      { signal: activeController?.signal ?? null })
      if (revision !== loadRevision) return
      page.items.forEach(option => counts.set(option.value, option.count))
      cursor = page.nextCursor
    } while (cursor)
    kanbanCounts.value = new Map(workflowStatuses.value.map(status => [status.statusCode, counts.get(status.statusCode) ?? 0]))
  } catch {
    // 计数失败时列头退回已加载数量，不影响看板本身。
  }
}

function loadMoreLane(statusCode: string): void {
  const state = lane(statusCode)
  if (state.nextCursor && !state.loading) void loadLane(statusCode, state.nextCursor)
}

function toggleKanbanSubitems(item: ProjectWorkItemListItem): void {
  const next = new Set(kanbanExpandedIds.value)
  if (next.has(item.id)) next.delete(item.id)
  else { next.add(item.id); void loadSubitems(item.id) }
  kanbanExpandedIds.value = next
}

function onKanbanSubitemCreated(parent: ProjectWorkItemListItem): void {
  kanbanExpandedIds.value = new Set([...kanbanExpandedIds.value, parent.id])
  onSubitemCreated(parent)
}

async function reloadLane(statusCode: string): Promise<void> {
  await Promise.all([loadLane(statusCode), loadKanbanCounts()])
}

type LanePlacement = { edge: 'top' | 'bottom' } | { edge: 'after'; anchorId: string }
const lanePlacements = new Map<string, LanePlacement>()
function laneGroup(statusCode: string): WorkItemGroup {
  return { key: statusCode, label: statusCode, color: '', count: 0, rank: 0 }
}
function laneCreateDisabledReason(group: WorkItemGroup): string {
  if (!canCreate.value || !defaultContentId.value) return '当前无法添加工作项'
  return workflowStatuses.value.some(item => item.code === group.key && item.active) ? '' : '此状态已停用'
}
/** 列尾新建在当前列最后一项之后；列头「+」与「在下方创建」先建「新工作项」，再进入改名。 */
async function onLaneCreated(created: WorkItemDetail, placement: LanePlacement | undefined): Promise<void> {
  notifyChanged()
  const statusCode = created.statusCode
  const state = lane(statusCode)
  try {
    if (placement?.edge === 'after') await moveWorkItemOrder(created, undefined, placement.anchorId, null)
    else if (placement?.edge === 'bottom' && !state.nextCursor) {
      const last = state.items.filter(item => item.id !== created.id).at(-1)
      if (last) await moveWorkItemOrder(created, undefined, last.id, null)
    }
  } catch (reason) {
    error.value = await toApiProblem(reason)
  }
  await reloadLane(statusCode)
  if (placement?.edge === 'bottom') return
  if (!lane(statusCode).items.some(item => item.id === created.id)) {
    ElMessage.success(`已创建 ${created.itemNo}，当前筛选下不显示`)
    return
  }
  await nextTick()
  autoEditId.value = created.id
}
const laneTopCreate = useWorkItemGroupCreate({
  defaults: () => createDefaults.value, projectId: () => projectId.value, field: () => 'STATUS',
  contentId: () => defaultContentId.value, disabledReason: laneCreateDisabledReason,
  changed: created => onLaneCreated(created, lanePlacements.get(`top:${created.statusCode}`)),
})
const laneBottomCreate = useWorkItemGroupCreate({
  defaults: () => createDefaults.value, projectId: () => projectId.value, field: () => 'STATUS',
  contentId: () => defaultContentId.value, disabledReason: laneCreateDisabledReason,
  changed: created => onLaneCreated(created, { edge: 'bottom' }),
})
async function createInLane(statusCode: string, placement: LanePlacement): Promise<void> {
  const group = laneGroup(statusCode)
  const draft = laneTopCreate.draft(group)
  if (draft.saving || laneCreateDisabledReason(group)) return
  autoEditId.value = ''
  draft.title = '新工作项'
  lanePlacements.set(`top:${statusCode}`, placement)
  await laneTopCreate.save(group)
  if (draft.error) { error.value = draft.error; laneTopCreate.cancel(group) }
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
  // 被取代的请求不会回写加载状态，否则新的表格加载会被当成仍在进行而跳过。
  tableLoading.value = false
  tableSorting.value = false
  const requestedProjectId = projectId.value
  loading.value = true
  error.value = undefined
  project.value = undefined
  catalog.value = undefined
  labelCatalog.value = undefined
  clearSelection()
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
    const revealId = route.query.workItemId
    if (revision === loadRevision && typeof revealId === 'string') void revealWorkItem(revealId)
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

async function createWithDefaults(title: string, csrf: string): Promise<WorkItemDetail> {
  const inserted = await workItemsApi.createWorkItem({
    projectId: projectId.value,
    xXSRFTOKEN: csrf,
    idempotencyKey: globalThis.crypto.randomUUID(),
    workItemCreateRequest: {
      contentId: defaultContentId.value!,
      title,
      priority: null,
      assigneeUserId: null,
      description: null,
      notes: null,
      timelineStartDate: null,
      timelineEndDate: null,
      dueDate: null,
      ...createDefaults.value.fields,
    },
  })
  return applyDefaultStatus(inserted, createDefaults.value.statusCode, csrf)
}

/** The server creates new items at the top of the manual order; the reload reveals the row and its name opens for editing. */
async function createAtTop(): Promise<void> {
  if (creatingTop.value || !defaultContentId.value) return
  const csrf = readCsrfToken()
  if (!csrf) { error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  creatingTop.value = true
  error.value = undefined
  try {
    autoEditId.value = ''
    const created = await createWithDefaults('新工作项', csrf)
    notifyChanged()
    let shown: boolean
    if (grouped.value) {
      await grouping.refresh()
      const key = grouping.keyOf(created)
      if (grouping.isCollapsed(key)) grouping.reveal(key)
      await grouping.load(key, false)
      const page = grouping.page(key)
      while (page.nextCursor && !page.error && !page.items.some(item => item.id === created.id)) await grouping.load(key)
      shown = page.items.some(item => item.id === created.id)
    } else {
      await reloadSortedTableInPlace(created.id)
      shown = tableItems.value.some(item => item.id === created.id)
    }
    // Search or filters can leave the new item out of the table; say it exists instead of editing a row nobody sees.
    if (!shown) { ElMessage.success(`已创建 ${created.itemNo}，当前筛选下不显示`); return }
    await nextTick()
    autoEditId.value = created.id
  } catch (reason) {
    error.value = await toApiProblem(reason)
  } finally {
    creatingTop.value = false
  }
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
    const created = await createWithDefaults(quickTitle.value.trim(), csrf)
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

async function loadDetail(workItemId: string, tab: 'details' | 'discussion' | 'activity' = 'details'): Promise<void> {
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

async function onDiscussionChanged(workItemId: string): Promise<void> {
  notifyChanged()
  const parents = Object.keys(subitems).filter(id => subitems[id]!.items.some(item => item.id === workItemId))
  parents.forEach(id => { subitems[id]!.loaded = false })
  await Promise.all([
    selectedView.value === 'table' ? reloadSortedTableInPlace() : refreshCurrentView(),
    ...parents.filter(id => expandedIds.value.includes(id)).map(id => loadSubitems(id, true)),
  ])
}

async function onRelationsChanged(affectedWorkItemIds: string[]): Promise<void> {
  notifyChanged()
  for (const id of affectedWorkItemIds) {
    if (subitems[id]) subitems[id].loaded = false
  }
  if (selectedView.value === 'table') await reloadSortedTableInPlace()
  else await refreshCurrentView()
  await Promise.all(visibleExpandedIds.value
    .filter(id => affectedWorkItemIds.includes(id))
    .map(id => loadSubitems(id, true)))
}

/** 导入的工作项按文件顺序排在最前，已展开的子项列表一并失效重载。 */
async function onWorkItemsImported(): Promise<void> {
  await onRelationsChanged(Object.keys(subitems))
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

/** 从页面外打开工作项：载入所在行，选中并滚到表格可视区中部；子项先展开父项。 */
async function revealWorkItem(workItemId: string): Promise<void> {
  if (embedded.value || selectedView.value !== 'table') return
  try {
    const relations = await workItemsApi.listWorkItemRelations({ workItemId, relationType: WorkItemRelationType.ParentChild, size: 100 })
    const parentId = relations.items.find(relation => relation.currentRole === WorkItemRelationRole.Child)?.counterpart.id
    const rowId = parentId ?? workItemId
    if (grouped.value) {
      const key = grouping.keyOf(await workItemsApi.getWorkItem({ workItemId: rowId }))
      if (!groupCountsReady.value || !workItemGroups.value.some(group => group.key === key)) return
      if (grouping.isCollapsed(key)) grouping.reveal(key)
      const page = grouping.page(key)
      while (!page.items.some(item => item.id === rowId) && !page.error && (!page.loaded || page.nextCursor)) await grouping.load(key)
    } else if (!tableItems.value.some(item => item.id === rowId)) await reloadSortedTableInPlace(rowId)
    if (parentId) {
      expandedSubitemIds.value = [...new Set([...expandedSubitemIds.value, parentId])]
      await loadSubitems(parentId)
    }
    selectCell(workItemId, 'title')
    await nextTick()
    const cell = tableRef.value?.$el.querySelector<HTMLElement>('.work-item-name-cell.monday-cell--selected')
    const wrap = resolveTableScrollElement()
    if (!cell || !wrap) return
    const cellBox = cell.getBoundingClientRect()
    const wrapBox = wrap.getBoundingClientRect()
    wrap.scrollTop += cellBox.top + cellBox.height / 2 - (wrapBox.top + wrapBox.height / 2)
  } catch (reason) {
    error.value = await toApiProblem(reason)
  }
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
  delete next.tab
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
  const targets = batchEditTargets(item.id)
  if (targets) { await runBatchEdit({ field: 'status', statusCode, resolution: null }, targets); return }
  dragging.value = item
  await dropInto(statusCode)
}

function replaceLightItem(id: string, updatedDetail: WorkItemDetail, regroup = true): void {
  notifyChanged()
  const apply = (item: ProjectWorkItemListItem): ProjectWorkItemListItem => {
    if (item.id !== id) return item
    const updated = {
      ...item, title: updatedDetail.title, contentId: updatedDetail.contentId, contentName: updatedDetail.contentName,
      contentColorToken: updatedDetail.contentColorToken,
      statusCode: updatedDetail.statusCode, statusCategory: updatedDetail.statusCategory,
      priority: updatedDetail.priority,
      assignees: workItemAssignees(updatedDetail),
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
  if (regroup && grouped.value && tableItems.value.some(item => item.id === id)) void grouping.changed()
}

const cellEdits = useWorkItemEdits({ updated: replaceLightItem, failed: async problem => { error.value = problem; notifyChanged(); await loadTable(null, false) } })
async function patchCell(item: ProjectWorkItemListItem, field: WorkItemPatchField, value: WorkItemPatchValue, dueTime?: string | null): Promise<boolean> {
  const targets = batchEditTargets(item.id)
  if (targets) return runBatchEdit(field === 'priority' ? { field, priority: value as string | null }
    : field === 'content' ? { field, contentId: value as string }
      : field === 'assignees' ? { field, mode: 'replace', userIds: value as readonly string[] }
        : dueDateBatchEdit(item, { dueDate: dueDateKey(value as Date | null),
          dueTime: dueTime === undefined ? item.dueTime ?? null : dueTime }), targets)
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

function sortDirectionForColumn(key: BuiltInColumnKey): 'ASC' | 'DESC' | undefined {
  return sortRules.value.find(rule => rule.field === sortFieldByColumn[key])?.direction
}

function applyColumnQuickSort(key: BuiltInColumnKey): void {
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

function clearColumnSort(key: BuiltInColumnKey): void {
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

/** 逐组加载完整排序结果；任一组失败或加载中途被重置时返回 undefined。 */
async function loadAllGroupedWorkItems(): Promise<ProjectWorkItemListItem[][] | undefined> {
  const revision = loadRevision
  const runs: ProjectWorkItemListItem[][] = []
  for (const group of workItemGroups.value) {
    if (!group.count) continue
    const page = grouping.page(group.key)
    do {
      await grouping.load(group.key)
      if (revision !== loadRevision || grouping.page(group.key) !== page) return undefined
    } while (page.nextCursor && !page.error)
    if (page.error || !page.loaded) return undefined
    runs.push([...page.items])
  }
  return runs
}

// 分组时每组各自排序：组内依次排在本组首项之后，组与组之间的相对位置不变。
async function saveSortedWorkItemOrder(): Promise<void> {
  if (savingSortOrder.value || !sortRules.value.length) return
  if (tableLoading.value || tableSorting.value || (grouped.value && groupsLoading.value)) {
    ElMessage.info('排序结果仍在加载，请稍后再保存工作项顺序')
    return
  }
  savingSortOrder.value = true
  try {
    const runs = grouped.value
      ? await loadAllGroupedWorkItems()
      : await loadAllSortedWorkItems() ? [[...tableItems.value]] : undefined
    if (!runs) {
      ElMessage.error('无法加载完整排序结果，暂未保存工作项顺序')
      return
    }
    const ordered = runs.flat()
    if (ordered.some(item => !item.capabilities.canMoveInProjectOrder)) {
      ElMessage.warning('当前结果中包含不可调整顺序的工作项')
      return
    }
    const csrf = readCsrfToken()
    if (!csrf) {
      error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
      return
    }
    for (const run of runs) {
      for (let index = 1; index < run.length; index += 1) {
        const item = tableItems.value.find(candidate => candidate.id === run[index]!.id) ?? run[index]!
        const updated = await workItemsApi.moveProjectWorkItemOrder({
          projectId: projectId.value,
          workItemId: item.id,
          xXSRFTOKEN: csrf,
          ifMatch: item.etag,
          idempotencyKey: globalThis.crypto.randomUUID(),
          projectWorkItemOrderMoveRequest: { previousVisibleWorkItemId: run[index - 1]!.id, nextVisibleWorkItemId: null },
        })
        replaceLightItem(item.id, updated, false)
      }
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
    collapsed: [...collapsedColumns.value],
    order: movableColumnOrder.value,
    subitemOrder: subitemMovableColumnOrder.value,
  }))
}

function loadTablePrefs(): void {
  try {
    const parsed = JSON.parse(localStorage.getItem(tablePrefsKey.value) ?? (embedded.value ? localStorage.getItem(projectTablePrefsKey) : null) ?? '{}') as {
      version?: number
      widths?: Partial<Record<BuiltInColumnKey, number>>
      collapsed?: string[]
      hidden?: BuiltInColumnKey[]
      order?: BuiltInColumnKey[]
      subitemOrder?: BuiltInColumnKey[]
    }
    if (parsed.version !== TABLE_PREFS_VERSION) return
    columns.forEach(column => {
      const value = parsed.widths?.[column.key]
      if (typeof value === 'number') columnWidths[column.key] = Math.max(column.minWidth, value)
    })
    hiddenColumns.value = new Set((parsed.hidden ?? []).filter(key => key !== 'title'))
    collapsedColumns.value = new Set((parsed.collapsed ?? []).filter((key): key is ColumnKey =>
      key !== 'title' && (isConnectColumnKey(key) || columnByKey.has(key as BuiltInColumnKey))))
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

function onHeaderDragEnd(newWidth: number, _oldWidth: number, column: { property?: string; columnKey?: string }): void {
  const key = column.property ?? column.columnKey ?? ''
  if (isColumnCollapsed(key as ColumnKey)) { scheduleResponsiveTableLayout(); return }
  if (isConnectColumnKey(key)) { connect.resizeColumn(key, newWidth); return }
  const config = columnByKey.get(key as BuiltInColumnKey)
  if (!config) return
  columnWidths[config.key] = Math.max(config.minWidth, Math.round(newWidth))
  persistTablePrefs()
}

const columnFlip = createWorkItemColumnFlip({
  measure: () => measureWorkItemColumns(tableRef.value?.$el),
  targets: () => visibleWorkItemColumnCells(tableRef.value?.$el),
  disabled: () => Boolean(columnDraggingKey.value || columnResizingKey.value
    || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches),
})
async function toggleColumn(key: ColumnKey, checked: boolean): Promise<void> {
  if (key === 'title') return
  if (isConnectColumnKey(key)) { connect.toggleColumn(key, checked); await nextTick(flushResponsiveTableLayout); return }
  const snapshot = columnFlip.capture()
  const next = new Set(hiddenColumns.value)
  if (checked) next.delete(key); else next.add(key)
  hiddenColumns.value = next
  persistTablePrefs()
  await nextTick()
  flushResponsiveTableLayout()
  columnFlip.play(snapshot)
}

const TABLE_DRAG_TILT_DEGREES = 1
const TABLE_DRAG_POINTER_THRESHOLD = 5

const {
  removeTableDragPreview, resetTableDragState, tableRowDragStyle, clearTablePointerTracking,
  suppressClickAfterTableDrag, onTableClickCapture, onTablePointerDown, tableDragging,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars -- 既有组件测试通过实例读取拖拽状态和入口。
  moveTableDragPreview, createTableDragPreview, updateTableDropTarget,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars -- 既有组件测试通过实例读取拖拽状态和入口。
  onTablePointerMove, onTablePointerUp, onTablePointerCancel,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars -- 既有组件测试通过实例读取拖拽状态和入口。
  commitTableDrop, tableDraggingIndex, tableDropIndex,
} = useTableRowDrag({
  tableRef, tableRowHeight, tableItems, tableNextCursor, tableLoading, tableSorting, grouped, groupCountsReady, grouping,
  inlineDraft, hasExplicitSort, sortRules, projectId, error, resolveTableScrollElement, loadTable, syncUrl, replaceLightItem,
  TABLE_DRAG_TILT_DEGREES, TABLE_DRAG_POINTER_THRESHOLD,
})

const {
  removeTableColumnDragPreview, tableCellStyle, tableHeaderCellStyle, clearTableColumnPointerTracking,
  clearTableColumnResizeTracking, onTableSurfacePointerDown, columnDraggingKey, columnResizingKey,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars -- 既有组件测试通过实例读取拖拽状态和入口。
  tableColumnDragStyle, onTableColumnResizePointerDown, onTableColumnResizePointerMove,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars -- 既有组件测试通过实例读取拖拽状态和入口。
  onTableColumnResizePointerUp, onTableColumnPointerDown, onTableColumnPointerMove,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars -- 既有组件测试通过实例读取拖拽状态和入口。
  commitTableColumnDrop, onTableColumnPointerUp, columnDraggingIndex,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars -- 既有组件测试通过实例读取拖拽状态和入口。
  columnDropIndex,
} = useTableColumnDrag({
  tableRef, grouped, tableDragging, movableVisibleColumns, movableColumnOrder, columnWidths, columnByKey, cellColoring,
  scheduleResponsiveTableLayout, flushResponsiveTableLayout, schedulePageScrollbarSync, persistTablePrefs,
  suppressClickAfterTableDrag, onTablePointerDown, TABLE_DRAG_TILT_DEGREES, TABLE_DRAG_POINTER_THRESHOLD,
})

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

const tableColoring = computed(() => {
  const colors = new Map<string, WorkItemColoring>()
  const rules = tableViewSettings.value.coloringRules
  if (!rules.length) return colors
  for (const row of displayTableItems.value) {
    if (isGroupDisplayRow(row) || isDraft(row)) continue
    const color = evaluateWorkItemColoring(rules, row, companyToday.value)
    if (color) colors.set(row.id, color)
  }
  return colors
})

function rowColoring(row: TableDisplayRow): WorkItemColoring | undefined {
  return tableColoring.value.get(row.id)
}

function cellColoring(row: TableDisplayRow, column: { property?: string; columnKey?: string }): string | undefined {
  const key = column.property ?? column.columnKey
  return key ? rowColoring(row)?.cells[key as ColoringColumnKey] : undefined
}

function tableRowClassName({ row }: { row: ProjectWorkItemListItem; rowIndex: number }): string {
  const classes = ['work-item-table-row']
  if (isDraft(row)) classes.push('work-item-draft-row')
  if (row.capabilities.canMoveInProjectOrder) classes.push('work-item-table-row--movable')
  if (tableDragging.value?.id === row.id) classes.push('work-item-table-row--dragging')
  if (tableSorting.value) classes.push('work-item-table-row--sorting')
  if (isRowSelected(row.id)) classes.push('work-item-table-row--selected')
  if (rowColoring(row)?.row) classes.push('work-item-table-row--colored')
  if (isCompletedRow(row)) classes.push('work-item-table-row--completed')
  return classes.join(' ')
}

function tableRowStyle(context: { row: ProjectWorkItemListItem; rowIndex: number }): CSSProperties {
  const style = tableRowDragStyle(context)
  const color = rowColoring(context.row)?.row
  return color ? { ...style, '--work-item-condition-row-bg': color } : style
}

async function resetCurrentData(): Promise<void> {
  invalidateFilterOptions()
  clearSelection()
  grouping.stop()
  filterToggledIds.value = new Set()
  // 子项表随搜索与筛选变化，平铺与分组都要丢弃已加载的子项。
  Object.keys(subitems).forEach(key => delete subitems[key])
  if (grouped.value && selectedView.value === 'table') {
    loadRevision++; activeController?.abort(); tableLoading.value = false; tableSorting.value = false
    await grouping.refresh()
    visibleExpandedIds.value.forEach(id => void loadSubitems(id))
    return
  }
  const revision = ++loadRevision
  activeController?.abort(); activeController = new AbortController()
  tableLoading.value = false; tableSorting.value = false
  error.value = undefined; tableItems.value = []; tableNextCursor.value = null
  Object.keys(lanes).forEach(key => delete lanes[key])
  if (selectedView.value === 'kanban') await loadKanban(revision); else await loadTable(null, false, revision)
}

function onDueDateChange(item: ProjectWorkItemListItem, value: string | null, dueTime?: string | null): void {
  void patchCell(item, 'dueDate', apiDate(value), dueTime)
}

function onDeadlineChange(item: ProjectWorkItemListItem, value: DueDateValue): void {
  onDueDateChange(item, value.dueDate, value.dueTime)
}

watch(projectId, () => {
  invalidateFilterOptions()
  if (embedded.value) {
    try { embeddedQuery.value = JSON.parse(localStorage.getItem(`${props.preferenceScope}:query`) ?? '{}') as LocationQuery } catch { embeddedQuery.value = {} }
    if ('tab' in embeddedQuery.value) {
      delete embeddedQuery.value.tab
      void setViewQuery(embeddedQuery.value, true)
    }
  }
  applyRouteState(); void (embedded.value ? loadWorkspace() : pageLoad.track(loadWorkspace))
}, { immediate: true })
watch(() => props.refreshKey, async () => {
  if (!project.value) return
  await reloadSortedTableInPlace()
  await Promise.all(visibleExpandedIds.value.map(id => loadSubitems(id, true)))
})
async function canClose(): Promise<boolean> {
  if (batchBusy.value) { ElMessage.info('批量操作正在执行，请停止或等待完成。'); return false }
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
  if (sortOnly) void pageLoad.track(reloadSortedTableInPlace)
  else void pageLoad.track(resetCurrentData)
})
watch([() => route.query.workItemId, () => route.query.tab], ([value, tab], [previousValue, previousTab]) => {
  if (value === previousValue && tab === undefined && previousTab !== undefined) return
  const workItemId = Array.isArray(value) ? value[0] : value
  if (tab !== undefined) {
    const next = { ...route.query }
    delete next.tab
    void setViewQuery(next, true)
  }
  if (workItemId) {
    const external = selectedRowId.value !== String(workItemId)
    selectedRowId.value = String(workItemId)
    if (!selectedCellKey.value || !selectedCellKey.value.startsWith(`${workItemId}:`)) {
      selectedCellKey.value = `${workItemId}:title`
    }
    void loadDetail(String(workItemId), tab === undefined ? detailTab.value
      : tab === 'discussion' || tab === 'activity' ? tab : 'details')
    if (external && project.value) void revealWorkItem(String(workItemId))
  } else {
    detailGeneration++
    detailOpen.value = false
    detail.value = undefined
  }
}, { immediate: true })
watch(isResizingDrawer, value => { if (!embedded.value) document.body.classList.toggle('yp-work-items-drawer-resizing', value) }, { flush: 'sync' })
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
  () => tableNextCursor.value,
  () => tableSorting.value,
  () => quickOpen.value,
  () => tableLoading.value,
  () => loadingMoreError.value,
  () => visibleColumns.value.map(item => `${item.key}:${columnWidth(item.key)}`).join('|'),
], schedulePageScrollbarSync, { flush: 'post' })

onMounted(() => {
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
  watch(tableSentinel, (next, previous) => { if (previous) tableObserver?.unobserve(previous); if (next) tableObserver?.observe(next) }, { immediate: true })
})
onBeforeUnmount(() => {
  loadRevision++
  columnFlip.cancel()
  groupObserver?.disconnect()
  activeController?.abort(); if (searchTimer) window.clearTimeout(searchTimer)
  tableObserver?.disconnect()
  document.removeEventListener('pointerdown', onDocumentPointerDown)
  clearTablePointerTracking()
  clearTableColumnPointerTracking()
  clearTableColumnResizeTracking()
  removeTableDragPreview()
  removeTableColumnDragPreview()
})
</script>

<template>
  <div
    class="project-view-stack project-overview-stack"
    :class="{ 'work-items-embedded': embedded }"
  >
    <inline-problem
      v-if="error"
      :problem="error"
    />
    <div
      v-if="loading && !project && !error"
      class="work-items-page-skeleton"
    >
      <div
        class="work-items-page-skeleton__head work-item-skeleton"
        aria-hidden="true"
      >
        <i class="work-item-skeleton-block work-items-page-skeleton__title" />
        <div class="work-items-page-skeleton__toolbar">
          <i
            v-for="width in PAGE_SKELETON_TOOLBAR"
            :key="width"
            class="work-item-skeleton-block"
            :style="{ width: `${width}px` }"
          />
        </div>
      </div>
      <div
        v-if="selectedView === 'kanban'"
        class="kanban-page-skeleton"
      >
        <section
          v-for="lane in 4"
          :key="lane"
          class="kanban-page-skeleton__lane"
        >
          <work-item-kanban-skeleton />
        </section>
      </div>
      <work-item-table-skeleton
        v-else
        class="work-items-page-skeleton__table"
        :columns="skeletonColumns"
        :lead-widths="TABLE_LEAD_WIDTHS"
        :trailing-width="TABLE_ADD_COLUMN_MIN_WIDTH"
        :rows="12"
        :row-height="skeletonRowHeight"
        header
        fade
      />
    </div>
    <template v-if="project">
      <work-item-import-dialog
        v-if="canCreate"
        v-model="importOpen"
        :project-id="projectId"
        :contents="catalog?.items ?? []"
        :statuses="labelCatalog?.statuses ?? []"
        :priorities="labelCatalog?.priorities ?? []"
        :members="members"
        :default-content-id="defaultContentId"
        @imported="onWorkItemsImported"
      />
      <work-item-archived-drawer
        v-if="!embedded"
        v-model="archivedOpen"
        :project-id="projectId"
        :statuses="labelCatalog?.statuses ?? []"
        :can-restore="canRestoreArchived"
        @open="openArchived"
        @restored="onArchivedRestored"
      />
      <template v-if="!embedded">
        <connect-column-delete-dialog
          :column="connect.deletingColumn"
          @close="connect.deletingColumn = undefined"
        />
        <connected-item-card
          v-if="connect.selectedConnection"
          v-model:open="connect.cardOpen"
          :connection="connect.selectedConnection"
          :perspective="connect.perspective"
          :read-only="selectedView === 'kanban'"
          @invalidated="connect.refreshConnection"
          @changed="connect.refreshConnection"
        />
      </template>
      <project-workspace-header
        v-if="!embedded"
        section="overview"
        :project="project"
        @changed="loadWorkspace"
      />

      <section class="work-items-home">
        <div v-if="!embedded" class="monday-view-header">
          <div
            class="view-tabs"
            data-tour="work-item-views"
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

        <div class="work-items-toolbar" aria-label="工作项工具栏" data-work-item-view-control data-tour="work-item-toolbar">
          <div
            v-if="selectedView === 'table' && canCreate"
            class="toolbar-create-group"
            :class="{ 'is-disabled': createDisabled }"
          >
            <button
              type="button"
              class="toolbar-create"
              :disabled="createDisabled"
              @click="createAtTop"
            >
              <el-icon><plus /></el-icon><span>新增工作项</span>
            </button>
            <el-dropdown
              trigger="click"
              placement="bottom-end"
              :disabled="createDisabled"
              @command="importOpen = true"
            >
              <button
                type="button"
                class="toolbar-create toolbar-create-more"
                aria-label="更多新增方式"
                aria-haspopup="menu"
                :disabled="createDisabled"
              >
                <el-icon><arrow-down /></el-icon>
              </button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="import">
                    <el-icon><upload /></el-icon>导入工作项
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
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

          <work-item-assignee-filter-popover :filter="filter" />
          <work-item-filter-popover
            :filter="filter" :active-members="activeMembers" :workflow-statuses="workflowStatuses"
            :priority-options="priorityOptions" :catalog="catalog"
          />

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

          <!-- 看板中「隐藏」控制卡片上显示的字段。 -->
          <el-popover placement="bottom-start" :width="320" trigger="click" popper-class="work-items-popover work-item-view-control">
            <template #reference>
              <button class="toolbar-button">
                <el-icon><hide /></el-icon><span>隐藏</span>
              </button>
            </template>
            <work-item-column-visibility-menu :columns="[...columns, ...connect.columns]" :hidden="allHiddenColumns"
              :disabled="Object.values(cellPopoverBusy).some(Boolean)" @toggle="toggleColumn" />
          </el-popover>
          <work-item-grouping-popover :field="groupingField" :order="groupingOrder" :show-empty="showEmptyGroups"
            :disabled="selectedView === 'kanban' || savingSortOrder"
            @field="changeGrouping" @order="groupingOrder = $event" @show-empty="showEmptyGroups = $event" />
          <work-item-completed-menu :statuses="workflowStatuses" :codes="tableViewSettings.completedStatusCodes"
            :disabled="false" :editable="tableSettingsState === 'ready'"
            @update="codes => updateTableSettings({ completedStatusCodes: codes })" />
          <button
            v-if="!embedded"
            class="toolbar-button"
            @click="archivedOpen = true"
          >
            <el-icon><box /></el-icon><span>已归档</span>
          </button>
          <button v-if="timerEnabled" class="toolbar-button" @click="openSmallTimer">◷ 小计时器</button>
          <work-item-table-settings-menu
            :settings="tableViewSettings"
            :state="tableSettingsState"
            :save-state="tableSettingsSaveState"
            :save-error="tableSettingsSaveError"
            :disabled="selectedView === 'kanban'"
            :pin-columns="tablePinColumns"
            :pin-width-budget="tablePinWidthBudget"
            :subitem-pin-columns="subitemPinColumns"
            :subitem-pin-width-budget="subitemPinWidthBudget"
            :defaults-count="createDefaults.count"
            :catalog="tableSettingsCatalog"
            :default-columns="defaultValueColumns"
            @update="updateTableSettings"
            @retry="reloadTableSettings"
            @retry-save="retrySaveTableSettings"
          />
        </div>

        <template v-if="!embedded && (connect.catalogError || connect.cellError)">
          <inline-problem :problem="connect.catalogError ?? connect.cellError!" />
          <el-button
            text
            @click="connect.reloadConnections"
          >
            重新加载连接
          </el-button>
        </template>
        <div
          v-if="selectedView === 'table'"
          :aria-busy="tableLoading || tableSorting || groupsLoading"
          class="table-surface monday-table-surface"
          :class="{ 'monday-table-surface--strike-completed': hideCompletedEffect !== HideCompletedEffect.Fade }"
          data-tour="work-item-table"
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
              :class="[tableHeightClasses, { 'monday-table--column-dragging': columnDraggingKey, 'monday-table--empty': !displayTableItems.length, 'monday-table--grouped': grouped }]"
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
                      <span class="monday-quick-lead" aria-hidden="true" />
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
                    <button v-else class="quick-add monday-quick-add work-item-group-quick" :style="quickGridStyle" data-tour="work-item-quick-add"
                      :disabled="Boolean(groupCreateDisabledReason(scope.row.group))" :title="groupCreateDisabledReason(scope.row.group) || undefined"
                      :aria-label="`添加工作项到${scope.row.group.label}`" @click="groupCreate.open(scope.row.group)">
                      <span class="monday-quick-lead" aria-hidden="true" />
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
                    :row-height="tableRowHeight"
                    :coloring-rules="tableViewSettings.coloringRules"
                    :today="companyToday"
                    :pinned-column-keys="subitemPinnedColumnKeys"
                    :connect-columns="connect.visibleColumns"
                    :connect-cells="connect.cells"
                    :connect-read-only="connect.readOnly"
                    :column-widths="columnWidths"
                    :active-contents="activeContents"
                    :content-catalog="catalog"
                    :members="members"
                    :workflow-statuses="workflowStatuses"
                    :priority-options="priorityOptions"
                    :label-catalog="labelCatalog"
                    :completed="isCompletedRow"
                    :selected="item => isRowSelected(item.id)"
                    :can-create="canCreate"
                    :editing-cell="Boolean(editingCell) || batchBusy"
                    :before-remove="beforeRowRemove"
                    @duplicate="duplicateRow"
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
                    @reorder="subitemState((scope.row.parent as ProjectWorkItemListItem).id).items = $event"
                    @open-detail="openDetail"
                    @patch="patchCell"
                    @due-date-change="onDeadlineChange"
                    @contents-updated="onContentsUpdated"
                    @labels-updated="onLabelsUpdated"
                    @transition="transitionItem"
                    @batch-assignees="applyBatchAssignees"
                    @selection-change="onSubitemSelectionChange"
                    @header-resize="onHeaderDragEnd"
                    @move-column="moveSubitemColumn"
                    @open-connection="connect.openCard"
                  >
                    <template #connect-header="{ column }">
                      <connect-table-column-header
                        :column="column"
                        :table="connect"
                        :project-id="projectId"
                        :primary="false"
                        @hide="toggleColumn($event, false)"
                      />
                    </template>
                    <template #column-add>
                      <connect-column-add-button
                        :can-manage="connect.canManage"
                        :hidden-columns="restorableColumns"
                        @add-connect-column="connect.startDraft()"
                        @show-column="showHiddenColumn"
                      />
                    </template>
                  </project-work-item-subitems-table>
                  <work-item-row-actions
                    v-if="!isGroupDisplayRow(scope.row) && !isDraft(scope.row as ProjectWorkItemListItem)"
                    :item="scope.row as ProjectWorkItemListItem"
                    :can-create="canCreate"
                    :sorted="hasExplicitSort"
                    :order-items="grouped ? grouping.edgeItems : embedded ? tableEdgeItems : undefined"
                    :disabled="batchBusy || Boolean(editingCell) || tableSorting || savingSortOrder || (grouped && groupsLoading)"
                    :before-remove="() => beforeRowRemove(scope.row as ProjectWorkItemListItem)"
                    @open="openDetail($event, 'details')"
                    @add-subitem="addSubitem"
                    @duplicate="duplicateRow($event)"
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
                      <small v-if="groupCountsReady">{{ scope.row.group.count }} 个工作项</small>
                      <i v-else class="work-item-skeleton work-item-skeleton-block work-item-group-count-skeleton" aria-hidden="true" />
                    </button>
                    <el-checkbox v-else-if="scope.row.groupRowKind === 'columns'" :model-value="groupSelection(scope.row.group).checked"
                      :indeterminate="groupSelection(scope.row.group).mixed" :disabled="!grouping.page(scope.row.group.key).items.length"
                      :aria-label="`选择${scope.row.group.label}已加载工作项`" @change="selectGroupRows(scope.row.group, Boolean($event))" />
                    <div v-else-if="scope.row.groupRowKind === 'load'" :ref="element => isGroupDisplayRow(scope.row) && setGroupSentinel(scope.row.group.key, element)"
                      class="work-item-group-load" :class="{ 'work-item-group-load--complete': scope.row.group.count > 0 && grouping.page(scope.row.group.key).loaded && !grouping.page(scope.row.group.key).nextCursor && !grouping.page(scope.row.group.key).error, 'work-item-group-load--loading': isGroupPageLoading(scope.row.group) }">
                      <template v-if="grouping.page(scope.row.group.key).error">
                        <span>此分组加载失败</span><el-button text size="small" @click="grouping.load(scope.row.group.key)">重试</el-button>
                      </template>
                      <work-item-table-skeleton v-else-if="isGroupPageLoading(scope.row.group)" :columns="skeletonColumns"
                        :lead-widths="[TABLE_SELECTION_COLUMN_WIDTH]" :sticky-offset="TABLE_MENU_COLUMN_WIDTH + TABLE_EXPAND_COLUMN_WIDTH"
                        :trailing-width="TABLE_ADD_COLUMN_MIN_WIDTH" :rows="2" :row-height="skeletonRowHeight" :label="`正在加载分组：${scope.row.group.label}`" />
                      <span v-else-if="scope.row.group.count === 0">暂无工作项</span>
                      <button v-else-if="!grouping.page(scope.row.group.key).loaded || grouping.page(scope.row.group.key).nextCursor"
                        class="text-button" @click="grouping.load(scope.row.group.key)">
                        加载更多工作项
                      </button>
                    </div>
                  </template>
                </template>
              </el-table-column>
              <el-table-column
                label="工作项名称"
                column-key="title"
                :width="columnWidths.title"
                header-align="center"
                class-name="monday-title-column"
                label-class-name="monday-title-column monday-sortable-column-header"
                fixed
                resizable
              >
                <template #header>
                  <work-item-column-header
                    data-tour="work-item-column-header"
                    :state="columnMenuState(columnByKey.get('title')!)"
                    :saving="savingSortOrder"
                    @sort="applyColumnQuickSort('title')"
                    @action="(action, anchor) => onColumnMenuAction('title', action, anchor)"
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
                    <work-item-column-header v-if="scope.row.groupRowKind === 'columns'" :state="columnMenuState(columnByKey.get('title')!)"
                      :saving="tableSorting || savingSortOrder"
                      @sort="applyColumnQuickSort('title')" @action="(action, anchor) => onColumnMenuAction('title', action, anchor)"
                    @clear="clearColumnSort('title')" @save="saveSortedWorkItemOrder" />
                    <span class="monday-column-resize-handle monday-title-column-resize-handle" data-column-key="title" aria-hidden="true" />
                  </template>
                  <div v-else class="title-cell" data-tour="work-item-row-drag" :data-work-item-id="scope.row.id">
                    <work-item-name-cell
                      class="work-item-link"
                      :item="scope.row as ProjectWorkItemListItem"
                      :strict-version="embedded"
                      :selected="selectedCellKey === `${scope.row.id}:title`"
                      :auto-edit="autoEditId === scope.row.id"
                      :disabled="Boolean(editingCell)"
                      :create="isDraft(scope.row as ProjectWorkItemListItem) ? saveInlineDraft : undefined"
                      @open="openDetail(scope.row as ProjectWorkItemListItem, 'details')"
                      @updated="replaceLightItem"
                      @editing="active => { if (active) { autoEditId = ''; editingNames.add(scope.row.id); selectCell(scope.row.id, 'title') } else editingNames.delete(scope.row.id) }"
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
                          :aria-label="expandedIds.includes((scope.row as ProjectWorkItemListItem).id) ? '收起子项' : '展开子项'"
                          :aria-expanded="expandedIds.includes((scope.row as ProjectWorkItemListItem).id)"
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
                          :aria-expanded="expandedIds.includes((scope.row as ProjectWorkItemListItem).id)"
                          class="clickable_b3ab95e8e9 monday-subitems-counter-component name-cell-component__subitems-counter disableTextSelection_fae179dda6"
                          @click.stop="toggleSubitems(scope.row as ProjectWorkItemListItem)"
                        >
                          <div class="monday-subitems-counter-component__subitems-count">{{ visibleSubitemCount(scope.row as ProjectWorkItemListItem) }}</div>
                        </div>
                      </template>
                    </work-item-name-cell>
                    <button
                      class="monday-discussion-btn"
                      data-tour="work-item-discussion"
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
                :fixed="pinnedColumnKeys.has(column.key)"
                :key="columnIndex"
                :label="column.label"
                :prop="column.key"
                :width="columnWidth(column.key)"
                align="center"
                :class-name="`monday-movable-column monday-column--${column.key}${column.key === 'status' || column.key === 'priority' || column.key === 'content' ? ' monday-block-column' : ''}${isColumnCollapsed(column.key) ? ' monday-column--collapsed' : ''}`"
                :label-class-name="`monday-movable-column-header monday-sortable-column-header monday-column-header--${column.key}${columnResizingKey === column.key ? ' monday-column-resizing' : ''}${isColumnCollapsed(column.key) ? ' monday-column-header--collapsed' : ''}`"
                resizable
              >
                <template #header>
                  <work-item-column-header
                    data-tour="work-item-column-drag"
                    :state="columnMenuState(column)"
                    :collapsed="isColumnCollapsed(column.key)"
                    :saving="savingSortOrder"
                    @sort="applyColumnQuickSort(column.key)"
                    @action="(action, anchor) => onColumnMenuAction(column.key, action, anchor)"
                    @clear="clearColumnSort(column.key)"
                    @save="saveSortedWorkItemOrder"
                  />
                  <span
                    v-if="!isColumnCollapsed(column.key)"
                    class="monday-column-resize-handle"
                    :data-column-key="column.key"
                    aria-hidden="true"
                  />
                </template>
                <template #default="scope">
                  <template v-if="isGroupDisplayRow(scope.row)">
                    <work-item-column-header v-if="scope.row.groupRowKind === 'columns'" :state="columnMenuState(column)"
                      :collapsed="isColumnCollapsed(column.key)" :saving="tableSorting || savingSortOrder"
                      @sort="applyColumnQuickSort(column.key)" @action="(action, anchor) => onColumnMenuAction(column.key, action, anchor)"
                    @clear="clearColumnSort(column.key)" @save="saveSortedWorkItemOrder" />
                    <span v-if="!isColumnCollapsed(column.key)" class="monday-column-resize-handle" :data-column-key="column.key" aria-hidden="true" />
                  </template>
                  <span v-else-if="isColumnCollapsed(column.key)" class="work-item-collapsed-cell" aria-hidden="true" />
                  <work-item-draft-cell
                    v-else-if="isDraft(scope.row as ProjectWorkItemListItem)"
                    :item="scope.row as ProjectWorkItemListItem"
                    :column="column.key"
                    :status-label="workflowStatuses.find(status => status.statusCode === scope.row.statusCode)?.displayName ?? '—'"
                    :status-color="workflowStatuses.find(status => status.statusCode === scope.row.statusCode)?.colorToken"
                  />
                  <template v-else-if="column.key === 'assignee'">
                    <el-popover :persistent="false" placement="bottom" :width="360" trigger="click" popper-class="work-items-popover"
                      :visible="openCell === scope.row.id + ':assignee'" @update:visible="setCellOpen(scope.row.id + ':assignee', $event)">
                      <template #reference>
                        <button
                          class="cell-editor-trigger monday-cell-centered"
                          :disabled="Boolean(editingCell) || batchBusy"
                          @click.stop="selectCell((scope.row as ProjectWorkItemListItem).id, 'assignee')"
                        >
                          <yp-assignee-stack :assignees="workItemAssignees(scope.row as ProjectWorkItemListItem)" tooltip-placement="right"
                            :tooltip-disabled="openCell === scope.row.id + ':assignee'" />
                        </button>
                      </template>
                      <work-item-batch-edit-scope :item-id="scope.row.id" class="work-item-batch-edit-scope--inset" />
                      <work-item-assignee-picker
                        :project-id="projectId"
                        :selected="workItemAssignees(scope.row as ProjectWorkItemListItem)"
                        :busy="editingCell === `${scope.row.id}:assignees`"
                        :batch-count="batchEditSync ? batchEditCount(scope.row.id) : 0"
                        @change="patchCell(scope.row as ProjectWorkItemListItem, 'assignees', $event)"
                        @batch-apply="(mode, ids) => applyBatchAssignees(scope.row as ProjectWorkItemListItem, mode, ids)"
                        @done="closeCell"
                      />
                    </el-popover>
                  </template>

                  <template v-else-if="column.key === 'status'">
                    <el-popover
                      placement="bottom"
                      width="auto"
                      trigger="click"
                      :persistent="Boolean(cellPopoverBusy[scope.row.id + ':status'])"
                      :visible="openCell === scope.row.id + ':status'"
                      @update:visible="setCellOpen(scope.row.id + ':status', $event)"
                      popper-class="work-items-label-popover status-popover"
                      @hide="resetLabelPopoverContent((scope.row as ProjectWorkItemListItem).id, 'status')"
                    >
                      <template #reference>
                        <button
                          class="monday-status-cell status-chip cell-editor-trigger"
                          :class="`monday-status-cell--${getStatusTone((scope.row as ProjectWorkItemListItem).statusCode)}`"
                          :style="getStatusCellStyle((scope.row as ProjectWorkItemListItem).statusCode)"
                          :disabled="Boolean(editingCell) || batchBusy"
                          @click.stop="selectCell((scope.row as ProjectWorkItemListItem).id, 'status')"
                        >
                          <span>{{ statusLabel((scope.row as ProjectWorkItemListItem).statusCode) }}</span>
                        </button>
                      </template>
                      <work-item-batch-edit-scope :item-id="scope.row.id" />
                      <work-item-label-popover-content
                        :ref="element => setLabelPopoverContentRef(labelPopoverKey((scope.row as ProjectWorkItemListItem).id, 'status'), element)"
                        @busy-change="cellPopoverBusy[scope.row.id + ':status'] = $event"
                        kind="status"
                        :project-id="projectId"
                        :catalog="labelCatalog"
                        :workflow-statuses="workflowStatuses.filter(item => item.active || item.statusCode === (scope.row as ProjectWorkItemListItem).statusCode)"
                        :current-value="(scope.row as ProjectWorkItemListItem).statusCode"
                        :can-manage="Boolean(labelCatalog?.canManage)"
                        :available-transitions="(scope.row as ProjectWorkItemListItem).capabilities.availableTransitions"
                        @select-status="closeCell(); transitionItem(scope.row as ProjectWorkItemListItem, $event)"
                        @updated="onLabelsUpdated"
                      />
                    </el-popover>
                  </template>

                  <template v-else-if="column.key === 'priority'">
                    <el-popover
                      placement="bottom"
                      width="auto"
                      trigger="click"
                      :persistent="Boolean(cellPopoverBusy[scope.row.id + ':priority'])"
                      :visible="openCell === scope.row.id + ':priority'"
                      @update:visible="setCellOpen(scope.row.id + ':priority', $event)"
                      popper-class="work-items-label-popover priority-popover"
                      @hide="resetLabelPopoverContent((scope.row as ProjectWorkItemListItem).id, 'priority')"
                    >
                      <template #reference>
                        <button
                          class="monday-priority-cell cell-editor-trigger"
                          :class="`monday-priority-cell--${getPriorityPresentation((scope.row as ProjectWorkItemListItem).priority).tone}`"
                          :style="getPriorityCellStyle((scope.row as ProjectWorkItemListItem).priority)"
                          :disabled="Boolean(editingCell) || batchBusy"
                          @click.stop="selectCell((scope.row as ProjectWorkItemListItem).id, 'priority')"
                        >
                          <span>{{ getPriorityPresentation((scope.row as ProjectWorkItemListItem).priority).label }}</span>
                        </button>
                      </template>
                      <work-item-batch-edit-scope :item-id="scope.row.id" />
                      <work-item-label-popover-content
                        :ref="element => setLabelPopoverContentRef(labelPopoverKey((scope.row as ProjectWorkItemListItem).id, 'priority'), element)"
                        @busy-change="cellPopoverBusy[scope.row.id + ':priority'] = $event"
                        kind="priority"
                        :project-id="projectId"
                        :catalog="labelCatalog"
                        :priority-options="priorityOptions.filter(item => item.active)"
                        :current-value="(scope.row as ProjectWorkItemListItem).priority"
                        :can-manage="Boolean(labelCatalog?.canManage)"
                        @select-priority="closeCell(); patchCell(scope.row as ProjectWorkItemListItem, 'priority', $event)"
                        @updated="onLabelsUpdated"
                      />
                    </el-popover>
                  </template>

                  <template v-else-if="column.key === 'content'">
                    <el-popover
                      placement="bottom"
                      width="auto"
                      trigger="click"
                      :persistent="Boolean(cellPopoverBusy[scope.row.id + ':content'])"
                      :visible="openCell === scope.row.id + ':content'"
                      @update:visible="setCellOpen(scope.row.id + ':content', $event)"
                      popper-class="work-items-label-popover content-popover"
                      @hide="resetLabelPopoverContent((scope.row as ProjectWorkItemListItem).id, 'content')"
                    >
                      <template #reference>
                        <button
                          class="monday-content-label cell-editor-trigger"
                          :style="labelCellStyle(contentLabel(scope.row as ProjectWorkItemListItem).colorToken)"
                          :disabled="Boolean(editingCell) || batchBusy"
                          @click.stop="selectCell((scope.row as ProjectWorkItemListItem).id, 'content')"
                        >
                          <span>{{ contentLabel(scope.row as ProjectWorkItemListItem).name || '—' }}</span>
                        </button>
                      </template>
                      <work-item-batch-edit-scope :item-id="scope.row.id" />
                      <work-item-content-popover-content
                        :ref="element => setLabelPopoverContentRef(labelPopoverKey((scope.row as ProjectWorkItemListItem).id, 'content'), element)"
                        @busy-change="cellPopoverBusy[scope.row.id + ':content'] = $event"
                        :project-id="projectId"
                        :catalog="catalog"
                        :current-value="(scope.row as ProjectWorkItemListItem).contentId"
                        :can-manage="Boolean(catalog?.canManage)"
                        @select="closeCell(); patchCell(scope.row as ProjectWorkItemListItem, 'content', $event)"
                        @updated="onContentsUpdated"
                      />
                    </el-popover>
                  </template>

                  <template v-else-if="column.key === 'dueDate'">
                    <work-item-due-date-cell
                      :item="scope.row as ProjectWorkItemListItem"
                      :can-edit="(scope.row as ProjectWorkItemListItem).capabilities.canEditFields"
                      :busy="Boolean(editingCell) || batchBusy"
                      :clear-label="batchClearDueDateLabel(scope.row.id)"
                      @select="selectCell((scope.row as ProjectWorkItemListItem).id, 'dueDate')"
                      @change="onDeadlineChange(scope.row as ProjectWorkItemListItem, $event)"
                    >
                      <template #header><work-item-batch-edit-scope :item-id="scope.row.id" class="work-item-batch-edit-scope--inset" /></template>
                    </work-item-due-date-cell>
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
                v-for="column in connect.visibleColumns"
                :fixed="pinnedColumnKeys.has(column.key)"
                :key="column.key"
                :column-key="column.key"
                :prop="column.key"
                :label="column.label"
                :width="columnWidth(column.key)"
                :min-width="isColumnCollapsed(column.key) ? COLLAPSED_COLUMN_WIDTH : column.minWidth"
                class-name="monday-connect-column"
                label-class-name="monday-connect-column-header"
                resizable
              >
                <template #header>
                  <work-item-column-expand-button v-if="isColumnCollapsed(column.key)" :label="column.label" @expand="toggleColumnCollapsed(column.key)" />
                  <connect-table-column-header
                    v-else
                    collapsible
                    @collapse="toggleColumnCollapsed"
                    :column="column"
                    :table="connect"
                    :project-id="projectId"
                    @hide="toggleColumn($event, false)"
                  />
                </template>
                <template #default="scope">
                  <work-item-column-expand-button v-if="isGroupDisplayRow(scope.row) && scope.row.groupRowKind === 'columns' && isColumnCollapsed(column.key)" :label="column.label" @expand="toggleColumnCollapsed(column.key)" />
                  <connect-table-column-header
                    v-else-if="isGroupDisplayRow(scope.row) && scope.row.groupRowKind === 'columns'"
                    collapsible
                    @collapse="toggleColumnCollapsed"
                    :column="column"
                    :table="connect"
                    :project-id="projectId"
                    :primary="false"
                    @hide="toggleColumn($event, false)"
                  />
                  <span v-else-if="!isGroupDisplayRow(scope.row) && isColumnCollapsed(column.key)" class="work-item-collapsed-cell" aria-hidden="true" />
                  <connect-table-cell
                    v-else-if="!isGroupDisplayRow(scope.row) && !isDraft(scope.row as ProjectWorkItemListItem)"
                    :column="column"
                    :item="scope.row as ProjectWorkItemListItem"
                    :cell="connect.cells.get(scope.row.id)"
                    :read-only="connect.readOnly"
                    :row-index="scope.$index"
                    :celebrate="connect.celebrated === column.key"
                    @open-card="connect.openCard"
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
                  <connect-column-add-button
                    :can-manage="connect.canManage"
                    :hidden-columns="restorableColumns"
                    @add-connect-column="connect.startDraft()"
                    @show-column="showHiddenColumn"
                  />
                </template>
                <template #default="scope">
                  <connect-column-add-button
                    v-if="isGroupDisplayRow(scope.row) && scope.row.groupRowKind === 'columns'"
                    :can-manage="connect.canManage"
                    :hidden-columns="restorableColumns"
                    @add-connect-column="connect.startDraft()"
                    @show-column="showHiddenColumn"
                  />
                </template>
              </el-table-column>

              <template #append>
                <inline-problem v-if="groupingError" :problem="groupingError" />
                <button v-if="groupingError" class="text-button" @click="grouping.refresh()">重试加载分组</button>
                <work-item-table-skeleton
                  v-if="tableInitialLoading"
                  :columns="skeletonColumns"
                  :lead-widths="TABLE_LEAD_WIDTHS"
                  :trailing-width="TABLE_ADD_COLUMN_MIN_WIDTH"
                  :rows="10"
                  :row-height="skeletonRowHeight"
                  fade
                />
                <work-item-table-skeleton
                  v-else-if="!grouped && tableLoading"
                  :columns="skeletonColumns"
                  :lead-widths="TABLE_LEAD_WIDTHS"
                  :trailing-width="TABLE_ADD_COLUMN_MIN_WIDTH"
                  :rows="3"
                  :row-height="skeletonRowHeight"
                  label="正在加载更多工作项"
                />
                <div
                  v-if="!grouped && quickOpen"
                  ref="quickRow"
                  class="quick-row monday-quick-row"
                  :style="quickGridStyle"
                >
                  <span class="monday-quick-lead" aria-hidden="true" />
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
                  v-else-if="!grouped && !tableInitialLoading"
                  class="quick-add monday-quick-add"
                  data-tour="work-item-quick-add"
                  :style="quickGridStyle"
                  :disabled="!canCreate"
                  @click="openQuick"
                >
                  <span class="monday-quick-lead" aria-hidden="true" />
                  <span class="monday-quick-checkbox" aria-hidden="true" />
                  <span class="monday-quick-add__field">添加工作项</span>
                </button>
                <div v-if="selectedCount || batchBusy" class="work-item-batch-spacer" aria-hidden="true" />
                <div ref="tableSentinel" class="cursor-sentinel" aria-hidden="true" />
                <div v-if="!grouped && loadingMoreError" class="incremental-state incremental-state--error">
                  <span>加载更多失败</span><el-button text @click="loadTable(tableNextCursor, true)">重试</el-button>
                </div>
              </template>
            </el-table>
          </div>
          <teleport to="body" :disabled="embedded">
            <work-item-batch-bar v-if="selectedCount || batchBusy" :count="selectedCount" :subitem-count="selectedSubitemCount"
              :busy="batchBusy" :stopping="batchStopping" :progress-label="batchProgressLabel"
              :can-create="canCreate" :can-delete="batchCanDelete" :can-convert="Boolean(batchConvertibleItems.length)"
              :move-disabled="batchMoveDisabled" :choosing-parent="batchChoosingParent" :embedded="embedded" :left="pageScrollbarLeft"
              @action="batch.run($event)" @choose-parent="batchChoosingParent = $event" @clear="clearSelection" @stop="batch.stop">
              <template #parent-picker>
                <work-item-parent-picker v-if="batchConvertibleItems.length" :item="batchConvertibleItems[0]"
                  :items="selectedBatchRows.map(row => row.item)" @choose="batch.run('convert', undefined, $event)" @close="batchChoosingParent = false" />
              </template>
            </work-item-batch-bar>
          </teleport>
          <work-item-column-settings-popover
            :kind="columnSettingsKind"
            :anchor="columnSettingsAnchor"
            :project-id="projectId"
            :label-catalog="labelCatalog"
            :content-catalog="catalog"
            @close="columnSettingsKind = undefined"
            @labels-updated="onLabelsUpdated"
            @contents-updated="onContentsUpdated"
          />
        </div>

        <work-item-kanban-board
          v-else
          :statuses="kanbanLaneStatuses"
          :lane="lane"
          :counts="kanbanCounts"
          :create-disabled-reason="status => laneCreateDisabledReason(laneGroup(status.statusCode))"
          :creating-top="statusCode => laneTopCreate.draft(laneGroup(statusCode)).saving"
          :draft="statusCode => laneBottomCreate.draft(laneGroup(statusCode))"
          :dragging="Boolean(dragging)"
          @load-more="loadMoreLane"
          @drop="dropInto"
          @create-top="statusCode => createInLane(statusCode, { edge: 'top' })"
          @open-draft="statusCode => laneBottomCreate.open(laneGroup(statusCode))"
          @draft-keydown="(statusCode, event) => laneBottomCreate.keydown(laneGroup(statusCode), event)"
          @cancel-draft="statusCode => laneBottomCreate.cancel(laneGroup(statusCode))"
          @draft-input="(statusCode, input) => laneBottomCreate.setInput(laneGroup(statusCode), input)"
        >
          <template #card="{ item }">
            <work-item-kanban-card
              :item="item as ProjectWorkItemListItem"
              :project-id="projectId"
              :fields="kanbanCardFields"
              :show-assignee="!hiddenColumns.has('assignee')"
              :label-catalog="labelCatalog"
              :content-catalog="catalog"
              :workflow-statuses="workflowStatuses"
              :priority-options="priorityOptions"
              :active-contents="activeContents"
              :can-create="canCreate"
              :busy="Boolean(editingCell) || batchBusy"
              :subitem-count="visibleSubitemCount(item as ProjectWorkItemListItem)"
              :expanded="kanbanExpandedIds.has(item.id)"
              :subitems="subitems[item.id]"
              :auto-edit="autoEditId === item.id"
              @open="openDetail"
              @updated="replaceLightItem"
              @editing="active => { if (active) { autoEditId = ''; editingNames.add(item.id) } else editingNames.delete(item.id) }"
              @transition="transitionItem"
              @patch="patchCell"
              @deadline="onDeadlineChange"
              @labels-updated="onLabelsUpdated"
              @contents-updated="onContentsUpdated"
              @timer-changed="notifyChanged"
              @toggle-subitems="toggleKanbanSubitems"
              @subitem-created="onKanbanSubitemCreated"
              @duplicate="duplicateRow"
              @moved="moved => reloadLane(moved.statusCode)"
              @changed="onRelationsChanged"
              @removed="removed => { void onRowRemoved(removed); void reloadLane(removed.statusCode) }"
              @create-below="anchor => createInLane(anchor.statusCode, { edge: 'after', anchorId: anchor.id })"
              @dragstart="started => dragging = started"
              @dragend="dragging = undefined"
            >
              <template #connections>
                <connect-kanban-connections
                  v-if="!embedded"
                  :cell="connect.cells.get(item.id)"
                  @open-card="connect.openCard"
                />
              </template>
            </work-item-kanban-card>
          </template>
        </work-item-kanban-board>
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
          <yp-notice
            v-if="detail.archived"
            class="work-item-archive-notice"
            tone="warning"
          >
            此工作项已归档
            <template #actions>
              <el-button
                v-if="detail.capabilities.canDelete"
                :loading="restoringArchive"
                text
                @click="restoreArchivedItem"
              >
                恢复工作项
              </el-button>
            </template>
          </yp-notice>
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
            @discussion-changed="onDiscussionChanged"
            @description-updated="replaceLightItem($event.id, $event)"
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
  position: sticky; left: 33px; flex: none;
  display: flex; align-items: center; gap: 10px; width: min(540px, calc(100vw - 200px));
  height: 38px; padding: 0 14px 0 10px; border: 0; background: transparent;
  color: var(--yp-text-primary); text-align: left; cursor: pointer;
}
.work-item-group-toggle svg { flex: none; transition: transform 140ms ease; }
.work-item-group-toggle svg.expanded { transform: rotate(90deg); }
.work-item-group-name { color: var(--work-item-group-accent); font-size: 16px; font-weight: 600; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.work-item-group-toggle small { flex: none; font-size: 12px; color: var(--yp-text-secondary); font-weight: 400; }
.work-item-group-load { position: sticky; left: 33px; flex: none; display: flex; width: fit-content; max-width: min(540px, calc(100vw - 200px)); gap: 8px; align-items: center; min-height: 36px; padding: 0 16px; color: var(--yp-text-secondary); font-size: 12px; }
.work-item-group-load--complete { min-height: 0; height: 0; padding: 0; }
.work-item-group-load--loading { --work-item-skeleton-accent: transparent; position: relative; left: auto; display: block; width: auto; max-width: none; min-height: 0; padding: 0; }
.work-item-group-count-skeleton { flex: none; width: 64px; height: 10px; }
:deep(.monday-table--grouped.el-table > .el-table__inner-wrapper > .el-table__body-wrapper) { height: 100% !important; }
:deep(.monday-table--grouped > .el-table__inner-wrapper > .el-table__body-wrapper > .el-scrollbar > .el-scrollbar__wrap > .el-scrollbar__view > table > tbody > tr.work-item-group-collapsed:not(.work-item-group-heading):not(.work-item-group-spacer)),
:deep(.monday-table--grouped tr.work-item-table-row.work-item-group-collapsed + tr:has(> .el-table__expanded-cell)) { display: none; }
:deep(.monday-table--grouped tr.work-item-group-heading > td.el-table__cell),
:deep(.monday-table--grouped tr.work-item-group-spacer > td.el-table__cell) { border: 0; background: var(--yp-bg-surface) !important; padding: 0; }
:deep(.monday-table--grouped tr.work-item-group-heading > td > .cell) { padding: 0; height: 38px; overflow: visible; }
:deep(.monday-table--grouped tr.work-item-group-heading > .monday-selection-column),
:deep(.monday-table--grouped tr.work-item-group-load > .monday-selection-column) { position: relative !important; left: auto !important; }
:deep(.monday-table--grouped tr.work-item-group-heading > .monday-selection-column)::before,
:deep(.monday-table--grouped tr.work-item-group-load > .monday-selection-column)::before { display: none; }
:deep(.monday-table--grouped tr.work-item-group-heading.work-item-group-collapsed) .work-item-group-toggle::before,
.work-item-group-load:not(.work-item-group-load--complete)::before {
  position: absolute; top: -1px; bottom: -1px; left: -1px; width: var(--work-item-hierarchy-bar-width);
  background: var(--work-item-group-accent); content: ''; pointer-events: none;
}
:deep(.monday-table--grouped tr.work-item-group-heading.work-item-group-collapsed) .work-item-group-toggle::before {
  border-radius: var(--work-item-hierarchy-corner-radius) 0 0 var(--work-item-hierarchy-corner-radius);
}
:deep(.monday-table--grouped tr.work-item-group-heading > td > .cell),
:deep(.monday-table--grouped tr.work-item-group-load > td > .cell) { justify-content: flex-start !important; }
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
:deep(.monday-table--grouped tr.work-item-group-load > td > .cell) { padding: 0; overflow: visible; }
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
.work-item-group-quick .monday-quick-lead::before { opacity: 1; }
.work-item-group-quick .monday-quick-add__field,
.work-item-group-quick .quick-controls { z-index: 4; }
.work-item-group-add-mask {
  position: absolute; z-index: 3; top: 0; right: 0; bottom: -1px; left: var(--work-item-quick-start);
  border-bottom-left-radius: var(--work-item-hierarchy-corner-radius);
  background: color-mix(in srgb, var(--yp-bg-surface) 50%, transparent); pointer-events: none;
}
.work-item-group-create-error {
  position: sticky;
  left: 81px;
  margin: 0 12px 0 81px;
  max-width: min(540px, calc(100vw - 220px)); white-space: normal;
}
:deep(.monday-table--grouped tr.work-item-group-heading.work-item-group-collapsed > .monday-selection-column) {
  border: 1px solid var(--yp-monday-grid-border); border-radius: var(--work-item-hierarchy-corner-radius);
  background: var(--work-item-table-cell-bg) !important;
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
  flex: none;
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

/* 工具栏不参与纵向收缩：看板内容高于可用空间时也保持完整高度。 */
.work-items-toolbar {
  display: flex;
  flex: none;
  min-width: 0;
  align-items: center;
  gap: var(--yp-space-2);
  margin: calc(-1 * var(--yp-space-1)) 0 var(--yp-space-4);
  overflow-x: auto;
}

.toolbar-button, :deep(.grouping-toolbar-button), :deep(.table-settings-toolbar-button) {
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

.toolbar-button:hover:not(:disabled), .toolbar-button.active, :deep(.grouping-toolbar-button:hover:not(:disabled)), :deep(.grouping-toolbar-button.active), :deep(.table-settings-toolbar-button:hover:not(:disabled)), :deep(.table-settings-toolbar-button.active) { background: var(--yp-bg-hover); color: var(--yp-action-primary); }
.toolbar-button:disabled, :deep(.grouping-toolbar-button:disabled), :deep(.table-settings-toolbar-button:disabled) { color: var(--yp-text-disabled); cursor: not-allowed; }
:deep(.table-settings-toolbar-button) { width: 36px; justify-content: center; padding: 0; }
:deep(.table-settings-toolbar-button--unsaved) { width: auto; padding: 0 var(--yp-space-3); }
/* 主操作底色不随主题变化；--yp-text-inverse 在深色主题是深色，文字固定用蓝底前景色。 */
.toolbar-create-group { display: inline-flex; height: 32px; flex: 0 0 auto; border-radius: var(--yp-radius-md); background: var(--yp-action-primary); box-shadow: 0 1px 2px color-mix(in srgb, var(--yp-action-primary) 35%, transparent); }
.toolbar-create-group.is-disabled { opacity: .6; box-shadow: none; }
.toolbar-create { display: inline-flex; height: 32px; align-items: center; gap: 6px; padding: 0 14px 0 12px; border: 0; border-radius: var(--yp-radius-md); color: var(--yp-status-blue-foreground); background: transparent; font: inherit; font-weight: 500; cursor: pointer; transition: background var(--yp-motion-fast) var(--yp-ease-standard); }
.toolbar-create .el-icon { font-size: 14px; }
.toolbar-create-group .toolbar-create:first-child { border-top-right-radius: 0; border-bottom-right-radius: 0; }
.toolbar-create-more { position: relative; width: 28px; justify-content: center; padding: 0; border-top-left-radius: 0; border-bottom-left-radius: 0; }
.toolbar-create-more::before { position: absolute; top: 8px; bottom: 8px; left: 0; width: 1px; background: color-mix(in srgb, var(--yp-status-blue-foreground) 40%, transparent); content: ''; }
.toolbar-create-more .el-icon { font-size: 12px; }
.toolbar-create:hover:not(:disabled) { background: var(--yp-action-primary-hover); }
.toolbar-create:active:not(:disabled) { background: var(--yp-action-primary-active); }
.toolbar-create:disabled { cursor: not-allowed; }
.toolbar-create:focus-visible { outline: 2px solid var(--yp-focus-ring); outline-offset: 2px; }
.toolbar-search { width: 94px; flex: 0 0 auto; transition: width 100ms cubic-bezier(0, 0, .35, 1); }
.toolbar-search > .toolbar-button { width: 100%; }
.toolbar-search--expanded { width: min(360px, 40vw); }

.sort-popover, .date-editor { display: grid; gap: var(--yp-space-3); }
.text-button, .popover-add {
  display: flex; align-items: center; gap: var(--yp-space-2); width: 100%; padding: 7px 8px; border: 0;
  border-radius: var(--yp-radius-sm); color: var(--yp-text-primary); background: transparent; text-align: left; cursor: pointer;
}
.popover-add:hover { background: var(--yp-bg-hover); }
.empty-avatar { display: inline-grid; width: 28px; height: 28px; place-items: center; border: 1px dashed var(--yp-border-default); border-radius: 50%; }
.sort-popover > header { display: flex; align-items: center; justify-content: space-between; }
.text-button { width: auto; color: var(--yp-action-primary); }
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
  flex-direction: column;
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

.work-item-batch-spacer { height: 160px; }

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

:deep(.monday-table.el-table.monday-table--header-double) { --work-item-table-header-height: 56px; }
:deep(.monday-table.el-table.monday-table--header-triple) { --work-item-table-header-height: 76px; }

/* 只放大真实工作项行；分组标题、添加行与子项表保持单行高度。 */
:deep(.monday-table.monday-table--row-double .el-table__body tr.work-item-table-row) { --work-item-table-row-height: 56px; }
:deep(.monday-table.monday-table--row-triple .el-table__body tr.work-item-table-row) { --work-item-table-row-height: 76px; }

:deep(.monday-table.monday-table--row-double tr.work-item-table-row .work-item-title-text),
:deep(.monday-table.monday-table--row-triple tr.work-item-table-row .work-item-title-text) {
  display: -webkit-box;
  width: auto;
  line-height: 1.35;
  white-space: normal;
  word-break: break-word;
  -webkit-box-orient: vertical;
}
:deep(.monday-table.monday-table--row-double tr.work-item-table-row .work-item-title-text) { -webkit-line-clamp: 2; }
:deep(.monday-table.monday-table--row-triple tr.work-item-table-row .work-item-title-text) { -webkit-line-clamp: 3; }

:deep(.monday-table > .el-table__inner-wrapper > .el-table__header-wrapper .connect-column-header),
:deep(.monday-table tr.work-item-group-columns .connect-column-header) { height: var(--work-item-table-header-height); }

:deep(.monday-table.monday-table--header-double > .el-table__inner-wrapper > .el-table__header-wrapper :is(.monday-column-quick-sort__label, .connect-column-header__label)),
:deep(.monday-table.monday-table--header-double tr.work-item-group-columns :is(.monday-column-quick-sort__label, .connect-column-header__label)),
:deep(.monday-table.monday-table--header-triple > .el-table__inner-wrapper > .el-table__header-wrapper :is(.monday-column-quick-sort__label, .connect-column-header__label)),
:deep(.monday-table.monday-table--header-triple tr.work-item-group-columns :is(.monday-column-quick-sort__label, .connect-column-header__label)) {
  display: -webkit-box;
  line-height: 1.3;
  white-space: normal;
  word-break: break-word;
  -webkit-box-orient: vertical;
}
:deep(.monday-table.monday-table--header-double > .el-table__inner-wrapper > .el-table__header-wrapper :is(.monday-column-quick-sort__label, .connect-column-header__label)),
:deep(.monday-table.monday-table--header-double tr.work-item-group-columns :is(.monday-column-quick-sort__label, .connect-column-header__label)) { -webkit-line-clamp: 2; }
:deep(.monday-table.monday-table--header-triple > .el-table__inner-wrapper > .el-table__header-wrapper :is(.monday-column-quick-sort__label, .connect-column-header__label)),
:deep(.monday-table.monday-table--header-triple tr.work-item-group-columns :is(.monday-column-quick-sort__label, .connect-column-header__label)) { -webkit-line-clamp: 3; }

:deep(.monday-table .el-scrollbar__wrap) {
  overscroll-behavior: contain;
}

:deep(.monday-table .el-scrollbar__bar) {
  display: none !important;
}

.project-table-scrollbar {
  position: fixed;
  z-index: 1999;
  overscroll-behavior: contain;
}

.project-table-scrollbar::-webkit-scrollbar {
  width: 12px;
  height: 12px;
}

.project-table-scrollbar::-webkit-scrollbar-thumb {
  border-width: 3px;
}

.project-table-scrollbar::-webkit-scrollbar-thumb:hover,
.project-table-scrollbar::-webkit-scrollbar-thumb:active {
  border-width: 2px;
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

/* 条件着色：行色铺满除菜单列与展开列外的单元格，单元格规则覆盖行色；选中行高亮（!important）仍优先。 */
:deep(.monday-table .el-table__body tr.work-item-table-row--colored > td.el-table__cell:not(.work-item-menu-column):not(.monday-expand-column)) {
  background-color: var(--work-item-condition-row-bg);
}

:deep(.monday-table .el-table__body tr:is(.work-item-table-row, .subitem-table-row) > td.el-table__cell.work-item-cell--colored:not(.work-item-menu-column):not(.monday-expand-column)) {
  background-color: var(--work-item-condition-cell-bg);
}

/* 隐藏已完成：只淡化单元格内容，单元格背景保持不透明，固定列下不透出滚动内容；主表与子项表共用。
   置灰加删除线与「不显示」（搜索、状态筛选或刚完成而仍在表格中的项）再给名称加删除线。 */
:deep(tr.work-item-table-row > td.el-table__cell > .cell), :deep(tr.subitem-table-row > td.el-table__cell > .cell) {
  transition: opacity var(--yp-motion-overlay) var(--yp-ease-standard);
}
:deep(tr.work-item-table-row--completed > td.el-table__cell > .cell) { opacity: .5; }
:deep(tr.work-item-table-row--completed .work-item-title-text) { color: var(--yp-text-muted); }
.monday-table-surface--strike-completed :deep(tr.work-item-table-row--completed .work-item-title-text) { text-decoration: line-through; }

:deep(.monday-table .el-table__body tr.work-item-table-row--selected > td.el-table__cell:not(.work-item-menu-column):not(.monday-expand-column)) {
  background-color: var(--yp-bg-selected-strong) !important;
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
  z-index: auto;
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
  z-index: auto;
}

:deep(.monday-table .el-table__header th.el-table-fixed-column--left:hover),
:deep(.monday-table .el-table__header th.el-table-fixed-column--left:focus-within),
:deep(.monday-table .el-table__header th.el-table-fixed-column--left:has(.sort-by-column--active)) {
  z-index: 14;
}

:deep(.monday-table--grouped tr.work-item-group-columns > td:has(.sort-by-column--active)),
:deep(.monday-table--grouped tr.work-item-group-columns > td:has(.sort-by-column):hover),
:deep(.monday-table--grouped tr.work-item-group-columns > td:has(.sort-by-column):focus-within) {
  z-index: auto;
}

:deep(.monday-table--grouped tr.work-item-group-columns > td.el-table-fixed-column--left:has(.sort-by-column--active)),
:deep(.monday-table--grouped tr.work-item-group-columns > td.el-table-fixed-column--left:has(.sort-by-column):hover),
:deep(.monday-table--grouped tr.work-item-group-columns > td.el-table-fixed-column--left:has(.sort-by-column):focus-within) {
  z-index: 14;
}

:deep(.monday-table :is(th, td).monday-sortable-column-header:not(.el-table-fixed-column--left) > .monday-column-resize-handle),
:deep(.monday-table :is(th, td).monday-sortable-column-header:not(.el-table-fixed-column--left) .work-item-column-header__menu) {
  z-index: 1;
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
  margin: calc((var(--work-item-table-row-height) - 26px) / 2) 24px;
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
  border-color: var(--yp-input-border-focus);
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
  --work-item-quick-start: var(--work-item-menu-column-width);
  border-bottom: 1px solid transparent;
  box-sizing: border-box;
}

/* 冻结区交给合成器按 sticky 定位；scroll 事件回写变量会晚一帧，导致添加行先跟随滚动再回弹。 */
:deep(.monday-table .el-table__append-wrapper) { overflow: visible; }

.monday-quick-lead {
  position: sticky;
  z-index: 2;
  left: 0;
  grid-column: 1 / 3;
  grid-row: 1;
  align-self: stretch;
  margin: -1px 0;
  background: var(--yp-bg-surface);
  pointer-events: none;
}

.monday-quick-checkbox { position: sticky; left: var(--work-item-quick-checkbox-left); }
.monday-quick-add__field { position: sticky; left: var(--work-item-quick-title-left); }
.quick-controls { position: sticky; left: var(--work-item-quick-controls-left); justify-self: start; }

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

.monday-quick-lead::before {
  position: absolute;
  top: 0;
  bottom: 0;
  left: var(--work-item-menu-column-width);
  width: 6px;
  border-radius: 0 0 0 6px;
  background: var(--work-item-quick-add-accent);
  content: '';
  opacity: .5;
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
.lane-cursor-sentinel--loading { display: block; min-height: 0; }
.work-items-page-skeleton { display: grid; gap: var(--yp-space-4); overflow: hidden; }
.work-items-page-skeleton__head { display: grid; gap: var(--yp-space-4); padding: var(--yp-space-2) 0; }
.work-items-page-skeleton__title { width: 220px; height: 22px; }
.work-items-page-skeleton__toolbar { display: flex; flex-wrap: wrap; gap: var(--yp-space-3); }
.work-items-page-skeleton__toolbar .work-item-skeleton-block { height: 30px; border-radius: var(--yp-radius-sm); }
.work-items-page-skeleton__table { border-top: 1px solid var(--yp-monday-grid-border); }
/* 新加载的行淡入；backwards 只作用于动画开始前，不会盖过拖动等状态对透明度的设置。 */
:deep(.monday-table .el-table__body tr.work-item-table-row) { animation: work-item-row-in 180ms var(--yp-ease-standard) backwards; }
@keyframes work-item-row-in { from { opacity: 0; } }
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
  border-color: var(--yp-input-border-focus);
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
  overflow: visible;
}

:deep(.monday-table .el-table__expanded-cell)::after {
  position: absolute;
  right: 0;
  bottom: -1px;
  left: 32px;
  height: 1px;
  background: var(--yp-monday-grid-border);
  content: '';
  pointer-events: none;
}


.table-pagination {
  justify-content: flex-end;
  padding: var(--yp-space-4) 0;
}

.kanban-page-skeleton {
  display: flex;
  gap: 16px;
  padding: 4px 0 8px;
  overflow: hidden;
}

.kanban-page-skeleton__lane {
  flex: 0 0 272px;
  padding: 0 8px 12px;
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-sunken);
}

.detail-heading h2 {
  margin: var(--yp-space-1) 0 var(--yp-space-4);
}

.work-item-archive-notice { margin-bottom: 16px; }

.detail-heading small {
  color: var(--yp-text-muted);
}

:deep(.monday-table td.monday-column--collapsed > .cell),
:deep(.monday-table th.monday-column-header--collapsed > .cell) { padding: 0; }
.work-item-collapsed-cell { display: block; width: 100%; height: 100%; }
</style>

<style>
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

/* 加载时详情为空：面板撑满抽屉内容区，品牌加载遮罩才能完整居中，不被抽屉滚动区裁掉。 */
.work-items-detail-drawer .detail-panel {
  min-height: 100%;
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
:deep(.monday-table td.monday-column--collapsed > .cell),
:deep(.monday-table th.monday-column-header--collapsed > .cell) { padding: 0; }
.work-item-collapsed-cell { display: block; width: 100%; height: 100%; }
</style>

<style scoped>
.work-items-embedded { min-height: 0; height: auto; flex: 1 1 0; gap: 0; }
.work-items-embedded .work-items-home { min-height: 0; }
.work-items-embedded .monday-table-surface { min-height: 0; }
.work-items-embedded .work-items-toolbar { margin: 0; flex: none; }
.work-items-embedded :deep(.el-scrollbar__bar) { display: block !important; }
.work-items-embedded :deep(.el-scrollbar__wrap) { overscroll-behavior: auto; }
.work-item-context-label { font-size: 11px; white-space: nowrap; color: var(--yp-text-secondary); padding: 0 5px; }
:deep(.monday-table td.monday-column--collapsed > .cell),
:deep(.monday-table th.monday-column-header--collapsed > .cell) { padding: 0; }
.work-item-collapsed-cell { display: block; width: 100%; height: 100%; }
</style>
