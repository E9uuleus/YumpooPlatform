<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { DesktopInboxReason } from '@yumpoo/preload-contract'
import { activateInbox, useInbox } from '../../composables/useInbox'
import { useSession } from '../../composables/useSession'
import { notificationLink } from './inboxPresentation'

const route = useRoute(), router = useRouter(), session = useSession(), inbox = useInbox()
const bridge = window.yumpooDesktop?.inbox
const surface = ref<'main' | 'timer' | undefined>(window.yumpooDesktop ? undefined : 'main')
let disposed = false
if (window.yumpooDesktop?.timer) void window.yumpooDesktop.timer.getWindowState()
  .then(state => { if (!disposed) surface.value = state.surface }).catch(() => undefined)
else if (window.yumpooDesktop) surface.value = 'main'
const active = computed(() => session.phase.value === 'authenticated' && !route.path.startsWith('/timer') && surface.value === 'main')
watch([active, () => session.authentication.value?.user.id], ([value]) => activateInbox(value), { immediate: true })
const state = computed(() => ({
  accountId: active.value ? session.authentication.value?.user.id ?? null : null,
  unreadCount: active.value ? inbox.unread.value : 0,
  latest: active.value ? inbox.latest.value.slice(0, 5).flatMap(item => isDesktopReason(item.reason) ? [{ id: item.id, reason: item.reason,
    actorName: item.actor?.displayName ?? null, createdAt: item.createdAt.toISOString() }] : []) : [],
}))
function isDesktopReason(value: string): value is DesktopInboxReason {
  return ['MENTION', 'REPLY', 'COMMENT', 'ASSIGNED', 'PROJECT_MEMBER_ADDED', 'PROJECT_MEMBER_REMOVED', 'PROJECT_OWNER_ASSIGNED', 'PROJECT_OWNER_TRANSFERRED'].includes(value)
}
watch(state, value => {
  if (surface.value === 'main' && (inbox.ready.value || !active.value)) void bridge?.publishState(value).catch(() => undefined)
}, { immediate: true })
const offOpen = bridge?.onOpen(id => {
  if (!active.value) return
  const item = inbox.latest.value.find(entry => entry.id === id)
  const link = item && notificationLink(item, session.authentication.value?.user.id)
  if (item && link) { void inbox.changeState(item.id, 'read'); void router.push(link) }
  else void router.push('/inbox')
})
onBeforeUnmount(() => {
  disposed = true; offOpen?.(); activateInbox(false)
  if (surface.value === 'main') void bridge?.publishState({ accountId: null, unreadCount: 0, latest: [] }).catch(() => undefined)
})
</script>

<template>
  <span
    hidden
    aria-hidden="true"
  />
</template>
