<script setup lang="ts">
import type { Language } from 'element-plus/es/locale'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { ElConfigProvider as ElConfigProviderRaw } from 'element-plus'
import type { DefineComponent } from 'vue'

const ElConfigProvider = ElConfigProviderRaw as unknown as DefineComponent<{
  locale?: Language
}>
</script>

<template>
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>
