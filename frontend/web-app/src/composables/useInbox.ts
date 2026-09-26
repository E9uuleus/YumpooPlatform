import { computed, onScopeDispose, ref, shallowRef, watch, type Ref } from 'vue'
import { readCsrfToken, NotificationListState, NotificationGroup, NotificationState, type NotificationItem, type NotificationUnreadCounts, type ListNotificationsRequest } from '@yumpoo/api-client'
import { notificationsApi } from '../api/client'

export type InboxFilter = 'unread' | 'all' | 'mention' | 'comment' | 'assigned' | 'project' | 'archived'
const counts = shallowRef<NotificationUnreadCounts>()
const latest = shallowRef<NotificationItem[]>([])
const revision = ref(0)
const ready = ref(false)
let generation = 0
let enabled = false
let refreshPromise: Promise<void> | undefined
let cleanup: (() => void) | undefined
let channel: BroadcastChannel | undefined
let latestAt: number | undefined
let latestDirty = true
let mutationRevision = 0

export function inboxQuery(filter: InboxFilter): Pick<ListNotificationsRequest, 'state' | 'group'> {
  return {
    state: filter === 'unread' ? NotificationListState.Unread : filter === 'archived' ? NotificationListState.Archived : NotificationListState.All,
    ...(filter === 'mention' ? { group: NotificationGroup.Mention } : filter === 'comment' ? { group: NotificationGroup.Comment }
      : filter === 'assigned' ? { group: NotificationGroup.Assigned } : filter === 'project' ? { group: NotificationGroup.Project } : {}),
  }
}

export function useInbox() {
  return { counts, latest, ready, revision, unread: computed(() => counts.value?.total ?? 0), refresh, changeState, readAll }
}

export function activateInbox(active: boolean) {
  cleanup?.(); cleanup = undefined
  ++generation
  enabled = active
  counts.value = undefined; latest.value = []; ready.value = false
  latestAt = undefined
  latestDirty = true
  refreshPromise = undefined
  ++revision.value
  if (!active) return
  channel = typeof BroadcastChannel === 'undefined' ? undefined : new BroadcastChannel('yumpoo-inbox')
  const invalidate = () => {
    latestDirty = true; ++mutationRevision; ++revision.value
    if (refreshPromise) void refreshPromise.then(() => refresh())
    else void refresh()
  }
  if (channel) channel.onmessage = invalidate
  const visible = () => { if (window.yumpooDesktop || document.visibilityState === 'visible') void refresh() }
  const poll = window.setInterval(visible, 30_000)
  window.addEventListener('focus', visible); window.addEventListener('online', visible)
  document.addEventListener('visibilitychange', visible)
  cleanup = () => {
    window.clearInterval(poll); channel?.close(); channel = undefined
    window.removeEventListener('focus', visible); window.removeEventListener('online', visible)
    document.removeEventListener('visibilitychange', visible)
  }
  void refresh()
}

function acceptCounts(value: NotificationUnreadCounts) {
  if (!counts.value || value.serverNow >= counts.value.serverNow) counts.value = value
}

async function refresh(): Promise<void> {
  if (!enabled) return
  if (refreshPromise) return refreshPromise
  const epoch = generation
  const version = mutationRevision
  const operation = (async () => {
    try {
      const result = await notificationsApi.getNotificationUnreadCounts()
      if (epoch !== generation || version !== mutationRevision) return
      const changed = latestDirty || !ready.value || result.newestUnreadAt?.getTime() !== latestAt || result.total !== counts.value?.total
      if (changed) {
        ++revision.value
        const page = await notificationsApi.listNotifications({ state: NotificationListState.Unread, limit: 5 })
        if (epoch !== generation || version !== mutationRevision) return
        latest.value = page.items.slice(0, result.total)
        latestAt = result.newestUnreadAt?.getTime()
        latestDirty = false
      }
      acceptCounts(result)
      ready.value = true
    } catch { /* Polling failures must not interrupt the active workspace. */ }
  })()
  refreshPromise = operation
  try { await operation } finally { if (refreshPromise === operation) refreshPromise = undefined }
}

async function mutation(call: (csrf: string) => Promise<NotificationUnreadCounts>): Promise<boolean> {
  if (!enabled) return false
  const csrf = readCsrfToken()
  if (!csrf) return false
  const epoch = generation
  ++mutationRevision
  try {
    const value = await call(csrf)
    if (epoch !== generation) return false
    ++mutationRevision
    if (!counts.value || value.serverNow >= counts.value.serverNow) {
      latest.value = []
      latestDirty = true
      acceptCounts(value)
    }
    ++revision.value; channel?.postMessage({ changed: true })
    if (refreshPromise) void refreshPromise.then(() => refresh())
    else void refresh()
    return true
  } catch { return false }
}

function changeState(id: string, action: 'read' | 'unread' | 'archive') {
  const method = action === 'read' ? 'markNotificationRead' : action === 'unread' ? 'markNotificationUnread' : 'archiveNotification'
  return mutation(xXSRFTOKEN => notificationsApi[method]({ id, xXSRFTOKEN }))
}

function readAll(upTo: Date, filter: InboxFilter) {
  const group = inboxQuery(filter).group
  return mutation(xXSRFTOKEN => notificationsApi.markAllNotificationsRead({ xXSRFTOKEN,
    notificationReadAllRequest: { upTo, ...(group ? { group } : {}) },
  }))
}

export function useInboxList(filter: Ref<InboxFilter>, active: Ref<boolean> = ref(true)) {
  const items = shallowRef<NotificationItem[]>([])
  const loading = ref(false)
  const error = ref('')
  const cursor = ref<string | null>()
  const serverNow = ref<Date>()
  let request = 0
  let listGeneration = generation
  async function load(more = false) {
    if (!active.value || !enabled || (more && (loading.value || !cursor.value))) return
    const serial = ++request, epoch = generation
    loading.value = true; error.value = ''
    try {
      const page = await notificationsApi.listNotifications({ ...inboxQuery(filter.value), limit: 20,
        ...(more && cursor.value ? { cursor: cursor.value } : {}) })
      if (serial !== request || epoch !== generation || !active.value) return
      items.value = more ? [...items.value, ...page.items.filter(item => !items.value.some(existing => existing.id === item.id))] : page.items
      cursor.value = page.nextCursor; serverNow.value = page.serverNow
    } catch { if (serial === request && epoch === generation) error.value = '通知暂时无法加载，请重试。' }
    finally { if (serial === request) loading.value = false }
  }
  watch([filter, active, revision], ([nextFilter], previous) => {
    ++request; loading.value = false
    if (listGeneration !== generation || nextFilter !== previous[0]) {
      items.value = []; cursor.value = undefined; serverNow.value = undefined; error.value = ''
      listGeneration = generation
    }
    if (!active.value || !enabled) { items.value = []; cursor.value = undefined; serverNow.value = undefined; return }
    void load()
  }, { immediate: true })
  onScopeDispose(() => { ++request })
  async function setState(item: NotificationItem, action: 'read' | 'unread' | 'archive') {
    const previous = item.state
    item.state = action === 'read' ? NotificationState.Read : action === 'unread' ? NotificationState.Unread : NotificationState.Archived
    items.value = [...items.value]
    const success = await changeState(item.id, action)
    if (!success) { item.state = previous; items.value = [...items.value]; error.value = '操作未完成，请重试。' }
    return success
  }
  return { items, loading, error, cursor, serverNow, load, setState }
}
