<script setup lang="ts">
import {
  readCsrfToken,
  type ProjectMember,
  type ProjectWorkItemListItem,
  type WorkItemLabelColorToken,
  type WorkItemDetail,
  type WorkItemLabelCatalog,
  type ProjectContentCatalog,
} from '@yumpoo/api-client'
import {
  ElButton,
  ElInput,
  ElLoading,
  ElMessage,
  ElPopover,
  ElTable,
  ElTableColumn,
} from 'element-plus'
import { computed, nextTick, onBeforeUnmount, ref, type CSSProperties } from 'vue'
import { workItemsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../InlineProblem.vue'
import YpAssignee from '../yp/YpAssignee.vue'
import MondayColumnQuickSort from './MondayColumnQuickSort.vue'
import WorkItemLabelPopoverContent from './WorkItemLabelPopoverContent.vue'
import WorkItemContentPopoverContent from './WorkItemContentPopoverContent.vue'
import WorkItemDueDateCell from './WorkItemDueDateCell.vue'
import type { DueDateValue } from './workItemDueDate'
import { workItemLabelColorValue } from './workItemLabelColors'

export interface ProjectWorkItemSubitemSortRule {
  field: string
  direction: 'ASC' | 'DESC'
}

export interface ProjectWorkItemSubitemColumn {
  key: 'title' | 'assignee' | 'status' | 'priority' | 'content' | 'dueDate' | 'updatedAt'
  label: string
}

interface ContentOption { id: string; name: string; colorToken: WorkItemLabelColorToken }
interface StatusOption {
  statusCode: string
  displayName: string
  statusCategory: string
  colorToken?: WorkItemLabelColorToken
  active: boolean
  sortOrder: number
}
interface PriorityOption {
  code: string
  displayName: string
  colorToken: WorkItemLabelColorToken
  active: boolean
  sortOrder: number
}

const props = defineProps<{
  projectId: string
  parent: ProjectWorkItemListItem
  items: ProjectWorkItemListItem[]
  loading: boolean
  error?: ApiProblem | undefined
  sortRules: ProjectWorkItemSubitemSortRule[]
  columns: ProjectWorkItemSubitemColumn[]
  columnWidths: Record<ProjectWorkItemSubitemColumn['key'], number>
  activeContents: ContentOption[]
  contentCatalog?: ProjectContentCatalog | undefined
  members: ProjectMember[]
  workflowStatuses: StatusOption[]
  priorityOptions: PriorityOption[]
  labelCatalog?: WorkItemLabelCatalog | undefined
  canCreate: boolean
  editingCell: boolean
}>()

const vLoading = ElLoading.directive

const emit = defineEmits<{
  retry: []
  sortChange: [rules: ProjectWorkItemSubitemSortRule[]]
  created: [parent: ProjectWorkItemListItem]
  updated: [id: string, detail: WorkItemDetail]
  openDetail: [item: ProjectWorkItemListItem, tab: 'details' | 'discussion']
  patch: [item: ProjectWorkItemListItem, field: 'assignee' | 'priority' | 'dueDate' | 'content', value: string | Date | null]
  dueDateChange: [item: ProjectWorkItemListItem, value: DueDateValue]
  contentsUpdated: [catalog: ProjectContentCatalog]
  transition: [item: ProjectWorkItemListItem, statusCode: string]
  selectionChange: [parentId: string, rows: ProjectWorkItemListItem[]]
  headerResize: [newWidth: number, oldWidth: number, column: { label: string }]
  moveColumn: [source: string, target: string, placement?: 'before' | 'after']
}>()

const quickOpen = ref(false)
const quickTitle = ref('')
const defaultContentId = computed(() => props.activeContents.find(content => content.id === props.parent.contentId)?.id
  ?? props.activeContents[0]?.id)
const quickCreating = ref(false)
const quickError = ref<ApiProblem>()
const quickTitleInput = ref<{ focus: () => void }>()
const assigneeSearch = ref('')
const draggingItem = ref<ProjectWorkItemListItem>()
const columnDraggingKey = ref<string>()
const columnDraggingIndex = ref(-1)
const columnDropIndex = ref<number>()
const columnDropAllowed = ref(false)
const subitemTableRef = ref<{ $el: HTMLElement }>()
const savingSortOrder = ref(false)
let columnDragPreview: HTMLElement | undefined
let columnDragPointerOffset = { x: 0, y: 0 }
let columnDragSourceElements: HTMLElement[] = []
let columnPointerCandidate: {
  pointerId: number
  key: string
  header: HTMLTableCellElement
  headerRects: Array<{ left: number; width: number }>
  index: number
  startX: number
  startY: number
} | undefined

const COLUMN_DRAG_POINTER_THRESHOLD = 5
const COLUMN_DRAG_TILT_DEGREES = 1
const COLUMN_RESIZE_HANDLE_WIDTH = 8
const SUBITEM_ADD_COLUMN_MIN_WIDTH = 96

const filteredMembers = computed(() => {
  const query = assigneeSearch.value.trim().toLocaleLowerCase()
  const active = props.members.filter(member => member.membershipStatus === 'ACTIVE')
  return query ? active.filter(member => member.displayName.toLocaleLowerCase().includes(query)) : active
})

const sortFieldByColumn: Record<ProjectWorkItemSubitemColumn['key'], string> = {
  title: 'TITLE', assignee: 'ASSIGNEE', status: 'STATUS', priority: 'PRIORITY',
  content: 'CONTENT', dueDate: 'DUE_DATE', updatedAt: 'UPDATED_AT',
}

function statusLabel(code: string): string {
  return props.workflowStatuses.find(status => status.statusCode === code)?.displayName ?? code
}

function statusStyle(code: string): CSSProperties {
  const token = props.workflowStatuses.find(status => status.statusCode === code)?.colorToken
  return token ? { backgroundColor: workItemLabelColorValue(token), color: 'var(--yp-text-inverse)' } : {}
}

function priorityPresentation(priority: string | null): { label: string; tone: string } {
  if (!priority) return { label: '—', tone: 'empty' }
  const option = props.priorityOptions.find(item => item.code === priority)
  const tones: Record<string, string> = {
    RED: 'urgent', MAGENTA: 'urgent', ORANGE: 'high', AMBER: 'high', GREEN: 'low',
    LIME: 'low', TEAL: 'medium', CYAN: 'medium', BLUE: 'low', INDIGO: 'medium',
    PURPLE: 'medium', GRAY: 'empty',
  }
  return { label: option?.displayName ?? priority, tone: tones[option?.colorToken ?? ''] ?? 'empty' }
}

function priorityStyle(priority: string | null): CSSProperties {
  const token = props.priorityOptions.find(item => item.code === priority)?.colorToken
  return token ? { backgroundColor: workItemLabelColorValue(token), color: 'var(--yp-text-inverse)' } : {}
}

function formatTime(value: Date | string): string {
  return new Date(value).toLocaleString('zh-CN')
}

function sortDirection(key: ProjectWorkItemSubitemColumn['key']): 'ASC' | 'DESC' | undefined {
  return props.sortRules.find(rule => rule.field === sortFieldByColumn[key])?.direction
}

function applySort(key: ProjectWorkItemSubitemColumn['key']): void {
  if (savingSortOrder.value) return
  const field = sortFieldByColumn[key]
  const next = props.sortRules.map(rule => ({ ...rule }))
  const index = next.findIndex(rule => rule.field === field)
  if (index >= 0) next[index] = { field, direction: next[index]!.direction === 'ASC' ? 'DESC' : 'ASC' }
  else if (next.length < 3) next.push({ field, direction: 'ASC' })
  else next[next.length - 1] = { field, direction: 'ASC' }
  emit('sortChange', next)
}

function clearSort(key: ProjectWorkItemSubitemColumn['key']): void {
  emit('sortChange', props.sortRules.filter(rule => rule.field !== sortFieldByColumn[key]))
}

async function saveSortedOrder(): Promise<void> {
  if (!props.sortRules.length || savingSortOrder.value || props.items.length < 2) return
  const csrf = readCsrfToken()
  if (!csrf) { quickError.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  savingSortOrder.value = true
  try {
    for (let index = 1; index < props.items.length; index += 1) {
      const item = props.items[index]!
      const updated = await workItemsApi.moveWorkItemSubitemOrder({
        parentWorkItemId: props.parent.id,
        subitemId: item.id,
        xXSRFTOKEN: csrf,
        ifMatch: item.etag,
        idempotencyKey: globalThis.crypto.randomUUID(),
        projectWorkItemOrderMoveRequest: {
          previousVisibleWorkItemId: props.items[index - 1]!.id,
          nextVisibleWorkItemId: null,
        },
      })
      emit('updated', item.id, updated)
    }
    emit('sortChange', [])
    ElMessage.success(`已保存 ${props.items.length} 个子项的当前顺序`)
  } catch (reason) {
    quickError.value = await toApiProblem(reason)
    emit('retry')
  } finally {
    savingSortOrder.value = false
  }
}

async function createQuick(continueAdding: boolean): Promise<void> {
  if (quickCreating.value || !quickTitle.value.trim() || !defaultContentId.value) return
  const csrf = readCsrfToken()
  if (!csrf) { quickError.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  quickCreating.value = true
  quickError.value = undefined
  try {
    await workItemsApi.createWorkItemSubitem({
      parentWorkItemId: props.parent.id,
      xXSRFTOKEN: csrf,
      idempotencyKey: globalThis.crypto.randomUUID(),
      workItemSubitemCreateRequest: {
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
    emit('created', props.parent)
    if (continueAdding) quickTitle.value = ''
    else { quickOpen.value = false; quickTitle.value = '' }
  } catch (reason) {
    quickError.value = await toApiProblem(reason)
  } finally {
    quickCreating.value = false
  }
}

function openQuick(): void {
  if (!props.canCreate || !defaultContentId.value) return
  quickOpen.value = true
  void nextTick(() => quickTitleInput.value?.focus())
}

function onQuickKeydown(rawEvent: Event | KeyboardEvent): void {
  const event = rawEvent as KeyboardEvent
  if (event.key !== 'Enter') return
  event.preventDefault()
  void createQuick(event.shiftKey)
}

function row(raw: unknown): ProjectWorkItemListItem {
  return raw as ProjectWorkItemListItem
}

function openItem(raw: unknown, tab: 'details' | 'discussion'): void {
  emit('openDetail', row(raw), tab)
}

function patchItem(raw: unknown, field: 'assignee' | 'priority' | 'dueDate' | 'content',
  value: string | Date | null): void {
  emit('patch', row(raw), field, value)
}

function labelCellStyle(colorToken?: string): CSSProperties {
  return { backgroundColor: workItemLabelColorValue(colorToken), color: 'var(--yp-text-inverse)' }
}

function transitionItem(raw: unknown, statusCode: string): void {
  emit('transition', row(raw), statusCode)
}

function onRowDragStart(raw: unknown): void {
  const item = row(raw)
  if (!props.sortRules.length && item.capabilities.canMoveInProjectOrder) draggingItem.value = item
}

async function dropBefore(raw: unknown): Promise<void> {
  const target = row(raw)
  const moving = draggingItem.value
  draggingItem.value = undefined
  if (!moving || moving.id === target.id) return
  const targetIndex = props.items.findIndex(item => item.id === target.id)
  const remaining = props.items.filter(item => item.id !== moving.id)
  const insertIndex = Math.max(0, remaining.findIndex(item => item.id === target.id))
  remaining.splice(insertIndex, 0, moving)
  const nextIndex = remaining.findIndex(item => item.id === moving.id)
  const csrf = readCsrfToken()
  if (!csrf) { quickError.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  try {
    const updated = await workItemsApi.moveWorkItemSubitemOrder({
      parentWorkItemId: props.parent.id,
      subitemId: moving.id,
      xXSRFTOKEN: csrf,
      ifMatch: moving.etag,
      idempotencyKey: globalThis.crypto.randomUUID(),
      projectWorkItemOrderMoveRequest: {
        previousVisibleWorkItemId: remaining[nextIndex - 1]?.id ?? null,
        nextVisibleWorkItemId: remaining[nextIndex + 1]?.id ?? null,
      },
    })
    emit('updated', moving.id, updated)
    emit('retry')
    if (targetIndex >= 0) ElMessage.success('子项顺序已更新')
  } catch (reason) {
    quickError.value = await toApiProblem(reason)
    emit('retry')
  }
}

function columnBodyClass(column: ProjectWorkItemSubitemColumn): string {
  const classes = column.key === 'title'
    ? ['monday-title-column', 'subitem-title-column']
    : ['monday-movable-column', `monday-column--${column.key}`, 'subitem-movable-column']
  if (column.key === 'status' || column.key === 'priority' || column.key === 'content') {
    classes.push('monday-block-column', 'subitem-block-column')
  }
  return classes.join(' ')
}

function columnHeaderClass(column: ProjectWorkItemSubitemColumn): string {
  return column.key === 'title'
    ? 'monday-title-column monday-sortable-column-header subitem-title-column-header'
    : `monday-movable-column-header monday-sortable-column-header monday-column-header--${column.key} subitem-movable-column-header`
}

function movableColumns(): ProjectWorkItemSubitemColumn[] {
  return props.columns.filter(column => column.key !== 'title')
}

function removeColumnDragPreview(): void {
  columnDragPreview?.remove()
  columnDragPreview = undefined
  columnDragSourceElements.forEach(element => element.classList.remove('subitem-column-drag-source'))
  columnDragSourceElements = []
  columnDragPointerOffset = { x: 0, y: 0 }
}

function moveColumnDragPreview(clientX: number, clientY: number): void {
  if (!columnDragPreview || (clientX === 0 && clientY === 0)) return
  columnDragPreview.style.left = `${Math.round(clientX - columnDragPointerOffset.x)}px`
  columnDragPreview.style.top = `${Math.round(clientY - columnDragPointerOffset.y)}px`
}

function sanitizeColumnDragPreview(root: HTMLElement): void {
  root.removeAttribute('id')
  root.removeAttribute('style')
  root.querySelectorAll('[id]').forEach(element => element.removeAttribute('id'))
  root.querySelectorAll<HTMLElement>('button,input,select,textarea,[tabindex]').forEach(element => {
    element.tabIndex = -1
  })
}

function createColumnDragPreview(header: HTMLTableCellElement, clientX: number, clientY: number): void {
  removeColumnDragPreview()
  const headerRect = header.getBoundingClientRect()
  const width = Math.max(headerRect.width, header.offsetWidth, 1)
  const tableRect = subitemTableRef.value?.$el?.getBoundingClientRect()
  const height = Math.max(headerRect.height, tableRect?.height ?? headerRect.height)
  columnDragPointerOffset = {
    x: Math.min(Math.max(clientX - headerRect.left, 0), width),
    y: Math.min(Math.max(clientY - headerRect.top, 0), height),
  }

  const preview = document.createElement('div')
  preview.className = 'work-item-column-drag-preview subitem-column-drag-preview'
  preview.setAttribute('aria-hidden', 'true')
  preview.style.width = `${width}px`
  preview.style.height = `${height}px`
  preview.style.transform = `rotate(${COLUMN_DRAG_TILT_DEGREES}deg)`
  preview.style.transformOrigin = `${columnDragPointerOffset.x}px ${columnDragPointerOffset.y}px`

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
  const sourceCells: HTMLTableCellElement[] = []
  const sourceRows = subitemTableRef.value?.$el?.querySelectorAll<HTMLTableRowElement>(
    '.el-table__body-wrapper tbody tr.el-table__row',
  ) ?? []
  sourceRows.forEach(sourceRow => {
    const sourceCell = sourceRow.children[headerIndex]
    if (!(sourceCell instanceof HTMLTableCellElement)) return
    sourceCells.push(sourceCell)
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
  columnDragSourceElements = [header, ...sourceCells]
  columnDragSourceElements.forEach(element => element.classList.add('subitem-column-drag-source'))
  document.body.appendChild(preview)
  columnDragPreview = preview
  moveColumnDragPreview(clientX, clientY)
}

function resetColumnDrag(): void {
  removeColumnDragPreview()
  columnDraggingKey.value = undefined
  columnDraggingIndex.value = -1
  columnDropIndex.value = undefined
  columnDropAllowed.value = false
}

function columnDragStyle(columnKey?: string): CSSProperties {
  const draggedKey = columnDraggingKey.value
  const dropIndex = columnDropIndex.value
  const from = columnDraggingIndex.value
  if (!draggedKey || dropIndex === undefined || from < 0 || !columnKey || columnKey === 'title') return {}
  const index = movableColumns().findIndex(column => column.key === columnKey)
  if (index < 0) return {}
  if (columnKey === draggedKey) return { opacity: 0, pointerEvents: 'none' }

  const draggedWidth = columnPointerCandidate?.headerRects[from]?.width
    || props.columnWidths[draggedKey as ProjectWorkItemSubitemColumn['key']]
  if (from < dropIndex && index > from && index < dropIndex) {
    return { transform: `translateX(-${draggedWidth}px)` }
  }
  if (from > dropIndex && index >= dropIndex && index < from) {
    return { transform: `translateX(${draggedWidth}px)` }
  }
  return { transform: 'translateX(0px)' }
}

function subitemCellStyle({ column }: { column: { property?: string } }): CSSProperties {
  return columnDragStyle(column.property)
}

function subitemHeaderCellStyle({ column }: { column: { property?: string } }): CSSProperties {
  return columnDragStyle(column.property)
}

function movableHeaderCells(): HTMLTableCellElement[] {
  return [...(subitemTableRef.value?.$el?.querySelectorAll<HTMLTableCellElement>(
    '.el-table__header-wrapper th.subitem-movable-column-header',
  ) ?? [])]
}

function updateColumnDropTarget(clientX: number, clientY: number): void {
  const tableRect = subitemTableRef.value?.$el?.getBoundingClientRect()
  const withinTable = Boolean(tableRect
    && clientX >= tableRect.left
    && clientX <= tableRect.right
    && clientY >= tableRect.top
    && clientY <= tableRect.bottom)
  columnDropAllowed.value = withinTable
  if (!withinTable) {
    columnDropIndex.value = undefined
    return
  }
  const headerRects = columnPointerCandidate?.headerRects ?? []
  let target = headerRects.findIndex(rect => clientX < rect.left + rect.width / 2)
  if (target < 0) target = headerRects.length
  columnDropIndex.value = target
}

function clearColumnPointerTracking(): void {
  const candidate = columnPointerCandidate
  try {
    if (candidate?.header.hasPointerCapture?.(candidate.pointerId)) {
      candidate.header.releasePointerCapture(candidate.pointerId)
    }
  } catch { /* 指针已经离开窗口时捕获可能已由浏览器自动释放 */ }
  columnPointerCandidate = undefined
  window.removeEventListener('pointermove', onColumnPointerMove, true)
  window.removeEventListener('pointerup', onColumnPointerUp, true)
  window.removeEventListener('pointercancel', onColumnPointerCancel, true)
}

function onColumnPointerDown(event: PointerEvent): void {
  if (event.isPrimary === false || event.button !== 0 || columnDraggingKey.value) return
  const target = event.target as HTMLElement | null
  if (target?.closest('.monday-column-resize-handle, .sort-by-column')) return
  const header = target?.closest<HTMLTableCellElement>('th.subitem-movable-column-header')
  if (!header) return
  const rect = header.getBoundingClientRect()
  if (rect.width > 0 && rect.right - event.clientX < COLUMN_RESIZE_HANDLE_WIDTH) return
  const headers = movableHeaderCells()
  const index = headers.indexOf(header)
  const key = movableColumns()[index]?.key
  if (!key) return

  clearColumnPointerTracking()
  columnPointerCandidate = {
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
  try {
    header.setPointerCapture?.(event.pointerId)
  } catch { /* 测试环境或已失效指针不支持捕获时仍由 window 监听继续拖拽 */ }
  window.addEventListener('pointermove', onColumnPointerMove, { capture: true, passive: false })
  window.addEventListener('pointerup', onColumnPointerUp, true)
  window.addEventListener('pointercancel', onColumnPointerCancel, true)
}

function onColumnPointerMove(event: PointerEvent): void {
  const candidate = columnPointerCandidate
  if (!candidate || candidate.pointerId !== event.pointerId) return
  if (!columnDraggingKey.value) {
    const distance = Math.hypot(event.clientX - candidate.startX, event.clientY - candidate.startY)
    if (distance < COLUMN_DRAG_POINTER_THRESHOLD) return
    createColumnDragPreview(candidate.header, candidate.startX, candidate.startY)
    columnDraggingKey.value = candidate.key
    columnDraggingIndex.value = candidate.index
    columnDropIndex.value = candidate.index
  }
  event.preventDefault()
  event.stopPropagation()
  moveColumnDragPreview(event.clientX, event.clientY)
  updateColumnDropTarget(event.clientX, event.clientY)
}

function commitColumnDrop(): void {
  const source = columnDraggingKey.value
  const from = columnDraggingIndex.value
  let target = columnDropIndex.value
  const allowed = columnDropAllowed.value
  resetColumnDrag()
  if (!allowed || !source || from < 0 || target === undefined) return
  const visibleKeys = movableColumns().map(column => column.key)
  const currentIndex = visibleKeys.indexOf(source as ProjectWorkItemSubitemColumn['key'])
  if (currentIndex < 0) return
  const remaining = visibleKeys.filter(key => key !== source)
  if (currentIndex < target) target -= 1
  target = Math.max(0, Math.min(target, remaining.length))
  if (target === currentIndex) return
  const beforeKey = remaining[target]
  if (beforeKey) {
    emit('moveColumn', source, beforeKey, 'before')
    return
  }
  const lastKey = remaining.at(-1)
  if (lastKey) emit('moveColumn', source, lastKey, 'after')
}

function onColumnPointerUp(event: PointerEvent): void {
  const candidate = columnPointerCandidate
  if (!candidate || candidate.pointerId !== event.pointerId) return
  const dragged = Boolean(columnDraggingKey.value)
  clearColumnPointerTracking()
  if (!dragged) return
  event.preventDefault()
  event.stopPropagation()
  commitColumnDrop()
}

function onColumnPointerCancel(event: PointerEvent): void {
  if (!columnPointerCandidate || columnPointerCandidate.pointerId !== event.pointerId) return
  clearColumnPointerTracking()
  resetColumnDrag()
}

onBeforeUnmount(() => {
  clearColumnPointerTracking()
  removeColumnDragPreview()
})
</script>

<template>
  <section
    class="subitem-table-shell"
    :style="{ '--subitem-title-column-width': `${columnWidths.title}px` }"
    :aria-label="`${parent.title} 的子项`"
    @pointerdown="onColumnPointerDown"
  >
    <inline-problem v-if="error" :problem="error" @retry="$emit('retry')" />
    <inline-problem v-if="quickError" :problem="quickError" />
    <div class="subitem-table-frame">
      <div
        class="subitem-hierarchy-bar"
        :class="{ 'subitem-hierarchy-bar--quick': quickOpen }"
        aria-hidden="true"
      >
        <span class="subitem-hierarchy-bar__main" />
        <span class="subitem-hierarchy-bar__trailing" />
      </div>
      <div class="subitem-hierarchy-branches" aria-hidden="true">
        <template v-if="items.length">
          <span
            v-for="item in items"
            :key="item.id"
            class="subitem-hierarchy-branch subitem-hierarchy-branch--data"
          />
        </template>
        <span v-else-if="loading || error" class="subitem-hierarchy-branch subitem-hierarchy-branch--empty" />
        <span
          class="subitem-hierarchy-branch"
          :class="quickOpen ? 'subitem-hierarchy-branch--quick' : 'subitem-hierarchy-branch--add'"
        />
      </div>

      <el-table
        ref="subitemTableRef"
        v-loading="loading"
        :data="items"
        row-key="id"
        class="monday-subitem-table"
        :class="{
          'monday-subitem-table--empty': !loading && !error && !items.length,
          'monday-subitem-table--column-dragging': columnDraggingKey,
        }"
        empty-text="暂无子项，可在下方快速添加"
        border
        :cell-style="subitemCellStyle"
        :header-cell-style="subitemHeaderCellStyle"
        @selection-change="$emit('selectionChange', parent.id, $event)"
        @header-dragend="(newWidth: number, oldWidth: number, column: { label: string }) => $emit('headerResize', newWidth, oldWidth, column)"
      >
        <el-table-column
          type="selection"
          width="48"
          reserve-selection
          fixed
          class-name="subitem-selection-column"
          label-class-name="subitem-selection-column"
        />
        <!-- 与主表一致，用可响应的 prop 标识当前位置的字段。 -->
        <el-table-column
          v-for="(column, columnIndex) in columns"
          :key="columnIndex"
          :label="column.label"
          :prop="column.key"
          :width="columnWidths[column.key]"
          :fixed="column.key === 'title'"
          align="center"
          :class-name="columnBodyClass(column)"
          :label-class-name="columnHeaderClass(column)"
          resizable
        >
          <template #header>
            <div
              class="subitem-column-header"
              :class="{
                'subitem-column-header--dragging': columnDraggingKey === column.key,
              }"
            >
              <monday-column-quick-sort
                :label="column.label"
                :direction="sortDirection(column.key)"
                :saving="savingSortOrder"
                @sort="applySort(column.key)"
                @clear="clearSort(column.key)"
                @save="saveSortedOrder"
              />
            </div>
            <span
              class="monday-column-resize-handle"
              :class="{ 'monday-title-column-resize-handle': column.key === 'title' }"
              aria-hidden="true"
            />
          </template>
          <template #default="scope">
            <div
              v-if="column.key === 'title'"
              class="subitem-title-cell"
              :draggable="scope.row.capabilities.canMoveInProjectOrder && !sortRules.length"
              @dragstart="onRowDragStart(scope.row)"
              @dragend="draggingItem = undefined"
              @dragover.prevent
              @drop.prevent="dropBefore(scope.row)"
            >
              <button class="subitem-link" @click.stop="openItem(scope.row, 'details')">
                {{ scope.row.title }}
              </button>
              <button class="subitem-discussion" aria-label="打开协作讨论" @click.stop="openItem(scope.row, 'discussion')">
                <svg width="17" height="17" viewBox="0 0 24 24" fill="none">
                  <path d="M12 21C16.9706 21 21 16.9706 21 12C21 7.02944 16.9706 3 12 3C7.02944 3 3 7.02944 3 12C3 13.8214 3.54139 15.5165 4.4741 16.9366L3.25 21L7.54583 19.8665C8.89531 20.5902 10.4079 21 12 21Z" stroke="currentColor" stroke-width="1.6" />
                  <path d="M12 8.5V15.5M8.5 12H15.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
                </svg>
              </button>
            </div>

            <el-popover v-else-if="column.key === 'assignee'" placement="bottom" :width="360" trigger="click" @show="assigneeSearch = ''">
              <template #reference>
                <button class="subitem-cell-button" :disabled="editingCell">
                  <yp-assignee :user-id="scope.row.assigneeUserId" :display-name="scope.row.assigneeDisplayName" :show-name="false" size="table" />
                </button>
              </template>
              <div class="subitem-popover-stack">
                <el-input v-model="assigneeSearch" clearable placeholder="搜索项目成员" />
                <button class="subitem-option" @click="patchItem(scope.row, 'assignee', null)">清空处理人</button>
                <button v-for="member in filteredMembers" :key="member.userId" class="subitem-option" @click="patchItem(scope.row, 'assignee', member.userId)">
                  <yp-assignee :user-id="member.userId" :display-name="member.displayName" />
                </button>
              </div>
            </el-popover>

            <el-popover v-else-if="column.key === 'status'" placement="bottom" width="auto" trigger="click">
              <template #reference>
                <button class="subitem-block-cell" :style="statusStyle(scope.row.statusCode)" :disabled="editingCell">{{ statusLabel(scope.row.statusCode) }}</button>
              </template>
              <work-item-label-popover-content
                kind="status"
                :project-id="projectId"
                :catalog="labelCatalog"
                :workflow-statuses="workflowStatuses.filter(item => item.active || item.statusCode === scope.row.statusCode)"
                :current-value="scope.row.statusCode"
                :can-manage="Boolean(labelCatalog?.canManage)"
                :available-transitions="scope.row.capabilities.availableTransitions"
                @select-status="transitionItem(scope.row, $event)"
              />
            </el-popover>

            <el-popover v-else-if="column.key === 'priority'" placement="bottom" width="auto" trigger="click">
              <template #reference>
                <button class="subitem-block-cell" :class="`subitem-priority--${priorityPresentation(scope.row.priority).tone}`" :style="priorityStyle(scope.row.priority)" :disabled="editingCell">
                  {{ priorityPresentation(scope.row.priority).label }}
                </button>
              </template>
              <work-item-label-popover-content
                kind="priority"
                :project-id="projectId"
                :catalog="labelCatalog"
                :priority-options="priorityOptions.filter(item => item.active)"
                :current-value="scope.row.priority"
                :can-manage="Boolean(labelCatalog?.canManage)"
                @select-priority="patchItem(scope.row, 'priority', $event)"
              />
            </el-popover>

            <el-popover v-else-if="column.key === 'content'" placement="bottom" width="auto" trigger="click">
              <template #reference>
                <button class="subitem-content-pill" :style="labelCellStyle(scope.row.contentColorToken)" :disabled="editingCell">
                  {{ scope.row.contentName || '—' }}
                </button>
              </template>
              <work-item-content-popover-content
                :project-id="projectId"
                :catalog="contentCatalog"
                :current-value="scope.row.contentId"
                :can-manage="Boolean(contentCatalog?.canManage)"
                @select="patchItem(scope.row, 'content', $event)"
                @updated="emit('contentsUpdated', $event)"
              />
            </el-popover>

            <work-item-due-date-cell
              v-else-if="column.key === 'dueDate'" :item="row(scope.row)"
              style="--deadline-cell-height: 34px"
              :can-edit="row(scope.row).capabilities.canEditFields" :busy="editingCell"
              @change="emit('dueDateChange', row(scope.row), $event)"
            />

            <span v-else-if="column.key === 'updatedAt'" class="subitem-timestamp">{{ formatTime(scope.row.updatedAt) }}</span>
          </template>
        </el-table-column>

        <el-table-column
          label="添加列"
          column-key="add-column"
          :min-width="SUBITEM_ADD_COLUMN_MIN_WIDTH"
          :resizable="false"
          header-align="left"
          class-name="subitem-add-column"
          label-class-name="subitem-add-column-header"
        >
          <template #header>
            <button
              type="button"
              class="subitem-add-column-button"
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
          <div v-if="quickOpen" class="subitem-quick-row">
            <span class="subitem-quick-checkbox" aria-hidden="true" />
            <el-input
              ref="quickTitleInput"
              v-model="quickTitle"
              class="subitem-quick-title"
              maxlength="300"
              :disabled="quickCreating"
              placeholder="添加子项"
              aria-label="子项名称；Enter 创建，Shift+Enter 创建后继续"
              @keydown="onQuickKeydown"
            />
            <el-button class="subitem-quick-submit" type="primary" :loading="quickCreating" :disabled="!quickTitle.trim() || !defaultContentId" @click="createQuick(false)">添加</el-button>
          </div>
          <button v-else class="subitem-add" :disabled="!canCreate || !defaultContentId" @click="openQuick">
            <span class="subitem-quick-checkbox" aria-hidden="true" />
            <span class="subitem-add__field">添加子项</span>
          </button>
        </template>
      </el-table>
    </div>
  </section>
</template>

<style scoped>
.subitem-table-shell {
  --subitem-hierarchy-indent: var(--work-item-hierarchy-indent, 40px);
  --subitem-hierarchy-line-width: var(--work-item-hierarchy-line-width, 1px);
  --subitem-hierarchy-bar-width: var(--work-item-hierarchy-bar-width, 6px);
  --subitem-hierarchy-bar-center: var(--work-item-hierarchy-bar-center, 3px);
  --subitem-hierarchy-corner-radius: var(--work-item-hierarchy-corner-radius, var(--subitem-hierarchy-bar-width));
  --subitem-add-row-accent: rgba(87, 155, 252, 0.5);
  --subitem-table-header-height: 38px;
  --subitem-table-row-height: 36px;
  --subitem-table-empty-height: 60px;
  --subitem-table-quick-height: var(--subitem-table-row-height);
  position: relative;
  width: calc(100% - var(--subitem-hierarchy-indent));
  margin-left: var(--subitem-hierarchy-indent);
  box-sizing: border-box;
  background: var(--yp-bg-surface);
}

.subitem-table-frame {
  position: relative;
  isolation: isolate;
}

.subitem-table-frame::after {
  position: absolute;
  z-index: 2;
  right: 0;
  bottom: 0;
  left: var(--subitem-hierarchy-bar-width);
  height: 1px;
  background: var(--yp-monday-grid-border, var(--yp-border-subtle));
  content: '';
  pointer-events: none;
}

.subitem-hierarchy-bar {
  position: absolute;
  z-index: 7;
  top: 0;
  bottom: var(--subitem-hierarchy-line-width);
  left: 0;
  display: flex;
  width: var(--subitem-hierarchy-bar-width);
  flex-direction: column;
  overflow: hidden;
  border-radius: var(--subitem-hierarchy-corner-radius) 0 0 var(--subitem-hierarchy-corner-radius);
  pointer-events: none;
}

.subitem-hierarchy-bar__main {
  min-height: 0;
  flex: 1 1 auto;
  background: var(--work-item-group-accent, rgb(87, 155, 252));
}

.subitem-hierarchy-bar__trailing {
  height: var(--subitem-table-row-height);
  flex: 0 0 var(--subitem-table-row-height);
  background: var(--subitem-add-row-accent);
}

.subitem-hierarchy-bar--quick .subitem-hierarchy-bar__trailing {
  height: var(--subitem-table-quick-height);
  flex-basis: var(--subitem-table-quick-height);
}

.subitem-hierarchy-branches {
  position: absolute;
  z-index: 2;
  top: var(--subitem-table-header-height);
  left: calc(
    -1 * var(--subitem-hierarchy-indent) + var(--subitem-hierarchy-bar-center) -
      (var(--subitem-hierarchy-line-width) / 2)
  );
  display: flex;
  width: calc(var(--subitem-hierarchy-indent) + (var(--subitem-hierarchy-line-width) / 2));
  flex-direction: column;
  pointer-events: none;
}

.subitem-hierarchy-branch {
  position: relative;
  height: var(--subitem-table-row-height);
  flex: 0 0 var(--subitem-table-row-height);
}

.subitem-hierarchy-branch::before {
  position: absolute;
  top: calc(50% - var(--subitem-hierarchy-corner-radius));
  right: 0;
  left: 0;
  height: var(--subitem-hierarchy-corner-radius);
  box-sizing: border-box;
  border-bottom: var(--subitem-hierarchy-line-width) solid var(--work-item-group-accent, rgb(87, 155, 252));
  border-left: var(--subitem-hierarchy-line-width) solid var(--work-item-group-accent, rgb(87, 155, 252));
  border-bottom-left-radius: var(--subitem-hierarchy-corner-radius);
  content: '';
}

.subitem-hierarchy-branch--add::before,
.subitem-hierarchy-branch--quick::before {
  border-color: var(--subitem-add-row-accent);
}

.subitem-hierarchy-branch--empty {
  height: var(--subitem-table-empty-height);
  flex-basis: var(--subitem-table-empty-height);
}

.subitem-hierarchy-branch--quick {
  height: var(--subitem-table-quick-height);
  flex-basis: var(--subitem-table-quick-height);
}

.monday-subitem-table {
  position: relative;
  width: 100%;
  --subitem-sort-overflow-space: 20px;
  --el-table-row-hover-bg-color: var(--yp-bg-sunken);
}
:deep(.monday-subitem-table.el-table--border::before),
:deep(.monday-subitem-table.el-table--border::after),
:deep(.monday-subitem-table .el-table__inner-wrapper::before),
:deep(.monday-subitem-table.el-table--border .el-table__inner-wrapper::after) {
  display: none;
}
:deep(.monday-subitem-table .el-table-fixed-column--left) {
  transform: translateX(var(--work-item-table-scroll-left, 0px));
  will-change: transform;
}
:deep(.monday-subitem-table.el-table),
:deep(.monday-subitem-table .el-table__inner-wrapper),
:deep(.monday-subitem-table .el-table__header),
:deep(.monday-subitem-table .el-table__header thead),
:deep(.monday-subitem-table .el-table__header tr),
:deep(.monday-subitem-table .el-table__header th.el-table__cell),
:deep(.monday-subitem-table .el-table__header th.el-table__cell > .cell),
:deep(.monday-subitem-table .el-table-fixed-column--left) {
  overflow: visible !important;
}
:deep(.monday-subitem-table.el-table > .el-table__inner-wrapper > .el-table__header-wrapper) {
  position: relative;
  z-index: 6;
  margin-top: calc(-1 * var(--subitem-sort-overflow-space));
  padding-top: var(--subitem-sort-overflow-space);
  overflow: hidden !important;
}
:deep(.monday-subitem-table th.monday-sortable-column-header) {
  position: relative;
}
:deep(.monday-subitem-table th.monday-sortable-column-header:hover),
:deep(.monday-subitem-table th.monday-sortable-column-header:focus-within),
:deep(.monday-subitem-table th.monday-sortable-column-header:has(.sort-by-column--active)) {
  z-index: 12;
}
:deep(.monday-subitem-table .el-table__header tr),
:deep(.monday-subitem-table .el-table__body tr) { height: var(--subitem-table-row-height); }
:deep(.monday-subitem-table .el-table__cell) { box-sizing: border-box; height: var(--subitem-table-row-height); padding: 0; border-color: var(--yp-border-subtle); }
:deep(.monday-subitem-table .cell) { padding: 0 8px; display: flex; align-items: center; justify-content: center; }
:deep(.monday-subitem-table .el-table__body .cell) { height: 34px; }
:deep(.monday-subitem-table td.subitem-block-column > .cell) {
  height: var(--subitem-table-row-height);
  padding: 0;
}
.subitem-content-pill {
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
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
:deep(.monday-subitem-table th.subitem-add-column-header),
:deep(.monday-subitem-table td.subitem-add-column) {
  border-right: 0 !important;
  border-left: 1px solid var(--yp-monday-grid-border, var(--yp-border-subtle)) !important;
}
:deep(.monday-subitem-table th.el-table__cell:has(+ th.subitem-add-column-header)),
:deep(.monday-subitem-table td.el-table__cell:has(+ td.subitem-add-column)) {
  border-right: 0;
}
:deep(.monday-subitem-table th.subitem-add-column-header > .cell) {
  display: flex;
  height: 100%;
  align-items: center;
  justify-content: flex-start;
  padding: 0 0 0 10px;
}
:deep(.monday-subitem-table th.el-table__cell) {
  border-top: 1px solid var(--yp-monday-grid-border, var(--yp-border-subtle)) !important;
}
:deep(.monday-subitem-table .subitem-selection-column) {
  border-left: 0;
}
:deep(.monday-subitem-table .el-table__header th.subitem-selection-column) {
  border-top: 0 !important;
  background-image: linear-gradient(
    var(--yp-monday-grid-border, var(--yp-border-subtle)),
    var(--yp-monday-grid-border, var(--yp-border-subtle))
  ) !important;
  background-position: var(--subitem-hierarchy-bar-width) top !important;
  background-repeat: no-repeat !important;
  background-size: calc(100% - var(--subitem-hierarchy-bar-width)) 1px !important;
}
:deep(.monday-subitem-table .subitem-selection-column .el-checkbox) {
  display: inline-flex;
  width: 100%;
  height: 100%;
  align-items: center;
  justify-content: center;
  margin: 0;
}
:deep(.monday-subitem-table .subitem-selection-column .el-checkbox__inner) {
  width: 16px;
  height: 16px;
  border-color: var(--yp-border-strong);
  border-radius: 2px;
  background: var(--yp-bg-surface);
}
:deep(.monday-subitem-table .subitem-selection-column .el-checkbox__input.is-checked .el-checkbox__inner),
:deep(.monday-subitem-table .subitem-selection-column .el-checkbox__input.is-indeterminate .el-checkbox__inner) {
  border-color: var(--yp-action-primary);
  background: var(--yp-action-primary);
}
:deep(.monday-subitem-table .subitem-selection-column .el-checkbox__inner::after) {
  display: none;
}
:deep(.monday-subitem-table .subitem-selection-column .el-checkbox__input.is-checked .el-checkbox__inner::after) {
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
:deep(.monday-subitem-table .subitem-selection-column .el-checkbox__input.is-indeterminate .el-checkbox__inner::before) {
  top: 7px;
  right: 3px;
  left: 3px;
  height: 2px;
}
:deep(.monday-subitem-table th .cell) { font-size: 13px; color: var(--yp-text-secondary); }
:deep(.monday-subitem-table .el-table__empty-block) {
  height: var(--subitem-table-empty-height);
  min-height: var(--subitem-table-empty-height);
}
:deep(.monday-subitem-table--empty .el-table__empty-block) { display: none; }
:deep(.monday-subitem-table th.subitem-movable-column-header),
:deep(.monday-subitem-table td.subitem-movable-column) {
  transition: none;
}
:deep(.monday-subitem-table--column-dragging th.subitem-movable-column-header),
:deep(.monday-subitem-table--column-dragging td.subitem-movable-column) {
  transition: transform 180ms cubic-bezier(0.2, 0, 0, 1);
  will-change: transform;
}
:deep(.monday-subitem-table .subitem-column-drag-source) {
  opacity: 0 !important;
  pointer-events: none !important;
  transition: none !important;
}
:deep(.monday-subitem-table th.subitem-movable-column-header),
:deep(.monday-subitem-table th.subitem-movable-column-header > .cell) {
  cursor: grab;
  user-select: none;
  touch-action: none;
}
:deep(.monday-subitem-table th.subitem-movable-column-header:active),
:deep(.monday-subitem-table th.subitem-movable-column-header:active > .cell) {
  cursor: grabbing;
}
.subitem-column-header { position: relative; width: 100%; height: 100%; min-width: 0; user-select: none; }
.subitem-column-header--dragging { cursor: grabbing; }
.subitem-add-column-button {
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
  transition: color var(--yp-motion-fast) var(--yp-ease-standard),
              background-color var(--yp-motion-fast) var(--yp-ease-standard);
}
.subitem-add-column-button:hover {
  background: var(--yp-bg-hover);
  color: var(--yp-text-primary);
}
.subitem-add-column-button:focus-visible {
  outline: 2px solid var(--yp-action-primary);
  outline-offset: -2px;
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
.monday-title-column-resize-handle { overflow: visible; }
.subitem-title-cell { width: 100%; height: 34px; display: flex; align-items: center; min-width: 0; }
.subitem-link { flex: 1; min-width: 0; text-align: left; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; border: 0; background: transparent; color: var(--yp-text-primary); cursor: pointer; }
.subitem-link:hover { color: var(--yp-link); text-decoration: underline; }
.subitem-discussion { width: 32px; height: 32px; border: 0; background: transparent; color: var(--yp-text-secondary); cursor: pointer; }
.subitem-cell-button, .subitem-block-cell { width: 100%; height: 100%; border: 0; background: transparent; color: inherit; cursor: pointer; }
.subitem-block-cell { display: flex; align-items: center; justify-content: center; box-sizing: border-box; padding: 0 var(--yp-space-2); color: var(--yp-text-inverse); }
.subitem-popover-stack { display: grid; gap: 6px; }
.subitem-option { min-height: 34px; border: 0; background: transparent; text-align: left; cursor: pointer; }
.subitem-option:hover { background: var(--yp-bg-sunken); }
.subitem-timestamp { color: var(--yp-text-secondary); font-size: 12px; }
.subitem-quick-row {
  --subitem-quick-control-height: 26px;
  display: grid;
  height: var(--subitem-table-quick-height);
  min-width: max-content;
  grid-template-columns: 48px var(--subitem-title-column-width, 320px) 72px;
  gap: 0;
  align-items: center;
  box-sizing: border-box;
  padding: 3px 12px 3px 0;
  border-top: 1px solid var(--yp-border-subtle);
  background: transparent;
  transition: background-color var(--yp-motion-fast) var(--yp-ease-standard);
}
.subitem-quick-row:focus-within { background: var(--yp-bg-selected); }
.subitem-quick-title { box-sizing: border-box; min-width: 0; padding: 0 4px 0 8px; }
.subitem-quick-checkbox {
  width: 16px;
  height: 16px;
  align-self: center;
  justify-self: center;
  box-sizing: border-box;
  border: 1px solid color-mix(in srgb, var(--yp-border-strong) 50%, transparent);
  border-radius: 2px;
  background: var(--yp-bg-surface);
  transform: translateX(2px);
  pointer-events: none;
}
.subitem-quick-submit {
  width: 64px;
  height: var(--subitem-quick-control-height);
  padding: 0 12px;
}
:deep(.subitem-quick-row .el-input__wrapper) {
  height: var(--subitem-quick-control-height);
  min-height: var(--subitem-quick-control-height);
}
:deep(.subitem-quick-row .el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--yp-border-default) inset !important;
}
:deep(.subitem-quick-row .el-input__wrapper:has(input:focus-visible)) {
  outline: none !important;
  outline-offset: 0;
}
.subitem-add {
  display: grid;
  width: 100%;
  height: var(--subitem-table-row-height);
  grid-template-columns: 48px var(--subitem-title-column-width, 320px) 1fr;
  align-items: center;
  box-sizing: border-box;
  padding: 0;
  border: 0;
  border-top: 1px solid var(--yp-border-subtle);
  background: transparent;
  color: var(--yp-text-secondary);
  font: inherit;
  cursor: pointer;
}
.subitem-add__field {
  display: flex;
  height: 26px;
  grid-column: 2;
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
.subitem-add:hover:not(:disabled) .subitem-add__field {
  border-color: var(--yp-border-strong, var(--yp-border-default));
  background: var(--yp-bg-surface);
  color: var(--yp-text-primary);
}
.subitem-add:focus-visible { outline: none; }
.subitem-add:focus-visible .subitem-add__field {
  border-color: var(--yp-action-primary);
  box-shadow: 0 0 0 1px var(--yp-action-primary);
}
.subitem-add:disabled { cursor: not-allowed; opacity: .55; }
</style>
