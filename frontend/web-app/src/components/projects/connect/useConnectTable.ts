import { computed, nextTick, provide, ref, watch, type MaybeRefOrGetter, toValue } from 'vue'
import { ProjectActorAccess, ProjectLifecycle, type ConnectColumn, type ConnectTargetProject, type ProjectDetail,
  type ProjectWorkItemListItem, type WorkItemConnection } from '@yumpoo/api-client'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from '../../../composables/useSession'
import { CONNECT_DRAFT_KEY, connectColumnAutoName, connectColumnDefaultWidth, connectColumnKey, connectColumnKeys, connectColumnMinWidth,
  reverseColumnKey, reverseColumnLabels, type ConnectColumnKey, type ConnectTableColumn } from './connectColumnKeys'
import { connectColumnPrefsKey, readConnectColumnPrefs, saveConnectColumnPrefs, type ConnectColumnPrefs } from './connectColumnPrefs'
import { connectColumnsContext, useConnectColumns } from './useConnectColumns'

export type ConnectSetupMode = 'create' | 'edit'
export type ConnectTargetChoice = Pick<ConnectTargetProject, 'id' | 'name' | 'code'>
const CELEBRATION_MS = 1400

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
  /** Unsaved column shown while the user picks projects; it never reaches the server or the preferences. */
  const draft = ref(false)
  const setup = ref<{ key: ConnectColumnKey; mode: ConnectSetupMode }>()
  const celebrated = ref<ConnectColumnKey>()
  let celebration: ReturnType<typeof setTimeout> | undefined
  const columns = computed<ConnectTableColumn[]>(() => {
    if (!toValue(options.enabled)) return []
    const labels = reverseColumnLabels(catalog.value?.incomingColumns ?? [])
    return [
      ...(catalog.value?.items ?? []).map(column => {
        const key = connectColumnKey(column.id)
        return { key, label: column.name, kind: 'connect' as const, column, width: width(key), minWidth: connectColumnMinWidth(key) }
      }),
      ...(catalog.value?.incomingColumns ?? []).map(reverse => {
        const key = reverseColumnKey(reverse.columnId)
        return { key, label: labels.get(reverse.columnId) ?? reverse.projectName, kind: 'reverse' as const, reverse, width: width(key),
          minWidth: connectColumnMinWidth(key) }
      }),
    ]
  })
  const hidden = computed(() => new Set(prefs.value.hidden))
  const visibleColumns = computed<ConnectTableColumn[]>(() => {
    const shown = columns.value.filter(column => !hidden.value.has(column.key))
    if (!draft.value || !toValue(options.enabled)) return shown
    // The new column lands after this project's own columns, so the draft takes that slot.
    const reverseAt = shown.findIndex(column => column.kind === 'reverse')
    const placeholder: ConnectTableColumn = { key: CONNECT_DRAFT_KEY, label: '新连接', kind: 'draft',
      width: connectColumnDefaultWidth(CONNECT_DRAFT_KEY), minWidth: connectColumnMinWidth(CONNECT_DRAFT_KEY) }
    return reverseAt < 0 ? [...shown, placeholder] : [...shown.slice(0, reverseAt), placeholder, ...shown.slice(reverseAt)]
  })
  const readOnly = computed(() => !toValue(options.project) || toValue(options.project)?.lifecycle !== ProjectLifecycle.Active
    || ![ProjectActorAccess.Owner, ProjectActorAccess.Member].includes(toValue(options.project)!.actorAccess))
  const canManage = computed(() => !readOnly.value && Boolean(catalog.value?.canManage))
  const canDelete = computed(() => !readOnly.value && Boolean(catalog.value?.canDelete))
  const projectName = computed(() => toValue(options.project)?.name ?? '本项目')
  const columnNames = computed(() => catalog.value?.items.map(column => column.name) ?? [])
  const deletingColumn = ref<ConnectColumn>()
  const cardOpen = ref(false), selectedConnection = ref<WorkItemConnection>(), perspective = ref<'source' | 'target'>('source')
  const versions = new Map<string, number>()

  watch([prefKey, () => keys.value.join('|')], () => { prefs.value = readConnectColumnPrefs(prefKey.value, keys.value) }, { immediate: true })
  watch(() => [toValue(options.projectId), toValue(options.enabled), prefKey.value], () => {
    versions.clear(); closeSetup(); deletingColumn.value = undefined; cardOpen.value = false; selectedConnection.value = undefined
    if (toValue(options.enabled)) void context.loadCatalog()
  }, { immediate: true, flush: 'sync' })
  watch(() => [toValue(options.project)?.actorAccess, toValue(options.project)?.lifecycle], (next, previous) => {
    if (previous[0] && next[0] && next.join() !== previous.join()) { void context.loadCatalog(); void context.refreshCells(options.rows().map(item => item.id)) }
  })
  watch(canManage, allowed => { if (!allowed) closeSetup() })
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
    if (key === CONNECT_DRAFT_KEY) { if (!visible) closeSetup(); return }
    const next = new Set(prefs.value.hidden)
    if (visible) next.delete(key); else next.add(key)
    prefs.value = { ...prefs.value, hidden: [...next] }; persist()
  }
  function resizeColumn(key: ConnectColumnKey, value: number, save = true) {
    if (!keys.value.includes(key) || !Number.isFinite(value)) return
    prefs.value = { ...prefs.value, widths: { ...prefs.value.widths, [key]: Math.max(connectColumnMinWidth(key), Math.round(value)) } }
    if (save) persist()
  }
  /** Brings a connect column into view; el-table scrolls its body and mirrors the header, so scroll the body wrapper. */
  async function scrollTo(key: ConnectColumnKey) {
    await nextTick()
    const root = options.table()
    const target = root?.querySelector<HTMLElement>(`[data-connect-key="${CSS.escape(key)}"]`)
    const cell = target?.closest<HTMLElement>('th, td'), body = root?.querySelector<HTMLElement>('.el-table__body-wrapper .el-scrollbar__wrap')
    if (!target) return
    if (!cell || !body) { target.scrollIntoView({ block: 'nearest', inline: 'nearest' }); return }
    const margin = 48, left = cell.offsetLeft, right = left + cell.offsetWidth
    if (right > body.scrollLeft + body.clientWidth) body.scrollLeft = right - body.clientWidth + margin
    else if (left < body.scrollLeft) body.scrollLeft = Math.max(0, left - margin)
    cell.closest<HTMLElement>('.el-table__header-wrapper')?.scrollIntoView({ block: 'nearest' })
  }
  async function reveal(column: ConnectColumn | string) {
    const key = connectColumnKey(typeof column === 'string' ? column : column.id)
    if (!toValue(options.enabled) || !keys.value.includes(key)) return false
    toggleColumn(key, true)
    await scrollTo(key)
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
  function startDraft() {
    if (!canManage.value || !toValue(options.enabled)) return
    draft.value = true
    setup.value = { key: CONNECT_DRAFT_KEY, mode: 'create' }
    void scrollTo(CONNECT_DRAFT_KEY)
  }
  function openSettings(column: ConnectColumn) {
    if (!canManage.value) return
    draft.value = false
    setup.value = { key: connectColumnKey(column.id), mode: 'edit' }
  }
  function closeSetup() { setup.value = undefined; draft.value = false }
  function celebrate(key: ConnectColumnKey) {
    clearTimeout(celebration)
    celebrated.value = key
    celebration = setTimeout(() => { if (celebrated.value === key) celebrated.value = undefined }, CELEBRATION_MS)
  }
  /** Creates the draft's column, or updates an existing column's projects; an untouched automatic name follows the projects. */
  async function connectProjects(projects: ConnectTargetChoice[], column?: ConnectColumn): Promise<ConnectColumn> {
    const targetProjectIds = new Set(projects.map(project => project.id))
    const others = columnNames.value.filter(name => name !== column?.name)
    if (!column) {
      const created = await context.createColumn({ name: connectColumnAutoName(projects.map(project => project.name), others), targetProjectIds })
      closeSetup()
      await reveal(created)
      celebrate(connectColumnKey(created.id))
      return created
    }
    const current = catalog.value?.items.find(item => item.id === column.id) ?? column
    const automatic = current.name === connectColumnAutoName(current.targets.map(target => target.name), others)
    const updated = await context.updateColumn(current, {
      name: automatic ? connectColumnAutoName(projects.map(project => project.name), others) : current.name, targetProjectIds })
    closeSetup()
    return updated
  }
  function rename(column: ConnectColumn, name: string) {
    const current = catalog.value?.items.find(item => item.id === column.id) ?? column
    return context.updateColumn(current, { name: name.trim(), targetProjectIds: new Set(current.targets.map(target => target.projectId)) })
  }
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
  return { ...context, columns, visibleColumns, hidden, readOnly, canManage, canDelete, projectName, columnNames, deletingColumn,
    draft, setup, celebrated, selectedConnection, perspective, cardOpen, startDraft, openSettings, closeSetup, connectProjects, rename,
    requestDelete, openCard, width, toggleColumn, resizeColumn, reveal, refreshConnection, reloadConnections }
}
