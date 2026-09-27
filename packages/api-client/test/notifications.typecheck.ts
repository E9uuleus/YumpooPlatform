import { NotificationsApi, NotificationGroup, NotificationReason, NotificationListState,
  type NotificationItem, type NotificationReadAllRequest, type NotificationUnreadCounts } from '../src/generated/index.js'

const api = new NotificationsApi()
const request: NotificationReadAllRequest = { upTo: new Date(), group: NotificationGroup.Mention }
void api.listNotifications({ state: NotificationListState.Unread, limit: 20 })
void api.getNotificationUnreadCounts()
void api.markAllNotificationsRead({ notificationReadAllRequest: request, xXSRFTOKEN: 'csrf' })
void api.markNotificationRead({ id: '00000000-0000-4000-8000-000000000001', xXSRFTOKEN: 'csrf' })
void api.markNotificationUnread({ id: '00000000-0000-4000-8000-000000000001', xXSRFTOKEN: 'csrf' })
void api.archiveNotification({ id: '00000000-0000-4000-8000-000000000001', xXSRFTOKEN: 'csrf' })
export const mentionReason: NotificationItem['reason'] = NotificationReason.Mention
export function hasUnread(counts: NotificationUnreadCounts): boolean { return counts.total > 0 }
