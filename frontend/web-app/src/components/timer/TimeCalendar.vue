<script setup lang="ts">
import { computed, ref } from 'vue'
import { companyDateTime } from './timeFormat'

/** Monday-first single-day picker shared by the manual time-session form and the team timesheet; days after today are disabled. */
const props = defineProps<{ modelValue: string; timezone: string; disabled?: boolean; expanded?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; expand: [value: boolean] }>()
const full = ref(props.expanded)
const today = computed(() => companyDateTime(new Date(), props.timezone).slice(0, 10))
const month = ref(props.modelValue.slice(0, 7))
const weekdays = ['一', '二', '三', '四', '五', '六', '日']
const dayMs = 86400000
const dateNumber = (day: string) => new Date(`${day}T00:00:00Z`).getTime()
const dayString = (value: number) => new Date(value).toISOString().slice(0, 10)
const year = computed(() => Number(month.value.slice(0, 4)))
const monthNumber = computed(() => Number(month.value.slice(5)))
const years = computed(() => { const latest = Number(today.value.slice(0, 4)); const earliest = Math.min(latest - 100, year.value); return Array.from({ length: latest - earliest + 1 }, (_, i) => latest - i) })
const days = computed(() => {
  const first = new Date(`${month.value}-01T00:00:00Z`)
  let start = first.getTime() - (first.getUTCDay() + 6) % 7 * dayMs
  if (!full.value) {
    const selected = dateNumber(props.modelValue)
    const week = Math.floor((selected - start) / (7 * dayMs))
    start += Math.max(0, week - 1) * 7 * dayMs
  }
  return Array.from({ length: full.value ? 42 : 14 }, (_, i) => {
    const date = dayString(start + i * dayMs)
    return { date, label: Number(date.slice(8)), muted: !date.startsWith(month.value), disabled: date > today.value }
  })
})
function moveMonth(delta: number) {
  const date = new Date(Date.UTC(year.value, monthNumber.value - 1 + delta, 1))
  month.value = date.toISOString().slice(0, 7)
}
function setMonthPart(part: 'year' | 'month', event: Event) {
  const value = Number((event.target as HTMLSelectElement).value)
  month.value = `${part === 'year' ? value : year.value}-${String(part === 'month' ? value : monthNumber.value).padStart(2, '0')}`
}
function expand() { full.value = !full.value; if (!full.value) month.value = props.modelValue.slice(0, 7); emit('expand', full.value) }
</script>

<template>
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
        :disabled="disabled"
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
        :disabled="disabled"
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
        :disabled="disabled"
        @click="moveMonth(-1)"
      >
        ‹
      </button>
      <button
        type="button"
        aria-label="下个月"
        :disabled="disabled || month >= today.slice(0, 7)"
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
        :aria-pressed="modelValue === day.date"
        :disabled="disabled || day.disabled"
        :class="{ selected: modelValue === day.date, muted: day.muted }"
        @click="emit('update:modelValue', day.date)"
      >
        {{ day.label }}
      </button>
    </div>
    <button
      type="button"
      class="expand-calendar"
      :aria-expanded="full"
      :disabled="disabled"
      @click="expand"
    >
      {{ full ? '收起日历' : '展开完整日历' }}
    </button>
  </div>
</template>

<style scoped>
.calendar{padding:0 14px}.calendar h4{font-size:19px;font-weight:500;text-align:center;margin:12px 0 20px}
.calendar--full .calendar-grid button{height:28px}
.calendar-navigation{display:flex;align-items:center;gap:6px;margin:10px 0 14px}.navigation-spacer{flex:1}
button,select{font:inherit;color:inherit}button{border:0;background:transparent;cursor:pointer;border-radius:5px}button:hover:not(:disabled){background:#f0f1f3;color:var(--el-text-color-primary)}button:disabled{opacity:.4;cursor:default}button:focus-visible,select:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}
.calendar-navigation select{border:0;background:var(--el-bg-color);padding:5px 2px;cursor:pointer}.calendar-navigation button{width:28px;height:30px;font-size:26px;line-height:1}
.calendar-grid{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:5px 4px;justify-items:center;align-items:center}.weekday{color:var(--el-text-color-secondary);font-size:14px;margin-bottom:8px}.calendar-grid button{width:32px;height:32px;font-size:14px;font-variant-numeric:tabular-nums}.calendar-grid .muted{color:var(--el-text-color-placeholder)}.calendar-grid .selected{background:var(--el-color-primary);color:#fff}
.expand-calendar{display:block;width:100%;padding:8px;margin-top:12px;background:var(--el-fill-color-light)}.calendar--full .expand-calendar{background:transparent;padding:3px;font-size:12px;margin-top:6px;color:var(--el-text-color-secondary)}
</style>
