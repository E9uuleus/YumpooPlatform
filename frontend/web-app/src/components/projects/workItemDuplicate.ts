import { readCsrfToken, type ProjectContentCatalog, type ProjectMember, type ProjectWorkItemListItem,
  type WorkItemCreateRequest, type WorkItemDetail, type WorkItemLabelCatalog } from '@yumpoo/api-client'
import { workItemsApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'
import { moveWorkItemOrder } from './workItemOrder'

export interface WorkItemDuplicateOptions {
  item: Pick<ProjectWorkItemListItem, 'id' | 'projectId'>
  parentId?: string | undefined
  sorted: boolean
  catalog: ProjectContentCatalog
  labels: WorkItemLabelCatalog
  members: ProjectMember[]
}
export type WorkItemDuplicateResult = { status: 'skipped'; reason: string }
  | { status: 'created'; item: WorkItemDetail; warning?: string }

export async function duplicateWorkItem(options: WorkItemDuplicateOptions): Promise<WorkItemDuplicateResult> {
  const detail = await workItemsApi.getWorkItem({ workItemId: options.item.id })
  if (!options.catalog.items.some(content => content.id === detail.contentId && content.active)) {
    return { status: 'skipped', reason: '类别已停用或不可用' }
  }
  const token = readCsrfToken()
  if (!token) throw new Error('缺少 CSRF 凭据，请刷新后重试。')
  const body: WorkItemCreateRequest = {
    contentId: detail.contentId,
    title: `${detail.title.slice(0, 296)}（副本）`,
    priority: options.labels.priorities.some(label => label.code === detail.priority && label.active) ? detail.priority : null,
    assigneeUserId: options.members.some(member => member.userId === detail.assigneeUserId
      && member.membershipStatus === 'ACTIVE' && member.employmentStatus === 'ACTIVE' && member.accountStatus === 'ENABLED')
      ? detail.assigneeUserId : null,
    description: detail.description, notes: detail.notes,
    timelineStartDate: detail.timelineStartDate, timelineEndDate: detail.timelineEndDate,
    dueDate: detail.dueDate, dueTime: detail.dueTime ?? null,
  }
  const common = { xXSRFTOKEN: token, idempotencyKey: crypto.randomUUID() }
  let created = options.parentId
    ? await workItemsApi.createWorkItemSubitem({ ...common, parentWorkItemId: options.parentId, workItemSubitemCreateRequest: body })
    : await workItemsApi.createWorkItem({ ...common, projectId: detail.projectId, workItemCreateRequest: body })
  if (!options.sorted) {
    try { created = await moveWorkItemOrder(created, options.parentId, detail.id, null) }
    catch (reason) {
      return { status: 'created', item: created,
        warning: `已创建 ${created.itemNo}，但放置到原项下方失败：${problemMessage(await toApiProblem(reason))}` }
    }
  }
  return { status: 'created', item: created }
}
