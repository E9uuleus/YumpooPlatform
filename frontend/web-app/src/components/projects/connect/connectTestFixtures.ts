import { ProjectLifecycle, WorkItemStatusCategory, WorkItemConnectionOriginEnum, WorkItemLabelColorToken,
  type ConnectColumn, type ConnectionCard, type WorkItemConnection } from '@yumpoo/api-client'

export const sourceCard: ConnectionCard = {
  workItemId: 'source-item', itemNo: 'P012-8', title: '现场打印超时', archived: false,
  projectId: 'source-project', projectCode: 'P012', projectName: '华东现场实施', projectLifecycle: ProjectLifecycle.Active,
  status: { code: 'DOING', name: '进行中', colorToken: WorkItemLabelColorToken.Orange, category: WorkItemStatusCategory.InProgress },
  priority: { code: 'HIGH', name: '高', colorToken: WorkItemLabelColorToken.Orange },
  category: { id: 'bug', name: '缺陷', colorToken: WorkItemLabelColorToken.Lipstick },
  assignee: { userId: 'user-1', displayName: '张三' }, canOpen: true,
}
export const targetCard: ConnectionCard = { ...sourceCard, workItemId: 'target-item', itemNo: 'P003-41', title: '打印服务调用超时',
  projectId: 'target-project', projectCode: 'P003', projectName: 'Yumpoo 门户', assignee: { userId: 'user-2', displayName: '李四' } }
export const connectColumn: ConnectColumn = { id: 'column-1', projectId: sourceCard.projectId, name: '产品缺陷', etag: '"1"', rowVersion: 1,
  createdAt: new Date('2026-09-30T14:20:00Z'), targets: [{ projectId: targetCard.projectId, code: targetCard.projectCode, name: targetCard.projectName,
    lifecycle: ProjectLifecycle.Active, actorCanLinkExisting: true }] }
export const connection: WorkItemConnection = { id: 'connection-1', etag: '"1"', rowVersion: 1, columnId: connectColumn.id,
  columnName: connectColumn.name, source: sourceCard, target: targetCard, origin: WorkItemConnectionOriginEnum.Created, active: true,
  createdAt: new Date('2026-09-30T14:20:00Z'), createdBy: { userId: 'user-1', displayName: '张三' }, capabilities: { canUnlink: true } }
export const connectionCatalog = { items: [connectColumn], incomingAvailable: false, incomingColumns: [], canManage: true, canDelete: true }
export const sourceItem = { id: sourceCard.workItemId, title: sourceCard.title }
