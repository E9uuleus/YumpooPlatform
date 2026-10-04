import { ProjectActorAccess, ProjectLifecycle, WorkItemLabelColorToken, WorkItemStatusCategory, WorkItemConnectionOriginEnum,
  type ConnectionCard, type WorkItemConnection, type ConnectColumn, type ProjectContentCatalog, type ProjectDetail, type WorkItemDetail,
  type WorkItemLabelCatalog } from '@yumpoo/api-client'

export const previewSource: ConnectionCard = {
  workItemId: 'preview-source-1', itemNo: 'P012-8', title: '现场打印超时', archived: false,
  projectId: 'preview-source', projectCode: 'P012', projectName: '华东现场实施', projectLifecycle: ProjectLifecycle.Active,
  status: { code: 'DOING', name: '进行中', colorToken: WorkItemLabelColorToken.Orange, category: WorkItemStatusCategory.InProgress },
  priority: { code: 'HIGH', name: '高', colorToken: WorkItemLabelColorToken.Orange },
  category: { id: 'preview-bug', name: '缺陷', colorToken: WorkItemLabelColorToken.Lipstick },
  assignee: { userId: 'preview-user-1', displayName: '张三' }, canOpen: true,
}
export const previewTarget: ConnectionCard = { ...previewSource, workItemId: 'preview-target-1', itemNo: 'P003-41', title: '打印服务调用超时',
  projectId: 'preview-target', projectCode: 'P003', projectName: 'Yumpoo 门户', assignee: { userId: 'preview-user-2', displayName: '李四' } }
export const previewColumn: ConnectColumn = { id: 'preview-column', projectId: previewSource.projectId, name: '产品缺陷', etag: '"1"', rowVersion: 1,
  createdAt: new Date('2026-09-30T14:20:00Z'), targets: [{ projectId: previewTarget.projectId, code: previewTarget.projectCode,
    name: previewTarget.projectName, lifecycle: ProjectLifecycle.Active, actorCanLinkExisting: true }] }
export const previewConnection: WorkItemConnection = { id: 'preview-connection-1', etag: '"1"', rowVersion: 1, columnId: previewColumn.id,
  columnName: previewColumn.name, source: previewSource, target: previewTarget, origin: WorkItemConnectionOriginEnum.Created, active: true,
  createdAt: new Date('2026-09-30T14:20:00Z'), createdBy: { userId: 'preview-user-1', displayName: '张三' }, capabilities: { canUnlink: true } }
export const previewSecondProject = { projectId: 'preview-second', code: 'P006', name: '移动端重构', lifecycle: ProjectLifecycle.Active, actorCanLinkExisting: true }
export const previewConnections: WorkItemConnection[] = [previewConnection,
  { ...previewConnection, id: 'preview-connection-2', target: { ...previewTarget, workItemId: 'preview-target-2', itemNo: 'P003-42', title: '批量导出偶发失败', assignee: null, priority: null } },
  { ...previewConnection, id: 'preview-connection-3', target: { ...previewTarget, workItemId: 'preview-target-3', itemNo: 'P003-37', title: '旧版打印兼容性', archived: true }, capabilities: { canUnlink: false } },
]

const previewStatuses = [
  { code: 'TODO', displayName: '未开始', colorToken: WorkItemLabelColorToken.AmericanGray, statusCategory: WorkItemStatusCategory.Todo },
  { code: 'DOING', displayName: '进行中', colorToken: WorkItemLabelColorToken.Orange, statusCategory: WorkItemStatusCategory.InProgress },
  { code: 'DONE', displayName: '已完成', colorToken: WorkItemLabelColorToken.Green, statusCategory: WorkItemStatusCategory.Done },
]
const previewStamp = new Date('2026-10-02T09:30:00Z')
export const previewLabels: WorkItemLabelCatalog = { rowVersion: 1, etag: '"1"', canManage: false,
  statuses: previewStatuses.map((status, index) => ({ ...status, sortOrder: index * 10, active: true, protectedLabel: index === 0, inUse: true })),
  priorities: [{ code: 'LOW', displayName: '低', colorToken: WorkItemLabelColorToken.Blue }, { code: 'MEDIUM', displayName: '中', colorToken: WorkItemLabelColorToken.Teal },
    { code: 'HIGH', displayName: '高', colorToken: WorkItemLabelColorToken.Orange }]
    .map((priority, index) => ({ ...priority, sortOrder: index * 10, active: true, inUse: true })) }
export const previewContents = (projectId: string): ProjectContentCatalog => ({ rowVersion: 1, etag: '"1"', canManage: false,
  items: [{ id: 'preview-bug', name: '缺陷', code: 'DEFECTS', colorToken: WorkItemLabelColorToken.Lipstick }, { id: 'preview-task', name: '任务', code: 'TASKS', colorToken: WorkItemLabelColorToken.BrightGreen }]
    .map((content, index) => ({ ...content, projectId, sortOrder: index * 10, active: true, protectedContent: false, inUse: true, rowVersion: 1,
      createdAt: previewStamp, createdByUserId: 'preview-user-1', updatedAt: previewStamp, updatedByUserId: 'preview-user-1' })) })
export function previewProject(card: ConnectionCard): ProjectDetail {
  return { id: card.projectId, workspaceId: 'preview-workspace', code: card.projectCode, name: card.projectName, description: null, lifecycle: card.projectLifecycle,
    ownerUserId: 'preview-user-1', rowVersion: 1, workspaceCode: 'MAIN', workspaceName: '默认工作空间', ownerDisplayName: '张三', actorAccess: ProjectActorAccess.Member,
    capabilities: { canUpdateSettings: false, canManageMembers: false, canReassignOwner: false, canArchive: false, canRestore: false, canMoveWorkspace: false, canOverrideArchive: false },
    etag: '"1"', createdAt: previewStamp, updatedAt: previewStamp, archivedAt: null }
}
/** A full detail for a connection card, as the work item endpoint would return it to a member of that project. */
export function previewDetail(card: ConnectionCard): WorkItemDetail {
  const transitions = previewStatuses.filter(status => status.code !== card.status.code)
    .map(status => ({ toStatus: status.code, displayName: status.displayName, statusCategory: status.statusCategory, requiresResolution: false }))
  return { id: card.workItemId, projectId: card.projectId, contentId: card.category.id, contentName: card.category.name, contentColorToken: card.category.colorToken,
    itemNo: card.itemNo, title: card.title, statusCode: card.status.code, statusCategory: card.status.category as WorkItemStatusCategory, priority: card.priority?.code ?? null,
    assigneeUserId: card.assignee?.userId ?? null, assigneeDisplayName: card.assignee?.displayName ?? null, reporterUserId: 'preview-user-1', reporterDisplayName: '张三',
    description: null, notes: null, timelineStartDate: null, timelineEndDate: null, dueDate: new Date('2026-10-09T00:00:00Z'), dueTime: null, rowVersion: 3, etag: '"3"',
    capabilities: { canEditFields: !card.archived, canMoveInKanban: true, canMoveInProjectOrder: true, canDiscuss: true, canDelete: false, canRestore: false, availableTransitions: transitions },
    createdAt: previewStamp, updatedAt: previewStamp, updatedByUserId: 'preview-user-2', updatedByDisplayName: '李四', archived: card.archived,
    deleted: false, deletedAt: null, deletedByUserId: null, deleteReason: null }
}
