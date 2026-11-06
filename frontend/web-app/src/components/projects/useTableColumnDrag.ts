import { nextTick, ref, type CSSProperties, type Ref } from 'vue'
import type { ProjectWorkItemListItem } from '@yumpoo/api-client'
import type { WorkItemGroupDisplayRow } from './useWorkItemGrouping'

export type BuiltInColumnKey = 'title' | 'assignee' | 'status' | 'priority' | 'content' | 'dueDate' | 'timeTracking' | 'updatedAt'
export type MovableColumnKey = Exclude<BuiltInColumnKey, 'title'>
type TableDisplayRow = ProjectWorkItemListItem | WorkItemGroupDisplayRow

interface ColumnDragOptions {
  tableRef: Ref<{ $el: HTMLElement } | undefined>
  grouped: Readonly<Ref<boolean>>
  tableDragging: Ref<ProjectWorkItemListItem | undefined>
  movableVisibleColumns: Readonly<Ref<readonly { key: BuiltInColumnKey }[]>>
  movableColumnOrder: Ref<MovableColumnKey[]>
  columnWidths: Record<BuiltInColumnKey, number>
  columnByKey: ReadonlyMap<BuiltInColumnKey, { minWidth: number }>
  cellColoring: (row: TableDisplayRow, column: { property?: string; columnKey?: string }) => string | undefined
  scheduleResponsiveTableLayout: () => void
  flushResponsiveTableLayout: () => void
  schedulePageScrollbarSync: () => void
  persistTablePrefs: () => void
  suppressClickAfterTableDrag: () => void
  onTablePointerDown: (event: PointerEvent) => void
  TABLE_DRAG_TILT_DEGREES: number
  TABLE_DRAG_POINTER_THRESHOLD: number
}

export function useTableColumnDrag({
  tableRef, grouped, tableDragging, movableVisibleColumns, movableColumnOrder, columnWidths, columnByKey, cellColoring,
  scheduleResponsiveTableLayout, flushResponsiveTableLayout, schedulePageScrollbarSync, persistTablePrefs,
  suppressClickAfterTableDrag, onTablePointerDown, TABLE_DRAG_TILT_DEGREES, TABLE_DRAG_POINTER_THRESHOLD
}: ColumnDragOptions) {
  const columnDraggingKey = ref<MovableColumnKey>()
  const columnDraggingIndex = ref(-1)
  const columnDropIndex = ref<number>()
  const columnResizingKey = ref<MovableColumnKey>()
  let tableColumnDragPreview: HTMLElement | undefined

  let tableColumnDragPointerOffset = { x: 0, y: 0 }

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
    key: BuiltInColumnKey
    minWidth: number
    startWidth: number
    startX: number
  } | undefined
  const TABLE_COLUMN_RESIZE_HANDLE_WIDTH = 8

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

  function tableCellStyle({ row, column }: { row: TableDisplayRow; column: { property?: string; columnKey?: string } }): CSSProperties {
    const style = tableColumnDragStyle(column.property)
    const color = cellColoring(row, column)
    return color ? { ...style, '--work-item-condition-cell-bg': color } : style
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
    const columnKey = handle.dataset.columnKey as BuiltInColumnKey | undefined
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
    if (target?.closest('.sort-by-column, .work-item-column-menu, .work-item-column-expand')) return
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

  return {
    removeTableColumnDragPreview, tableColumnDragStyle, tableCellStyle, tableHeaderCellStyle,
    clearTableColumnPointerTracking, clearTableColumnResizeTracking, onTableColumnResizePointerDown, onTableColumnResizePointerMove,
    onTableColumnResizePointerUp, onTableColumnPointerDown, onTableColumnPointerMove, commitTableColumnDrop,
    onTableColumnPointerUp, onTableSurfacePointerDown, columnDraggingKey, columnDraggingIndex,
    columnDropIndex, columnResizingKey,
  }
}
