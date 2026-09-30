<script setup lang="ts">
import { ElButton, ElMessage } from 'element-plus'
import { computed, ref } from 'vue'
import type { OperationsLogEntry } from '@yumpoo/api-client'
import YpEmptyState from '../yp/YpEmptyState.vue'
import { time, moduleLabel, EVENT_LABELS } from './operationsPresentation'
const props = defineProps<{
  entries: OperationsLogEntry[]
  filters?: boolean
  utc?: boolean
  relativeTo?: Date | undefined
}>()
const emit = defineEmits<{
  trace: [entry: OperationsLogEntry]
  module: [entry: OperationsLogEntry]
  window: [entry: OperationsLogEntry]
}>()
const expandedStacks = ref(new Set<string>())
const showDate = computed(
  () =>
    new Set(
      props.entries.map((entry) =>
        entry.record.time.toLocaleDateString('zh-CN', props.utc ? { timeZone: 'UTC' } : {}),
      ),
    ).size > 1,
)
function clock(at: Date): string {
  return props.relativeTo
    ? '+' + (at.getTime() - props.relativeTo.getTime()) + ' ms'
    : at.toLocaleString('zh-CN', {
        ...(showDate.value ? ({ month: '2-digit', day: '2-digit' } as const) : {}),
        hour12: false,
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        fractionalSecondDigits: 3,
        ...(props.utc ? { timeZone: 'UTC' } : {}),
      })
}
function eventLabel(entry: OperationsLogEntry): string {
  return EVENT_LABELS[entry.record.event ?? ''] ?? entry.record.msg
}
function stack(entry: OperationsLogEntry): string {
  const value = entry.record.error?.stack ?? ''
  return expandedStacks.value.has(entry.id) ? value : value.split('\n').slice(0, 12).join('\n')
}
function fields(entry: OperationsLogEntry) {
  return Object.entries(entry.record.fields)
    .filter(([key]) => ['status', 'durationMs', 'attempt', 'outcome', 'job'].includes(key))
    .slice(0, 3)
}
async function copy(value: string) {
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('无法访问剪贴板，请选中文本复制')
  }
}
</script>
<template>
  <div
    class="ops-log-stream"
    :class="{ 'with-date': showDate }"
  >
    <details
      v-for="entry in entries"
      :key="entry.id"
      class="ops-log-line"
      :class="'level-' + entry.record.level"
    >
      <summary>
        <span
          class="ops-log-time"
          :title="time(entry.record.time)"
        >{{ clock(entry.record.time) }}</span>
        <span
          class="ops-lvl"
          :class="'ops-lvl-' + entry.record.level"
        >{{ entry.record.level }}</span>
        <span
          class="ops-log-module"
          :title="entry.record.module"
        >{{ moduleLabel(entry.record.module) }}</span>
        <span
          class="ops-log-message"
          :title="entry.record.event ?? undefined"
        ><b>{{ eventLabel(entry) }}</b><span
          v-if="entry.record.fields.method && entry.record.fields.route"
          class="ops-log-route"
        > · {{ entry.record.fields.method }} {{ entry.record.fields.route }}</span><span
          v-if="entry.record.error"
          class="ops-log-cause"
        > · {{ entry.record.error.msg }}</span></span>
        <span
          v-for="[key, value] in fields(entry)"
          :key="key"
          class="ops-kv ops-log-field"
        >{{ key }}={{ value }}</span>
        <button
          v-if="entry.record.requestId"
          type="button"
          class="ops-log-request"
          :title="'请求链路 ' + entry.record.requestId"
          @click.stop.prevent="emit('trace', entry)"
        >
          {{ entry.record.requestId.slice(0, 8) }}
        </button>
      </summary>
      <div class="ops-log-detail">
        <div class="ops-toolbar">
          <strong>{{ entry.record.event ?? '未命名事件' }}</strong><el-button
            v-if="entry.record.requestId"
            size="small"
            @click="emit('trace', entry)"
          >
            请求追踪
          </el-button><el-button
            size="small"
            @click="copy(entry.record.error?.stack ?? entry.record.msg)"
          >
            复制{{ entry.record.error ? '堆栈' : '消息' }}
          </el-button>
          <el-button
            size="small"
            @click="copy(JSON.stringify(entry.record, null, 2))"
          >
            复制 JSON
          </el-button>
          <el-button
            v-if="filters"
            size="small"
            @click="emit('module', entry)"
          >
            只看此模块
          </el-button>
          <el-button
            v-if="filters"
            size="small"
            @click="emit('window', entry)"
          >
            前后 5 分钟
          </el-button>
        </div>
        <dl class="ops-definition">
          <dt>原始消息</dt>
          <dd>{{ entry.record.msg }}</dd>
          <dt>Logger</dt>
          <dd>{{ entry.record.logger }}</dd>
          <dt>线程</dt>
          <dd>{{ entry.record.thread }}</dd>
          <dt>Request ID</dt>
          <dd>{{ entry.record.requestId ?? '—' }}</dd>
          <dt>User ID</dt>
          <dd>{{ entry.record.userId ?? '—' }}</dd>
          <template
            v-for="(value, key) in entry.record.fields"
            :key="key"
          >
            <dt>{{ key }}</dt>
            <dd>{{ value }}</dd>
          </template>
        </dl>
        <div
          v-if="entry.record.error"
          class="ops-log-error"
        >
          <div class="ops-log-error__head">
            <b>{{ entry.record.error.type }}</b>
            <span>{{ entry.record.error.msg }}</span>
            <small>错误指纹 {{ entry.record.error.hash }}</small>
          </div>
          <pre>{{ stack(entry) }}</pre>
          <el-button
            v-if="entry.record.error.stack.split('\n').length > 12"
            size="small"
            text
            type="primary"
            @click="
              expandedStacks.has(entry.id)
                ? expandedStacks.delete(entry.id)
                : expandedStacks.add(entry.id)
            "
          >
            {{ expandedStacks.has(entry.id) ? '收起堆栈' : '展开全部帧' }}
          </el-button>
        </div>
      </div>
    </details>
    <yp-empty-state
      v-if="!entries.length"
      compact
      reason="no-results"
      title="当前条件下没有日志"
      description="可以放宽级别、模块或时间范围。"
    />
  </div>
