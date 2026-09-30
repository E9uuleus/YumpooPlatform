<script setup lang="ts">
import { Close, Filter, Search } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElCheckbox,
  ElDrawer,
  ElIcon,
  ElInput,
  ElPopover,
  ElSwitch,
} from 'element-plus'
import type { OperationsLogEntry, OperationsLogPage } from '@yumpoo/api-client'
import { operationsApi } from '../../api/client'
import { useOperationsQuery } from '../../composables/useOperationsQuery'
import OperationsLogStream from '../../components/operations/OperationsLogStream.vue'
import OperationsChart from '../../components/operations/OperationsChart.vue'
import InlineProblem from '../../components/InlineProblem.vue'
import OpsSegmented from '../../components/operations/OpsSegmented.vue'
import { mergeLogEntries as merge } from '../../components/operations/logEntries'
import {
  MODULE_LABELS,
  formatOperationsTimeRange,
  moduleLabel,
} from '../../components/operations/operationsPresentation'
const route = useRoute(),
  router = useRouter()
const initial = (key: string) =>
  typeof route.query[key] === 'string' ? String(route.query[key]) : ''
const q = ref(initial('q')),
  requestId = ref(initial('requestId')),
  event = ref(initial('event')),
  userId = ref(initial('userId')),
  modules = ref(initial('modules').split(',').filter(Boolean))
const levels = ref((initial('levels') || 'INFO,WARN,ERROR').split(','))
const range = ref<[Date, Date]>([new Date(Date.now() - 900000), new Date()])
const following = ref(!initial('from')),
  paused = ref(false),
  entries = ref<OperationsLogEntry[]>([]),
  pending = ref<OperationsLogEntry[]>([])
const customRange = ref(!following.value)
let timeBeforeSelection: { following: boolean; range: [Date, Date] } | undefined
const utc = ref(false)
const warning = ref(''),
  cursor = ref<string | null>(null),
  bootId = ref<string>(),
  afterSeq = ref<string>(),
  source = ref('')
let more = false
if (initial('from') && initial('to'))
  range.value = [new Date(initial('from')), new Date(initial('to'))]
const appliedRange = ref<[Date, Date]>(range.value),
  histogramRange = ref<[Date, Date]>(range.value)
let lastHistogramAt = 0
const filters = () => ({
  levels: levels.value.join(','),
  modules: modules.value.join(','),
  q: q.value,
  requestId: requestId.value,
  event: event.value,
  userId: userId.value,
})
const applied = ref(filters())
const routeKey = (value: object) =>
  JSON.stringify(Object.entries(value).sort(([a], [b]) => a.localeCompare(b)))
