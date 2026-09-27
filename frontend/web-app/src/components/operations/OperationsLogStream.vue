<script setup lang="ts">
import { ElButton, ElMessage } from 'element-plus'
import { computed, ref } from 'vue'
import type { OperationsLogEntry } from '@yumpoo/api-client'
import YpStatusTag from '../yp/YpStatusTag.vue'
import { time, moduleLabel, EVENT_LABELS } from './operationsPresentation'
const props = defineProps<{ entries: OperationsLogEntry[]; filters?: boolean; utc?: boolean; relativeTo?: Date | undefined }>()
const emit = defineEmits<{ trace: [entry: OperationsLogEntry]; module: [entry: OperationsLogEntry]; window: [entry: OperationsLogEntry] }>()
const expandedStacks = ref(new Set<string>())
const showDate = computed(() => new Set(props.entries.map(entry => entry.record.time.toLocaleDateString('zh-CN', props.utc ? { timeZone: 'UTC' } : {}))).size > 1)
function clock(at: Date): string { return props.relativeTo ? '+' + (at.getTime()-props.relativeTo.getTime()) + ' ms' : at.toLocaleString('zh-CN', { ...(showDate.value ? { month: '2-digit', day: '2-digit' } as const : {}), hour12:false, hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits:3, ...(props.utc ? { timeZone:'UTC' } : {}) }) }
function eventLabel(entry: OperationsLogEntry): string { return EVENT_LABELS[entry.record.event ?? ''] ?? entry.record.msg }
function stack(entry: OperationsLogEntry): string { const value=entry.record.error?.stack??'';return expandedStacks.value.has(entry.id)?value:value.split('\n').slice(0,12).join('\n') }
function fields(entry: OperationsLogEntry) { return Object.entries(entry.record.fields).filter(([key])=>['status','durationMs','attempt','outcome','job'].includes(key)).slice(0,3) }
async function copy(value: string) { try { await navigator.clipboard.writeText(value); ElMessage.success('已复制') } catch { ElMessage.error('无法访问剪贴板，请选中文本复制') } }
</script>
<template>
  <div class="ops-log-stream" :class="{ 'with-date': showDate }">
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
        >{{ clock(entry.record.time) }}</span><yp-status-tag
          domain="operations"
          :status="entry.record.level"
          size="small"
          effect="soft"
        /><span class="ops-log-module" :title="entry.record.module">{{ moduleLabel(entry.record.module) }}</span><span
          class="ops-log-message"
          :title="entry.record.event??undefined"
        >{{ eventLabel(entry) }}<span v-if="entry.record.fields.method && entry.record.fields.route"> · {{ entry.record.fields.method }} {{ entry.record.fields.route }}</span><span v-if="entry.record.error"> · {{ entry.record.error.msg }}</span></span>
        <span
          v-for="[key,value] in fields(entry)"
          :key="key"
          class="ops-log-field"
        >{{ key }}={{ value }}</span>
        <el-button
          v-if="entry.record.requestId"
          size="small"
          text
          :title="entry.record.requestId"
          @click.stop.prevent="emit('trace',entry)"
        >
          {{ entry.record.requestId.slice(0,8) }}
        </el-button>
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
            @click="copy(JSON.stringify(entry.record,null,2))"
          >
            复制 JSON
          </el-button>
          <el-button
            v-if="filters"
            size="small"
            @click="emit('module',entry)"
          >
            只看此模块
          </el-button>
          <el-button
            v-if="filters"
            size="small"
            @click="emit('window',entry)"
          >
            前后 5 分钟
          </el-button>
        </div>
        <dl class="ops-definition">
          <dt>原始消息</dt><dd>{{ entry.record.msg }}</dd>
          <dt>Logger</dt><dd>{{ entry.record.logger }}</dd><dt>线程</dt><dd>{{ entry.record.thread }}</dd><dt>Request ID</dt><dd>{{ entry.record.requestId ?? '—' }}</dd><dt>User ID</dt><dd>{{ entry.record.userId ?? '—' }}</dd><template
            v-for="(value,key) in entry.record.fields"
            :key="key"
          >
            <dt>{{ key }}</dt><dd>{{ value }}</dd>
          </template>
        </dl>
        <template v-if="entry.record.error">
          <p>{{ entry.record.error.type }} · {{ entry.record.error.msg }}</p>
          <pre>{{ stack(entry) }}</pre>
          <el-button
            v-if="entry.record.error.stack.split('\n').length>12"
            size="small"
            @click="expandedStacks.has(entry.id)?expandedStacks.delete(entry.id):expandedStacks.add(entry.id)"
          >
            {{ expandedStacks.has(entry.id)?'收起堆栈':'展开全部帧' }}
          </el-button>
          <small>错误指纹 {{ entry.record.error.hash }}</small>
        </template>
      </div>
    </details>
    <div
      v-if="!entries.length"
      class="ops-empty"
    >
      当前条件下没有日志
    </div>
  </div>
</template>
<style scoped>
.with-date .ops-log-time{flex-basis:160px}
.ops-log-stream{font-family:var(--yp-font-mono);font-size:12px;border:1px solid var(--yp-border-subtle);border-radius:var(--yp-radius-md);background:var(--yp-bg-surface)}.ops-log-line{border-bottom:1px solid var(--yp-border-subtle);border-left:3px solid transparent}.ops-log-line.level-ERROR{border-left-color:var(--yp-status-red)}.ops-log-line.level-WARN{border-left-color:var(--yp-status-yellow)}summary{display:flex;align-items:center;gap:var(--yp-space-3);min-height:28px;padding:0 var(--yp-space-3);cursor:pointer}.ops-log-time{flex:0 0 90px;color:var(--yp-text-secondary)}.ops-log-module{flex:0 0 100px;overflow:hidden;text-overflow:ellipsis;color:var(--yp-text-secondary)}.ops-log-field{color:var(--yp-text-muted);white-space:nowrap}.ops-log-message{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.ops-log-detail{padding:var(--yp-space-4);border-top:1px solid var(--yp-border-subtle)}pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:400px;overflow:auto;font:inherit;background:var(--yp-bg-surface);padding:var(--yp-space-4)}summary:focus-visible{outline:2px solid var(--yp-action-primary);outline-offset:-2px}@media(max-width:760px){.ops-log-time{flex-basis:115px;font-size:10px}.ops-log-module,.ops-log-field{display:none}summary{gap:var(--yp-space-2)}}
</style>

