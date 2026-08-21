<script setup lang="ts">
import { Calendar as CalendarIcon } from '@element-plus/icons-vue'
import { ElIcon } from 'element-plus'
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  date: Date | string | null
  timezone: string
  completed?: boolean
}>(), {
  completed: false,
})

interface CalendarDay {
  year: number
  month: number
  day: number
}

function calendarDay(date: Date, timezone: string): CalendarDay | undefined {
  if (Number.isNaN(date.getTime())) return undefined
  try {
    const parts = new Intl.DateTimeFormat('en-CA', {
      timeZone: timezone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).formatToParts(date)
    const value = (type: Intl.DateTimeFormatPartTypes) => Number(parts.find(part => part.type === type)?.value)
    return { year: value('year'), month: value('month'), day: value('day') }
  } catch {
    return undefined
  }
}

function ordinal(day: CalendarDay): number {
  return Math.floor(Date.UTC(day.year, day.month - 1, day.day) / 86_400_000)
}

const presentation = computed(() => {
  if (!props.date) return { label: '未设置', tone: 'neutral' }
  const value = props.date instanceof Date ? props.date : new Date(props.date)
  const target = calendarDay(value, props.timezone)
  const today = calendarDay(new Date(), props.timezone)
  if (!target || !today) return { label: '日期无效', tone: 'red' }
  const label = `${target.year}-${String(target.month).padStart(2, '0')}-${String(target.day).padStart(2, '0')}`
  if (props.completed) return { label, tone: 'green' }
  const difference = ordinal(target) - ordinal(today)
  if (difference < 0) return { label: `${label} · 已逾期`, tone: 'red' }
  if (difference <= 1) return { label: `${label} · 临期`, tone: 'yellow' }
  return { label, tone: 'neutral' }
})
</script>

<template>
  <span
    class="yp-date-badge"
    :class="`yp-date-badge--${presentation.tone}`"
  >
    <el-icon aria-hidden="true">
      <calendar-icon />
    </el-icon>
    {{ presentation.label }}
  </span>
</template>

<style scoped>
.yp-date-badge {
  display: inline-flex;
  align-items: center;
  gap: var(--yp-space-1);
  min-height: var(--yp-status-height);
  padding: 0 var(--yp-space-2);
  border: 1px solid var(--yp-border-default);
  border-radius: var(--yp-radius-sm);
  color: var(--yp-text-secondary);
  background: var(--yp-bg-surface);
  font-size: var(--yp-type-caption-size);
  white-space: nowrap;
}

.yp-date-badge .el-icon {
  flex: 0 0 auto;
  font-size: 14px;
}

.yp-date-badge--red { color: var(--yp-status-red); border-color: color-mix(in srgb, var(--yp-status-red) 40%, var(--yp-border-default)); }
.yp-date-badge--yellow { color: var(--yp-text-primary); border-color: color-mix(in srgb, var(--yp-status-yellow) 55%, var(--yp-border-default)); background: color-mix(in srgb, var(--yp-status-yellow) 14%, var(--yp-bg-surface)); }
.yp-date-badge--green { color: var(--yp-text-primary); border-color: color-mix(in srgb, var(--yp-status-green) 45%, var(--yp-border-default)); background: color-mix(in srgb, var(--yp-status-green) 14%, var(--yp-bg-surface)); }
</style>