let lastRouteKey = routeKey(route.query)
const query = useOperationsQuery(
  async (signal) => {
    if (following.value) {
      const result = await operationsApi.tailOperationsLogs(
        {
          ...applied.value,
          ...(bootId.value ? { bootId: bootId.value } : {}),
          ...(afterSeq.value ? { afterSeq: afterSeq.value } : {}),
          limit: 500,
        },
        { signal },
      )
      if (signal.aborted) return
      if (result.gap) {
        warning.value =
          result.gapReason === 'RESTART'
            ? '服务已重启，已开始新的日志流。此前记录可用历史查询查看。'
            : '缓冲已淘汰部分日志，请缩小时间范围查询文件补齐。'
        entries.value = []
        pending.value = []
      }
      bootId.value = result.bootId
      afterSeq.value = result.nextAfterSeq
      more = result.hasMore
      source.value = '实时缓冲'
      if (paused.value) {
        if (pending.value.length + result.items.length > 500)
          warning.value = '暂停期间新日志超过 500 条，较早记录请用历史查询查看。'
        pending.value = merge(pending.value, result.items)
      } else entries.value = merge(entries.value, result.items)
      if (result.droppedCount) warning.value = '部分超长日志未进入缓冲，请查询历史文件。'
    } else {
      const result = await operationsApi.queryOperationsLogs(
        { ...applied.value, from: appliedRange.value[0], to: appliedRange.value[1], limit: 100 },
        { signal },
      )
      if (signal.aborted) return
      entries.value = result.items
      cursor.value = result.nextCursor
      source.value = result.source === 'FILE' ? '历史文件' : '内存缓冲'
      warnPartial(result)
    }
    if (Date.now() - lastHistogramAt >= 60000) {
      lastHistogramAt = Date.now()
      if (following.value) histogramRange.value = [new Date(Date.now() - 900000), new Date()]
      void histogram.refresh()
    }
  },
  { interval: () => (following.value ? (more ? 500 : 3000) : 0) },
)
function warnPartial(result: OperationsLogPage) {
  warning.value = result.partial
    ? '查询结果不完整（文件变化或扫描预算耗尽），请缩小范围重查。'
    : result.partialReason === 'NO_LOG_FILES'
      ? '没有可读取的日志文件，仅能查看服务启动后的内存日志。'
      : ''
  if (result.skippedLines) warning.value += ' 已跳过损坏或不支持的日志行。'
}
const histogram = useOperationsQuery(
  (signal) =>
    operationsApi.getOperationsLogHistogram(
      {
        ...applied.value,
        levels: 'TRACE,DEBUG,INFO,WARN,ERROR',
        from: histogramRange.value[0],
        to: histogramRange.value[1],
      },
      { signal },
    ),
  { immediate: false },
)
const histogramSeries = computed(() =>
  ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR'].map((key) => ({
    key,
    unit: 'COUNT',
    values: histogram.data.value?.buckets.map((b) => b.counts[key] ?? 0) ?? [],
  })),
)
const levelCounts = computed(() =>
  Object.fromEntries(
    histogramSeries.value.map((series) => [
      series.key,
      histogram.data.value ? series.values.reduce((sum, count) => sum + count, 0) : null,
    ]),
  ),
)
const moduleOptions = computed(() => [
  ...new Set([
    ...Object.keys(MODULE_LABELS),
    ...modules.value,
    ...entries.value.map((entry) => entry.record.module),
  ]),
])
const selectedPeriod = computed(() =>
  following.value ? 0 : customRange.value ? null :
    (range.value[1].getTime() - range.value[0].getTime()) / 60000,
)
const timeSelectionLabel = computed(() =>
  formatOperationsTimeRange(appliedRange.value[0], appliedRange.value[1], utc.value),
)
const periodOptions = [
  { value: 0, label: '实时' },
  { value: 15, label: '15 分钟' },
  { value: 60, label: '1 小时' },
  { value: 360, label: '6 小时' },
  { value: 1440, label: '24 小时' },
]
const levelTones = {
  ERROR: 'red',
  WARN: 'yellow',
  INFO: 'blue',
  DEBUG: 'gray',
  TRACE: 'gray',
} as const
const levelOptions = computed(() =>
  (['ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE'] as const).map((level) => ({
    value: level,
    label: level,
    tone: levelTones[level],
    count: (histogram.data.value?.partial ? '≥' : '') + (levelCounts.value[level] ?? '—'),
  })),
)
const modulesOpen = ref(false),
  moreOpen = ref(false)
