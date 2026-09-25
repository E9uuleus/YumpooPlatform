import { readCsrfToken, WorkItemViewType, type ProjectWorkItemListItem, type WorkItemDetail } from '@yumpoo/api-client'
import { workItemsApi } from '../../api/client'

export type WorkItemOrderEdge = 'top' | 'bottom'
export type WorkItemOrderSource = (item: ProjectWorkItemListItem, edge: WorkItemOrderEdge) => Promise<ProjectWorkItemListItem[]>

export async function workItemOrderSiblings(item: ProjectWorkItemListItem, edge: WorkItemOrderEdge,
  parentId?: string, source?: WorkItemOrderSource): Promise<ProjectWorkItemListItem[]> {
  if (source) return source(item, edge)
  if (parentId) return (await workItemsApi.listWorkItemSubitems({ parentWorkItemId: parentId })).items
  const siblings: ProjectWorkItemListItem[] = []
  const seen = new Set<string>()
  let cursor: string | null = null
  do {
    const page = await workItemsApi.listProjectWorkItems({ projectId: item.projectId,
      view: WorkItemViewType.Table, limit: edge === 'top' ? 2 : 100, ...(cursor ? { cursor } : {}) })
    siblings.push(...page.items)
    cursor = page.nextCursor
    if (cursor && seen.has(cursor)) throw new Error('工作项游标重复，请刷新后重试。')
    if (cursor) seen.add(cursor)
  } while (cursor && edge === 'bottom')
  return siblings
}

/** 按同级手动顺序保留整块内的相对位置；只有成功的移动才能推进前锚点。 */
export function workItemOrderBlock(siblings: ProjectWorkItemListItem[], selectedIds: Set<string>, edge: WorkItemOrderEdge) {
  const items = siblings.filter(item => selectedIds.has(item.id))
  const others = siblings.filter(item => !selectedIds.has(item.id))
  let previousId: string | null = edge === 'bottom' ? others.at(-1)?.id ?? null : null
  const nextId = edge === 'top' ? others[0]?.id ?? null : null
  return { items, hasAnchor: others.length > 0,
    position: () => ({ previousVisibleWorkItemId: previousId, nextVisibleWorkItemId: nextId }),
    moved: (id: string) => { previousId = id },
  }
}

export async function moveWorkItemOrder(item: Pick<WorkItemDetail, 'id' | 'etag' | 'projectId'>,
  parentId: string | undefined, previousId: string | null, nextId: string | null): Promise<WorkItemDetail> {
  const token = readCsrfToken()
  if (!token) throw new Error('缺少 CSRF 凭据，请刷新后重试。')
  const common = { xXSRFTOKEN: token, ifMatch: item.etag, idempotencyKey: crypto.randomUUID(),
    projectWorkItemOrderMoveRequest: { previousVisibleWorkItemId: previousId, nextVisibleWorkItemId: nextId } }
  return parentId
    ? workItemsApi.moveWorkItemSubitemOrder({ ...common, parentWorkItemId: parentId, subitemId: item.id })
    : workItemsApi.moveProjectWorkItemOrder({ ...common, projectId: item.projectId, workItemId: item.id })
}
