import { ProjectNotificationMode, type ProjectNotificationPreference } from '@yumpoo/api-client'

/** 自定义模式可选的通知类型；成员与负责人变更通知始终送达，不在此列。 */
export const PROJECT_NOTIFICATION_CATEGORIES = [
  { key: 'mention', label: '@提及我', description: '有人在评论中 @ 你' },
  { key: 'comment', label: '评论与回复', description: '你负责或创建的工作项有新评论，或有人回复你的评论' },
  { key: 'assigned', label: '指派给我', description: '你被设为工作项处理人' },
  { key: 'connectionCreated', label: '关联新建工作项', description: '其他项目向本项目新建关联工作项（仅负责人会收到）' },
] as const

export function projectNotificationSummary(preference: ProjectNotificationPreference): string {
  if (preference.mode === ProjectNotificationMode.Muted) return '已静音'
  if (preference.mode === ProjectNotificationMode.Custom) return '自定义'
  return '接收全部'
}
