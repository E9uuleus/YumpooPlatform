import { nextTick, onBeforeUnmount, ref, type CSSProperties, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import { readCsrfToken, type ProjectWorkItemListItem, type WorkItemDetail } from '@yumpoo/api-client'
import { workItemsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import type { useWorkItemGrouping } from './useWorkItemGrouping'
import type { useWorkItemInlineCreate } from './useWorkItemInlineCreate'

interface RowDragOptions {
  tableRef: Ref<{ $el: HTMLElement } | undefined>
  tableRowHeight: Readonly<Ref<number>>
  tableItems: Ref<ProjectWorkItemListItem[]>
  tableNextCursor: Ref<string | null>
  tableLoading: Ref<boolean>
  tableSorting: Ref<boolean>
  grouped: Readonly<Ref<boolean>>
  groupCountsReady: Readonly<Ref<boolean>>
  grouping: Pick<ReturnType<typeof useWorkItemGrouping>, 'keyOf' | 'page' | 'load' | 'replaceOrder'>
  inlineDraft: ReturnType<typeof useWorkItemInlineCreate>['draft']
  hasExplicitSort: Readonly<Ref<boolean>>
  sortRules: Ref<{ field: string; direction: 'ASC' | 'DESC' }[]>
  projectId: Readonly<Ref<string>>
  error: Ref<ApiProblem | undefined>
  resolveTableScrollElement: () => HTMLElement | undefined
  loadTable: (cursor: string | null, append: boolean) => Promise<void>
  syncUrl: () => Promise<void>
  replaceLightItem: (id: string, detail: WorkItemDetail) => void
  TABLE_DRAG_TILT_DEGREES: number
  TABLE_DRAG_POINTER_THRESHOLD: number
}

export function useTableRowDrag({
  tableRef, tableRowHeight, tableItems, tableNextCursor, tableLoading, tableSorting, grouped, groupCountsReady, grouping,
  inlineDraft, hasExplicitSort, sortRules, projectId, error, resolveTableScrollElement, loadTable, syncUrl, replaceLightItem,
  TABLE_DRAG_TILT_DEGREES, TABLE_DRAG_POINTER_THRESHOLD
}: RowDragOptions) {
  const tableDragging = ref<ProjectWorkItemListItem>()
  const tableDraggingIndex = ref<number>(-1)
  const tableDropIndex = ref<number>()
  let tableDragPreview: HTMLElement | undefined

  let tableDragPointerOffset = { x: 0, y: 0 }

  let tablePointerCandidate: {
    pointerId: number
    row: HTMLElement
    item: ProjectWorkItemListItem
    index: number
    startX: number
    startY: number
  } | undefined
  let suppressTableClick = false
  let suppressTableClickTimer: number | undefined

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
    const height = Math.max(rect.height, source.offsetHeight, tableRowHeight.value)
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

  function tableRowDragStyle({ rowIndex }: { row: ProjectWorkItemListItem; rowIndex: number }): CSSProperties {
    if (!tableDragging.value || tableDraggingIndex.value < 0 || tableDropIndex.value === undefined) {
      return {}
    }
    const from = tableDraggingIndex.value
    const to = tableDropIndex.value
    let offset = 0

    if (rowIndex === from) {
      return { opacity: 0, pointerEvents: 'none' }
    } else if (from < to && rowIndex > from && rowIndex < to) {
      offset = -tableRowHeight.value
    } else if (from > to && rowIndex >= to && rowIndex < from) {
      offset = tableRowHeight.value
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
          Math.round(relativeY / tableRowHeight.value)))
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

  onBeforeUnmount(() => { if (suppressTableClickTimer) window.clearTimeout(suppressTableClickTimer) })

  return {
    removeTableDragPreview, moveTableDragPreview, createTableDragPreview, resetTableDragState,
    tableRowDragStyle, clearTablePointerTracking, suppressClickAfterTableDrag, onTableClickCapture,
    updateTableDropTarget, onTablePointerDown, onTablePointerMove, onTablePointerUp,
    onTablePointerCancel, commitTableDrop, tableDragging, tableDraggingIndex,
    tableDropIndex,
  }
}
