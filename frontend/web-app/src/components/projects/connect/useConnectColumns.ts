import { readCsrfToken, type ConnectCandidateField, type ConnectCandidateSort, type ConnectColumn, type ConnectColumnCatalog,
  type ConnectColumnCreateRequest, type ConnectedWorkItemCreateRequest, type ReverseConnectedWorkItemCreateRequest,
  type WorkItemConnection, type WorkItemConnectionCell } from '@yumpoo/api-client'
import { inject, onScopeDispose, ref, shallowRef, toValue, watch, type InjectionKey, type MaybeRefOrGetter } from 'vue'
import { workItemsApi } from '../../../api/client'
import { toApiProblem, type ApiProblem } from '../../../api/problems'
import { missingConnectCsrf } from './connectProblems'

export type ConnectColumns = ReturnType<typeof useConnectColumns>
export interface CandidateSearchOptions { fields?: ConnectCandidateField[] | undefined; sort?: ConnectCandidateSort | undefined }
const searchOptions = (options: CandidateSearchOptions) => ({ ...(options.fields?.length ? { fields: options.fields } : {}),
  ...(options.sort ? { sort: options.sort } : {}) })
export const connectColumnsContext: InjectionKey<ConnectColumns> = Symbol('connectColumns')
export function useConnectContext(): ConnectColumns {
  const context = inject(connectColumnsContext)
  if (!context) throw new Error('Connect components require a project connection context')
  return context
}