function toggleModule(module: string, checked: boolean) {
  modules.value = checked
    ? [...modules.value, module]
    : modules.value.filter((value) => value !== module)
  search()
}
function clearModules() {
  modules.value = []
  modulesOpen.value = false
  search()
}
function applyMoreFilters() {
  moreOpen.value = false
  search()
}
function clearMoreFilters() {
  requestId.value = ''
  event.value = ''
  userId.value = ''
  moreOpen.value = false
  search()
}
const moreFilterCount = computed(
  () => [applied.value.requestId, applied.value.event, applied.value.userId].filter(Boolean).length,
)
const shortId = (value: string) => (value.length > 12 ? value.slice(0, 8) + '…' : value)
const activeFilters = computed(() =>
  [
    applied.value.modules && {
      key: 'modules',
      label:
        '模块：' +
        applied.value.modules
          .split(',')
          .map((module) => moduleLabel(module))
          .join('、'),
    },
    applied.value.q && { key: 'q', label: '关键字：' + applied.value.q },
    applied.value.requestId && { key: 'requestId', label: '请求：' + shortId(applied.value.requestId) },
    applied.value.event && { key: 'event', label: '事件：' + applied.value.event },
    applied.value.userId && { key: 'userId', label: '用户：' + shortId(applied.value.userId) },
  ].filter((filter): filter is { key: string; label: string } => Boolean(filter)),
)
function removeFilter(key: string) {
  if (key === 'time') restoreTimeSelection()
  else if (key === 'modules') modules.value = []
  else if (key === 'q') q.value = ''
  else if (key === 'requestId') requestId.value = ''
  else if (key === 'event') event.value = ''
  else if (key === 'userId') userId.value = ''
  search()
}
function clearFilters() {
  restoreTimeSelection()
  modules.value = []
  q.value = ''
  requestId.value = ''
  event.value = ''
  userId.value = ''
  search()
}
function restoreTimeSelection() {
  if (!customRange.value) return
  following.value = timeBeforeSelection?.following ?? true
  if (timeBeforeSelection) range.value = timeBeforeSelection.range
  customRange.value = false
  timeBeforeSelection = undefined
}
function toggleLevel(level: string) {
  if (levels.value.includes(level)) {
    if (levels.value.length === 1) return
    levels.value = levels.value.filter((value) => value !== level)
  } else levels.value = [...levels.value, level]
  search()
}
function validRange(from: Date, to: Date) {
  const start = from.getTime(), end = to.getTime()
  return Number.isFinite(start) && Number.isFinite(end) && end > start && end - start <= 86400000
}
function search() {
  if (!following.value && !validRange(range.value[0], range.value[1])) {
    warning.value = '请选择不超过 24 小时的有效时间范围。'
    return
  }
  query.cancel()
  pagination.cancel()
  histogram.cancel()
  applied.value = filters()
  appliedRange.value = range.value
  lastHistogramAt = 0
  entries.value = []
  pending.value = []
  bootId.value = undefined
  afterSeq.value = undefined
  cursor.value = null
  warning.value = ''
  histogramRange.value = following.value ? [new Date(Date.now() - 900000), new Date()] : range.value
  const queryState = {
    ...filters(),
    ...(!following.value
      ? { from: range.value[0].toISOString(), to: range.value[1].toISOString() }
      : {}),
  }
  const url = Object.fromEntries(Object.entries(queryState).filter(([, value]) => value))
  lastRouteKey = routeKey(url)
  void router.replace({ query: url })
  void query.refresh()
}
function period(minutes: number) {
  customRange.value = false
  timeBeforeSelection = undefined
  if (minutes === 0) {
    if (following.value) return
    following.value = true
  } else {
    const now = Date.now()
    range.value = [new Date(now - minutes * 60000), new Date(now)]
    following.value = false
  }
  search()
}
function resume() {
  paused.value = false
  entries.value = merge(entries.value, pending.value)
  pending.value = []
}
const pagination = useOperationsQuery(
  async (signal) => {
    if (!cursor.value || following.value) return
    const result = await operationsApi.queryOperationsLogs(
      {
        ...applied.value,
        from: appliedRange.value[0],
        to: appliedRange.value[1],
        cursor: cursor.value,
        limit: 100,
      },
      { signal },
    )
    if (!signal.aborted) {
      entries.value = merge(entries.value, result.items)
      cursor.value = result.nextCursor
      warnPartial(result)
    }
  },
  { immediate: false },
)
function brush(from: Date, to: Date) {
  if (!validRange(from, to)) {
    warning.value = '请选择不超过 24 小时的有效时间范围。'
    return
  }
  if (!customRange.value)
    timeBeforeSelection = { following: following.value, range: [...range.value] }
  customRange.value = true
  range.value = [from, to]
  following.value = false
  search()
}
const selected = ref<OperationsLogEntry>(),
  traceOpen = ref(false)
