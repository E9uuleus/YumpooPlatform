<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
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
import OpsSegmented from '../../components/operations/OpsSegmented.vue'
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
const rangeOptions = [
  { value: GetOperationsMetricsRangeEnum._1h, label: '1 小时' },
  { value: GetOperationsMetricsRangeEnum._6h, label: '6 小时' },
  { value: GetOperationsMetricsRangeEnum._24h, label: '24 小时' },
  { value: GetOperationsMetricsRangeEnum._7d, label: '7 天' },
  { value: GetOperationsMetricsRangeEnum._14d, label: '14 天' },
]
function selectRange(value: string | number) {
  range.value = value as GetOperationsMetricsRangeEnum
}
function freeRatio(volume: { freeBytes: number; totalBytes: number }): number {
  return volume.totalBytes ? volume.freeBytes / volume.totalBytes : 0
}
function volumeTone(volume: { freeBytes: number; totalBytes: number }): string {
  const ratio = freeRatio(volume)
  return ratio <= 0.1 ? 'red' : ratio <= 0.2 ? 'yellow' : 'green'
}
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
        <dd>
          {{ host.cpuModel ?? '未知' }}<span
            v-if="!host.cpuModel"
            class="ops-muted"
          >（JDK 未提供）</span>
        </dd>
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
        <dd class="ops-code">
          {{ host.pid }}
        </dd>
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
        <dd class="ops-code">
          {{ host.version }}
        </dd>
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
        <dd class="ops-code">
          {{ host.profiles.join(' · ') || 'default' }}
        </dd>
      </dl>
    </section>
  </div>
  <section
    v-if="host"
    class="ops-card ops-disks"
  >
    <h2>磁盘卷</h2>
    <div
      v-for="volume in host.volumes"
      :key="volume.id"
      class="ops-volume"
      :class="`ops-tone-${volumeTone(volume)}`"
    >
      <div class="ops-volume__top">
        <strong>{{ volume.label }}</strong>
        <span class="ops-muted">已用 {{ bytes(volume.totalBytes - volume.freeBytes) }} /
          {{ bytes(volume.totalBytes) }} · 剩余 {{ Math.round(freeRatio(volume) * 1000) / 10 }}%</span>
      </div>
      <div
        class="ops-volume__bar"
        role="meter"
        :aria-label="volume.label + ' 已用空间'"
        aria-valuemin="0"
        aria-valuemax="100"
        :aria-valuenow="Math.round((1 - freeRatio(volume)) * 100)"
      >
        <i :style="{ width: (1 - freeRatio(volume)) * 100 + '%' }" />
      </div>
      <div class="ops-volume__purposes">
        <span
          v-for="purpose in volume.purposes"
          :key="purpose"
          class="ops-chip"
        >{{ volumePurpose(purpose) }}</span>
      </div>
    </div>
    <div
      v-if="!host.volumes.length"
      class="ops-muted"
    >
      磁盘信息暂不可用
    </div>
    <p class="ops-muted">
      按存储卷合并显示用途，不显示绝对路径。剩余低于 20% 标黄，低于 10% 标红。
    </p>
  </section>
  <div class="ops-toolbar ops-sticky host-toolbar">
    <strong>指标历史</strong>
    <ops-segmented
      group-label="指标时间范围"
      :options="rangeOptions"
      :selected="range"
      @select="selectRange"
    />
    <span class="ops-muted">{{
      query.data.value
        ? (query.data.value.resolutionSeconds <= 15 ? '数据来源：内存采样' : '数据来源：分钟历史') +
          ' · 分辨率 ' + query.data.value.resolutionSeconds + ' 秒 · 空白表示缺失采样，竖线表示服务重启'
        : '加载中'
    }}</span>
  </div>
  <inline-problem
    v-if="query.error.value"
    :problem="query.error.value"
  />
  <div class="ops-grid">
    <section
      v-for="chart in charts"
      :key="chart.title"
      class="ops-card ops-chart-card"
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
.ops-disks {
  display: grid;
  gap: var(--yp-space-4);
  margin-bottom: var(--yp-space-5);
}

.ops-disks h2,
.ops-disks p {
  margin: 0;
}

.ops-volume {
  display: grid;
  gap: var(--yp-space-2);
}

.ops-volume__top {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: var(--yp-space-3);
  font-variant-numeric: tabular-nums;
}

.ops-volume__bar {
  height: 10px;
  overflow: hidden;
  border-radius: var(--yp-radius-pill);
  background: var(--yp-bg-sunken);
}

.ops-volume__bar i {
  display: block;
  height: 100%;
  border-radius: var(--yp-radius-pill);
  background: var(--ops-tone);
}

.ops-volume__purposes {
  display: flex;
  flex-wrap: wrap;
  gap: var(--yp-space-2);
}

.host-toolbar {
  margin-bottom: var(--yp-space-3);
}

.ops-chart-card {
  padding: var(--yp-space-4);
}

.ops-chart-card h2 {
  margin-bottom: var(--yp-space-2);
}
</style>
