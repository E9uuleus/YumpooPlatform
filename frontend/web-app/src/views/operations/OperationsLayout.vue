<script setup lang="ts">
import { computed, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton } from 'element-plus'
import { operationsApi } from '../../api/client'
import { useOperationsQuery } from '../../composables/useOperationsQuery'
import { useSession } from '../../composables/useSession'
import { operationsContext } from '../../components/operations/operationsContext'
import { duration, time } from '../../components/operations/operationsPresentation'
import YpPageHeader from '../../components/yp/YpPageHeader.vue'
import InlineProblem from '../../components/InlineProblem.vue'
import OpsSegmented from '../../components/operations/OpsSegmented.vue'
import './operations.css'
const route = useRoute(),
  session = useSession(),
  interval = ref(route.name === 'operations-sessions' ? 30 : 60),
  refreshVersion = ref(0)
const router = useRouter()
watch(
  () => [session.phase.value, session.isPlatformAdmin.value],
  () => {
    if (session.phase.value === 'authenticated' && !session.isPlatformAdmin.value)
      void router.replace({ name: 'forbidden' })
  },
)
const title = computed(
  () =>
    ({
      'operations-overview': '概览',
      'operations-host': '主机与运行时',
      'operations-sessions': '在线会话',
      'operations-logs': '运行日志',
      'operations-alerts': '告警',
    })[String(route.name)] ?? '运维中心',
)
const intervalOptions = [
  { value: 0, label: '关' },
  { value: 15, label: '15 秒' },
  { value: 30, label: '30 秒' },
  { value: 60, label: '60 秒' },
]
function schemaLabel(version: string): string {
  return /^\d/.test(version) ? 'V' + version : version
}
const hasActions = computed(() =>
  ['operations-overview', 'operations-host', 'operations-sessions'].includes(String(route.name)),
)
const query = useOperationsQuery((signal) => operationsApi.getOperationsOverview({ signal }), {
  interval: () => (hasActions.value ? interval.value * 1000 : 0),
  enabled: () => session.isPlatformAdmin.value,
})
provide(operationsContext, { ...query, interval, refreshVersion })
function refresh() {
  refreshVersion.value++
  void query.refresh()
}
watch(
  () => route.name,
  (next, previous) => {
    if (next === 'operations-sessions') interval.value = 30
    else if (previous === 'operations-sessions' && interval.value === 30) interval.value = 60
    if (hasActions.value) void query.refresh()
  },
)
</script>
<template>
  <section class="operations-page">
    <yp-page-header :title="title">
      <template #meta>
        <span class="ops-muted">运维中心</span>
        <template v-if="query.data.value">
          <span>{{ query.data.value.runtime.host.hostName }}</span>
          <span class="ops-code">{{ query.data.value.runtime.host.version }} ·
            {{ query.data.value.runtime.host.commit.slice(0, 8) }}</span>
          <span>迁移 {{ schemaLabel(query.data.value.runtime.host.schemaVersion) }}</span>
          <span>已运行 {{ duration(query.data.value.runtime.host.uptimeMs) }}</span>
        </template>
      </template>
      <template
        v-if="hasActions"
        #actions
      >
        <ops-segmented
          group-label="自动刷新间隔"
          :options="intervalOptions"
          :selected="interval"
          @select="interval = Number($event)"
        />
        <el-button
          :loading="query.loading.value"
          @click="refresh"
        >
          刷新
        </el-button>
        <span class="ops-muted operations-updated">更新于 {{ time(query.updatedAt.value) }}</span>
      </template>
    </yp-page-header>
    <inline-problem
      v-if="query.error.value"
      :problem="query.error.value"
      title="运行信息暂时不可用；已显示的数据可能过期"
    />
    <router-view v-if="session.isPlatformAdmin.value && session.phase.value === 'authenticated'" />
  </section>
</template>

<style scoped>
.operations-updated {
  font-variant-numeric: tabular-nums;
}
</style>
