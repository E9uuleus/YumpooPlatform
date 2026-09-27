<script setup lang="ts">
import { ElOption } from '../../components/operations/elementPlus'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElDatePicker, ElDrawer, ElInput, ElSelect, ElSwitch } from 'element-plus'
import type { OperationsLogEntry, OperationsLogPage } from '@yumpoo/api-client'
import { operationsApi } from '../../api/client'
import { useOperationsQuery } from '../../composables/useOperationsQuery'
import OperationsLogStream from '../../components/operations/OperationsLogStream.vue'
import OperationsChart from '../../components/operations/OperationsChart.vue'
import InlineProblem from '../../components/InlineProblem.vue'
import { mergeLogEntries as merge } from '../../components/operations/logEntries'
import { MODULE_LABELS, moduleLabel } from '../../components/operations/operationsPresentation'
const route = useRoute(), router = useRouter()
const initial = (key: string) => typeof route.query[key] === 'string' ? String(route.query[key]) : ''
const q = ref(initial('q')), requestId = ref(initial('requestId')), event = ref(initial('event')), userId = ref(initial('userId')), modules = ref(initial('modules').split(',').filter(Boolean))
const levels = ref((initial('levels') || 'INFO,WARN,ERROR').split(','))
const range = ref<[Date, Date]>([new Date(Date.now()-900000), new Date()])
const following = ref(!initial('from')), paused = ref(false), entries = ref<OperationsLogEntry[]>([]), pending = ref<OperationsLogEntry[]>([])
const utc = ref(false)
const warning = ref(''), cursor = ref<string | null>(null), bootId = ref<string>(), afterSeq = ref<string>(), source = ref('')
let more = false
if (initial('from') && initial('to')) range.value = [new Date(initial('from')), new Date(initial('to'))]
const appliedRange = ref<[Date,Date]>(range.value), histogramRange = ref<[Date,Date]>(range.value)
let lastHistogramAt = 0
const filters = () => ({ levels: levels.value.join(','), modules: modules.value.join(','), q:q.value, requestId:requestId.value, event:event.value, userId:userId.value })
const applied = ref(filters())
const routeKey = (value: object) => JSON.stringify(Object.entries(value).sort(([a],[b])=>a.localeCompare(b)))
let lastRouteKey = routeKey(route.query)
const query = useOperationsQuery(async signal => {
  if (following.value) {
    const result = await operationsApi.tailOperationsLogs({ ...applied.value, ...(bootId.value ? { bootId:bootId.value } : {}), ...(afterSeq.value ? { afterSeq:afterSeq.value } : {}), limit:500 }, { signal })
    if (signal.aborted) return
    if (result.gap) { warning.value = result.gapReason === 'RESTART' ? '服务已重启，已开始新的日志流。此前记录可用历史查询查看。' : '缓冲已淘汰部分日志，请缩小时间范围查询文件补齐。'; entries.value=[]; pending.value=[] }
    bootId.value=result.bootId; afterSeq.value=result.nextAfterSeq; more=result.hasMore; source.value='实时缓冲'
    if (paused.value) { if(pending.value.length+result.items.length>500)warning.value='暂停期间新日志超过 500 条，较早记录请用历史查询查看。'; pending.value=merge(pending.value,result.items) }
    else entries.value=merge(entries.value,result.items)
    if (result.droppedCount) warning.value='部分超长日志未进入缓冲，请查询历史文件。'
  } else {
    const result = await operationsApi.queryOperationsLogs({ ...applied.value,from:appliedRange.value[0],to:appliedRange.value[1],limit:100 }, { signal })
    if (signal.aborted) return
    entries.value=result.items; cursor.value=result.nextCursor; source.value=result.source==='FILE'?'历史文件':'内存缓冲'; warnPartial(result)
  }
  if (Date.now() - lastHistogramAt >= 60000) {
    lastHistogramAt = Date.now()
    if (following.value) histogramRange.value = [new Date(Date.now()-900000),new Date()]
    void histogram.refresh()
  }
}, { interval: () => following.value ? more ? 500 : 3000 : 0 })
function warnPartial(result: OperationsLogPage) { warning.value=result.partial ? '查询结果不完整（文件变化或扫描预算耗尽），请缩小范围重查。' : result.partialReason==='NO_LOG_FILES' ? '没有可读取的日志文件，仅能查看服务启动后的内存日志。' : ''; if (result.skippedLines) warning.value += ' 已跳过损坏或不支持的日志行。' }
const histogram = useOperationsQuery(signal => operationsApi.getOperationsLogHistogram({ ...applied.value,levels:'TRACE,DEBUG,INFO,WARN,ERROR',from:histogramRange.value[0],to:histogramRange.value[1] }, { signal }), { immediate: false })
const histogramSeries = computed(() => ['TRACE','DEBUG','INFO','WARN','ERROR'].map(key => ({key,unit:'COUNT',values:histogram.data.value?.buckets.map(b => b.counts[key] ?? 0) ?? []})))
const levelCounts = computed(() => Object.fromEntries(histogramSeries.value.map(series => [series.key, histogram.data.value ? series.values.reduce((sum, count) => sum + count, 0) : null])))
const moduleOptions = computed(() => [...new Set([...Object.keys(MODULE_LABELS), ...modules.value, ...entries.value.map(entry => entry.record.module)])])
const selectedPeriod = computed(() => following.value ? 0 : (range.value[1].getTime() - range.value[0].getTime()) / 60000)
function toggleLevel(level: string) {
  if (levels.value.includes(level)) { if (levels.value.length === 1) return; levels.value = levels.value.filter(value => value !== level) }
  else levels.value = [...levels.value, level]
  search()
}
function search() {
  if (!following.value && (!range.value || !Number.isFinite(range.value[0].getTime()) || range.value[1].getTime() <= range.value[0].getTime() || range.value[1].getTime()-range.value[0].getTime()>86400000)) { warning.value='请选择不超过 24 小时的有效时间范围。'; return }
  query.cancel(); pagination.cancel(); histogram.cancel(); applied.value=filters(); appliedRange.value=range.value; lastHistogramAt=0; entries.value=[]; pending.value=[]; bootId.value=undefined; afterSeq.value=undefined; cursor.value=null; warning.value=''
  histogramRange.value=following.value?[new Date(Date.now()-900000),new Date()]:range.value
  const queryState = { ...filters(), ...(!following.value ? { from: range.value[0].toISOString(), to: range.value[1].toISOString() } : {}) }
  const url = Object.fromEntries(Object.entries(queryState).filter(([,value])=>value))
  lastRouteKey = routeKey(url); void router.replace({ query: url })
  void query.refresh()
}
function period(minutes: number) { if (minutes === 0) { if (!following.value) following.value = true; return } const now = Date.now(); range.value=[new Date(now-minutes*60000),new Date(now)]; if(following.value)following.value=false;else search() }
function resume() { paused.value=false; entries.value=merge(entries.value,pending.value); pending.value=[] }
watch(following, search)
const pagination = useOperationsQuery(async signal => {
  if (!cursor.value || following.value) return
  const result = await operationsApi.queryOperationsLogs({ ...applied.value,from:appliedRange.value[0],to:appliedRange.value[1],cursor:cursor.value,limit:100 }, { signal })
  if (!signal.aborted) { entries.value=merge(entries.value,result.items); cursor.value=result.nextCursor; warnPartial(result) }
}, { immediate: false })
function brush(from: Date,to:Date) { range.value=[from,to]; if(following.value) following.value=false; else search() }
const selected = ref<OperationsLogEntry>(), traceOpen = ref(false)
const trace = useOperationsQuery(async signal => {
  if (!selected.value?.record.requestId) return
  const at=selected.value.record.time.getTime()
  return operationsApi.queryOperationsLogs({ from:new Date(at-3600000),to:new Date(at+3600000),levels:'TRACE,DEBUG,INFO,WARN,ERROR',requestId:selected.value.record.requestId,limit:500 }, { signal })
}, { immediate: false })
function openTrace(entry: OperationsLogEntry) { selected.value=entry; traceOpen.value=true; void trace.refresh() }
function onlyModule(entry: OperationsLogEntry) { modules.value=[entry.record.module];search() }
function around(entry: OperationsLogEntry) { const at=entry.record.time.getTime();brush(new Date(at-300000),new Date(at+300000)) }
watch(traceOpen, open => { if(!open) trace.cancel() })
watch(() => route.query, () => {
  if(lastRouteKey===routeKey(route.query))return
  lastRouteKey=routeKey(route.query);requestId.value=initial('requestId');q.value=initial('q');event.value=initial('event');userId.value=initial('userId');modules.value=initial('modules').split(',').filter(Boolean);levels.value=(initial('levels')||'INFO,WARN,ERROR').split(',')
  const live=!initial('from');if(!live)range.value=[new Date(initial('from')),new Date(initial('to'))]
  if(following.value!==live)following.value=live;else search()
})
</script>
<template>
  <div class="ops-toolbar" role="group" aria-label="日志时间范围">
    <el-button
      v-for="item in [{label:'实时',minutes:0},{label:'15 分钟',minutes:15},{label:'1 小时',minutes:60},{label:'6 小时',minutes:360},{label:'24 小时',minutes:1440}]"
      :key="item.minutes"
      size="small"
      :aria-pressed="selectedPeriod === item.minutes"
      :type="selectedPeriod === item.minutes ? 'primary' : 'default'"
      @click="period(item.minutes)"
    >
      {{ item.label }}
    </el-button>
  </div>
  <div class="ops-toolbar log-levels" role="group" aria-label="日志级别">
    <el-button v-for="level in ['ERROR','WARN','INFO','DEBUG','TRACE']" :key="level" size="small" :aria-pressed="levels.includes(level)" :type="levels.includes(level) ? 'primary' : 'default'" @click="toggleLevel(level)">
      {{ level }} <span>{{ histogram.data.value?.partial ? '≥' : '' }}{{ levelCounts[level] ?? '—' }}</span>
    </el-button>
    <span class="ops-muted">{{ following ? '最近 15 分钟' : '当前区间' }} · 所选模块</span>
  </div>
  <div class="ops-toolbar">
    <el-select
      v-model="modules"
      multiple
      filterable
      collapse-tags
      :multiple-limit="16"
      aria-label="模块"

    >
      <el-option
        v-for="module in moduleOptions"
        :key="module"
        :label="moduleLabel(module)"
        :value="module"
      />
    </el-select>
    <el-input
      v-model="q"
      placeholder="搜索消息或事件"
      :maxlength="200"
      aria-label="搜索日志"
      @keyup.enter="search"
    />
    <el-button
      :loading="query.loading.value"
      @click="search"
    >
      查询
    </el-button>
  </div>
  <details class="log-more-filters">
    <summary>更多筛选</summary>
    <div class="ops-toolbar">
    <el-date-picker
      v-if="!following"
      v-model="range"
      type="datetimerange"
      range-separator="至"
      start-placeholder="开始时间"
      end-placeholder="结束时间"
      :clearable="false"
    />
    <el-input
      v-model="requestId"
      placeholder="Request ID"
      aria-label="请求标识"
    /><el-input
      v-model="event"
      placeholder="精确事件代码"
      aria-label="事件代码"
    /><el-input
      v-model="userId"
      placeholder="User ID"
      aria-label="用户标识"
    />
    </div>
  </details>
  <operations-chart
    class="log-histogram"
    title="日志级别分布；拖动选择时间范围"
    :times="histogram.data.value?.buckets.map(b => b.time) ?? []"
    :series="histogramSeries"
    bars
    zoom
    @range="brush"
  />
  <el-alert
    v-if="warning || histogram.data.value?.partial"
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
  <div class="ops-toolbar">
    <span class="ops-muted">{{ source }} · {{ entries.length }} 条（最多保留 500 条）</span>
    <el-switch
      v-model="utc"
      active-text="UTC"
      inactive-text="本地时间"
    />
    <el-button
      v-if="following && !paused"
      size="small"
      @click="paused=true"
    >
      暂停显示
    </el-button><el-button
      v-if="following && paused"
      size="small"
      type="primary"
      @click="resume"
    >
      恢复 · {{ pending.length }} 条新日志
    </el-button>
    <el-button
      v-if="!following && cursor && entries.length < 500"
      :loading="pagination.loading.value"
      @click="pagination.refresh"
    >
      加载更早
    </el-button>
  </div>
  <div
    class="log-scroll"
    @scroll="following && ($event.target as HTMLElement).scrollTop > 8 && (paused=true)"
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
  <el-drawer
    v-model="traceOpen"
    title="请求追踪 · 前后 1 小时"
    size="min(1000px, 95vw)"
  >
    <p class="ops-code">
      {{ selected?.record.requestId }}
    </p><inline-problem
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
<style scoped>.log-scroll{max-height:65vh;overflow:auto;overflow-anchor:none}.ops-toolbar{margin-top:var(--yp-space-3)}.log-histogram{height:120px}</style>

