<script setup lang="ts">
import { ElAlert, ElButton } from 'element-plus'
import { onBeforeUnmount, ref } from 'vue'
import type { DesktopUpdateState } from '@yumpoo/preload-contract'

// Only shells with in-app updates expose this bridge; browsers and older shells never show the banner.
const bridge = window.yumpooDesktop?.update
const state = ref<DesktopUpdateState>()
const dismissedVersion = ref<string>()
const restarting = ref(false)
const failed = ref(false)

const offState = bridge?.onState(value => { state.value = value })
void bridge?.getState().then(value => { state.value ??= value }).catch(() => undefined)
onBeforeUnmount(() => offState?.())

async function restart(): Promise<void> {
  if (!bridge || restarting.value) return
  restarting.value = true
  failed.value = false
  try {
    await bridge.restart()
  } catch {
    failed.value = true
  } finally {
    restarting.value = false
  }
}
</script>

<template>
  <el-alert
    v-if="state?.phase === 'ready' && state.version && state.version !== dismissedVersion"
    class="desktop-update-banner"
    type="success"
    :closable="false"
    show-icon
    :title="`Yumpoo Desktop ${state.version} 已下载并通过校验`"
  >
    <p class="desktop-update-banner__text">
      重启后完成更新，未保存的计时会先请你确认。也可以稍后关闭应用时自动安装。
    </p>
    <p
      v-if="failed"
      class="desktop-update-banner__error"
      role="alert"
    >
      无法开始更新，请稍后再试。
    </p>
    <div class="desktop-update-banner__actions">
      <el-button
        type="primary"
        size="small"
        :loading="restarting"
        @click="restart"
      >
        重启以更新
      </el-button>
      <el-button
        size="small"
        @click="dismissedVersion = state.version"
      >
        稍后
      </el-button>
    </div>
  </el-alert>
</template>

<style scoped>
.desktop-update-banner {
  margin-bottom: 12px;
}

.desktop-update-banner__text,
.desktop-update-banner__error {
  margin: 4px 0 0;
}

.desktop-update-banner__error {
  color: var(--el-color-danger);
}

.desktop-update-banner__actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
</style>
