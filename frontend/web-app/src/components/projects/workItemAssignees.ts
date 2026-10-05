import type { ProjectWorkItemListItem, WorkItemAssignee } from '@yumpoo/api-client'

export const MAX_WORK_ITEM_ASSIGNEES = 20

type AssigneeCarrier = Pick<ProjectWorkItemListItem, 'assigneeUserId' | 'assigneeDisplayName'> & {
  assignees?: WorkItemAssignee[] | undefined
}

/** 旧幂等响应可能缺少 assignees，此时退回主处理人。 */
export function workItemAssignees(item: AssigneeCarrier): WorkItemAssignee[] {
  if (item.assignees) return item.assignees
  return item.assigneeUserId ? [{ userId: item.assigneeUserId, displayName: item.assigneeDisplayName ?? '' }] : []
}

export function workItemAssigneeIds(item: AssigneeCarrier): string[] {
  return workItemAssignees(item).map(assignee => assignee.userId)
}

/** 与服务端 ASSIGNEE_SET 选项值一致：小写 UUID 升序后以逗号连接。 */
export function assigneeSetKey(userIds: readonly string[]): string {
  return [...userIds].sort().join(',')
}
