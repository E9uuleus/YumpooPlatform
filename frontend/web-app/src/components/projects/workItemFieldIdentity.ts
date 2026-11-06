import { markRaw, type Component } from 'vue'
import {
  Calendar, CollectionTag, Connection, Flag, List, Refresh, Tickets, Timer, User, UserFilled,
} from '@element-plus/icons-vue'

export type WorkItemFieldKey =
  | 'title' | 'assignee' | 'status' | 'priority' | 'content' | 'dueDate' | 'timeTracking' | 'updatedAt' | 'reporter' | 'connect'

export interface WorkItemFieldIdentity { label: string; icon: Component; tone: string }

/**
 * 工作项字段的图标与颜色的唯一来源：固定列面板、显示列菜单、连接卡片与看板标签都从这里取，
 * 新字段先在这里登记再使用。理由见 .agents/notes/implemented/product/2026-11-04-work-item-field-identity.md。
 */
export const WORK_ITEM_FIELD_IDENTITIES: Readonly<Record<WorkItemFieldKey, WorkItemFieldIdentity>> = {
  title: { label: '工作项名称', icon: markRaw(Tickets), tone: 'var(--yp-label-indigo)' },
  assignee: { label: '处理人', icon: markRaw(User), tone: 'var(--yp-label-chili-blue)' },
  status: { label: '状态', icon: markRaw(List), tone: 'var(--yp-label-green)' },
  priority: { label: '优先级', icon: markRaw(Flag), tone: 'var(--yp-label-orange)' },
  content: { label: '工作项类别', icon: markRaw(CollectionTag), tone: 'var(--yp-label-egg-yolk)' },
  dueDate: { label: '截止日期', icon: markRaw(Calendar), tone: 'var(--yp-label-purple)' },
  timeTracking: { label: '时长追踪', icon: markRaw(Timer), tone: 'var(--yp-label-aquamarine)' },
  updatedAt: { label: '最后更新时间', icon: markRaw(Refresh), tone: 'var(--yp-label-lavender)' },
  reporter: { label: '报告人', icon: markRaw(UserFilled), tone: 'var(--yp-label-teal)' },
  connect: { label: '连接', icon: markRaw(Connection), tone: 'var(--yp-label-sunset)' },
}

/** 表格列 key 到字段身份；连接列（connect:*、connect-reverse:*、connect-draft）共用连接图标。 */
export function workItemFieldIdentity(columnKey: string): WorkItemFieldIdentity {
  if (columnKey.startsWith('connect')) return WORK_ITEM_FIELD_IDENTITIES.connect
  return WORK_ITEM_FIELD_IDENTITIES[columnKey as WorkItemFieldKey] ?? WORK_ITEM_FIELD_IDENTITIES.title
}
