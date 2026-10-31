<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { TimeSessionFillMode, type MyTimeSession } from '@yumpoo/api-client'
import { timeTrackingApi } from '../../api/client'
import OverlapModeControl from '../my-time/day/OverlapModeControl.vue'
import { blockFromSession, defaultMode, MIN_BLOCK_MS, overlapEffect, type DayBlock, type Range } from '../my-time/day/dayModel'
import YpCalendar from '../yp/YpCalendar.vue'
import { ElOption, ElSelect, ElTimePicker } from 'element-plus'
import { companyDateTime, parseCompanyDateTime } from './timeFormat'

const props = withDefaults(defineProps<{ start: string; end: string; timezone: string; editing: boolean; busy: boolean; problem?: string
  mode?: TimeSessionFillMode; workItemId?: string }>(), { mode: TimeSessionFillMode.Gaps, problem: '', workItemId: '' })
const emit = defineEmits<{ 'update:start': [value: string]; 'update:end': [value: string]; 'update:mode': [value: TimeSessionFillMode]; expand: [value: boolean]; save: [] }>()
const selectedDay = computed(() => props.start.slice(0, 10))
const dayMs = 86400000
const dateNumber = (day: string) => new Date(`${day}T00:00:00Z`).getTime()
const dayString = (value: number) => new Date(value).toISOString().slice(0, 10)
const endOffset = computed(() => Math.max(0, Math.round((dateNumber(props.end.slice(0, 10)) - dateNumber(selectedDay.value)) / dayMs)))
const duration = computed(() => {
  try { return parseCompanyDateTime(props.end, props.timezone).getTime() - parseCompanyDateTime(props.start, props.timezone).getTime() } catch { return 0 }
})
const preview = computed(() => {
  const seconds = Math.max(0, Math.floor(duration.value / 1000))
  return `${String(Math.floor(seconds / 3600)).padStart(2, '0')}h ${String(Math.floor(seconds / 60) % 60).padStart(2, '0')}m`
})
const existing = ref<MyTimeSession[]>([])
const existingLoading = ref(false)
const existingProblem = ref('')
const clock = (date: Date) => companyDateTime(date, props.timezone).slice(11, 16)
watch([() => props.start, () => props.end, () => props.timezone], async ([start, end, timezone], _previous, onCleanup) => {
  let active = true
  onCleanup(() => { active = false })
  existing.value = []; existingProblem.value = ''; existingLoading.value = false
  let from: Date, to: Date
  try { from = parseCompanyDateTime(start, timezone); to = parseCompanyDateTime(end, timezone) } catch { return }
  if (to <= from) return
  existingLoading.value = true
  try {
    const result = await timeTrackingApi.listMyTimeSessions({ from, to })
    if (active) existing.value = result.items
  } catch { if (active) existingProblem.value = '无法加载该时段记录。' }
  finally { if (active) existingLoading.value = false }
}, { immediate: true })
const range = computed<Range | undefined>(() => {
  try {
    const value = { start: parseCompanyDateTime(props.start, props.timezone).getTime(), end: parseCompanyDateTime(props.end, props.timezone).getTime() }
    return value.end > value.start ? value : undefined
  } catch { return undefined }
})
const blocks = computed<DayBlock[]>(() => existing.value.map(blockFromSession))
/** The overlap choice only appears when the new range overlaps something, with the same defaults as the day timeline. */
const effect = computed(() => range.value && !props.editing
  ? overlapEffect(range.value, props.workItemId ? [props.workItemId] : [], blocks.value, Date.now()) : undefined)
const touched = ref(false)
watch(() => [effect.value?.overlapping.length ?? 0, effect.value?.fullyCovered], () => {
  if (!touched.value && effect.value && defaultMode(effect.value) !== props.mode) emit('update:mode', defaultMode(effect.value))
})
function setMode(value: TimeSessionFillMode) { touched.value = true; emit('update:mode', value) }
function selectDay(day: string) {
  const offset = endOffset.value
  emit('update:start', `${day}T${props.start.slice(11)}`)
  emit('update:end', `${dayString(dateNumber(day) + offset * dayMs)}T${props.end.slice(11)}`)
}
function setTime(which: 'start' | 'end', value: string) {
  const timestamp = `${props[which].slice(0, 10)}T${value}:00`
  if (which === 'start') emit('update:start', timestamp)
  else emit('update:end', timestamp)
}
function setOffset(value: number) {
  emit('update:end', `${dayString(dateNumber(selectedDay.value) + value * dayMs)}T${props.end.slice(11)}`)
}
</script>

