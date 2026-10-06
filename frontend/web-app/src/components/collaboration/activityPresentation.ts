import { ChatDotRound, Clock, Connection, Document, Folder, Paperclip, User } from '@element-plus/icons-vue'
import type { Component } from 'vue'

export type ActivityCategory = 'work-item' | 'discussion' | 'member' | 'time' | 'connection' | 'attachment' | 'project'

interface ActivityCategoryDefinition {
  label: string
  icon: Component
  tone: string
  eventTypes: readonly string[]
}

// 事件类型须与后端 audit 模块的 Activity 白名单同步；未登记的类型只会出现在"全部"中。
export const ACTIVITY_CATEGORIES: Record<ActivityCategory, ActivityCategoryDefinition> = {
  'work-item': {
    label: '工作项',
    icon: Document,
    tone: 'var(--yp-status-blue)',
    eventTypes: [
      'workitem.work_item_created', 'workitem.work_item_fields_changed', 'workitem.work_item_status_changed',
      'workitem.work_item_assigned', 'workitem.work_item_unassigned', 'workitem.work_item_assignees_changed',
      'workitem.work_item_archived', 'workitem.work_item_unarchived', 'workitem.work_item_deleted',
      'workitem.work_item_restored', 'workitem.work_item_rank_changed', 'workitem.work_item_parent_changed',
      'workitem.work_item_relation_created', 'workitem.work_item_relation_deleted',
      'workitem.content_created', 'workitem.content_updated', 'workitem.content_archived',
      'workitem.content_restored', 'workitem.content_deleted',
    ],
  },
  discussion: {
    label: '讨论',
    icon: ChatDotRound,
    tone: 'var(--yp-status-purple)',
    eventTypes: [
      'workitem.work_item_update_published', 'workitem.work_item_update_edited',
      'workitem.work_item_update_deleted', 'workitem.work_item_update_pin_changed',
    ],
  },
  member: {
    label: '成员',
    icon: User,
    tone: 'var(--yp-status-teal)',
    eventTypes: ['catalog.project_member_added', 'catalog.project_member_removed', 'catalog.project_owner_reassigned'],
  },
  time: {
    label: '计时',
    icon: Clock,
    tone: 'var(--yp-status-orange)',
    eventTypes: [
      'workitem.time_tracking_started', 'workitem.time_tracking_stopped', 'workitem.time_tracking_added',
      'workitem.time_tracking_edited', 'workitem.time_tracking_deleted',
    ],
  },
  connection: {
    label: '连接',
    icon: Connection,
    tone: 'var(--yp-status-pink)',
    eventTypes: [
      'workitem.connection_created', 'workitem.connection_deleted', 'workitem.connect_column_created',
      'workitem.connect_column_updated', 'workitem.connect_column_deleted',
    ],
  },
  attachment: {
    label: '附件',
    icon: Paperclip,
    tone: 'var(--yp-status-gray)',
    eventTypes: ['filestorage.attachment_available', 'filestorage.attachment_deleted'],
  },
  project: {
    label: '项目',
    icon: Folder,
    tone: 'var(--yp-status-green)',
    eventTypes: [
      'catalog.project_created', 'catalog.project_updated', 'catalog.project_archived',
      'catalog.project_reopened', 'catalog.project_moved_to_workspace',
    ],
  },
}

export const ACTIVITY_CATEGORY_ORDER: readonly ActivityCategory[] = [
  'work-item', 'discussion', 'member', 'time', 'connection', 'attachment', 'project',
]

const CATEGORY_BY_EVENT_TYPE = new Map<string, ActivityCategory>(
  ACTIVITY_CATEGORY_ORDER.flatMap(key => ACTIVITY_CATEGORIES[key].eventTypes.map(type => [type, key] as const)),
)

export function activityCategory(eventType: string): ActivityCategory {
  const known = CATEGORY_BY_EVENT_TYPE.get(eventType)
  if (known) return known
  if (eventType.startsWith('catalog.')) return 'project'
  if (eventType.startsWith('filestorage.')) return 'attachment'
  return 'work-item'
}
