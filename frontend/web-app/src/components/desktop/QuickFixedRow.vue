<script setup lang="ts">
import { computed } from 'vue'
import QuickComposer from './QuickComposer.vue'
import type { Range } from '../my-time/day/dayModel'
import { workItemLabelColorValue } from '../projects/workItemLabelColors'
import type { TimeCategoryOption } from '../timer/timeCategories'
import { shortDuration, type DateChoice } from './quickTime'

/** One compact row for the non-project categories: a pill per category, opening the same record area as a work item row. */
const props = defineProps<{
  categories: TimeCategoryOption[]
  /** The open row's key; the record area shows when it is one of these categories. */
  openKey: string | null
  flashKey: string | null
  todayMs: ReadonlyMap<string, number>
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
const emit = defineEmits<{ toggle: [category: TimeCategoryOption]; record: [category: TimeCategoryOption]; start: [category: TimeCategoryOption] }>()
const dateChoice = defineModel<DateChoice>('dateChoice', { required: true })
const pickedDate = defineModel<string>('pickedDate', { required: true })
const durationMs = defineModel<number>('durationMs', { required: true })
const custom = defineModel<boolean>('custom', { required: true })
const customText = defineModel<string>('customText', { required: true })
const rangeMode = defineModel<boolean>('rangeMode', { required: true })
const rangeStart = defineModel<number>('rangeStart', { required: true })
const rangeEnd = defineModel<number>('rangeEnd', { required: true })
const open = computed(() => props.categories.find(category => category.key === props.openKey))
/** Only the open row edits a range, so a closed record area never renders a second pair of time fields. */
const openRangeMode = computed({ get: () => !!open.value && rangeMode.value, set: value => { rangeMode.value = value } })
</script>

<template>
  <div
    class="quick-fixed"
    :class="{ 'is-expanded': open, 'is-flashing': categories.some(category => category.key === flashKey) }"
  >
    <div class="quick-fixed__inner">
      <div
        class="quick-fixed__row"
        role="group"
        aria-label="非项目工时"
      >
        <span class="quick-fixed__label">非项目</span>
        <button
          v-for="category in categories"
          :key="category.key"
          type="button"
          class="quick-fixed__pill"
          :class="{ 'is-active': category.key === openKey }"
          :style="{ '--pill-color': workItemLabelColorValue(category.colorToken) }"
          :aria-expanded="category.key === openKey"
          :aria-label="`${category.label}，${todayMs.get(category.key) ? `今日 ${shortDuration(todayMs.get(category.key)!)}` : '今日未记录'}`"
          @click="emit('toggle', category)"
        >
          <component
            :is="category.icon"
            aria-hidden="true"
          />{{ category.label }}<span
            v-if="todayMs.get(category.key)"
            class="quick-fixed__today"
            aria-hidden="true"
          >{{ shortDuration(todayMs.get(category.key)!) }}</span>
        </button>
      </div>
      <div class="quick-composer-wrap">
        <div>
          <QuickComposer
            v-model:date-choice="dateChoice"
            v-model:picked-date="pickedDate"
            v-model:duration-ms="durationMs"
            v-model:custom="custom"
            v-model:custom-text="customText"
            v-model:range-mode="openRangeMode"
            v-model:range-start="rangeStart"
            v-model:range-end="rangeEnd"
            :expanded="!!open"
            :today="today"
            :hint="hint"
            :target-date="targetDate"
            :timezone="timezone"
            :day-range="dayRange"
            :range-note="rangeNote"
            :range-invalid="rangeInvalid"
            :can-record="canRecord"
            :recording="open ? recording : 'idle'"
            :timer-enabled="timerEnabled"
            @record="open && emit('record', open)"
            @start="open && emit('start', open)"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.quick-fixed{display:grid;grid-template-rows:1fr;margin-bottom:2px}
.quick-fixed__inner,.quick-composer-wrap>div{min-height:0;overflow:hidden}
.quick-fixed__row{display:flex;align-items:center;gap:6px;padding:6px;border-radius:9px;overflow-x:auto;scrollbar-width:none;transition:background .3s}
.is-expanded .quick-fixed__row{background:var(--yp-timer-sunken)}
.is-flashing .quick-fixed__row{background:color-mix(in srgb,var(--yp-timer-saved) 18%,transparent)}
.quick-fixed__label{flex-shrink:0;margin-right:2px;color:var(--yp-text-muted);font-size:11px}
.quick-fixed__pill{display:inline-flex;align-items:center;gap:4px;flex-shrink:0;height:26px;padding:0 9px 0 8px;border:1px solid var(--yp-timer-hairline);border-radius:999px;background:none;color:var(--yp-text-primary);font:12px var(--yp-font-family);white-space:nowrap;cursor:pointer;transition:background .15s,border-color .15s}
.quick-fixed__pill svg{width:13px;height:13px;color:var(--pill-color)}
.quick-fixed__pill:hover{background:var(--yp-timer-hover)}
.quick-fixed__pill.is-active{border-color:color-mix(in srgb,var(--pill-color) 60%,transparent);background:color-mix(in srgb,var(--pill-color) 14%,transparent)}
.quick-fixed__pill:focus-visible{outline:2px solid var(--yp-timer-accent);outline-offset:-2px}
.quick-fixed__today{margin-left:2px;color:var(--yp-text-secondary);font-size:11px;font-variant-numeric:tabular-nums}
.quick-composer-wrap{display:grid;grid-template-rows:0fr;transition:grid-template-rows .26s var(--yp-timer-ease)}
.is-expanded .quick-composer-wrap{grid-template-rows:1fr}
</style>
