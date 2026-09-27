<script setup lang="ts">
import { ElOption } from '../../components/operations/elementPlus'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElSelect, ElProgress } from 'element-plus'
import { GetOperationsMetricsRangeEnum } from '@yumpoo/api-client'
import { operationsApi } from '../../api/client'
import { useOperations } from '../../components/operations/operationsContext'
import { useOperationsQuery } from '../../composables/useOperationsQuery'
import {
  bytes,
  time,
  duration,
  volumePurpose,
} from '../../components/operations/operationsPresentation'
import OperationsChart from '../../components/operations/OperationsChart.vue'
import InlineProblem from '../../components/InlineProblem.vue'
const overview = useOperations(),
  host = computed(() => overview.data.value?.runtime.host)
const route = useRoute(),
  router = useRouter()
const readRange = () =>
  Object.values(GetOperationsMetricsRangeEnum).find((value) => value === route.query.range) ??
  GetOperationsMetricsRangeEnum._1h
const range = ref<GetOperationsMetricsRangeEnum>(readRange())
const query = useOperationsQuery(
  (signal) => operationsApi.getOperationsMetrics({ range: range.value }, { signal }),
  { interval: () => overview.interval.value * 1000 },
)
const rules = useOperationsQuery((signal) => operationsApi.listOperationsAlertRules({ signal }))
watch(overview.refreshVersion, () => {
  void query.refresh()
  void rules.refresh()
})
watch(range, () => {
  void router.replace({ query: { ...route.query, range: range.value } })
  void query.refresh()
})
watch(
  () => route.query.range,
  () => {
    range.value = readRange()
  },
)
const charts: { title: string; keys: string[]; rule?: string }[] = [
  { title: 'CPU 使用率', keys: ['cpu.system', 'cpu.process'], rule: 'HOST_CPU_HIGH' },
  { title: '物理内存与 JVM 堆', keys: ['memory.used', 'heap.used', 'heap.max'] },
  { title: '请求与 5xx（次/分钟）', keys: ['http.requests', 'http.errors'] },
  { title: '业务请求 P95', keys: ['http.p95'], rule: 'HTTP_LATENCY_P95' },
  { title: '数据库连接池', keys: ['db.pool.active', 'db.pool.pending', 'db.pool.max'] },
  { title: '数据库 Ping', keys: ['db.ping'], rule: 'DB_SLOW' },
  { title: '事件队列', keys: ['outbox.backlog', 'outbox.dead'] },
  { title: '最老事件等待', keys: ['outbox.oldestAge'], rule: 'OUTBOX_BACKLOG_AGE' },
  { title: 'JVM 线程数', keys: ['threads.live'] },
  { title: 'GC 暂停（每采样区间）', keys: ['gc.pause'] },
]
const series = computed(
  () =>
    query.data.value?.series.map((item) =>
      ['http.requests', 'http.errors'].includes(item.key)
        ? {
            ...item,
            unit: 'PER_MINUTE',
            values: item.values.map((value) =>
              value == null ? null : (value * 60) / query.data.value!.resolutionSeconds,
            ),
          }
        : item,
    ) ?? [],
)
function threshold(code?: string): number | undefined {
  const rule = rules.data.value?.find((item) => item.code === code)
  return rule?.enabled ? (rule.warningThreshold ?? rule.criticalThreshold) : undefined
}
</script>
<template>
  <div
    v-if="host"
    class="ops-grid three"
  >
    <section class="ops-card">
      <h2>主机</h2>
      <dl class="ops-definition">
        <dt>主机名</dt>
        <dd>{{ host.hostName }}</dd>
        <dt>操作系统</dt>
        <dd>{{ host.os }}</dd>
        <dt>架构</dt>
        <dd>{{ host.architecture }}</dd>
        <dt>CPU</dt>
        <dd>{{ host.cpuModel ?? '未知（JDK 未提供）' }}</dd>
        <dt>逻辑核心</dt>
        <dd>{{ host.processors }}</dd>
        <dt>物理内存</dt>
        <dd>{{ bytes(overview.data.value?.runtime.metrics['memory.total']) }}</dd>
        <dt>交换空间</dt>
        <dd>{{ bytes(overview.data.value?.runtime.metrics['swap.total']) }}</dd>
      </dl>
    </section>
    <section class="ops-card">
      <h2>JVM</h2>
      <dl class="ops-definition">
        <dt>Java</dt>
        <dd>{{ host.javaVersion }}</dd>
        <dt>PID</dt>
        <dd>{{ host.pid }}</dd>
        <dt>启动时间</dt>
        <dd>{{ time(host.startedAt) }}</dd>
        <dt>运行时长</dt>
        <dd>{{ duration(host.uptimeMs) }}</dd>
        <dt>堆上限</dt>
        <dd>{{ bytes(host.heapMax) }}</dd>
        <dt>GC</dt>
        <dd>{{ host.garbageCollectors.join(' · ') }}</dd>
        <dt>字符集</dt>
        <dd>{{ host.defaultCharset }}</dd>
        <dt>时区</dt>
        <dd>{{ host.timeZone }}</dd>
      </dl>
    </section>
    <section class="ops-card">
      <h2>应用</h2>
      <dl class="ops-definition">
        <dt>版本</dt>
        <dd>{{ host.version }}</dd>
        <dt>Commit</dt>
        <dd class="ops-code">
          {{ host.commit }}
        </dd>
        <dt>构建时间</dt>
        <dd>{{ time(host.buildTime) }}</dd>
        <dt>迁移版本</dt>
        <dd>{{ host.schemaVersion }}</dd>
        <dt>数据库</dt>
        <dd>{{ host.databaseVersion }}</dd>
        <dt>环境</dt>
        <dd>{{ host.profiles.join(' · ') || 'default' }}</dd>
      </dl>
    </section>
  </div>
  <section
    v-if="host"
    class="ops-card"
  >
    <h2>磁盘卷</h2>
    <div
      v-for="volume in host.volumes"
      :key="volume.id"
      class="ops-volume"
    >
      <strong>{{ volume.label }}</strong><span class="ops-muted">{{ volume.purposes.map(volumePurpose).join(' · ') }} · 剩余 {{ bytes(volume.freeBytes) }} /
        {{ bytes(volume.totalBytes) }}</span><el-progress
        :percentage="
          volume.totalBytes ? Math.round((1 - volume.freeBytes / volume.totalBytes) * 100) : 0
        "
        :status="volume.totalBytes && volume.freeBytes / volume.totalBytes < 0.1 ? 'exception' : ''"
      />
    </div>
    <div
      v-if="!host.volumes.length"
      class="ops-muted"
    >
      磁盘信息暂不可用
    </div>
  </section>
  <div class="ops-toolbar host-toolbar">
    <strong>指标历史</strong><el-select
      v-model="range"
      aria-label="指标时间范围"
    >
      <el-option
        v-for="item in ['1h', '6h', '24h', '7d', '14d']"
        :key="item"
        :value="item"
        :label="item"
      />
    </el-select><span class="ops-muted">分辨率 {{ query.data.value?.resolutionSeconds ?? '—' }} 秒 · 空白表示缺失采样</span>
  </div>
  <inline-problem
    v-if="query.error.value"
    :problem="query.error.value"
  />
  <div class="ops-grid">
    <section
      v-for="chart in charts"
      :key="chart.title"
      class="ops-card"
    >
      <h2>{{ chart.title }}</h2>
      <operations-chart
        :title="chart.title"
        :times="query.data.value?.timestamps ?? []"
        :series="series.filter((s) => chart.keys.includes(s.key))"
        :restarts="query.data.value?.restarts"
        :threshold="threshold(chart.rule)"
      />
    </section>
  </div>
</template>
<style scoped>
.ops-volume {
  margin-bottom: var(--yp-space-4);
}
.ops-volume strong {
  margin-right: var(--yp-space-3);
}
.host-toolbar {
  margin-top: var(--yp-space-5);
}
</style>
