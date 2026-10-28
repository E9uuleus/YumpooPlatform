import { workItemAssignees } from './workItemAssignees'
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch, type Ref } from 'vue'
import { ListProjectWorkItemFilterOptionsFieldEnum, type ListProjectWorkItemsRequest,
  type ProjectWorkItemListItem, type ProjectWorkItemFilterOption, type WorkItemGroupingField, type WorkItemGroupingOrder,
  type WorkItemTableGrouping } from '@yumpoo/api-client'
import type { WorkItemTableSource } from './workItemTableSource'
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
  source?: () => WorkItemTableSource
  items: Ref<ProjectWorkItemListItem[]>
  /** 本机分组偏好键：内嵌表在这里保存分组；项目表只从这里迁移旧版本留下的分组。 */
  preferenceKey: () => string
  /** 个人表格设置中的分组；undefined 表示尚未读取。 */
  stored?: () => WorkItemTableGrouping | null | undefined
  /** 提供时分组写入个人表格设置（项目表）；省略时写入本机，本机尚无分组时沿用 stored（内嵌表）。 */
  save?: (value: WorkItemTableGrouping) => void
  request: () => ListProjectWorkItemsRequest
  sources: () => GroupSources
  today: () => string
  ready: () => boolean
  restore: () => Promise<void>
  protectedIds?: () => Set<string>
}

/** 与 WorkItemTableSettingsService.MAX_GROUPING_KEYS 一致。 */
const MAX_SAVED_COLORS = 500

/** 比较时忽略颜色映射的键顺序：服务端 jsonb 会重排对象键。 */
function canonical(value: unknown): string | undefined {
  return JSON.stringify(value, (key, item: unknown) => key === 'colors' && item && typeof item === 'object'
    ? Object.fromEntries(Object.entries(item).sort(([left], [right]) => left < right ? -1 : 1)) : item)
}

/** 把服务端、本机或损坏的分组值规整为当前支持的字段与顺序。 */
function normalizeGrouping(value: unknown): WorkItemTableGrouping {
  const saved = (value && typeof value === 'object' ? value : {}) as Record<string, unknown>
  const field = groupingFields.find(option => option.value === saved.field)?.value ?? ''
  const orders = groupOrders(field)
  return {
    field: (field || null) as WorkItemGroupingField | null,
    order: (orders.find(option => option.value === saved.order) ?? orders[0]!).value as WorkItemGroupingOrder,
    showEmpty: saved.showEmpty === true,
    collapsed: Array.isArray(saved.collapsed) ? saved.collapsed.filter((key): key is string => typeof key === 'string') : [],
    colors: saved.colors && typeof saved.colors === 'object' && !Array.isArray(saved.colors)
      ? Object.fromEntries(Object.entries(saved.colors).filter((entry): entry is [string, string] => typeof entry[1] === 'string')) : {},
  }
}