<template>
  <form
    class="manual-session"
    @submit.prevent="emit('save')"
  >
    <YpCalendar
      :model-value="selectedDay"
      :timezone="timezone"
      :disabled="busy"
      @update:model-value="selectDay"
      @expand="emit('expand', $event)"
    />
    <div class="session-times">
      <label>开始时间<el-time-picker
        :model-value="start.slice(11, 16)"
        format="HH:mm"
        value-format="HH:mm"
        :clearable="false"
        :disabled="busy"
        aria-label="开始时间"
        @update:model-value="setTime('start', $event)"
      /></label>
      <label>结束时间<el-time-picker
        :model-value="end.slice(11, 16)"
        format="HH:mm"
        value-format="HH:mm"
        :clearable="false"
        :disabled="busy"
        aria-label="结束时间"
        @update:model-value="setTime('end', $event)"
      /></label>
    </div>
    <div class="end-day">
      <el-select
        :model-value="endOffset"
        size="small"
        :disabled="busy"
        aria-label="结束日期"
        @update:model-value="setOffset"
      >
        <el-option
          :value="0"
          label="当天结束"
        />
        <el-option
          :value="1"
          label="次日结束"
        />
        <el-option
          v-if="endOffset > 1"
          :value="endOffset"
          :label="`${endOffset} 天后结束`"
        />
      </el-select>
    </div>
    <OverlapModeControl
      v-if="effect && range && effect.overlapping.length"
      :model-value="mode"
      :effect="effect"
      :range="range"
      :item-count="1"
      @update:model-value="setMode"
    />
    <div class="existing-sessions">
      <strong>该时段已有记录</strong>
      <span v-if="existingLoading">正在加载…</span>
      <span v-else-if="existingProblem">{{ existingProblem }}</span>
      <ul v-else-if="existing.length">
        <li
          v-for="item in existing.slice(0, 3)"
          :key="item.session.id"
        >
          {{ clock(item.session.startedAt) }}–{{ item.session.stoppedAt ? clock(item.session.stoppedAt) : '计时中' }} {{ item.itemNo ? `#${item.itemNo} ` : '' }}{{ item.title ?? '不可见工作项' }}
        </li>
        <li v-if="existing.length > 3">
          等 {{ existing.length }} 条
        </li>
      </ul>
      <span v-else>暂无记录</span>
    </div>
    <p
      v-if="problem"
      class="save-error"
      role="alert"
    >
      {{ problem }}
    </p>
    <footer>
      <output aria-label="累计时长">{{ preview }}</output><button
        class="save-session"
        type="submit"
        :disabled="busy || duration <= 0 || (!editing && duration < MIN_BLOCK_MS)"
        :title="!editing && duration > 0 && duration < MIN_BLOCK_MS ? '至少记录 15 分钟' : undefined"
      >
        {{ busy ? '正在保存…' : editing ? '保存记录' : '添加记录' }}
      </button>
    </footer>
  </form>
</template>

<style scoped>
.save-error{margin:0;color:var(--yp-status-red);font-size:13px}
.existing-sessions{display:grid;gap:5px;font-size:11px;color:var(--yp-text-secondary)}.existing-sessions strong{font-weight:500}.existing-sessions ul{list-style:none;margin:0;padding:0;display:grid;gap:4px}.existing-sessions li{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.manual-session{display:flex;flex-direction:column;gap:12px;padding:4px 12px 8px}
button{font:inherit;color:inherit}button{border:0;background:transparent;cursor:pointer;border-radius:5px}button:hover:not(:disabled){background:var(--yp-bg-hover);color:var(--yp-text-primary)}button:disabled{opacity:.4;cursor:default}button:focus-visible{outline:2px solid var(--yp-focus-ring);outline-offset:2px}
.session-times{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin-top:8px}label{display:grid;gap:10px;font-size:14px}
.session-times :deep(.el-date-editor){width:100%}
.end-day :deep(.el-select){width:112px}
.end-day{display:flex;justify-content:flex-end;font-size:12px;margin-top:-8px;color:var(--yp-text-secondary)}footer{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:2px}output{font-size:21px;font-variant-numeric:tabular-nums;white-space:nowrap}.save-session{background:var(--yp-action-primary);color:var(--yp-on-solid);padding:11px 20px;font-size:16px}.save-session:hover:not(:disabled){background:var(--yp-action-primary-hover);color:var(--yp-on-solid)}
</style>