const trace = useOperationsQuery(
  async (signal) => {
    if (!selected.value?.record.requestId) return
    const at = selected.value.record.time.getTime()
    return operationsApi.queryOperationsLogs(
      {
        from: new Date(at - 3600000),
        to: new Date(at + 3600000),
        levels: 'TRACE,DEBUG,INFO,WARN,ERROR',
        requestId: selected.value.record.requestId,
        limit: 500,
      },
      { signal },
    )
  },
  { immediate: false },
)
function openTrace(entry: OperationsLogEntry) {
  selected.value = entry
  traceOpen.value = true
  void trace.refresh()
}
function onlyModule(entry: OperationsLogEntry) {
  modules.value = [entry.record.module]
  search()
}
function around(entry: OperationsLogEntry) {
  const at = entry.record.time.getTime()
  brush(new Date(at - 300000), new Date(at + 300000))
}
watch(traceOpen, (open) => {
  if (!open) trace.cancel()
})
watch(
  () => route.query,
  () => {
    if (lastRouteKey === routeKey(route.query)) return
    lastRouteKey = routeKey(route.query)
    requestId.value = initial('requestId')
    q.value = initial('q')
    event.value = initial('event')
    userId.value = initial('userId')
    modules.value = initial('modules').split(',').filter(Boolean)
    levels.value = (initial('levels') || 'INFO,WARN,ERROR').split(',')
    const live = !initial('from')
    if (!live) range.value = [new Date(initial('from')), new Date(initial('to'))]
    timeBeforeSelection = undefined
    customRange.value = !live
    following.value = live
    search()
  },
)
</script>
<template>
  <div class="ops-sticky log-toolbar">
    <div class="log-toolbar__row">
      <ops-segmented
        group-label="日志时间范围"
        :options="periodOptions"
        :selected="selectedPeriod"
        @select="period(Number($event))"
      />
      <ops-segmented
        group-label="日志级别"
        :options="levelOptions"
        :selected="levels"
        @select="toggleLevel(String($event))"
      />
      <span class="log-toolbar__end log-live">
        <span
          v-if="following"
          class="log-live__pill"
          :class="{ paused }"
        ><i aria-hidden="true" />{{ paused ? '已暂停' : '实时' }}</span>
        <el-button
          v-if="following && !paused"
          @click="paused = true"
        >
          暂停显示
        </el-button><el-button
          v-if="following && paused"
          type="primary"
          @click="resume"
        >
          恢复 · {{ pending.length }} 条新日志
        </el-button>
        <el-switch
          v-model="utc"
          active-text="UTC"
          inactive-text="本地时间"
        />
      </span>
    </div>
    <div class="log-toolbar__row">
      <el-popover
        v-model:visible="modulesOpen"
        placement="bottom-start"
        trigger="click"
        :width="300"
        :teleported="false"
      >
        <template #reference>
          <el-button
            class="log-filter-button"
            :class="{ active: modules.length }"
            :icon="Filter"
            :aria-expanded="modulesOpen"
          >
            模块{{ modules.length ? ` · ${modules.length}` : '' }}
          </el-button>
        </template>
        <div class="log-panel">
          <div class="log-panel__head">
            <strong>按模块筛选</strong>
            <el-button
              v-if="modules.length"
              link
              type="primary"
              @click="clearModules"
            >
              清除
            </el-button>
          </div>
          <div
            class="log-panel__modules"
            role="group"
            aria-label="模块"
          >
            <el-checkbox
              v-for="module in moduleOptions"
              :key="module"
              :model-value="modules.includes(module)"
              :aria-label="moduleLabel(module)"
              @update:model-value="toggleModule(module, Boolean($event))"
            >
              {{ moduleLabel(module) }}<small>{{ module }}</small>
            </el-checkbox>
          </div>
        </div>
      </el-popover>
      <el-input
        v-model="q"
        class="log-search"
        :prefix-icon="Search"
        placeholder="关键字、事件码或异常类型"
        :maxlength="200"
        aria-label="搜索日志"
        @keyup.enter="search"
      />
      <el-button
        type="primary"
        :loading="query.loading.value"
        @click="search"
      >
        查询
      </el-button>
      <span class="log-more-filters">
        <el-popover
          v-model:visible="moreOpen"
          placement="bottom-start"
          trigger="click"
          :width="320"
          :teleported="false"
        >
          <template #reference>
            <el-button
              class="log-filter-button"
              :class="{ active: moreFilterCount }"
              :aria-expanded="moreOpen"
            >
              更多筛选{{ moreFilterCount ? ` · ${moreFilterCount}` : '' }}
            </el-button>
          </template>
          <div class="log-panel">
            <div class="log-panel__head">
              <strong>精确筛选</strong>
            </div>
            <label class="log-panel__field">
              <span>Request / Correlation ID</span>
              <el-input
                v-model="requestId"
                placeholder="完整 ID"
                aria-label="请求标识"
                @keyup.enter="applyMoreFilters"
              />
            </label>
            <label class="log-panel__field">
              <span>事件代码</span>
              <el-input
                v-model="event"
                placeholder="如 http.request.failed"
                aria-label="事件代码"
                @keyup.enter="applyMoreFilters"
              />
            </label>
            <label class="log-panel__field">
              <span>用户 ID</span>
              <el-input
                v-model="userId"
                placeholder="完整用户 UUID"
                aria-label="用户标识"
                @keyup.enter="applyMoreFilters"
              />
            </label>
            <div class="log-panel__foot">
              <el-button @click="clearMoreFilters">
                清除
              </el-button>
              <el-button
                type="primary"
                @click="applyMoreFilters"
              >
                应用
              </el-button>
            </div>
          </div>
        </el-popover>
      </span>
    </div>
    <div
      v-if="customRange || activeFilters.length"
      class="log-toolbar__row log-chips"
    >
      <span
        v-if="customRange"
        class="ops-chip log-time-chip"
      >
        时间：{{ timeSelectionLabel }}
        <button
          type="button"
          class="log-chip__remove"
          aria-label="清除时间筛选"
          @click="removeFilter('time')"
        >
          <el-icon aria-hidden="true"><close /></el-icon>
        </button>
      </span>
      <button
        v-for="filter in activeFilters"
        :key="filter.key"
        type="button"
        class="ops-chip log-chip"
        :aria-label="'移除筛选 ' + filter.label"
        @click="removeFilter(filter.key)"
      >
        {{ filter.label }}<el-icon aria-hidden="true">
          <close />
        </el-icon>
      </button>
      <el-button
        link
        type="primary"
        @click="clearFilters"
      >
        清除全部
      </el-button>
    </div>
    <operations-chart
      class="log-histogram"
      title="日志级别分布；在柱状图上拖拽选择时间范围"
      :times="histogram.data.value?.buckets.map((b) => b.time) ?? []"
      :series="histogramSeries"
      :bucket-seconds="histogram.data.value?.bucketSeconds"
      :utc="utc"
      bars
      zoom
      @range="brush"
    />
    <div class="log-source">
      <span>来源：{{ source || '—' }} · 在柱状图上拖拽可缩放时间</span>
      <span>{{ entries.length }} 条（最多保留 500 条）</span>
    </div>
  </div>
  <el-alert
    v-if="warning || histogram.data.value?.partial"
    class="log-notice"
    type="warning"
    :title="warning || '直方图为部分结果，请缩小范围重查。'"
    :closable="false"
  />
  <inline-problem
    v-if="histogram.error.value"
    :problem="histogram.error.value"
  />
  <inline-problem
    v-if="query.error.value"
    :problem="query.error.value"
  /><inline-problem
    v-if="pagination.error.value"
    :problem="pagination.error.value"
  />
  <div
    class="log-scroll"
    @scroll="following && ($event.target as HTMLElement).scrollTop > 8 && (paused = true)"
  >
    <operations-log-stream
      :entries="entries"
      :utc="utc"
      filters
      @trace="openTrace"
      @module="onlyModule"
      @window="around"
    />
  </div>
  <div
    v-if="!following && cursor && entries.length < 500"
    class="log-more"
  >
    <el-button
      :loading="pagination.loading.value"
      @click="pagination.refresh"
    >
      加载更早
    </el-button>
  </div>
  <el-drawer
    v-model="traceOpen"
    class="operations-overlay"
    title="请求链路 · 前后 1 小时"
    size="min(760px, 95vw)"
  >
    <p class="log-trace-id">
      <span class="ops-muted">Request / Correlation ID</span>
      <span class="ops-code">{{ selected?.record.requestId }}</span>
    </p>
    <inline-problem
      v-if="trace.error.value"
      :problem="trace.error.value"
    /><el-alert
      v-if="trace.data.value?.partial || trace.data.value?.nextCursor"
      title="仅显示部分关联记录，可用 Request ID 在历史查询中缩小范围查看。"
      type="warning"
      :closable="false"
    /><operations-log-stream
      :entries="[...(trace.data.value?.items ?? [])].reverse()"
      :relative-to="trace.data.value?.items.at(-1)?.record.time"
    />
  </el-drawer>
