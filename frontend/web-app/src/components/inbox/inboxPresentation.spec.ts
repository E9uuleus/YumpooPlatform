import { describe, expect, it } from 'vitest'
import { NotificationReason, NotificationState, NotificationTargetKind, type NotificationItem } from '@yumpoo/api-client'
import { notificationDateGroup, notificationExcerpt, notificationLink, notificationText, parseInboxFilter, relativeTime } from './inboxPresentation'

const item: NotificationItem = { id: 'notification', reason: NotificationReason.Mention, state: NotificationState.Unread, createdAt: new Date(), readAt: null,
  actor: { id: 'author', displayName: '张三' }, subject: { id: 'reader', displayName: '李四' },
  target: { kind: NotificationTargetKind.WorkItemUpdate, accessible: true, projectId: 'project', workItemId: 'item', itemNo: 'YP-12', title: '整理验收', projectName: '平台', excerpt: '你好' } }
describe('notification presentation', () => {
  it('纯图片讨论以图片摘要展示，不可访问的摘要仍隐藏', () => {
    expect(notificationExcerpt({ ...item, target: { ...item.target, excerpt: ' ' } })).toBe('[图片]')
    expect(notificationExcerpt(item)).toBe('你好')
    expect(notificationExcerpt({ ...item, target: { ...item.target, accessible: false, excerpt: '[图片]' } })).toBe('')
    expect(notificationExcerpt({ ...item, reason: NotificationReason.Assigned, target: { ...item.target, excerpt: null } })).toBe('')
  })
  it.each([
    [NotificationReason.Mention, '在 YP-12 整理验收 中提到了你'], [NotificationReason.Reply, '回复了你在 YP-12 整理验收 的评论'],
    [NotificationReason.Comment, '评论了 YP-12 整理验收'], [NotificationReason.Assigned, '将 YP-12 整理验收 指派给你'],
    [NotificationReason.ProjectMemberAdded, '将你加入项目 平台'], [NotificationReason.ProjectMemberRemoved, '将你移出了一个项目'],
    [NotificationReason.ProjectOwnerAssigned, '将项目 平台 的负责人转交给你'], [NotificationReason.ProjectOwnerTransferred, '将项目 平台 的负责人转交给 你'],
  ] as const)('formats %s', (reason, text) => expect(notificationText({ ...item, reason }, 'reader')).toBe(text))
  it('names another membership subject for the project owner', () => {
    expect(notificationText({ ...item, reason: NotificationReason.ProjectMemberAdded }, 'owner')).toBe('将李四加入项目 平台')
  })
  it('renders connection notifications from target data and hides their link after access is lost', () => {
    const connection = { ...item, reason: NotificationReason.ConnectionCreated }
    expect(notificationText(connection)).toBe('向你负责的项目新建了 YP-12 整理验收')
    expect(notificationLink(connection)).toEqual({ path: '/projects/project/overview', query: { workItemId: 'item' } })
    const hidden = { ...connection, target: { ...connection.target, accessible: false } }
    expect(notificationText(hidden)).toBe('相关内容已不可访问')
    expect(notificationLink(hidden)).toBeUndefined()
  })
  it.each([
    [NotificationReason.ProjectDeletionScheduled, '已申请删除项目 平台，将于 2026-11-08 永久删除'],
    [NotificationReason.ProjectDeletionReminder, '提醒：项目 平台 将于 2026-11-08 永久删除'],
    [NotificationReason.ProjectDeletionCancelled, '已撤销项目 平台 的删除计划'],
  ] as const)('renders %s with the event deadline and a project link', (reason, expected) => {
    const deletion: NotificationItem = { ...item, reason, deletionPurgeAfter: new Date('2026-11-08T02:00:00Z'),
      target: { kind: NotificationTargetKind.Project, accessible: true, projectId: 'project', projectName: '平台' } }
    expect(notificationText(deletion, 'reader', 'Asia/Shanghai')).toBe(expected)
    expect(notificationLink(deletion)).toEqual({ path: '/projects/project/overview', query: {} })
    const hidden = { ...deletion, target: { ...deletion.target, accessible: false } }
    expect(notificationText(hidden)).toBe('相关内容已不可访问')
    expect(notificationLink(hidden)).toBeUndefined()
  })
  it('uses the company calendar date for a deletion deadline across UTC midnight', () => {
    expect(notificationText({ ...item, reason: NotificationReason.ProjectDeletionReminder,
      deletionPurgeAfter: new Date('2026-11-08T02:00:00Z') }, undefined, 'America/New_York'))
      .toBe('提醒：项目 平台 将于 2026-11-07 永久删除')
    expect(notificationText({ ...item, reason: NotificationReason.ProjectDeletionReminder, deletionPurgeAfter: null }))
      .toBe('提醒：项目 平台 即将永久删除')
  })
  it('explains removal to the removed reader even after project access is lost', () => {
    const removed = { ...item, reason: NotificationReason.ProjectMemberRemoved, target: { ...item.target, accessible: false } }
    expect(notificationText(removed, 'reader')).toBe('将你移出了一个项目')
    expect(notificationText(removed, 'owner')).toBe('相关内容已不可访问')
    expect(notificationLink(removed, 'reader')).toBeUndefined()
  })
  it('never exposes inaccessible titles or deep links', () => {
    const hidden = { ...item, target: { ...item.target, accessible: false } }
    expect(notificationText(hidden)).toBe('相关内容已不可访问')
    expect(notificationLink(hidden)).toBeUndefined()
    expect(notificationLink({ ...item, reason: NotificationReason.ProjectMemberRemoved }, 'reader')).toBeUndefined()
  })
  it('opens discussion for comments and the item for assignments', () => {
    expect(notificationLink(item)).toEqual({ path: '/projects/project/overview', query: { workItemId: 'item', tab: 'discussion' } })
    expect(notificationLink({ ...item, reason: NotificationReason.Assigned })).toEqual({ path: '/projects/project/overview', query: { workItemId: 'item' } })
  })
  it('uses company calendar days across UTC midnight and DST', () => {
    const now = new Date('2026-09-26T01:00:00Z')
    expect(notificationDateGroup(new Date('2026-09-25T23:00:00Z'), now, 'America/New_York')).toBe('今天')
    expect(notificationDateGroup(new Date('2026-09-24T23:00:00Z'), now, 'America/New_York')).toBe('昨天')
    expect(notificationDateGroup(new Date('2026-09-21T15:00:00Z'), now, 'America/New_York')).toBe('本周')
    expect(notificationDateGroup(new Date('2026-09-20T15:00:00Z'), now, 'America/New_York')).toBe('更早')
    expect(notificationDateGroup(new Date('2026-03-08T04:00:00Z'), new Date('2026-03-09T03:00:00Z'), 'America/New_York')).toBe('昨天')
  })
  it('falls back to unread and handles clock skew', () => {
    expect(parseInboxFilter(['all'])).toBe('unread')
    expect(relativeTime(new Date(1000), new Date(0))).toBe('刚刚')
  })
})
