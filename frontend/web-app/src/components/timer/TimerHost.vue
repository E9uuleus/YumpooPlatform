<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElButton, ElDialog } from 'element-plus'
import type { DesktopTimerState, TimerOrbLayout } from '@yumpoo/preload-contract'
import { useSession } from '../../composables/useSession'
import { activateTimeTracker, timerExitPrompt, chooseTimerExit, confirmTimerExit, formatDuration, useTimeTracker } from '../../composables/useTimeTracker'
import { logoutGuard } from '../../composables/logoutGuard'
import TimerPanel from './TimerPanel.vue'
import TimerIcon from './TimerIcon.vue'

const route = useRoute()
const session = useSession()
const tracker = useTimeTracker()
const desktop = window.yumpooDesktop?.timer
const inline = ref(false)
const expanded = ref(true)
const orb = ref<TimerOrbLayout>({ size: 176, side: null, detailWidth: 0 })
const surface = ref<'main' | 'timer'>()
const floating = ref<HTMLElement>()
const position = ref<{ left: number; top: number }>()
const pipTarget = ref<HTMLElement>()
let pipWindow: Window | undefined
let opening = false
let openRevision = 0
let disposed = false
let themeObserver: MutationObserver | undefined
let endDrag: (() => void) | undefined
const standalone = computed(() => route.path === '/timer')
const authenticated = computed(() => session.phase.value === 'authenticated')
if (desktop) void desktop.getWindowState().then(state => { if (!disposed) surface.value = state.surface }).catch(() => undefined)
watch([authenticated, () => session.authentication.value?.user.id], ([active]) => {
  activateTimeTracker(active)
  if (!active) { inline.value = false; pipWindow?.close() }
}, { immediate: true })
watch(() => route.fullPath, () => { void tracker.refresh().catch(() => undefined) })
const desktopState = computed(() => {
  const current = tracker.current.value
  const running = current?.session
  const recent = current?.recentItems[0]
  return { accountId: authenticated.value ? session.authentication.value?.user.id ?? null : null,
    rowVersion: current?.rowVersion ?? 0, connected: tracker.connected.value, busy: tracker.busy.value,
    savedAt: tracker.savedAt.value,
    running: running ? { sessionId: running.id, workItemId: running.workItemId, title: current?.workItemTitle ?? '不可见工作项', startedAt: running.startedAt.toISOString() } : null,
    recent: recent ? { workItemId: recent.workItemId, title: recent.title } : null,
  } satisfies DesktopTimerState
})
watch([surface, desktopState], ([owner, state]) => {
  if (owner === 'main' && session.phase.value !== 'checking') void desktop?.publishState(state).catch(() => undefined)
}, { immediate: true })

const beforeUnload = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
watch(() => !!tracker.current.value?.session, running => {
  window.removeEventListener('beforeunload', beforeUnload)
  if (running && !desktop) window.addEventListener('beforeunload', beforeUnload)
}, { immediate: true })
logoutGuard.check = confirmTimerExit
const offExit = desktop?.onExitRequest(async requestId => {
  await desktop.acknowledgeExit(requestId)
  const allow = !authenticated.value || await confirmTimerExit()
  await desktop.completeExit(requestId, allow)
})
const offCommand = desktop?.onCommand(async command => {
  const success = authenticated.value && (command.action === 'stop' ? await tracker.stop(command.sessionId) : await tracker.start(command.workItemId))
  await desktop.completeCommand(command.requestId, success).catch(() => undefined)
})
const openFromToolbar = () => { void open() }
const started = () => {
  if (standalone.value) return
  resizePip(false)
  if (desktop) void desktop.show('compact', false).catch(() => undefined)
  else if (!pipWindow) inline.value = true
}
watch(tracker.savedAt, at => {
  if (!at || standalone.value || desktop || inline.value || pipWindow) return
  resizePip(false)
  inline.value = true
})
window.addEventListener('yumpoo:open-timer', openFromToolbar)
window.addEventListener('yumpoo:timer-started', started)
window.addEventListener('resize', constrainPosition)
onBeforeUnmount(() => {
  disposed = true
  window.removeEventListener('yumpoo:open-timer', openFromToolbar)
  window.removeEventListener('yumpoo:timer-started', started)
  window.removeEventListener('resize', constrainPosition)
  offExit?.(); offCommand?.(); endDrag?.(); themeObserver?.disconnect()
  window.removeEventListener('beforeunload', beforeUnload)
  pipWindow?.close(); activateTimeTracker(false); delete logoutGuard.check
})

