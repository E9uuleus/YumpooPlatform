<script setup lang="ts">
import { computed } from 'vue'
import { ElButton, ElTooltip } from 'element-plus'
import { ArrowLeft, ArrowRight, Back } from '@element-plus/icons-vue'
import { CalendarDayType, type TeamTimesheetDay } from '@yumpoo/api-client'
import { addDays, weekday } from '../../team/teamDashboardModel'
import { durationLabel } from './dayModel'

const props = defineProps<{ date: string; today: string; day?: TeamTimesheetDay | undefined; recordedMs: number }>()
const emit = defineEmits<{ navigate: [date: string]; back: [] }>()

const title = computed(() => `${Number(props.date.slice(5, 7))}月${Number(props.date.slice(8))}日 周${weekday(props.date)}`)
const relative = computed(() => props.date === props.today ? '今天' : props.date === addDays(props.today, -1) ? '昨天' : '')
const dayKind = computed(() => {
  if (!props.day) return ''
  if (props.day.dayType === CalendarDayType.Workday) return props.day.note ? `工作日 · ${props.day.note}` : '工作日'
  if (props.day.dayType === CalendarDayType.Holiday) return props.day.note ? `节假日 · ${props.day.note}` : '节假日'
  return props.day.note ? `休息日 · ${props.day.note}` : '休息日'
})
</script>

<template>
  <header
    class="day-header"
    data-tour="day-header"
  >
    <el-button
      text
      :icon="Back"
      class="day-header__back"
      @click="emit('back')"
    >
      返回月历
    </el-button>
    <div
      class="day-header__date"
      role="group"
      aria-label="切换日期"
    >
      <el-tooltip content="前一天（←）">
        <el-button
          text
          :icon="ArrowLeft"
          aria-label="前一天"
          @click="emit('navigate', addDays(date, -1))"
        />
      </el-tooltip>
      <h1>
        {{ title }}<small v-if="relative">{{ relative }}</small>
      </h1>
      <el-tooltip content="后一天（→）">
        <el-button
          text
          :icon="ArrowRight"
          aria-label="后一天"
          :disabled="date >= today"
          @click="emit('navigate', addDays(date, 1))"
        />
      </el-tooltip>
      <el-button
        size="small"
        :disabled="date === today"
        @click="emit('navigate', today)"
      >
        今天
      </el-button>
    </div>
    <span
      v-if="dayKind"
      class="day-header__kind"
      :class="{ 'is-rest': day?.dayType !== CalendarDayType.Workday }"
    >{{ dayKind }}</span>
    <div
      class="day-header__total"
      aria-live="polite"
    >
      已记录<strong>{{ durationLabel(recordedMs) }}</strong>
    </div>
    <div class="day-header__actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.day-header{display:flex;flex-wrap:wrap;align-items:center;gap:8px 16px;min-height:56px;padding:8px 16px 8px 12px;border-bottom:1px solid var(--yp-border-default);background:var(--yp-bg-surface)}
.day-header__back{color:var(--yp-text-secondary)}
.day-header__date{display:flex;align-items:center;gap:2px}
.day-header__date .el-button+.el-button{margin-left:0}
.day-header__date h1{display:flex;align-items:baseline;gap:6px;min-width:128px;margin:0 4px;justify-content:center;font-size:var(--yp-type-card-title-size);font-weight:600;font-variant-numeric:tabular-nums;white-space:nowrap}
.day-header__date small{color:var(--yp-action-primary);font-size:12px;font-weight:500}
.day-header__date>.el-button--small{margin-left:8px}
.day-header__kind{padding:2px 8px;border-radius:999px;background:color-mix(in srgb,var(--yp-action-primary) 10%,var(--yp-bg-surface));color:var(--yp-action-primary);font-size:12px;line-height:18px;white-space:nowrap}
.day-header__kind.is-rest{background:var(--yp-bg-sunken);color:var(--yp-text-secondary)}
.day-header__total{margin-left:auto;color:var(--yp-text-secondary);font-size:13px;white-space:nowrap}
.day-header__total strong{margin-left:6px;color:var(--yp-text-primary);font:600 18px var(--yp-font-mono);font-variant-numeric:tabular-nums}
.day-header__actions{display:flex;align-items:center;gap:4px}
.day-header__actions:empty{display:none}
</style>
