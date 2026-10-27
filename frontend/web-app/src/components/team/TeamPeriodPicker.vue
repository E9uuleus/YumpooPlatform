<script setup lang="ts">
import { ref } from 'vue'
import { ElButton, ElIcon, ElPopover } from 'element-plus'
import { ArrowLeft, ArrowRight, Calendar } from '@element-plus/icons-vue'
import TimeCalendar from '../timer/TimeCalendar.vue'
import YpSegmented from '../yp/YpSegmented.vue'
import { periodFor, periodLabel, periodOptions, shiftPeriod, todayIn, type Period, type PeriodKind } from './teamDashboardModel'

/** Week/month/quarter switch with previous/next arrows and a calendar jump; `step` lets the grid animate towards the paged side. */
const props = defineProps<{ timezone: string }>()
const period = defineModel<Period>('period', { required: true })
const emit = defineEmits<{ step: [direction: -1 | 1] }>()
const calendarOpen = ref(false)
function changeKind(kind: PeriodKind) { period.value = periodFor(kind, todayIn(props.timezone)) }
function step(direction: -1 | 1) { emit('step', direction); period.value = shiftPeriod(period.value, direction) }
function pickDay(day: string) { calendarOpen.value = false; period.value = periodFor(period.value.kind, day) }
defineExpose({ close: () => { calendarOpen.value = false } })
</script>

<template>
  <div class="team-period-picker">
    <YpSegmented
      :model-value="period.kind"
      :options="periodOptions"
      label="统计周期"
      @update:model-value="changeKind"
    />
    <div class="team-period">
      <el-button
        :icon="ArrowLeft"
        text
        aria-label="上一周期"
        @click="step(-1)"
      />
      <el-popover
        v-model:visible="calendarOpen"
        trigger="click"
        placement="bottom"
        :width="320"
        popper-class="team-calendar-popover"
        transition="team-pop"
      >
        <template #reference>
          <button
            type="button"
            class="team-period__label"
            aria-haspopup="dialog"
            :aria-expanded="calendarOpen"
          >
            <el-icon aria-hidden="true">
              <Calendar />
            </el-icon><span class="team-period__text"><Transition name="team-label">
              <span :key="periodLabel(period)">{{ periodLabel(period) }}</span>
            </Transition></span>
          </button>
        </template>
        <TimeCalendar
          v-if="calendarOpen"
          :model-value="period.from"
          :timezone="timezone"
          expanded
          @update:model-value="pickDay"
        />
      </el-popover>
      <el-button
        :icon="ArrowRight"
        text
        aria-label="下一周期"
        @click="step(1)"
      />
    </div>
  </div>
</template>
