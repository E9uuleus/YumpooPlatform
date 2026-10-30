<script setup lang="ts">
import PageProgress from './brand/PageProgress.vue'
import TimerHost from './components/timer/TimerHost.vue'
import InboxHost from './components/inbox/InboxHost.vue'
import type { Language } from 'element-plus/es/locale'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { ElConfigProvider as ElConfigProviderRaw } from 'element-plus'
import type { DefineComponent } from 'vue'

const ElConfigProvider = ElConfigProviderRaw as unknown as DefineComponent<{
  locale?: Language
  message?: { showClose?: boolean }
}>
const MESSAGE_CONFIG = { showClose: true }
</script>

<template>
  <el-config-provider
    :locale="zhCn"
    :message="MESSAGE_CONFIG"
  >
    <PageProgress />
    <router-view />
    <TimerHost />
    <InboxHost />
  </el-config-provider>
</template>
