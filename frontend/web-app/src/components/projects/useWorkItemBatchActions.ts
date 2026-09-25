import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { readCsrfToken, WorkItemRelationRole, WorkItemRelationType, type ProjectWorkItemListItem,
  type WorkItemRelationCounterpart } from '@yumpoo/api-client'
import { workItemsApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'
import { duplicateWorkItem, type WorkItemDuplicateOptions } from './workItemDuplicate'
import { moveWorkItemOrder, workItemOrderBlock, type WorkItemOrderSource } from './workItemOrder'

export type WorkItemBatchAction = 'duplicate' | 'archive' | 'delete' | 'convert' | 'top' | 'bottom'
export interface WorkItemSelection { id: string; parentId?: string | undefined }
interface Options {
  selection: () => WorkItemSelection[]
  resolve: (id: string) => ProjectWorkItemListItem | undefined
  contextId: () => string
  canCreate: () => boolean
  sorted: (parentId?: string) => boolean
  duplicateOptions: () => Pick<WorkItemDuplicateOptions, 'catalog' | 'labels' | 'members'>
  orderItems: WorkItemOrderSource
  scopeKey: (selection: WorkItemSelection, item: ProjectWorkItemListItem) => string
  deselect: (selection: WorkItemSelection, action: WorkItemBatchAction) => void | Promise<void>
  refresh: (ids: string[]) => Promise<void>
  beforeRemove: (items: ProjectWorkItemListItem[]) => Promise<boolean>
}
export interface WorkItemBatchResult {
  succeeded: string[]
  failed: Array<{ id: string; reason: string }>
  skipped: Array<{ id: string; reason: string }>
  warnings: string[]
  remaining: number
}
const labels: Record<WorkItemBatchAction, string> = { duplicate: '复制', archive: '归档', delete: '删除', convert: '转为子工作项', top: '置顶', bottom: '置底' }

export function useWorkItemBatchActions(options: Options) {
  const busy = ref(false), stopping = ref(false), choosingParent = ref(false)
  const progress = ref<{ action: WorkItemBatchAction; completed: number; total: number }>()
  const selected = computed(() => options.selection().flatMap(selection => {
    const item = options.resolve(selection.id)
    return item ? [{ ...selection, item }] : []
  }))
  const count = computed(() => selected.value.length)
  const subitemCount = computed(() => selected.value.filter(value => value.parentId).length)
  const convertibleItems = computed(() => selected.value.filter(value => !value.parentId && !value.item.subitemCount && value.item.capabilities.canEditFields).map(value => value.item))
  const canDelete = computed(() => selected.value.some(value => value.item.capabilities.canDelete))
  const moveDisabled = computed(() => options.sorted() || selected.value.some(value => options.sorted(value.parentId))
    || !selected.value.some(value => value.item.capabilities.canMoveInProjectOrder))
  const progressLabel = computed(() => progress.value
    ? `${labels[progress.value.action]}中 ${Math.min(progress.value.completed + 1, progress.value.total)}/${progress.value.total}` : '')
  function stop(): void { if (busy.value) stopping.value = true }
  function skipReason(action: WorkItemBatchAction, selection: WorkItemSelection, item?: ProjectWorkItemListItem): string | undefined {
    if (!item) return '已不在当前列表'
    if (action === 'duplicate' && !options.canCreate()) return '没有创建权限'
    if ((action === 'archive' || action === 'delete') && !item.capabilities.canDelete) return '没有删除权限'
    if (action === 'convert') {
      if (selection.parentId) return '已是子工作项'
      if (item.subitemCount > 0) return '已有子工作项'
      if (!item.capabilities.canEditFields) return '没有编辑权限'
    }
    if (action === 'top' || action === 'bottom') {
      if (options.sorted() || options.sorted(selection.parentId)) return '请先清除排序'
      if (!item.capabilities.canMoveInProjectOrder) return '没有移动权限'
    }
    return undefined
  }

  async function run(action: WorkItemBatchAction, selections = options.selection(), parent?: WorkItemRelationCounterpart): Promise<WorkItemBatchResult | undefined> {
    if (busy.value || !selections.length || (action === 'convert' && !parent)) return
    const targets = [...new Map(selections.map(selection => [selection.id, { id: selection.id, parentId: selection.parentId }])).values()]
    if (action === 'convert' && targets.some(target => target.id === parent!.id)) return
    const context = options.contextId()
    const result: WorkItemBatchResult = { succeeded: [], failed: [], skipped: [], warnings: [], remaining: 0 }
    const affected = new Set<string>()
    busy.value = true; stopping.value = false; choosingParent.value = false
    try {
      const eligible = targets.filter(target => !skipReason(action, target, options.resolve(target.id)))
      if (action === 'archive' || action === 'delete') {
        if (!await options.beforeRemove(eligible.map(target => options.resolve(target.id)!))) return
        const children = targets.filter(target => target.parentId).length
        const withChildren = eligible.some(target => options.resolve(target.id)!.subitemCount > 0)
        await ElMessageBox.confirm(`将${labels[action]} ${eligible.length} 项，含 ${children} 个子工作项；跳过 ${targets.length - eligible.length} 项（无权限或已不在列表）。${withChildren ? '父项的子工作项也将从主表隐藏，父子关系保留。' : ''}`,
          `批量${labels[action]}`, { confirmButtonText: labels[action], cancelButtonText: '取消', type: 'warning' })
      } else if (action === 'duplicate' && targets.length > 20) {
        await ElMessageBox.confirm(`将复制 ${targets.length} 个工作项（含子工作项），是否继续？`, '批量复制', { confirmButtonText: '复制', cancelButtonText: '取消' })
      }
      if (context !== options.contextId()) return
      progress.value = { action, completed: 0, total: targets.length }
      let queue: WorkItemSelection[] = targets
      const blocks = new Map<string, ReturnType<typeof workItemOrderBlock>>()
      if (action === 'top' || action === 'bottom') {
        queue = []
        const scopes = new Map<string, WorkItemSelection[]>()
        for (const target of targets) {
          const item = options.resolve(target.id), reason = skipReason(action, target, item)
          if (reason) { result.skipped.push({ id: target.id, reason }); continue }
          const key = options.scopeKey(target, item!)
          scopes.set(key, [...(scopes.get(key) ?? []), target])
        }
        for (const [key, scope] of scopes) {
          if (stopping.value || context !== options.contextId()) break
          try {
            // 整块移动需要完整同级顺序，不能把整页已勾选时的空锚点当作真正边界。
            const siblings = await options.orderItems(options.resolve(scope[0]!.id)!, 'bottom')
            const block = workItemOrderBlock(siblings, new Set(scope.map(target => target.id)), action)
            blocks.set(key, block)
            queue.push(...block.items.map(item => scope.find(target => target.id === item.id)!))
            for (const target of scope) if (!block.items.some(item => item.id === target.id)) result.skipped.push({ id: target.id, reason: '已不在当前排序范围' })
          } catch (error) {
            const reason = problemMessage(await toApiProblem(error))
            result.failed.push(...scope.map(target => ({ id: target.id, reason })))
          }
        }
        progress.value.completed = result.skipped.length + result.failed.length
      } else if (action === 'archive' || action === 'delete') {
        queue = [...targets].sort((a, b) => Number(Boolean(b.parentId)) - Number(Boolean(a.parentId)))
      }
      for (const target of queue) {
        if (stopping.value || context !== options.contextId()) break
        const item = options.resolve(target.id), reason = skipReason(action, target, item)
        if (reason) { result.skipped.push({ id: target.id, reason }); progress.value.completed++; continue }
        affected.add(target.id)
        if (target.parentId) affected.add(target.parentId)
        try {
          if (action === 'duplicate') {
            const copy = await duplicateWorkItem({ item: item!, parentId: target.parentId,
              sorted: options.sorted(target.parentId), ...options.duplicateOptions() })
            if (copy.status === 'skipped') { result.skipped.push({ id: target.id, reason: copy.reason }); continue }
            affected.add(copy.item.id)
            if (copy.warning) result.warnings.push(copy.warning)
          } else if (action === 'top' || action === 'bottom') {
            const block = blocks.get(options.scopeKey(target, item!))!
            const position = block.position()
            if (block.hasAnchor) await moveWorkItemOrder(item!, target.parentId, position.previousVisibleWorkItemId, position.nextVisibleWorkItemId)
            block.moved(item!.id)
          } else {
            const token = readCsrfToken()
            if (!token) throw new Error('缺少 CSRF 凭据，请刷新后重试。')
            const common = { workItemId: item!.id, xXSRFTOKEN: token, ifMatch: item!.etag, idempotencyKey: crypto.randomUUID() }
            if (action === 'archive') await workItemsApi.archiveWorkItem(common)
            else if (action === 'delete') await workItemsApi.deleteWorkItem({ ...common, workItemDeleteRequest: { reason: '通过勾选批量工作台删除' } })
            else {
              await workItemsApi.createWorkItemRelation({ ...common, workItemRelationCreateRequest: {
                relationType: WorkItemRelationType.ParentChild, currentRole: WorkItemRelationRole.Child,
                targetProjectId: parent!.projectId, targetWorkItemId: parent!.id,
              } })
              affected.add(parent!.id)
            }
          }
          result.succeeded.push(target.id)
          if (['archive', 'delete', 'convert'].includes(action) && context === options.contextId()) await options.deselect(target, action)
        } catch (error) { result.failed.push({ id: target.id, reason: problemMessage(await toApiProblem(error)) }) }
        finally { progress.value.completed++ }
      }
      result.remaining = targets.length - result.succeeded.length - result.failed.length - result.skipped.length
      if (affected.size && context === options.contextId()) {
        try { await options.refresh([...affected]) }
        catch (error) { result.warnings.push(`操作已完成，刷新失败：${problemMessage(await toApiProblem(error))}`) }
      }
      const details = [...new Set([...result.failed, ...result.skipped].map(item => item.reason)), ...result.warnings]
      const message = `${labels[action]}完成：成功 ${result.succeeded.length} 项，失败 ${result.failed.length} 项，跳过 ${result.skipped.length} 项${result.remaining ? `，未执行 ${result.remaining} 项` : ''}${details.length ? `。${details.join('；')}` : ''}`
      if (result.failed.length || result.skipped.length || result.warnings.length || result.remaining) ElMessage.warning(message)
      else ElMessage.success(message)
      return result
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') ElMessage.error(problemMessage(await toApiProblem(error)))
    } finally { busy.value = false; progress.value = undefined; stopping.value = false }
  }
  return { busy, stopping, progress, progressLabel, choosingParent, selected, count, subitemCount, convertibleItems, canDelete, moveDisabled, stop, run }
}
