<script setup lang="ts">
import { computed, ref } from 'vue'
import { companyDateTime, parseCompanyDateTime } from './timeFormat'

const props = defineProps<{ start: string; end: string; timezone: string; editing: boolean; busy: boolean; problem?: string }>()
const emit = defineEmits<{ 'update:start': [value: string]; 'update:end': [value: string]; expand: [value: boolean]; save: [] }>()
const full = ref(false)
const selectedDay = computed(() => props.start.slice(0, 10))
const today = computed(() => companyDateTime(new Date(), props.timezone).slice(0, 10))
const month = ref(selectedDay.value.slice(0, 7))
const weekdays = ['一', '二', '三', '四', '五', '六', '日']
const dayMs = 86400000
const dateNumber = (day: string) => new Date(`${day}T00:00:00Z`).getTime()
const dayString = (value: number) => new Date(value).toISOString().slice(0, 10)
const endOffset = computed(() => Math.max(0, Math.round((dateNumber(props.end.slice(0, 10)) - dateNumber(selectedDay.value)) / dayMs)))
const year = computed(() => Number(month.value.slice(0, 4)))
const monthNumber = computed(() => Number(month.value.slice(5)))
const years = computed(() => { const latest = Number(today.value.slice(0, 4)); const earliest = Math.min(latest - 100, year.value); return Array.from({ length: latest - earliest + 1 }, (_, i) => latest - i) })
const days = computed(() => {
  const first = new Date(`${month.value}-01T00:00:00Z`)
  let start = first.getTime() - (first.getUTCDay() + 6) % 7 * dayMs
  if (!full.value) {
    const selected = dateNumber(selectedDay.value)
    const week = Math.floor((selected - start) / (7 * dayMs))
    start += Math.max(0, week - 1) * 7 * dayMs
  }
  return Array.from({ length: full.value ? 42 : 14 }, (_, i) => {
    const date = dayString(start + i * dayMs)
    return { date, label: Number(date.slice(8)), muted: !date.startsWith(month.value), disabled: date > today.value }
  })
})
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
function moveMonth(delta: number) {
  const date = new Date(Date.UTC(year.value, monthNumber.value - 1 + delta, 1))
  month.value = date.toISOString().slice(0, 7)
}
function setMonthPart(part: 'year' | 'month', event: Event) {
  const value = Number((event.target as HTMLSelectElement).value)
  month.value = `${part === 'year' ? value : year.value}-${String(part === 'month' ? value : monthNumber.value).padStart(2, '0')}`
}
function expand() { full.value = !full.value; if (!full.value) month.value = selectedDay.value.slice(0, 7); emit('expand', full.value) }
</script>

<template>
  <form
    class="manual-session"
    @submit.prevent="emit('save')"
  >
    <div
      class="calendar"
      :class="{ 'calendar--full': full }"
    >
      <div
        v-if="full"
        class="calendar-navigation"
      >
        <select
          aria-label="选择月份"
          :value="monthNumber"
          :disabled="busy"
          @change="setMonthPart('month', $event)"
        >
          <option
            v-for="value in 12"
            :key="value"
            :value="value"
          >
            {{ value }}月
          </option>
        </select>
        <select
          aria-label="选择年份"
          :value="year"
          :disabled="busy"
          @change="setMonthPart('year', $event)"
        >
          <option
            v-for="value in years"
            :key="value"
            :value="value"
          >
            {{ value }}年
          </option>
        </select>
        <span class="navigation-spacer" />
        <button
          type="button"
          aria-label="上个月"
          :disabled="busy"
          @click="moveMonth(-1)"
        >
          ‹
        </button>
        <button
          type="button"
          aria-label="下个月"
          :disabled="busy || month >= today.slice(0, 7)"
          @click="moveMonth(1)"
        >
          ›
        </button>
      </div>
      <h4 v-else>
        {{ year }}年{{ monthNumber }}月
      </h4>
      <div
        class="calendar-grid"
        role="group"
        aria-label="选择记录日期"
      >
        <span
          v-for="day in weekdays"
          :key="day"
          class="weekday"
        >{{ day }}</span>
        <button
          v-for="day in days"
          :key="day.date"
          type="button"
          :aria-label="day.date"
          :aria-pressed="selectedDay === day.date"
          :disabled="busy || day.disabled"
          :class="{ selected: selectedDay === day.date, muted: day.muted }"
          @click="selectDay(day.date)"
        >
          {{ day.label }}
        </button>
      </div>
      <button
        type="button"
        class="expand-calendar"
        :aria-expanded="full"
        :disabled="busy"
        @click="expand"
      >
        {{ full ? '收起日历' : '展开完整日历' }}
      </button>
    </div>
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
.calendar{padding:0 14px}.calendar h4{font-size:19px;font-weight:500;text-align:center;margin:12px 0 20px}
.calendar--full .calendar-grid button{height:28px}
.calendar-navigation{display:flex;align-items:center;gap:6px;margin:10px 0 14px}.navigation-spacer{flex:1}
button,select,input{font:inherit;color:inherit}button{border:0;background:transparent;cursor:pointer;border-radius:5px}button:hover:not(:disabled){background:#f0f1f3;color:var(--el-text-color-primary)}button:disabled{opacity:.4;cursor:default}button:focus-visible,select:focus-visible,input:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}
.calendar-navigation select,.end-day select{border:0;background:var(--el-bg-color);padding:5px 2px;cursor:pointer}.calendar-navigation button{width:28px;height:30px;font-size:26px;line-height:1}
.calendar-grid{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:5px 4px;justify-items:center;align-items:center}.weekday{color:var(--el-text-color-secondary);font-size:14px;margin-bottom:8px}.calendar-grid button{width:32px;height:32px;font-size:14px;font-variant-numeric:tabular-nums}.calendar-grid .muted{color:var(--el-text-color-placeholder)}.calendar-grid .selected{background:var(--el-color-primary);color:#fff}
.expand-calendar{display:block;width:100%;padding:8px;margin-top:12px;background:var(--el-fill-color-light)}.calendar--full .expand-calendar{background:transparent;padding:3px;font-size:12px;margin-top:6px;color:var(--el-text-color-secondary)}
.session-times{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin-top:8px}label{display:grid;gap:10px;font-size:14px}.time-input{display:flex;gap:5px;align-items:center;border:1px solid var(--el-border-color);border-radius:5px;padding:10px 8px}.time-input>span{font-size:18px;color:var(--el-text-color-secondary)}.time-input input{width:100%;min-width:0;border:0;background:transparent;font-size:14px}.time-input input::-webkit-calendar-picker-indicator{display:none}
.end-day{display:flex;justify-content:flex-end;font-size:12px;margin-top:-8px;color:var(--el-text-color-secondary)}footer{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:2px}output{font-size:21px;font-variant-numeric:tabular-nums;white-space:nowrap}.save-session{background:var(--el-color-primary);color:white;padding:11px 20px;font-size:16px}.save-session:hover:not(:disabled){background:#f0f1f3;color:var(--el-text-color-primary)}
</style>
