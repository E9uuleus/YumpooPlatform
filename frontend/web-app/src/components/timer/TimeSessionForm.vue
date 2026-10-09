<script setup lang="ts">
import { computed } from 'vue'
import TimeCalendar from './TimeCalendar.vue'
import { parseCompanyDateTime } from './timeFormat'

const props = defineProps<{ start: string; end: string; timezone: string; editing: boolean; busy: boolean; problem?: string }>()
const emit = defineEmits<{ 'update:start': [value: string]; 'update:end': [value: string]; expand: [value: boolean]; save: [] }>()
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
function selectDay(day: string) {
  const offset = endOffset.value
  emit('update:start', `${day}T${props.start.slice(11)}`)
  emit('update:end', `${dayString(dateNumber(day) + offset * dayMs)}T${props.end.slice(11)}`)
}
function setTime(which: 'start' | 'end', event: Event) {
  const value = (event.target as HTMLInputElement).value
  const timestamp = `${props[which].slice(0, 10)}T${value.length === 5 ? `${value}:00` : value}`
  if (which === 'start') emit('update:start', timestamp)
  else emit('update:end', timestamp)
}
function setOffset(event: Event) {
  emit('update:end', `${dayString(dateNumber(selectedDay.value) + Number((event.target as HTMLSelectElement).value) * dayMs)}T${props.end.slice(11)}`)
}
</script>

<template>
  <form
    class="manual-session"
    @submit.prevent="emit('save')"
  >
    <TimeCalendar
      :model-value="selectedDay"
      :timezone="timezone"
      :disabled="busy"
      @update:model-value="selectDay"
      @expand="emit('expand', $event)"
    />
    <div class="session-times">
      <label>开始时间<span class="time-input"><span aria-hidden="true">◷</span><input
        aria-label="开始时间"
        type="time"
        step="60"
        :value="start.slice(11, 16)"
        :disabled="busy"
        required
        @input="setTime('start', $event)"
      ></span></label>
      <label>结束时间<span class="time-input"><span aria-hidden="true">◷</span><input
        aria-label="结束时间"
        type="time"
        step="60"
        :value="end.slice(11, 16)"
        :disabled="busy"
        required
        @input="setTime('end', $event)"
      ></span></label>
    </div>
    <div class="end-day">
      <select
        aria-label="结束日期"
        :value="endOffset"
        :disabled="busy"
        @change="setOffset"
      >
        <option :value="0">
          当天结束
        </option><option :value="1">
          次日结束
        </option><option
          v-if="endOffset > 1"
          :value="endOffset"
        >
          {{ endOffset }} 天后结束
        </option>
      </select>
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
        :disabled="busy || duration <= 0"
      >
        {{ busy ? '正在保存…' : editing ? '保存记录' : '添加记录' }}
      </button>
    </footer>
  </form>
</template>

<style scoped>
.save-error{margin:0;color:var(--el-color-danger);font-size:13px}
.manual-session{display:flex;flex-direction:column;gap:12px;padding:4px 12px 8px}
button,select,input{font:inherit;color:inherit}button{border:0;background:transparent;cursor:pointer;border-radius:5px}button:hover:not(:disabled){background:#f0f1f3;color:var(--el-text-color-primary)}button:disabled{opacity:.4;cursor:default}button:focus-visible,select:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}
.end-day select{border:0;background:var(--el-bg-color);padding:5px 2px;cursor:pointer}
.session-times{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin-top:8px}label{display:grid;gap:10px;font-size:14px}.time-input{display:flex;gap:5px;align-items:center;border:1px solid var(--yp-input-border);border-radius:var(--yp-input-radius);padding:10px 8px;transition:border-color var(--yp-motion-fast) var(--yp-ease-standard)}.time-input:hover{border-color:var(--yp-input-border-hover)}.time-input:focus-within{border-color:var(--yp-input-border-focus)}.time-input>span{font-size:18px;color:var(--el-text-color-secondary)}.time-input input{width:100%;min-width:0;border:0;outline:none;background:transparent;font-size:14px}.time-input input::-webkit-calendar-picker-indicator{display:none}
.end-day{display:flex;justify-content:flex-end;font-size:12px;margin-top:-8px;color:var(--el-text-color-secondary)}footer{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:2px}output{font-size:21px;font-variant-numeric:tabular-nums;white-space:nowrap}.save-session{background:var(--el-color-primary);color:white;padding:11px 20px;font-size:16px}.save-session:hover:not(:disabled){background:#f0f1f3;color:var(--el-text-color-primary)}
</style>
