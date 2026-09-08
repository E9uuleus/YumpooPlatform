<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElButton, ElDrawer, ElDialog } from 'element-plus'
import { useSession } from '../../composables/useSession'
import { activateTimeTracker, timerExitPrompt, chooseTimerExit, confirmTimerExit, formatDuration, useTimeTracker } from '../../composables/useTimeTracker'
import { logoutGuard } from '../../composables/logoutGuard'
import TimerPanel from './TimerPanel.vue'

const route = useRoute()
const session = useSession()
const tracker = useTimeTracker()
const inline = ref(false)
const pipTarget = ref<HTMLElement>()
let pipWindow: Window | undefined
const project = computed(() => String(route.params.projectId ?? route.query.projectId ?? ''))
const standalone = computed(() => route.path === '/timer')
const authenticated = computed(() => session.phase.value === 'authenticated')
watch(() => session.authentication.value?.user.id, () => activateTimeTracker(authenticated.value), { immediate: true })
watch(() => route.fullPath, () => { void tracker.refresh().catch(() => undefined) })
watch(project, id => {
  if (id && !standalone.value && authenticated.value) void window.yumpooDesktop?.timer?.setProject(id)
  void tracker.refresh().catch(() => undefined)
}, { immediate: true })
watch(authenticated, active => { if (active && project.value && !standalone.value) void window.yumpooDesktop?.timer?.setProject(project.value) })
const beforeUnload = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
watch(() => !!tracker.current.value?.session, running => {
  window.removeEventListener('beforeunload', beforeUnload)
  if (running && !window.yumpooDesktop) window.addEventListener('beforeunload', beforeUnload)
}, { immediate: true })
logoutGuard.check = confirmTimerExit
const offExit = window.yumpooDesktop?.timer?.onExitRequest(async requestId => {
  await window.yumpooDesktop?.timer.acknowledgeExit(requestId)
  const allow = !authenticated.value || await confirmTimerExit()
  await window.yumpooDesktop?.timer.completeExit(requestId, allow)
})
const openFromToolbar = () => { void open() }
window.addEventListener('yumpoo:open-timer', openFromToolbar)
onBeforeUnmount(() => { window.removeEventListener('yumpoo:open-timer', openFromToolbar); offExit?.(); window.removeEventListener('beforeunload', beforeUnload); pipWindow?.close(); activateTimeTracker(false); delete logoutGuard.check })
async function open() {
  if (window.yumpooDesktop?.timer) { await window.yumpooDesktop.timer.show(); return }
  if (pipWindow && !pipWindow.closed) { pipWindow.focus(); return }
  inline.value = true
  const api = (window as Window & { documentPictureInPicture?: { requestWindow(options: { width: number; height: number }): Promise<Window> } }).documentPictureInPicture
  if (api && window.isSecureContext) {
    try {
      pipWindow = await api.requestWindow({ width: 380, height: 550 })
      for (const style of document.querySelectorAll('style, link[rel="stylesheet"]')) pipWindow.document.head.append(style.cloneNode(true))
      pipWindow.document.documentElement.className = document.documentElement.className
      pipTarget.value = pipWindow.document.body
      inline.value = false
      pipWindow.addEventListener('pagehide', () => { pipTarget.value = undefined; pipWindow = undefined }, { once: true })
      return
    } catch { /* Browser policy may reject picture-in-picture. */ }
  }
  const popup = window.open(`/timer?projectId=${encodeURIComponent(project.value)}`, 'yumpoo-timer', 'popup,width=380,height=550')
  if (!popup) inline.value = true
}
</script>

<template>
  <div v-if="authenticated && !standalone" class="global-timer-entry"><el-button @click="open">{{ tracker.current.value?.session ? `● ${formatDuration(tracker.runningDuration.value)}` : '◷' }} 小计时器</el-button></div>
  <el-dialog :model-value="timerExitPrompt" title="退出前处理计时" width="min(460px, 92vw)" :close-on-click-modal="false" @close="chooseTimerExit('cancel')">
    <p>计时会持续到主动停止。离线时可能无法确认最新状态。</p>
    <template #footer><el-button @click="chooseTimerExit('cancel')">取消</el-button><el-button @click="chooseTimerExit('continue')">继续计时并退出</el-button><el-button type="primary" @click="chooseTimerExit('stop')">停止并退出</el-button></template>
  </el-dialog>
  <el-drawer v-model="inline" title="小计时器" size="380px"><TimerPanel :project-id="project" /></el-drawer>
  <Teleport v-if="pipTarget" :to="pipTarget"><TimerPanel :project-id="project" /></Teleport>
</template>

<style scoped>.global-timer-entry{position:fixed;right:24px;bottom:22px;z-index:1000;box-shadow:0 4px 20px #0002;border-radius:6px}</style>
