import { nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import type { BuiltInColumnKey, MovableColumnKey } from './useTableColumnDrag'
import type { ConnectColumnKey } from './connect/connectColumnKeys'
import { COLLAPSED_COLUMN_WIDTH } from './workItemColumnMenu'

export const pageColumns: { key: BuiltInColumnKey; label: string; width: number; min: number }[] = [
  { key: 'title', label: '工作项名称', width: 320, min: 220 },
  { key: 'assignee', label: '处理人', width: 90, min: 72 },
  { key: 'status', label: '状态', width: 96, min: 96 },
  { key: 'priority', label: '优先级', width: 90, min: 90 },
  { key: 'content', label: '工作项类别', width: 110, min: 110 },
  { key: 'dueDate', label: '截止日期', width: 140, min: 112 },
  { key: 'timeTracking', label: '时长追踪', width: 140, min: 120 },
  { key: 'updatedAt', label: '最后更新时间', width: 170, min: 135 },
]

export function useWorkItemPagePresentation(scope: () => string) {
  const widths = reactive(Object.fromEntries(pageColumns.map(c => [c.key, c.width])) as Record<BuiltInColumnKey, number>)
  const defaults = pageColumns.filter(c => c.key !== 'title').map(c => c.key as MovableColumnKey)
  const order = ref([...defaults]), subitemOrder = ref([...defaults])
  const hidden = ref(new Set<BuiltInColumnKey>()), collapsed = ref(new Set<BuiltInColumnKey | ConnectColumnKey>())
  const pinned = ref(0), subitemPinned = ref(0)
  const scrollbar = ref<HTMLElement>(), extent = ref(0), overflow = ref(false)
  const position = ref(0), viewport = ref(0)
  const tables = new Set<HTMLElement>()
  const expected = new WeakMap<HTMLElement, number>()
  const key = () => `yumpoo:my-work:layout:v1:${scope()}`
  function normalizeOrder(value: unknown): MovableColumnKey[] {
    const saved = Array.isArray(value) ? value.filter((v): v is MovableColumnKey => defaults.includes(v)) : []
    return [...new Set([...saved, ...defaults])]
  }
  watch(scope, () => {
    let saved: Record<string, unknown> = {}
    try { saved = JSON.parse(localStorage.getItem(key()) ?? '{}') ?? {} } catch { /* 使用默认布局。 */ }
    pageColumns.forEach(c => {
      const value = (saved.widths as Record<string, unknown> | undefined)?.[c.key]
      widths[c.key] = typeof value === 'number' && Number.isFinite(value) ? Math.max(c.min, value) : c.width
    })
    order.value = normalizeOrder(saved.order); subitemOrder.value = normalizeOrder(saved.subitemOrder)
    hidden.value = new Set(Array.isArray(saved.hidden) ? saved.hidden.filter(v => defaults.includes(v)) : [])
    collapsed.value = new Set(Array.isArray(saved.collapsed) ? saved.collapsed.filter(v => defaults.includes(v)) : [])
    pinned.value = typeof saved.pinned === 'number' ? Math.max(0, Math.min(defaults.length, Math.floor(saved.pinned))) : 0
    subitemPinned.value = typeof saved.subitemPinned === 'number' ? Math.max(0, Math.min(defaults.length, Math.floor(saved.subitemPinned))) : 0
    position.value = 0
  }, { immediate: true, flush: 'sync' })
  watch([widths, order, subitemOrder, hidden, collapsed, pinned, subitemPinned], () => {
    try { localStorage.setItem(key(), JSON.stringify({ widths, order: order.value, subitemOrder: subitemOrder.value,
      hidden: [...hidden.value], collapsed: [...collapsed.value], pinned: pinned.value, subitemPinned: subitemPinned.value })) } catch { /* 当前页面仍可调整布局。 */ }
    void nextTick(sync)
  }, { deep: true })
  function apply(element: HTMLElement, left: number) {
    const value = Math.min(left, Math.max(0, element.scrollWidth - element.clientWidth))
    expected.set(element, value)
    if (Math.abs(element.scrollLeft - value) > 0.5) element.scrollLeft = value
  }
  function sync() {
    const active = [...tables].filter(t => t.clientWidth > 0)
    viewport.value = active.length ? Math.min(...active.map(t => t.clientWidth)) : 0
    const maximum = Math.max(0, ...active.map(t => t.scrollWidth - t.clientWidth))
    overflow.value = maximum > 1
    extent.value = (scrollbar.value?.clientWidth ?? 0) + maximum
    if (active.length) position.value = Math.min(position.value, maximum)
    active.forEach(t => apply(t, position.value))
    // 滚动范围由 Vue 更新 DOM 后才能使用，新挂载或调整列宽时保留当前位置。
    void nextTick(() => { if (scrollbar.value) apply(scrollbar.value, position.value) })
  }
  function onScroll(event: Event) {
    const element = event.currentTarget as HTMLElement
    if (Math.abs((expected.get(element) ?? -1) - element.scrollLeft) < 0.5) return
    position.value = element.scrollLeft
    sync()
  }
  function register(element: HTMLElement) {
    tables.add(element)
    element.addEventListener('scroll', onScroll, { passive: true })
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(sync)
    observer?.observe(element)
    if (element.firstElementChild) observer?.observe(element.firstElementChild)
    void nextTick(sync)
    return () => { observer?.disconnect(); element.removeEventListener('scroll', onScroll); tables.delete(element); void nextTick(sync) }
  }
  watch(scrollbar, () => void nextTick(sync))
  onBeforeUnmount(() => tables.clear())
  function toggleColumn(key: BuiltInColumnKey) {
    if (key === 'title') return
    const next = new Set(hidden.value)
    if (!next.delete(key)) next.add(key)
    hidden.value = next
  }
  function pinColumns(count: number, subitems = false) {
    ;(subitems ? subitemPinned : pinned).value = count
  }
  function canPin(count: number, subitems = false) {
    const keys = (subitems ? subitemOrder : order).value.filter(key => !hidden.value.has(key))
    const width = widths.title + keys.slice(0, count).reduce((sum, key) => sum + (collapsed.value.has(key) ? COLLAPSED_COLUMN_WIDTH : widths[key]), 0)
    return !viewport.value || width <= viewport.value - (subitems ? 72 : 33) - 200
  }
  return { toggleColumn, pinColumns, canPin, widths, order, subitemOrder, hidden, collapsed, pinned, subitemPinned, scrollbar, extent, overflow, position, register, sync, onScroll }
}
export type WorkItemPagePresentation = ReturnType<typeof useWorkItemPagePresentation>