export function useConnectColumns(projectId: MaybeRefOrGetter<string>, enabled: MaybeRefOrGetter<boolean> = true, identity: MaybeRefOrGetter<string> = '') {
  const catalog = shallowRef<ConnectColumnCatalog>()
  const cells = shallowRef(new Map<string, WorkItemConnectionCell>())
  const catalogLoading = ref(false)
  const catalogError = ref<ApiProblem>()
  const cellError = ref<ApiProblem>()
  const pending = new Map<string, Promise<void>>()
  const versions = new Map<string, number>()
  const controllers = new Set<AbortController>()
  let revision = 0
  let catalogRevision = 0

  function reset() {
    revision++; catalogRevision++
    controllers.forEach(controller => controller.abort()); controllers.clear()
    pending.clear(); versions.clear()
    catalog.value = undefined; cells.value = new Map()
    catalogError.value = undefined; cellError.value = undefined; catalogLoading.value = false
  }
  watch(() => [toValue(projectId), toValue(enabled), toValue(identity)], reset, { flush: 'sync' })
  onScopeDispose(reset)

  async function read<T>(request: (signal: AbortSignal) => Promise<T>): Promise<T> {
    const controller = new AbortController()
    controllers.add(controller)
    try { return await request(controller.signal) } finally { controllers.delete(controller) }
  }
  async function loadCatalog(): Promise<void> {
    if (!toValue(enabled) || !toValue(projectId)) return
    const current = revision, requestRevision = ++catalogRevision, id = toValue(projectId)
    catalogLoading.value = true; catalogError.value = undefined
    try {
      const result = await read(signal => workItemsApi.listConnectColumns({ projectId: id }, { signal }))
      if (current !== revision || requestRevision !== catalogRevision) return
      cells.value = new Map([...cells.value].map(([id, cell]) => [id, validCell(cell, result)]))
      catalog.value = result
    } catch (reason) {
      const problem = await toApiProblem(reason)
      if (current === revision && requestRevision === catalogRevision) catalogError.value = problem
    } finally { if (current === revision && requestRevision === catalogRevision) catalogLoading.value = false }
  }

  /** Drops connections of columns that were deleted, or stopped targeting this project, since the cell was read. */
  function validCell(cell: WorkItemConnectionCell, current: ConnectColumnCatalog): WorkItemConnectionCell {
    const outgoing = new Set(current.items.map(column => column.id))
    const reverse = new Set(current.incomingColumns.map(column => column.columnId))
    return { ...cell, outgoing: cell.outgoing.filter(column => outgoing.has(column.columnId)),
      incomingByColumn: cell.incomingByColumn.filter(column => reverse.has(column.columnId)) }
  }

  async function requestCells(ids: string[], force: boolean): Promise<void> {
    if (!toValue(enabled) || !ids.length) return
    const current = revision, id = toValue(projectId)
    const unique = [...new Set(ids)]
    const waiting = force ? [] : unique.flatMap(id => pending.has(id) ? [pending.get(id)!] : [])
    const missing = unique.filter(id => force || (!cells.value.has(id) && !pending.has(id)))
    cellError.value = undefined
    const tasks: Promise<void>[] = [...waiting]
    for (let offset = 0; offset < missing.length; offset += 100) {
      const batch = missing.slice(offset, offset + 100)
      const stamps = new Map(batch.map(id => { const version = (versions.get(id) ?? 0) + 1; versions.set(id, version); return [id, version] }))
      const task = (async () => {
        try {
          const result = await read(signal => workItemsApi.listWorkItemConnectionCells({ projectId: id, workItemIds: batch }, { signal }))
          if (current !== revision) return
          const received = new Map(result.items.map(cell => [cell.workItemId, cell]))
          const next = new Map(cells.value)
          for (const itemId of batch) {
            if (versions.get(itemId) !== stamps.get(itemId)) continue
            const cell = received.get(itemId) ?? { workItemId: itemId, outgoing: [], incoming: [], incomingTotal: 0, incomingByColumn: [] }
            next.set(itemId, catalog.value ? validCell(cell, catalog.value) : cell)
          }
          cells.value = next
        } catch (reason) {
          const problem = await toApiProblem(reason)
          if (current === revision && batch.some(id => versions.get(id) === stamps.get(id))) cellError.value = problem
        } finally {
          if (current === revision) for (const id of batch) if (versions.get(id) === stamps.get(id)) pending.delete(id)
        }
      })()
      batch.forEach(id => pending.set(id, task)); tasks.push(task)
    }
    await Promise.all(tasks)
  }
  const ensureCells = (ids: string[]) => requestCells(ids, false)
  const refreshCells = (ids: string[]) => requestCells(ids, true)

  async function write<T>(command: (csrf: string, signal: AbortSignal) => Promise<T>, refresh: (result: T) => Promise<void>, affected: string[] = []): Promise<T> {
    const csrf = readCsrfToken()
    if (!csrf) throw missingConnectCsrf()
    const current = revision
    try {
      const result = await read(signal => command(csrf, signal))
      if (current === revision) await refresh(result)
      return result
    } catch (reason) {
      const problem = await toApiProblem(reason)
      if (current === revision && problem.kind === 'response' && [404, 409, 412].includes(problem.status)) {
        await loadCatalog()
        if (current === revision && (affected.length || problem.status !== 404)) {
          await refreshCells(affected.length ? affected : [...cells.value.keys()])
        }
      }
      throw reason
    }
  }
  function affectedItems(connection: WorkItemConnection): string[] {
    return [connection.source, connection.target].filter(card => card.projectId === toValue(projectId)).map(card => card.workItemId)
  }
  const connectionChanged = async (connection: WorkItemConnection) => { await refreshCells(affectedItems(connection)) }
  function createColumn(input: ConnectColumnCreateRequest) {
    const id = toValue(projectId)
    return write((xXSRFTOKEN, signal) => workItemsApi.createConnectColumn({ projectId: id, xXSRFTOKEN,
      idempotencyKey: crypto.randomUUID(), connectColumnCreateRequest: input }, { signal }), loadCatalog)
  }
  function updateColumn(column: ConnectColumn, input: ConnectColumnCreateRequest) {
    return write((xXSRFTOKEN, signal) => workItemsApi.updateConnectColumn({ projectId: column.projectId, columnId: column.id,
      xXSRFTOKEN, ifMatch: column.etag, connectColumnUpdateRequest: input }, { signal }), loadCatalog)
  }
  async function deleteColumn(column: ConnectColumn): Promise<number> {
    const result = await write((xXSRFTOKEN, signal) => workItemsApi.deleteConnectColumn({ projectId: column.projectId, columnId: column.id,
      xXSRFTOKEN, ifMatch: column.etag, idempotencyKey: crypto.randomUUID() }, { signal }), loadCatalog)
    return result.removedConnectionCount
  }
  function link(sourceItemId: string, columnId: string, targetItemId: string) {
    return write((xXSRFTOKEN, signal) => workItemsApi.linkWorkItemConnection({ workItemId: sourceItemId, xXSRFTOKEN,
      idempotencyKey: crypto.randomUUID(), workItemConnectionLinkRequest: { columnId, targetWorkItemId: targetItemId } }, { signal }), connectionChanged, [sourceItemId])
  }
  function createAndLink(sourceItemId: string, input: ConnectedWorkItemCreateRequest) {
    return write((xXSRFTOKEN, signal) => workItemsApi.createConnectedWorkItem({ workItemId: sourceItemId, xXSRFTOKEN,
      idempotencyKey: crypto.randomUUID(), connectedWorkItemCreateRequest: input }, { signal }), connectionChanged, [sourceItemId])
  }
  /** Links from a reverse column: the picked item is the source, the row in this project is the target. */
  function reverseLink(targetItemId: string, columnId: string, sourceItemId: string) {
    return write((xXSRFTOKEN, signal) => workItemsApi.linkWorkItemConnection({ workItemId: sourceItemId, xXSRFTOKEN,
      idempotencyKey: crypto.randomUUID(), workItemConnectionLinkRequest: { columnId, targetWorkItemId: targetItemId } }, { signal }), connectionChanged, [targetItemId])
  }
  function reverseCreateAndLink(targetItemId: string, input: ReverseConnectedWorkItemCreateRequest) {
    return write((xXSRFTOKEN, signal) => workItemsApi.createReverseConnectedWorkItem({ workItemId: targetItemId, xXSRFTOKEN,
      idempotencyKey: crypto.randomUUID(), reverseConnectedWorkItemCreateRequest: input }, { signal }), connectionChanged, [targetItemId])
  }
  async function unlink(connection: WorkItemConnection): Promise<void> {
    await write((xXSRFTOKEN, signal) => workItemsApi.unlinkWorkItemConnection({ connectionId: connection.id, xXSRFTOKEN,
      ifMatch: connection.etag, idempotencyKey: crypto.randomUUID() }, { signal }), connectionChanged, affectedItems(connection))
  }
  function query<T>(request: (signal: AbortSignal) => Promise<T>, caller?: AbortSignal) {
    return read(signal => request(caller ? AbortSignal.any([signal, caller]) : signal))
  }
  const getConnection = (connectionId: string, caller?: AbortSignal) => query(signal => workItemsApi.getWorkItemConnection({ connectionId }, { signal }), caller)
  const searchTargets = (text: string, page: number, caller?: AbortSignal) => query(signal => workItemsApi.searchConnectTargetProjects({ query: text, page, size: 20 }, { signal }), caller)
  const createOptions = (columnId: string, targetProjectId: string, caller?: AbortSignal) => query(signal => workItemsApi.getConnectCreateOptions({ projectId: toValue(projectId), columnId, targetProjectId }, { signal }), caller)
  const searchCandidates = (columnId: string, targetProjectId: string, sourceWorkItemId: string, q: string, page: number, caller?: AbortSignal,
    options: CandidateSearchOptions = {}) => query(signal => workItemsApi.searchConnectCandidates({ projectId: toValue(projectId), columnId,
    targetProjectId, sourceWorkItemId, q, page, size: 20, ...searchOptions(options) }, { signal }), caller)
  const reverseCandidates = (columnId: string, targetWorkItemId: string, q: string, page: number, caller?: AbortSignal,
    options: CandidateSearchOptions = {}) => query(signal => workItemsApi.searchReverseConnectCandidates({ workItemId: targetWorkItemId,
    columnId, q, page, size: 20, ...searchOptions(options) }, { signal }), caller)
  const reverseCreateOptions = (columnId: string, targetWorkItemId: string, caller?: AbortSignal) => query(signal =>
    workItemsApi.getReverseConnectCreateOptions({ workItemId: targetWorkItemId, columnId }, { signal }), caller)
  const incoming = (workItemId: string, page: number, caller?: AbortSignal, columnId?: string) => query(signal =>
    workItemsApi.listIncomingWorkItemConnections({ workItemId, page, size: 50, ...(columnId ? { columnId } : {}) }, { signal }), caller)
  return { catalog, cells, catalogLoading, catalogError, cellError, loadCatalog, ensureCells, refreshCells,
    createColumn, updateColumn, deleteColumn, link, createAndLink, reverseLink, reverseCreateAndLink, unlink, getConnection, searchTargets,
    createOptions, searchCandidates, reverseCandidates, reverseCreateOptions, incoming }
}
