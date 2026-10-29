<script setup lang="ts">
import { usePageLoad } from '../../brand/page-progress'

import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ProjectLifecycle } from '@yumpoo/api-client'
import type { DesktopPreferencesChange, DesktopPreferencesState, RailProject, TimerPreferences, TimerPreferencesChange } from '@yumpoo/preload-contract'
import { useSession } from '../../composables/useSession'
import { useProjectRecents } from '../../composables/useProjectRecents'
import WindowSettingsCard from '../../components/settings/WindowSettingsCard.vue'
import GeneralSettingsCard from '../../components/settings/GeneralSettingsCard.vue'
import AppearanceSettingsCard from '../../components/settings/AppearanceSettingsCard.vue'
import YpModulePage from '../../components/yp/YpModulePage.vue'
import { plainRailProjects } from '../../components/desktop/railProjects'

// Shells before 1.1.0 and browsers have no preferences bridge: the page then offers appearance only.
const pageLoad = usePageLoad()
const bridge = window.yumpooDesktop?.preferences
const timerBridge = window.yumpooDesktop?.timer
const route = useRoute()
const session = useSession()
const preferences = ref<DesktopPreferencesState>()
const timer = ref<TimerPreferences>()
const pinned = ref(true)
const busy = ref(false)
const scope = computed(() => {
  const authentication = session.authentication.value
  return authentication ? `${authentication.company.id}:${authentication.user.id}` : undefined
})
const recents = useProjectRecents(() => scope.value)
const defaults = computed<RailProject[]>(() => recents.items.value.filter(project => project.lifecycle === ProjectLifecycle.Active && !project.endedAt).slice(0, 6)
  .map(project => ({ id: project.id, name: project.name.slice(0, 64) })))
const projects = computed(() => preferences.value?.railProjects?.owner === scope.value ? preferences.value!.railProjects!.items : defaults.value)

async function refresh() {
  if (!bridge || !timerBridge) return
  const [state, surface] = await Promise.all([bridge.get(), timerBridge.getWindowState()])
  preferences.value = state
  timer.value = surface.preferences
  pinned.value = surface.pinned
}
async function run(action: () => Promise<unknown>) {
  if (busy.value) return
  busy.value = true
  try { await action() } catch (error) { console.error('[YUMPOO_SETTINGS_SAVE_FAILED]', error); ElMessage.error('设置未能保存，请重试。') } finally { busy.value = false }
  await refresh().catch(() => undefined)
}
function change(value: DesktopPreferencesChange) {
  // Without the rail there is nowhere to show the timer slot, so the timer falls back to the orb.
  void run(async () => {
    preferences.value = await bridge!.set(value)
    if (value.railEnabled === false && timer.value?.display === 'dock') await timerBridge!.setPreferences?.({ display: 'orb' })
  })
}
function changeTimer(value: TimerPreferencesChange) {
  void run(() => timerBridge!.setPreferences!(value))
}
function pin(value: boolean) {
  void run(() => timerBridge!.setAlwaysOnTop(value))
}
function shortcut(value: string | null) {
  void run(async () => {
    if (!await bridge!.setShortcut(value)) ElMessage.warning('这个组合键已被系统或其他应用占用，仍使用原来的快捷键。')
  })
}
function saveProjects(items: RailProject[]) {
  if (scope.value) change({ railProjects: { owner: scope.value, items: plainRailProjects(items) } })
}

const offChange = bridge?.onChange(value => { preferences.value = value })
const focus = () => { void refresh().catch(() => undefined) }
onMounted(async () => {
  window.addEventListener('focus', focus)
  await pageLoad.track(refresh).catch(() => undefined)
  if (route.query.section !== 'rail') return
  await nextTick()
  document.getElementById('settings-rail-projects')?.scrollIntoView({ block: 'center' })
})
onBeforeUnmount(() => { offChange?.(); window.removeEventListener('focus', focus) })
</script>

<template>
  <yp-module-page
    module="settings"
    title="偏好设置"
  >
    <div class="settings-page">
      <div class="settings-page__cards">
        <template v-if="preferences && timer">
          <WindowSettingsCard
            :preferences="preferences"
            :timer="timer"
            :pinned="pinned"
            :projects="projects"
            :defaults="defaults"
            :busy="busy"
            @change="change"
            @timer="changeTimer"
            @pin="pin"
            @shortcut="shortcut"
            @projects="saveProjects"
          />
          <GeneralSettingsCard
            :preferences="preferences"
            :busy="busy"
            @change="change"
          />
        </template>
        <AppearanceSettingsCard :desktop="!!bridge" />
      </div>
    </div>
  </yp-module-page>
</template>

<style scoped>
.settings-page{box-sizing:border-box;max-width:760px;margin:0 auto;color:var(--yp-text-primary);font-size:13px}
.settings-page__cards{display:flex;flex-direction:column;gap:12px}
</style>