</template>

<style scoped>
.log-toolbar {
  display: grid;
  gap: var(--yp-space-2);
  margin-bottom: var(--yp-space-3);
  border-bottom: 1px solid var(--yp-border-subtle);
}

.log-toolbar__row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--yp-space-2);
}

.log-toolbar__end {
  margin-left: auto;
}

.log-search {
  flex: 1 1 240px;
  max-width: 360px;
}

.log-filter-button.active {
  border-color: color-mix(in srgb, var(--yp-action-primary) 50%, var(--yp-border-default));
  color: var(--yp-action-primary);
  background: var(--yp-bg-selected);
}

.log-panel {
  display: grid;
  gap: var(--yp-space-3);
}

.log-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 24px;
}

.log-panel__modules {
  display: grid;
  max-height: 320px;
  margin-inline: calc(var(--yp-space-2) * -1);
  overflow-x: hidden;
  overflow-y: auto;
}

.log-panel__modules :deep(.el-checkbox) {
  display: flex;
  width: 100%;
  min-width: 0;
  height: 30px;
  margin-right: 0;
  padding-inline: var(--yp-space-2);
  border-radius: var(--yp-radius-sm);
}

.log-panel__modules :deep(.el-checkbox:hover) {
  background: var(--yp-bg-hover);
}

