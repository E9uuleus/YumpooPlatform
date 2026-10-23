<script setup lang="ts">
import type { TimerCandidate, WorkItemDetail } from '@yumpoo/api-client'
import { Plus } from '@element-plus/icons-vue'
import QuickComposer from './QuickComposer.vue'
import QuickStatusButton from './QuickStatusButton.vue'
import type { Range } from '../my-time/day/dayModel'
import { workItemLabelColorValue } from '../projects/workItemLabelColors'
import { shortDuration, type DateChoice } from './quickTime'

defineProps<{
  item: TimerCandidate
  todayMs: number
  expanded: boolean
  /** Completed while "hide completed" is on: shown only in search results, struck through and faded. */
  faded: boolean
  flashing: boolean
  today: string
  hint: string
  targetDate: string
  timezone: string
  dayRange: Range
  rangeNote: string
  rangeInvalid: boolean
  canRecord: boolean
  recording: 'idle' | 'saving' | 'done'
  timerEnabled: boolean
}>()
const emit = defineEmits<{ toggle: []; record: []; start: []; statusChanged: [detail: WorkItemDetail, statusName: string]; statusFailed: [message: string] }>()
const dateChoice = defineModel<DateChoice>('dateChoice', { required: true })
const pickedDate = defineModel<string>('pickedDate', { required: true })
const durationMs = defineModel<number>('durationMs', { required: true })
const custom = defineModel<boolean>('custom', { required: true })
const customText = defineModel<string>('customText', { required: true })
const rangeMode = defineModel<boolean>('rangeMode', { required: true })
const rangeStart = defineModel<number>('rangeStart', { required: true })
const rangeEnd = defineModel<number>('rangeEnd', { required: true })
</script>

<template>
  <div
    class="quick-item"
    :class="{ 'is-expanded': expanded, 'is-flashing': flashing, 'is-completed': faded }"
  >
    <div class="quick-item__inner">
      <div
        class="quick-row"
        role="button"
        tabindex="0"
        :aria-expanded="expanded"
        @click="emit('toggle')"
        @keydown.enter.prevent="emit('toggle')"
        @keydown.space.prevent="emit('toggle')"
      >
        <span
          class="quick-row__bar"
          :style="{ background: workItemLabelColorValue(item.contentColorToken) }"
        />
        <span class="quick-row__text">
          <span class="quick-row__title">{{ item.title }}</span>
          <span class="quick-row__meta">
            <span class="quick-row__status"><QuickStatusButton
              :item="item"
              @changed="(detail, name) => emit('statusChanged', detail, name)"
              @failed="emit('statusFailed', $event)"
            /></span>
            <span class="quick-row__today">{{ todayMs ? `今日 ${shortDuration(todayMs)}` : '今日未记录' }}</span>
          </span>
        </span>
        <span
          class="quick-row__add"
          aria-hidden="true"
        ><Plus /></span>
      </div>
      <div class="quick-composer-wrap">
        <div>
          <QuickComposer
            v-model:date-choice="dateChoice"
            v-model:picked-date="pickedDate"
            v-model:duration-ms="durationMs"
            v-model:custom="custom"
            v-model:custom-text="customText"
            v-model:range-mode="rangeMode"
            v-model:range-start="rangeStart"
            v-model:range-end="rangeEnd"
            :expanded="expanded"
            :today="today"
            :hint="hint"
            :target-date="targetDate"
            :timezone="timezone"
            :day-range="dayRange"
            :range-note="rangeNote"
            :range-invalid="rangeInvalid"
            :can-record="canRecord"
            :recording="recording"
            :timer-enabled="timerEnabled"
            @record="emit('record')"
            @start="emit('start')"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.quick-item{display:grid;grid-template-rows:1fr}
.quick-item__inner,.quick-composer-wrap>div{min-height:0;overflow:hidden}
.quick-row:focus-visible{outline:2px solid var(--yp-timer-accent);outline-offset:-2px}
.quick-row{display:flex;align-items:center;gap:10px;padding:7px 6px;border-radius:9px;cursor:pointer;outline:0;transition:background .3s}
.quick-row:hover{background:var(--yp-timer-hover)}
.is-expanded .quick-row{background:var(--yp-timer-sunken)}
.is-flashing .quick-row{background:color-mix(in srgb,var(--yp-timer-saved) 18%,transparent)}
.quick-row__bar{width:4px;align-self:stretch;border-radius:2px;flex-shrink:0;transition:opacity .2s}
.quick-row__text{flex:1;min-width:0}
.quick-row__title{display:block;overflow:hidden;font-weight:500;text-overflow:ellipsis;white-space:nowrap;transition:color .2s}
.quick-row__meta{display:flex;align-items:center;gap:6px;margin-top:3px;color:var(--yp-text-secondary);font-size:11.5px}
.quick-row__status{display:inline-flex;transition:opacity .2s}
.quick-row__today{font-variant-numeric:tabular-nums}
.quick-row__add{display:grid;place-items:center;width:28px;height:28px;border-radius:8px;color:var(--yp-text-secondary);opacity:.55;transition:opacity .15s}
.quick-row__add svg{width:16px;height:16px}
.quick-row:hover .quick-row__add{opacity:1}
.is-completed .quick-row__title{color:var(--yp-text-muted);text-decoration:line-through}
.is-completed .quick-row__bar,.is-completed .quick-row__status{opacity:.45}
.quick-composer-wrap{display:grid;grid-template-rows:0fr;transition:grid-template-rows .26s var(--yp-timer-ease)}
.is-expanded .quick-composer-wrap{grid-template-rows:1fr}
</style>
