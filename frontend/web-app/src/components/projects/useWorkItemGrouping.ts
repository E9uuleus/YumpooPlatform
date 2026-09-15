import { computed, nextTick, onBeforeUnmount, reactive, ref, watch, type Ref } from 'vue'
import { ListProjectWorkItemFilterOptionsFieldEnum, type ListProjectWorkItemsRequest,
  type ProjectWorkItemListItem, type ProjectWorkItemFilterOption } from '@yumpoo/api-client'
import { workItemsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import { buildWorkItemGroups, EMPTY_GROUP, groupingFields, groupListRequest, groupOrders,
  itemGroupKey, type GroupField, type GroupOrder, type GroupSources, type WorkItemGroup } from './workItemGrouping'

interface GroupPage {
  items: ProjectWorkItemListItem[]
  nextCursor: string | null
  loaded: boolean
  loading: boolean
  error: ApiProblem | undefined
}
interface Options {
  items: Ref<ProjectWorkItemListItem[]>
  preferenceKey: () => string
  request: () => ListProjectWorkItemsRequest
  sources: () => GroupSources
  today: () => string
  ready: () => boolean
  restore: () => Promise<void>
  protectedIds?: () => Set<string>
}

export function useWorkItemGrouping(options: Options) {
  const field = ref<GroupField | ''>('')
  const order = ref<GroupOrder>('DEFAULT')
  const showEmpty = ref(false)
  const collapsed = ref(new Set<string>())
  const colors: Record<string, string> = {}
  const facets = ref<ProjectWorkItemFilterOption[]>([])
  const countsReady = ref(false)
  const loading = ref(false)
  const error = ref<ApiProblem>()
  const pages = reactive<Record<string, GroupPage>>({})
  let revision = 0
  let controller = new AbortController()
  let base: ListProjectWorkItemsRequest
  let stopped = false
  let restoringPrefs = false
  const pending = new Map<string, Promise<void>>()
  const groups = computed(() => field.value ? buildWorkItemGroups(field.value, order.value, showEmpty.value,
    facets.value, options.sources(), options.today(), colors) : [])
  const active = computed(() => Boolean(field.value))
  const keyOf = (item: ProjectWorkItemListItem) => field.value ? itemGroupKey(item, field.value, options.today()) : ''
  const collapseKey = (key: string) => `${field.value}:${key}`
  const isCollapsed = (key: string) => collapsed.value.has(collapseKey(key))
  function page(key: string): GroupPage {
    return pages[key] ??= { items: [], nextCursor: null, loaded: false, loading: false, error: undefined }
  }
  function persist(): void {
    if (restoringPrefs) return
    try { localStorage.setItem(options.preferenceKey(), JSON.stringify({ field: field.value, order: order.value,
      showEmpty: showEmpty.value, collapsed: [...collapsed.value], colors })) } catch { /* 本地存储不可用时仍可分组。 */ }
  }
  function syncItems(): void {
    if (!active.value || stopped) return
    const items = groups.value.flatMap(group => page(group.key).items)
    options.items.value = [...new Map(items.map(item => [item.id, item])).values()]
    persist()
  }
  function stop(): void {
    revision++
    controller.abort()
    pending.clear()
    loading.value = false
  }
  function seed(items: ProjectWorkItemListItem[]): void {
    Object.keys(pages).forEach(key => delete pages[key])
    const counts = new Map<string, ProjectWorkItemFilterOption>()
    for (const item of items) {
      const key = keyOf(item)
      page(key).items.push(item)
      const value = field.value === 'DUE_DATE' ? item.dueDate?.toISOString().slice(0, 10) ?? EMPTY_GROUP : key
      const label = field.value === 'ASSIGNEE' ? item.assigneeDisplayName ?? '未分配'
        : field.value === 'CONTENT' ? item.contentName : value
      const option = counts.get(value) ?? { value, label, count: 0 }
      option.count++
      counts.set(value, option)
    }
    facets.value = [...counts.values()]
  }
  async function refresh(): Promise<void> {
    if (!active.value || !options.ready() || stopped) return
    const warm = [...options.items.value]
    stop()
    controller = new AbortController()
    const signal = controller.signal
    const current = revision
    const requestedField = field.value as GroupField
    base = options.request()
    countsReady.value = false
    loading.value = true
    error.value = undefined
    seed(warm)
    syncItems()
    const loaded: ProjectWorkItemFilterOption[] = []
    const cursors = new Set<string>()
    let cursor: string | null = null
    try {
      do {
        const { view: _view, emptyField: _empty, ...context } = base
        void _view; void _empty
        const result = await workItemsApi.listProjectWorkItemFilterOptions({ ...context, ...(cursor ? { cursor } : {}),
          field: requestedField as ListProjectWorkItemFilterOptionsFieldEnum, limit: 100 }, { signal })
        if (current !== revision || stopped) return
        loaded.push(...result.items)
        cursor = result.nextCursor
        if (cursor && cursors.has(cursor)) throw new Error('分组游标重复，请重试。')
        if (cursor) cursors.add(cursor)
      } while (cursor)
      facets.value = loaded
      countsReady.value = true
      syncItems()
    } catch (reason) {
      const failure = await toApiProblem(reason)
      if (current === revision && !signal.aborted) error.value = failure
    } finally {
      if (current === revision) loading.value = false
    }
  }
  async function load(key: string, more = true): Promise<void> {
    if (!active.value || !countsReady.value || stopped) return
    const group = groups.value.find(item => item.key === key)
    if (!group) return
    const state = page(key)
    if (pending.has(key)) return pending.get(key)
    if (more && state.loaded && !state.nextCursor) return
    const current = revision
    const signal = controller.signal
    const request = groupListRequest(base, field.value as GroupField, group)
    if (!request || group.count === 0) {
      state.items = []; state.loaded = true; state.nextCursor = null
      syncItems()
      return
    }
    const append = more && state.loaded
    const cursor = append ? state.nextCursor : null
    state.loading = true
    state.error = undefined
    const task = (async () => {
      try {
        // Warm rows may already contain an open editor beyond the first page.
        const protectedIds = options.protectedIds?.() ?? new Set<string>()
        const retained = new Set(append ? [] : state.items.filter(item => protectedIds.has(item.id)).map(item => item.id))
        const items = new Map<string, ProjectWorkItemListItem>(append ? state.items.map(item => [item.id, item]) : [])
        let next = cursor
        const seen = new Set<string>()
        do {
          const result = await workItemsApi.listProjectWorkItems({ ...request, ...(next ? { cursor: next } : {}) }, { signal })
          if (current !== revision || stopped) return
          result.items.forEach(item => { items.set(item.id, item); retained.delete(item.id) })
          next = result.nextCursor
          if (next && (next === cursor || seen.has(next))) throw new Error('工作项游标重复，请重试。')
          if (next) seen.add(next)
        } while (next && retained.size)
        state.items = [...items.values()]
        state.nextCursor = next
        state.loaded = true
        syncItems()
      } catch (reason) {
        if (current !== revision || signal.aborted) return
        const failure = await toApiProblem(reason)
        if (current !== revision || signal.aborted) return
        if (JSON.stringify(failure).includes('TIME_TRACKING_CURSOR_EXPIRED')) {
          state.loaded = false
          state.nextCursor = null
          state.error = localProblem('计时记录已变化，请重新加载此分组。')
        } else state.error = failure
      } finally {
        if (current === revision) { state.loading = false; pending.delete(key) }
      }
    })()
    pending.set(key, task)
    return task
  }
  async function edgeItems(item: ProjectWorkItemListItem, edge: 'top' | 'bottom'): Promise<ProjectWorkItemListItem[]> {
    const current = revision
    const key = keyOf(item)
    await load(key)
    const state = page(key)
    while (current === revision && edge === 'bottom' && state.nextCursor && !state.error) await load(key)
    if (current !== revision || !countsReady.value) throw new Error('分组已变化，请重新调整顺序。')
    if (state.error) throw new Error('分组加载失败，请重试后调整顺序。')
    return state.items
  }
  function replaceOrder(key: string, items: ProjectWorkItemListItem[]): void {
    page(key).items = items
    syncItems()
  }
  function toggle(key: string): void {
    const next = new Set(collapsed.value)
    const value = collapseKey(key)
    if (next.has(value)) next.delete(value); else next.add(value)
    collapsed.value = next
    persist()
  }
  async function setField(value: GroupField | ''): Promise<void> {
    if (value === field.value) return
    stop()
    field.value = value
    order.value = groupOrders(value)[0]!.value
    persist()
    if (value) await refresh()
    else if (options.ready()) await options.restore()
  }
  function readPrefs(): void {
    stop()
    restoringPrefs = true
    field.value = ''; order.value = 'DEFAULT'; showEmpty.value = false
    collapsed.value = new Set()
    Object.keys(colors).forEach(key => delete colors[key])
    facets.value = []
    Object.keys(pages).forEach(key => delete pages[key])
    try {
      const saved = JSON.parse(localStorage.getItem(options.preferenceKey()) ?? '{}') as Record<string, unknown>
      if (groupingFields.some(option => option.value === saved.field)) field.value = saved.field as GroupField
      order.value = groupOrders(field.value).find(option => option.value === saved.order)?.value ?? groupOrders(field.value)[0]!.value
      showEmpty.value = saved.showEmpty === true
      if (Array.isArray(saved.collapsed)) collapsed.value = new Set(saved.collapsed.filter((v): v is string => typeof v === 'string'))
      if (saved.colors && typeof saved.colors === 'object' && !Array.isArray(saved.colors)) {
        for (const [key, value] of Object.entries(saved.colors)) if (typeof value === 'string') colors[key] = value
      }
    } catch { /* 损坏的偏好恢复默认值。 */ }
    restoringPrefs = false
  }
  watch(options.preferenceKey, readPrefs, { immediate: true, flush: 'sync' })
  watch([order, showEmpty], () => { if (!restoringPrefs) syncItems() })
  watch(() => JSON.stringify(options.sources()), () => { if (active.value && !restoringPrefs) syncItems() })
  watch(options.today, () => { if (field.value === 'DUE_DATE') void refresh() })
  onBeforeUnmount(() => { stopped = true; stop() })
  const changed = async () => { await nextTick(); await refresh() }
  return { active, field, order, showEmpty, collapsed, groups, countsReady, loading, error,
    page, keyOf, isCollapsed, toggle, setField, refresh, load, stop, changed, edgeItems, replaceOrder }
}

export interface WorkItemGroupDisplayRow {
  id: string
  groupRowKind: 'heading' | 'columns' | 'load' | 'spacer' | 'subitems' | 'add'
  group: WorkItemGroup
  parent?: ProjectWorkItemListItem
}
export function isGroupDisplayRow(row: unknown): row is WorkItemGroupDisplayRow {
  return Boolean(row && typeof row === 'object' && 'groupRowKind' in row)
}