async function open() {
  resizePip(true)
  if (desktop) { await desktop.show('picker').catch(() => undefined); return }
  if (pipWindow && !pipWindow.closed) { pipWindow.focus(); resizePip(true); return }
  inline.value = true
  if (opening) return
  const request = ++openRevision
  const api = (window as Window & { documentPictureInPicture?: { requestWindow(options: { width: number; height: number }): Promise<Window> } }).documentPictureInPicture
  if (api && window.isSecureContext) {
    opening = true
    try {
      const target = await api.requestWindow({ width: 392, height: 520 })
      if (disposed || !authenticated.value || request !== openRevision) { target.close(); return }
      pipWindow = target
      for (const style of document.querySelectorAll('style, link[rel="stylesheet"]')) target.document.head.append(style.cloneNode(true))
      const copyTheme = () => {
        target.document.documentElement.className = document.documentElement.className
        target.document.documentElement.style.cssText = document.documentElement.style.cssText
        const theme = document.documentElement.getAttribute('data-theme')
        if (theme) target.document.documentElement.setAttribute('data-theme', theme)
      }
      copyTheme()
      themeObserver = new MutationObserver(copyTheme)
      themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'style', 'data-theme'] })
      target.document.body.style.margin = '0'
      target.document.body.style.minWidth = '0'
      target.document.body.style.display = 'grid'
      target.document.body.style.placeItems = 'center'
      pipTarget.value = target.document.body
      inline.value = false
      target.addEventListener('pagehide', () => {
        pipTarget.value = undefined; pipWindow = undefined; themeObserver?.disconnect()
        if (!disposed && authenticated.value && request === openRevision) inline.value = true
      }, { once: true })
      return
    } catch { /* The same panel remains available in the page when PiP is unavailable. */ }
    finally { opening = false }
  }
  inline.value = true
  await nextTick()
  constrainPosition()
}
function hide() {
  ++openRevision
  inline.value = false
  pipWindow?.close()
}
function resizePip(value: boolean) {
  if (expanded.value === value) return
  if (expanded.value !== value && position.value && floating.value) {
    const rect = floating.value.getBoundingClientRect()
    const width = value ? Math.min(392, window.innerWidth - 24) : orb.value.size
    const height = value ? Math.min(520, window.innerHeight - 32) : orb.value.size
    const right = expanded.value ? rect.right : rect.left + (orb.value.side === 'left' ? orb.value.detailWidth : 0) + orb.value.size
    position.value = { left: right - width, top: rect.bottom - height }
  }
  orb.value = { ...orb.value, side: null, detailWidth: 0, ...(value ? { titleVisible: false } : {}) }
  expanded.value = value
  try { pipWindow?.resizeTo(value ? 392 : orb.value.size, value ? 520 : orb.value.size + (orb.value.titleVisible ? 40 : 0)) } catch { /* Some browsers constrain PiP resizing. */ }
  void nextTick(constrainPosition)
}
function layoutOrb(layout: TimerOrbLayout) {
  if (floating.value && !expanded.value) {
    const rect = floating.value.getBoundingClientRect()
    const circleX = rect.left + (orb.value.side === 'left' ? orb.value.detailWidth : 0)
    position.value = { left: circleX - (layout.side === 'left' ? layout.detailWidth : 0), top: rect.bottom - layout.size - (layout.titleVisible ? 40 : 0) }
  }
  orb.value = layout
  try { pipWindow?.resizeTo(layout.size + layout.detailWidth, layout.size + (layout.titleVisible ? 40 : 0)) } catch { /* The browser may constrain the requested window size. */ }
  void nextTick(constrainPosition)
}
function constrainPosition() {
  if (!expanded.value && floating.value) {
    const size = Math.min(orb.value.size, Math.max(128, window.innerWidth - 176), window.innerHeight - 16 - (orb.value.titleVisible ? 40 : 0))
    const detailWidth = orb.value.side ? Math.min(orb.value.detailWidth, Math.max(0, window.innerWidth - size - 16)) : 0
    if (size !== orb.value.size || detailWidth !== orb.value.detailWidth) {
      orb.value = { ...orb.value, size, detailWidth }
      void nextTick(constrainPosition)
      return
    }
  }
  if (!position.value || !floating.value) return
  const rect = floating.value.getBoundingClientRect()
  position.value = { left: Math.max(8, Math.min(position.value.left, window.innerWidth - rect.width - 8)),
    top: Math.max(8, Math.min(position.value.top, window.innerHeight - rect.height - 8)) }
}
function drag(event: PointerEvent) {
  if (desktop || pipWindow || event.button !== 0 || (event.target as HTMLElement).closest('button') || !floating.value) return
  endDrag?.()
  const rect = floating.value.getBoundingClientRect()
  const startX = event.clientX, startY = event.clientY
  const handle = event.currentTarget as HTMLElement
  handle.setPointerCapture?.(event.pointerId)
  const move = (next: PointerEvent) => {
    position.value = { left: rect.left + next.clientX - startX, top: rect.top + next.clientY - startY }
    constrainPosition()
  }
  endDrag = () => {
    window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', endDrag!); window.removeEventListener('pointercancel', endDrag!)
    window.removeEventListener('blur', endDrag!)
    if (handle.hasPointerCapture?.(event.pointerId)) handle.releasePointerCapture(event.pointerId)
    endDrag = undefined
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', endDrag, { once: true })
  window.addEventListener('pointercancel', endDrag, { once: true })
  window.addEventListener('blur', endDrag, { once: true })
}
</script>

<template>
  <button
    v-if="authenticated && !standalone && !inline && !pipTarget"
    class="global-timer-entry"
    :class="{ 'is-running': tracker.current.value?.session }"
    aria-label="查找工作与计时"
    @click="open"
  >
    <TimerIcon name="clock" /><span>{{ tracker.current.value?.session ? formatDuration(tracker.runningDuration.value) : '开始工作' }}</span>
  </button>
  <el-dialog
    :model-value="timerExitPrompt"
    title="退出前处理计时"
    width="min(460px, 92vw)"
    :close-on-click-modal="false"
    @close="chooseTimerExit('cancel')"
  >
    <p>计时会持续到主动暂停。关闭主窗口或隐藏浮窗不会暂停。</p>
    <template #footer>
      <el-button @click="chooseTimerExit('cancel')">
        取消
      </el-button><el-button @click="chooseTimerExit('continue')">
        继续计时并退出
      </el-button><el-button
        type="primary"
        @click="chooseTimerExit('stop')"
      >
        停止并退出
      </el-button>
    </template>
  </el-dialog>
  <div
    v-if="inline && authenticated && !standalone"
    ref="floating"
    class="timer-floating"
    :class="{ 'is-orb': !expanded }"
    :style="{ ...(position ? { left: `${position.left}px`, top: `${position.top}px`, right: 'auto', bottom: 'auto' } : {}), ...(!expanded ? { width: `${orb.size + orb.detailWidth}px`, height: `${orb.size + (orb.titleVisible ? 40 : 0)}px` } : {}) }"
  >
    <TimerPanel
      floating
      :initial-expanded="expanded"
      :orb-layout="orb"
      @hide="hide"
      @mode="resizePip"
      @drag="drag"
      @orb="layoutOrb"
    />
  </div>
  <Teleport
    v-if="pipTarget && authenticated"
    :to="pipTarget"
  >
    <TimerPanel
      floating
      :initial-expanded="expanded"
      :orb-layout="orb"
      @hide="hide"
      @mode="resizePip"
      @orb="layoutOrb"
    />
  </Teleport>
</template>

<style scoped>
.global-timer-entry{position:fixed;right:24px;bottom:22px;z-index:1000;display:flex;align-items:center;gap:9px;padding:11px 14px;border:1px solid var(--yp-border-subtle);border-radius:12px;background:var(--yp-bg-surface);color:var(--yp-text-primary);box-shadow:var(--yp-shadow-popover);font:600 13px var(--yp-font-family);font-variant-numeric:tabular-nums;cursor:pointer}.global-timer-entry:hover{border-color:var(--yp-action-primary)}.global-timer-entry>svg{color:var(--yp-action-primary)}.global-timer-entry.is-running>svg{color:var(--el-color-success)}.global-timer-entry:focus-visible{outline:2px solid var(--yp-action-primary);outline-offset:3px}.timer-floating{position:fixed;right:24px;bottom:22px;width:min(392px,calc(100vw - 24px));z-index:1001;box-shadow:var(--yp-shadow-overlay);border-radius:14px}.timer-floating :deep(.timer-panel){max-height:calc(100dvh - 32px)}.timer-floating :deep(.panel-header){cursor:grab}.timer-floating :deep(.panel-header:active){cursor:grabbing}
@media(max-width:440px){.timer-floating,.global-timer-entry{right:12px;bottom:12px}}
.timer-floating.is-orb{width:176px;height:176px;box-shadow:none;border-radius:50%}.timer-floating{transform-origin:bottom right;animation:timer-appear .24s cubic-bezier(.2,.8,.2,1)}.global-timer-entry{transition:transform .18s,box-shadow .18s}.global-timer-entry:active{transform:scale(.95)}@keyframes timer-appear{from{opacity:0;transform:scale(.9) translateY(8px)}to{opacity:1;transform:none}}@media(prefers-reduced-motion:reduce){.timer-floating{animation:none}.global-timer-entry{transition:none}}
</style>
