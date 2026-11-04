<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import YpModulePage from '../../components/yp/YpModulePage.vue'

const TITLES: Record<string, string> = {
  'company-overview': '概览',
  'company-projects': '项目',
  'company-members': '成员与角色',
  'company-activity': '活动日志',
  'company-sync-runs': '同步运行',
  'company-scheduled-jobs': '定时任务',
  'company-work-calendar': '工作日历',
}
const route = useRoute()
const title = computed(() => TITLES[String(route.name)] ?? '公司管理')
</script>

<template>
  <yp-module-page
    module="company"
    :title="title"
  >
    <router-view />
  </yp-module-page>
</template>
