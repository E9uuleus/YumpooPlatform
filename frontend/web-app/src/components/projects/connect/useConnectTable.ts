import { computed, nextTick, provide, ref, watch, type MaybeRefOrGetter, toValue } from 'vue'
import { ProjectActorAccess, ProjectLifecycle, type ConnectColumn, type ProjectDetail, type ProjectWorkItemListItem, type WorkItemConnection } from '@yumpoo/api-client'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from '../../../composables/useSession'
import { connectColumnDefaultWidth, connectColumnKey, connectColumnKeys, connectColumnMinWidth, type ConnectColumnKey, type ConnectTableColumn } from './connectColumnKeys'
import { connectColumnPrefsKey, readConnectColumnPrefs, saveConnectColumnPrefs, type ConnectColumnPrefs } from './connectColumnPrefs'
import { connectColumnsContext, useConnectColumns } from './useConnectColumns'

export function useConnectTable(options: { projectId: MaybeRefOrGetter<string>; enabled: MaybeRefOrGetter<boolean>;
  project: MaybeRefOrGetter<ProjectDetail | undefined>; rows: () => ProjectWorkItemListItem[]; table: () => HTMLElement | undefined }) {
  const session = useSession(), route = useRoute(), router = useRouter()
  const prefs = ref<ConnectColumnPrefs>({ hidden: [], widths: {} })
  const prefKey = computed(() => {
    const authentication = session.authentication.value
    return authentication ? connectColumnPrefsKey(authentication.company.id, authentication.user.id, toValue(options.projectId)) : ''
  })
  const context = useConnectColumns(options.projectId, options.enabled, prefKey)
  provide(connectColumnsContext, context)
  const catalog = context.catalog
  const keys = computed(() => connectColumnKeys(catalog.value))
  const width = (key: ConnectColumnKey) => prefs.value.widths[key] ?? connectColumnDefaultWidth(key)
  const columns = computed<ConnectTableColumn[]>(() => !toValue(options.enabled) ? [] : [
    ...(catalog.value?.items ?? []).map(column => ({ key: connectColumnKey(column.id), label: column.name, kind: 'connect' as const, column,
      width: width(connectColumnKey(column.id)), minWidth: 140 })),
    ...(catalog.value?.incomingAvailable ? [{ key: 'connect-incoming' as const, label: '被连接', kind: 'incoming' as const, width: width('connect-incoming'), minWidth: 160 }] : []),
  ])
  const hidden = computed(() => new Set(prefs.value.hidden))
  const visibleColumns = computed(() => columns.value.filter(column => !hidden.value.has(column.key)))
  const readOnly = computed(() => !toValue(options.project) || toValue(options.project)?.lifecycle !== ProjectLifecycle.Active
    || ![ProjectActorAccess.Owner, ProjectActorAccess.Member].includes(toValue(options.project)!.actorAccess))
  const canManage = computed(() => !readOnly.value && Boolean(catalog.value?.canManage))
  const canDelete = computed(() => !readOnly.value && Boolean(catalog.value?.canDelete))
  const columnDialogOpen = ref(false), editingColumn = ref<ConnectColumn>(), deletingColumn = ref<ConnectColumn>()
  const cardOpen = ref(false), selectedConnection = ref<WorkItemConnection>(), perspective = ref<'source' | 'target'>('source')
  const versions = new Map<string, number>()

  watch([prefKey, () => keys.value.join('|')], () => { prefs.value = readConnectColumnPrefs(prefKey.value, keys.value) }, { immediate: true })
  watch(() => [toValue(options.projectId), toValue(options.enabled), prefKey.value], () => {
    versions.clear(); columnDialogOpen.value = false; deletingColumn.value = undefined; cardOpen.value = false; selectedConnection.value = undefined
    if (toValue(options.enabled)) void context.loadCatalog()
  }, { immediate: true, flush: 'sync' })
  watch(() => [toValue(options.project)?.actorAccess, toValue(options.project)?.lifecycle], (next, previous) => {
    if (previous[0] && next[0] && next.join() !== previous.join()) { void context.loadCatalog(); void context.refreshCells(options.rows().map(item => item.id)) }
  })
  watch([() => toValue(options.enabled), () => options.rows().map(item => `${item.id}:${item.rowVersion}`).join('|'), () => Boolean(catalog.value)], () => {
    if (!toValue(options.enabled) || !catalog.value) return
    const items = options.rows()
    const changed = items.filter(item => versions.has(item.id) && versions.get(item.id) !== item.rowVersion).map(item => item.id)
    items.forEach(item => versions.set(item.id, item.rowVersion))
    if (changed.length) void context.refreshCells(changed)
    void context.ensureCells(items.map(item => item.id))
  }, { immediate: true })
  function persist() { if (prefKey.value) saveConnectColumnPrefs(prefKey.value, prefs.value) }
  function toggleColumn(key: ConnectColumnKey, visible: boolean) {
    const next = new Set(prefs.value.hidden)
    if (visible) next.delete(key); else next.add(key)
    prefs.value = { ...prefs.value, hidden: [...next] }; persist()
  }
  function resizeColumn(key: ConnectColumnKey, value: number, save = true) {
    if (!keys.value.includes(key) || !Number.isFinite(value)) return
    prefs.value = { ...prefs.value, widths: { ...prefs.value.widths, [key]: Math.max(connectColumnMinWidth(key), Math.round(value)) } }
    if (save) persist()
  }
  async function reveal(column: ConnectColumn | string) {
    const key = connectColumnKey(typeof column === 'string' ? column : column.id)
    if (!toValue(options.enabled) || !keys.value.includes(key)) return false
    toggleColumn(key, true)
    await nextTick()
    options.table()?.querySelector<HTMLElement>(`[data-connect-key="${CSS.escape(key)}"]`)?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
    return true
  }
  watch([() => route.query.connectColumn, () => keys.value.join('|')], async ([id], _, onCleanup) => {
    if (typeof id !== 'string') return
    let stale = false
    onCleanup(() => { stale = true })
    if (!await reveal(id) || stale || route.query.connectColumn !== id) return
    const query = { ...route.query }
    delete query.connectColumn
    await router.replace({ path: route.path, query, hash: route.hash })
  }, { immediate: true })
  function edit(column?: ConnectColumn) { if (canManage.value) { editingColumn.value = column; columnDialogOpen.value = true } }
  function requestDelete(column: ConnectColumn) { if (canDelete.value) deletingColumn.value = column }
  function openCard(connection: WorkItemConnection, side: 'source' | 'target' = 'source') { selectedConnection.value = connection; perspective.value = side; cardOpen.value = true }
  function refreshConnection(connection: WorkItemConnection) {
    return context.refreshCells([connection.source, connection.target].filter(card => card.projectId === toValue(options.projectId)).map(card => card.workItemId))
  }
  async function reloadConnections() {
    const identity = prefKey.value
    await context.loadCatalog()
    if (identity === prefKey.value) await context.refreshCells(options.rows().map(item => item.id))
  }
  return { ...context, columns, visibleColumns, hidden, readOnly, canManage, canDelete, columnDialogOpen, editingColumn, deletingColumn,
    selectedConnection, perspective, cardOpen, edit, requestDelete, openCard, width, toggleColumn, resizeColumn, reveal, refreshConnection, reloadConnections }
}