</template>
<style scoped>
.ops-log-stream {
  overflow: hidden;
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-surface);
  font: 12.5px / 1.5 var(--yp-font-mono);
}

.ops-log-line {
  border-top: 1px solid var(--yp-border-subtle);
}

.ops-log-line:first-child {
  border-top: 0;
}

.ops-log-line.level-ERROR {
  background: color-mix(in srgb, var(--yp-status-red) 5%, var(--yp-bg-surface));
}

summary {
  display: flex;
  align-items: center;
  gap: var(--yp-space-3);
  min-height: 30px;
  padding: 3px var(--yp-space-3);
  overflow: hidden;
  list-style: none;
  cursor: pointer;
}

summary::-webkit-details-marker {
  display: none;
}

summary:hover {
  background: var(--yp-bg-hover);
}

summary:focus-visible {
  outline: 2px solid var(--yp-focus-ring);
  outline-offset: -2px;
}

.ops-log-time {
  flex: 0 0 90px;
  color: var(--yp-text-muted);
  font-variant-numeric: tabular-nums;
}

.with-date .ops-log-time {
  flex-basis: 150px;
}

.ops-log-module {
  flex: 0 0 88px;
  overflow: hidden;
  color: var(--yp-text-secondary);
  font: var(--yp-type-caption-size) var(--yp-font-family);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ops-log-message {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ops-log-message b {
  font: 600 var(--yp-type-body-size) var(--yp-font-family);
}

.ops-log-route,
.ops-log-cause {
  color: var(--yp-text-secondary);
}

.ops-log-request {
  flex: 0 0 auto;
  margin-left: auto;
  padding: 0 var(--yp-space-1);
  border: 0;
  color: var(--yp-link);
  background: transparent;
  font: inherit;
  font-size: var(--yp-type-caption-size);
  cursor: pointer;
}

.ops-log-request:hover {
  text-decoration: underline;
}

.ops-log-detail {
  display: grid;
  gap: var(--yp-space-3);
  padding: var(--yp-space-3) var(--yp-space-4) var(--yp-space-4);
  border-top: 1px dashed var(--yp-border-subtle);
  background: var(--yp-bg-sunken);
  font-family: var(--yp-font-family);
}

.ops-log-detail .ops-toolbar {
  margin-bottom: 0;
}

.ops-log-detail .ops-definition {
  gap: var(--yp-space-1) var(--yp-space-4);
  font: 12.5px / 1.6 var(--yp-font-mono);
}

.ops-log-error {
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--yp-status-red) 35%, var(--yp-border-subtle));
  border-radius: var(--yp-radius-sm);
  background: var(--yp-bg-surface);
}

.ops-log-error__head {
  display: grid;
  gap: 2px;
  padding: var(--yp-space-2) var(--yp-space-3);
  border-bottom: 1px solid var(--yp-border-subtle);
  font-size: var(--yp-type-caption-size);
}

.ops-log-error__head b {
  color: var(--yp-status-red);
  font-family: var(--yp-font-mono);
  overflow-wrap: anywhere;
}

.ops-log-error__head small {
  color: var(--yp-text-muted);
  font-family: var(--yp-font-mono);
}

pre {
  max-height: 360px;
  margin: 0;
  padding: var(--yp-space-3);
  overflow: auto;
  color: var(--yp-text-secondary);
  font: 12px / 19px var(--yp-font-mono);
  white-space: pre;
}

@media (max-width: 760px) {
  .ops-log-time {
    flex-basis: 96px;
    font-size: 11px;
  }

  .ops-log-module,
  .ops-log-field {
    display: none;
  }

  summary {
    gap: var(--yp-space-2);
  }
}
</style>
