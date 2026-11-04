<script setup lang="ts">
import { computed, ref } from 'vue'
import { companyDateTime } from '../timer/timeFormat'

/** 周一开头的单日日历，工时补录与团队周期共用；今天之后的日期不可选。完整模式下点年月标题切换到月份面板。 */
const props = defineProps<{ modelValue: string; timezone: string; disabled?: boolean; expanded?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; expand: [value: boolean] }>()
const full = ref(props.expanded)
const pickingMonth = ref(false)
const today = computed(() => companyDateTime(new Date(), props.timezone).slice(0, 10))
const month = ref(props.modelValue.slice(0, 7))
const weekdays = ['一', '二', '三', '四', '五', '六', '日']
const dayMs = 86400000
const dateNumber = (day: string) => new Date(`${day}T00:00:00Z`).getTime()
const dayString = (value: number) => new Date(value).toISOString().slice(0, 10)
const monthKey = (year: number, value: number) => `${year}-${String(value).padStart(2, '0')}`
const year = computed(() => Number(month.value.slice(0, 4)))
const monthNumber = computed(() => Number(month.value.slice(5)))
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
function moveYear(delta: number) {
  const next = monthKey(year.value + delta, monthNumber.value)
  month.value = next > today.value.slice(0, 7) ? today.value.slice(0, 7) : next
}
function pickMonth(value: number) {
  month.value = monthKey(year.value, value)
  pickingMonth.value = false
}
function expand() {
  full.value = !full.value
  pickingMonth.value = false
  if (!full.value) month.value = props.modelValue.slice(0, 7)
  emit('expand', full.value)
}
</script>

<template>
  <div
    class="yp-calendar"
    :class="{ 'yp-calendar--full': full }"
  >
    <div
      v-if="full"
      class="yp-calendar__navigation"
    >
      <button
        type="button"
        class="yp-calendar__title"
        :aria-expanded="pickingMonth"
        :disabled="disabled"
        @click="pickingMonth = !pickingMonth"
      >
        {{ year }}年{{ monthNumber }}月<svg
          width="12"
          height="12"
          viewBox="0 0 20 20"
          fill="currentColor"
          aria-hidden="true"
        ><path d="M9.442 12.76a.77.77 0 0 0 1.116 0l4.21-4.363a.84.84 0 0 0 0-1.157.77.77 0 0 0-1.116 0L10 11.025 6.348 7.24a.77.77 0 0 0-1.117 0 .84.84 0 0 0 0 1.157l4.21 4.363Z" /></svg>
      </button>
      <span class="yp-calendar__spacer" />
      <template v-if="pickingMonth">
        <button
          type="button"
          class="yp-calendar__step"
          aria-label="上一年"
          :disabled="disabled"
          @click="moveYear(-1)"
        >
          ‹
        </button>
        <button
          type="button"
          class="yp-calendar__step"
          aria-label="下一年"
          :disabled="disabled || month.slice(0, 4) >= today.slice(0, 4)"
          @click="moveYear(1)"
        >
          ›
        </button>
      </template>
      <template v-else>
        <button
          type="button"
          class="yp-calendar__step"
          aria-label="上个月"
          :disabled="disabled"
          @click="moveMonth(-1)"
        >
          ‹
        </button>
        <button
          type="button"
          class="yp-calendar__step"
          aria-label="下个月"
          :disabled="disabled || month >= today.slice(0, 7)"
          @click="moveMonth(1)"
        >
          ›
        </button>
      </template>
    </div>
    <h4 v-else>
      {{ year }}年{{ monthNumber }}月
    </h4>
    <div
      v-if="pickingMonth"
      class="yp-calendar__months"
      role="group"
      aria-label="选择月份"
    >
      <button
        v-for="value in 12"
        :key="value"
        type="button"
        :class="{ selected: value === monthNumber }"
        :aria-pressed="value === monthNumber"
        :disabled="disabled || monthKey(year, value) > today.slice(0, 7)"
        @click="pickMonth(value)"
      >
        {{ value }}月
      </button>
    </div>
    <div
      v-else
      class="yp-calendar__grid"
      role="group"
      aria-label="选择记录日期"
    >
      <span
        v-for="day in weekdays"
        :key="day"
        class="yp-calendar__weekday"
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
      class="yp-calendar__expand"
      :aria-expanded="full"
      :disabled="disabled"
      @click="expand"
    >
      {{ full ? '收起日历' : '展开完整日历' }}
    </button>
  </div>
</template>

<style scoped>
.yp-calendar { padding: 0 14px; }
.yp-calendar h4 { margin: 12px 0 20px; font-size: 19px; font-weight: 500; text-align: center; }
.yp-calendar button { border: 0; border-radius: var(--yp-radius-sm); color: inherit; background: transparent; font: inherit; cursor: pointer; }
.yp-calendar button:hover:not(:disabled) { color: var(--yp-text-primary); background: var(--yp-bg-hover); }
.yp-calendar button:disabled { opacity: .4; cursor: default; }
.yp-calendar button:focus-visible { outline: 2px solid var(--yp-focus-ring); outline-offset: 2px; }
.yp-calendar button.selected,
.yp-calendar button.selected:hover:not(:disabled) { color: var(--yp-on-solid); background: var(--yp-action-primary); }
.yp-calendar__navigation { display: flex; align-items: center; gap: 6px; margin: 10px 0 14px; }
.yp-calendar__spacer { flex: 1; }
.yp-calendar__title { display: inline-flex; height: 30px; align-items: center; gap: 4px; padding: 0 8px; font-weight: 600; }
.yp-calendar__step { width: 28px; height: 30px; font-size: 26px; line-height: 1; }
.yp-calendar__grid { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); align-items: center; justify-items: center; gap: 5px 4px; }
.yp-calendar__weekday { margin-bottom: 8px; color: var(--yp-text-muted); font-size: 14px; }
.yp-calendar__grid button { width: 32px; height: 32px; font-size: 14px; font-variant-numeric: tabular-nums; }
.yp-calendar--full .yp-calendar__grid button { height: 28px; }
.yp-calendar__grid .muted { color: var(--yp-text-placeholder); }
.yp-calendar__months { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; padding: 4px 0 8px; }
.yp-calendar__months button { height: 36px; }
.yp-calendar__expand { display: block; width: 100%; margin-top: 12px; padding: 8px; background: var(--yp-fill-neutral); }
.yp-calendar--full .yp-calendar__expand { margin-top: 6px; padding: 3px; color: var(--yp-text-secondary); background: transparent; font-size: 12px; }
</style>