export function useWorkItemGrouping(options: Options) {
  const field = ref<GroupField | ''>('')
  const order = ref<GroupOrder>('DEFAULT')
  const showEmpty = ref(false)
  const collapsed = ref(new Set<string>())
  /** Saved-collapsed groups opened for this visit only, such as to show an item just created; never saved. */
  const revealed = ref(new Set<string>())
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
  const isCollapsed = (key: string) => collapsed.value.has(collapseKey(key)) && !revealed.value.has(collapseKey(key))
  function page(key: string): GroupPage {
    return pages[key] ??= { items: [], nextCursor: null, loaded: false, loading: false, error: undefined }
  }
  /** 只保存当前有工作项的处理人分组颜色：项目成员可能多于服务端上限，其余分组按稳定散列重新分配。 */
  const savedColors = () => {
    const used = new Set(field.value === 'ASSIGNEE' ? facets.value.map(option => option.value) : [])
    return Object.fromEntries(Object.entries(colors).filter(([key]) => used.has(key)).slice(0, MAX_SAVED_COLORS))
  }
  const snapshot = (): WorkItemTableGrouping => ({ field: (field.value || null) as WorkItemGroupingField | null,
    order: order.value as WorkItemGroupingOrder, showEmpty: showEmpty.value, collapsed: [...collapsed.value], colors: savedColors() })
  function persist(): void {
    if (restoringPrefs) return
    const value = snapshot()
    // 个人表格设置读取完成前（加载中或读取失败）先存本机，读取后由 restorePrefs 上传或丢弃。
    if (options.save && options.stored?.() !== undefined) {
      if (canonical(value) !== canonical(options.stored?.())) options.save(value)
      return
    }
    try { localStorage.setItem(options.preferenceKey(), JSON.stringify(value)) } catch { /* 本地存储不可用时仍可分组。 */ }
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
      const label = field.value === 'ASSIGNEE' ? workItemAssignees(item).map(assignee => assignee.displayName).join('、') || '未分配'
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
        const result = await (options.source?.() ?? workItemsApi).listProjectWorkItemFilterOptions({ ...context, ...(cursor ? { cursor } : {}),
          field: (requestedField === 'ASSIGNEE' ? ListProjectWorkItemFilterOptionsFieldEnum.AssigneeSet : requestedField) as ListProjectWorkItemFilterOptionsFieldEnum, limit: 100 }, { signal })
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
          const result = await (options.source?.() ?? workItemsApi).listProjectWorkItems({ ...request, ...(next ? { cursor: next } : {}) }, { signal })
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
  function reveal(key: string): void {
    revealed.value = new Set(revealed.value).add(collapseKey(key))
  }
  function toggle(key: string): void {
    const value = collapseKey(key)
    if (revealed.value.has(value)) {
      // Closing a revealed group returns it to the saved collapsed state, so nothing needs saving.
      const next = new Set(revealed.value)
      next.delete(value)
      revealed.value = next
      return
    }
    const next = new Set(collapsed.value)
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
  function readPrefs(value: unknown): void {
    const saved = normalizeGrouping(value)
    stop()
    restoringPrefs = true
    field.value = (saved.field ?? '') as GroupField | ''
    order.value = saved.order as GroupOrder
    showEmpty.value = saved.showEmpty
    collapsed.value = new Set(saved.collapsed)
    Object.keys(colors).forEach(key => delete colors[key])
    Object.assign(colors, saved.colors)
    facets.value = []
    Object.keys(pages).forEach(key => delete pages[key])
    restoringPrefs = false
  }
  function localPrefs(): unknown {
    try { return JSON.parse(localStorage.getItem(options.preferenceKey()) ?? 'null') ?? undefined } catch { /* 损坏的偏好恢复默认值。 */ return {} }
  }
  /**
   * 项目表以个人表格设置为准：设置为空时上传本机分组（旧版本留下的，或设置读取失败期间保存的），设置已有分组时丢弃本机分组；
   * 两种情况都删除本机键。内嵌表优先本机分组。
   */
  function restorePrefs(keyChanged: boolean): void {
    const stored = options.stored?.()
    const local = localPrefs()
    const saved = options.save ? stored ?? local : local ?? stored
    const changed = keyChanged || canonical(normalizeGrouping(saved)) !== canonical(snapshot())
    const wasActive = active.value
    if (changed) readPrefs(saved)
    if (options.save && stored !== undefined && local !== undefined) {
      if (stored === null) options.save(snapshot())
      try { localStorage.removeItem(options.preferenceKey()) } catch { /* 本地存储不可用时只上传。 */ }
    }
    // 切换项目时由页面加载负责刷新；同一项目内设置晚到或被其他窗口改动时在这里重新分组。
    if (!changed || keyChanged || !options.ready()) return
    if (active.value) void refresh()
    else if (wasActive) void options.restore()
  }
  watch(() => [options.preferenceKey(), canonical(options.stored?.())] as const,
    ([key], previous) => restorePrefs(key !== previous?.[0]), { immediate: true })
  watch([order, showEmpty], () => { if (!restoringPrefs) syncItems() })
  watch(() => JSON.stringify(options.sources()), () => { if (active.value && !restoringPrefs) syncItems() })
  watch(options.today, () => { if (field.value === 'DUE_DATE') void refresh() })
  onBeforeUnmount(() => { stopped = true; stop() })
  const changed = async () => { await nextTick(); await refresh() }
  return { active, field, order, showEmpty, collapsed, groups, countsReady, loading, error,
    page, keyOf, isCollapsed, reveal, toggle, setField, refresh, load, stop, changed, edgeItems, replaceOrder }
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
