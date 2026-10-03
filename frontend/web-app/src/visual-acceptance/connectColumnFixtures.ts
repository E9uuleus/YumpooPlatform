import { ProjectLifecycle, WorkItemLabelColorToken, WorkItemStatusCategory, WorkItemConnectionOriginEnum,
  type ConnectionCard, type WorkItemConnection, type ConnectColumn } from '@yumpoo/api-client'

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