.log-panel__modules :deep(.el-checkbox__label) {
  display: flex;
  flex: 1;
  justify-content: space-between;
  gap: var(--yp-space-3);
  min-width: 0;
  overflow: hidden;
}

.log-panel__modules small {
  flex: none;
  color: var(--yp-text-muted);
  font: 11px var(--yp-font-mono);
}

.log-panel__field {
  display: grid;
  gap: var(--yp-space-1);
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-caption-size);
}

.log-panel__foot {
  display: flex;
  justify-content: flex-end;
  gap: var(--yp-space-2);
}

.log-panel__foot .el-button + .el-button {
  margin-left: 0;
}

.log-chip {
  cursor: pointer;
}

.log-chip__remove {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  color: inherit;
  background: transparent;
  cursor: pointer;
}

.log-chip__remove:hover {
  color: var(--yp-text-primary);
  background: var(--yp-bg-hover);
}

.log-chip__remove:focus-visible {
  outline: 2px solid var(--yp-focus-ring);
  outline-offset: 1px;
}

.log-chip:hover {
  border-color: var(--yp-border-strong);
  color: var(--yp-text-primary);
}

.log-live {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--yp-space-2);
}

.log-live .el-button + .el-button {
  margin-left: 0;
}

.log-live__pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-caption-size);
}

.log-live__pill i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--yp-status-green);
  animation: log-pulse 1.6s ease-in-out infinite;
}

.log-live__pill.paused i {
  background: var(--yp-status-gray);
  animation: none;
}

@keyframes log-pulse {
  50% {
    box-shadow: 0 0 0 5px color-mix(in srgb, var(--yp-status-green) 25%, transparent);
  }
}

.log-histogram {
  height: 72px;
  cursor: crosshair;
}

.log-source {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: var(--yp-space-3);
  padding-bottom: var(--yp-space-2);
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
  font-variant-numeric: tabular-nums;
}

.log-notice {
  margin-bottom: var(--yp-space-3);
}

.log-scroll {
  max-height: 65vh;
  overflow: auto;
  overflow-anchor: none;
}

.log-more {
  display: flex;
  justify-content: center;
  margin-top: var(--yp-space-3);
}

.log-trace-id {
  display: grid;
  gap: 2px;
  margin: 0 0 var(--yp-space-3);
}

@media (max-width: 760px) {
  .log-search,
  .log-range {
    flex-basis: 100%;
    max-width: none;
  }

  .log-toolbar__end {
    margin-left: 0;
  }
}
</style>
