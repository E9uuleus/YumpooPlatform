import type { NotificationItem } from '@yumpoo/api-client'
import type { RouteLocationRaw } from 'vue-router'
import type { InboxFilter } from '../../composables/useInbox'

export function isOwnProjectRemoval(item: NotificationItem, readerId?: string): boolean {
  return Boolean(readerId) && item.reason === 'PROJECT_MEMBER_REMOVED' && item.subject?.id === readerId
}

export function notificationText(item: NotificationItem, readerId?: string): string {
  if (isOwnProjectRemoval(item, readerId)) return '将你移出了一个项目'
  if (!item.target.accessible) return '相关内容已不可访问'
  const target = [item.target.itemNo, item.target.title].filter(Boolean).join(' ') || '工作项'
  const project = item.target.projectName || '一个项目'
  const subject = item.subject?.id === readerId ? '你' : item.subject?.displayName || '成员'
  switch (item.reason) {
    case 'MENTION': return `在 ${target} 中提到了你`
    case 'REPLY': return `回复了你在 ${target} 的评论`
    case 'COMMENT': return `评论了 ${target}`
    case 'ASSIGNED': return `将 ${target} 指派给你`
    case 'PROJECT_MEMBER_ADDED': return `将${subject}加入项目 ${project}`
    case 'PROJECT_MEMBER_REMOVED': return `将${subject}移出项目 ${project}`
    case 'PROJECT_OWNER_ASSIGNED': return `将项目 ${project} 的负责人转交给你`
    case 'PROJECT_OWNER_TRANSFERRED': return `将项目 ${project} 的负责人转交给 ${subject}`
    default: return '更新了与你相关的内容'
  }
}

export function notificationLink(item: NotificationItem, readerId?: string): RouteLocationRaw | undefined {
  const target = item.target
  if (!target.accessible || !target.projectId || isOwnProjectRemoval(item, readerId)) return
  return { path: `/projects/${target.projectId}/overview`, query: {
    ...(target.workItemId ? { workItemId: target.workItemId } : {}),
    ...(['MENTION', 'REPLY', 'COMMENT'].includes(item.reason) ? { tab: 'discussion' } : {}),
  } }
}

export function relativeTime(at: Date, now: Date): string {
  const minutes = Math.max(0, Math.floor((now.getTime() - at.getTime()) / 60_000))
  if (!minutes) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  if (minutes < 1440) return `${Math.floor(minutes / 60)} 小时前`
  return `${Math.floor(minutes / 1440)} 天前`
}

function calendarDay(at: Date, timezone: string): number {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(at)
  const value = (name: string) => Number(parts.find(part => part.type === name)?.value)
  return Date.UTC(value('year'), value('month') - 1, value('day')) / 86_400_000
}

export function notificationDateGroup(at: Date, now: Date, timezone: string): string {
  const today = calendarDay(now, timezone), day = calendarDay(at, timezone)
  if (day >= today) return '今天'
  if (day === today - 1) return '昨天'
  const weekday = new Date(today * 86_400_000).getUTCDay()
  return day >= today - ((weekday + 6) % 7) ? '本周' : '更早'
}

export function parseInboxFilter(value: unknown): InboxFilter {
  return typeof value === 'string' && ['unread', 'all', 'mention', 'comment', 'assigned', 'project', 'archived'].includes(value)
    ? value as InboxFilter : 'unread'
}
