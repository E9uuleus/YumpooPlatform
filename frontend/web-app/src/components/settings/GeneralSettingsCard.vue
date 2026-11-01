<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { DesktopPreferencesChange, DesktopPreferencesState, DesktopUpdateState } from '@yumpoo/preload-contract'
import { ElButton, ElSwitch } from 'element-plus'
import { Operation } from '@element-plus/icons-vue'
import SettingsCard from './SettingsCard.vue'
import { loadDesktopRelease, type DesktopRelease } from '../../views/download/desktopRelease'

const props = defineProps<{ preferences: DesktopPreferencesState; busy: boolean }>()
const emit = defineEmits<{ change: [change: DesktopPreferencesChange] }>()
const bridge = window.yumpooDesktop?.update
const update = ref<DesktopUpdateState>()
const release = ref<DesktopRelease>()
const acting = ref(false)

const offState = bridge?.onState(value => { update.value = value })
void bridge?.getState().then(value => { update.value ??= value }).catch(() => undefined)
onMounted(async () => { release.value = await loadDesktopRelease().catch(() => undefined) })
onBeforeUnmount(() => offState?.())

const summary = computed(() => `登录时启动 ${props.preferences.launchAtLogin ? '开' : '关'} · 自动下载更新 ${props.preferences.autoDownload ? '开' : '关'}`)
const latest = computed(() => {
  const state = update.value, version = state?.version ? `v${state.version}` : ''
  switch (state?.phase) {
    case 'checking': return '检查中…'
    case 'available': return `${version}（可下载）`
    case 'downloading': return `${version}（下载中 ${state.percent ?? 0}%）`
    case 'verifying': return `${version}（校验中）`
    case 'ready': return `${version}（已下载，重启后更新）`
    case 'error': return release.value ? `v${release.value.version}（更新失败，可重试）` : '更新失败，可重试'
  }
  if (!release.value) return '—'
  return release.value.version === state?.currentVersion ? `v${release.value.version}（已是最新）` : `v${release.value.version}`
})
const action = computed(() => {
  const phase = update.value?.phase
  if (!bridge || !phase || phase === 'disabled') return { label: '应用内更新未启用', disabled: true, run: undefined }
  if (phase === 'ready') return { label: '重启以更新', disabled: false, run: () => bridge.restart() }
  if (phase === 'available') return { label: '下载更新', disabled: false, run: () => bridge.download?.() }
  if (phase === 'checking' || phase === 'downloading' || phase === 'verifying') return { label: phase === 'checking' ? '检查中…' : '下载中…', disabled: true, run: undefined }
  return { label: '检查更新', disabled: false, run: () => bridge.check() }
})
async function act() {
  if (!action.value.run || acting.value) return
  acting.value = true
  try { await action.value.run() } catch { /* The update state shows the failure. */ } finally { acting.value = false }
}
</script>

<template>
  <SettingsCard
    title="常规"
    :icon="Operation"
    tag="仅桌面端"
    :summary="summary"
  >
    <div class="settings-section">
      启动
    </div>
    <div class="settings-row">
      <div class="settings-row__label">
        <strong>登录时启动</strong>
        <span>{{ preferences.launchAtLogin === null ? '仅安装版可用。' : '登录 Windows 时在后台启动 YumpooPlatform，只显示托盘与侧边栏。' }}</span>
      </div>
      <el-switch
        :model-value="preferences.launchAtLogin === true"
        :disabled="busy || preferences.launchAtLogin === null"
        aria-label="登录时启动"
        @update:model-value="value => emit('change', { launchAtLogin: value === true })"
      />
    </div>
    <div class="settings-section">
      应用更新<span class="settings-section__aside">公司更新源</span>
    </div>
    <div class="settings-row">
      <div class="settings-row__label">
        <strong>自动下载更新</strong>
        <span>有新版本时在后台下载，签名校验通过后提示重新启动；关闭后检查到新版本时由你手动下载。</span>
      </div>
      <el-switch
        :model-value="preferences.autoDownload"
        :disabled="busy"
        aria-label="自动下载更新"
        @update:model-value="value => emit('change', { autoDownload: value === true })"
      />
    </div>
    <div class="general-versions">
      <div><span>已安装</span><b>v{{ update?.currentVersion ?? '—' }}</b></div>
      <div><span>最新版本</span><b>{{ latest }}</b></div>
    </div>
    <div class="general-action">
      <el-button
        size="small"
        :disabled="action.disabled"
        :loading="acting"
        text
        @click="act"
      >
        {{ action.label }}
      </el-button>
    </div>
    <details
      v-if="release?.notes.length"
      class="general-notes"
    >
      <summary>v{{ release.version }} 更新内容</summary>
      <ul>
        <li
          v-for="note in release.notes"
          :key="note"
        >
          {{ note }}
        </li>
      </ul>
    </details>
  </SettingsCard>
</template>

<style scoped>
.general-versions>div{display:flex;justify-content:space-between;padding:6px 16px;color:var(--yp-text-secondary);font-size:12.5px}
.general-versions b{color:var(--yp-text-primary);font:500 12.5px var(--yp-font-mono)}
.general-action{padding:8px 16px 4px}
.general-notes{padding:2px 16px 12px}
.general-notes summary{color:var(--yp-text-secondary);font-size:12.5px;cursor:pointer}
.general-notes ul{margin:6px 0 0;padding-left:20px;color:var(--yp-text-secondary);font-size:12.5px;line-height:1.8}
</style>
