<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { TimerCandidate, WorkItemDetail } from '@yumpoo/api-client'
import { Calendar, Check, Plus, RefreshLeft } from '@element-plus/icons-vue'
import TimerIcon from '../timer/TimerIcon.vue'
import TimeField from '../my-time/day/TimeField.vue'
import QuickStatusButton from './QuickStatusButton.vue'
import type { Range } from '../my-time/day/dayModel'
import { workItemLabelColorValue } from '../projects/workItemLabelColors'
import { QUICK_DURATIONS, shortDuration, spokenDuration, stepDate, stepperLabel, type DateChoice } from './quickTime'
import { addDays } from '../team/teamDashboardModel'

const props = defineProps<{
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
  /** Range mode's problem, or what the fill will skip; `rangeInvalid` marks it as a problem. */
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
const fieldsValid = ref({ start: true, end: true })
watch(rangeMode, () => { fieldsValid.value = { start: true, end: true } })
const rangeReady = computed(() => !rangeMode.value || (fieldsValid.value.start && fieldsValid.value.end))

const dates: Array<{ value: DateChoice; label: string }> = [{ value: 'today', label: '今天' }, { value: 'yesterday', label: '昨天' }]
const target = computed(() => dateChoice.value === 'today' ? props.today : dateChoice.value === 'yesterday' ? addDays(props.today, -1) : pickedDate.value)
function step(delta: -1 | 1) {
  const next = stepDate(target.value, delta, props.today)
  dateChoice.value = next.choice
  pickedDate.value = next.picked
}
function chooseDuration(ms: number) { custom.value = false; durationMs.value = ms }
/** Edited ends describe the duration themselves, so no chip, not even 自定义, stays lit unless the length matches one. */
function setRange(edge: 'start' | 'end', value: number) {
  custom.value = false
  if (edge === 'start') rangeStart.value = value
  else rangeEnd.value = value
}
/** Enter in either time field records, after TimeField has committed what was typed. */
function submit(event: KeyboardEvent) { if (event.target instanceof HTMLInputElement && rangeReady.value) emit('record') }
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
          <div
            class="quick-composer"
            :inert="!expanded"
          >
            <div
              class="quick-chips"
              role="radiogroup"
              aria-label="日期"
            >
              <button
                v-for="option in dates"
                :key="option.value"
                type="button"
                class="quick-chip"
                role="radio"
                :aria-checked="dateChoice === option.value"
                :class="{ 'is-active': dateChoice === option.value }"
                @click="dateChoice = option.value"
              >
                {{ option.label }}
              </button>
              <span
                class="quick-stepper"
                :class="{ 'is-active': dateChoice === 'pick' }"
              >
                <button
                  type="button"
                  aria-label="前一天"
                  @click="step(-1)"
                >‹</button>
                <span class="quick-stepper__label">{{ stepperLabel(target) }}</span>
                <button
                  type="button"
                  aria-label="后一天"
                  :disabled="target >= today"
                  @click="step(1)"
                >›</button>
              </span>
            </div>
            <div
              class="quick-chips"
              role="radiogroup"
              aria-label="时长"
            >
              <button
                v-for="ms in QUICK_DURATIONS"
                :key="ms"
                type="button"
                class="quick-chip"
                role="radio"
                :aria-checked="!custom && durationMs === ms"
                :class="{ 'is-active': !custom && durationMs === ms }"
                @click="chooseDuration(ms)"
              >
                {{ spokenDuration(ms) }}
              </button>
              <button
                type="button"
                class="quick-chip"
                role="radio"
                :aria-checked="custom"
                :class="{ 'is-active': custom }"
                @click="custom = true"
              >
                自定义
              </button>
              <input
                v-if="custom"
                v-model="customText"
                class="quick-custom"
                placeholder="如 1h30m"
                aria-label="自定义时长"
              >
            </div>
            <p
              v-if="!rangeMode"
              class="quick-hint"
              role="status"
            >
              <Calendar /><span class="quick-hint__text">{{ hint }}</span>
              <button
                type="button"
                class="quick-hint__action"
                @click="rangeMode = true"
              >
                改时段
              </button>
            </p>
            <div
              v-else
              class="quick-hint quick-range"
              @keydown.enter="submit"
            >
              <TimeField
                :model-value="rangeStart"
                :date="targetDate"
                :timezone="timezone"
                :day-start="dayRange.start"
                :day-end="dayRange.end"
                label="开始时间"
                immediate
                @update:model-value="setRange('start', $event)"
                @validity="fieldsValid.start = $event"
              />
              <span aria-hidden="true">–</span>
              <TimeField
                :model-value="rangeEnd"
                :date="targetDate"
                :timezone="timezone"
                :day-start="dayRange.start"
                :day-end="dayRange.end"
                label="结束时间"
                immediate
                @update:model-value="setRange('end', $event)"
                @validity="fieldsValid.end = $event"
              />
              <span v-if="rangeEnd > rangeStart">· {{ spokenDuration(rangeEnd - rangeStart) }}</span>
              <button
                type="button"
                class="quick-hint__action"
                title="回到自动排入空档"
                @click="rangeMode = false"
              >
                <RefreshLeft />自动
              </button>
              <span
                v-if="rangeNote"
                class="quick-range__note"
                :class="{ 'is-problem': rangeInvalid }"
                :role="rangeInvalid ? 'alert' : 'status'"
              >{{ rangeInvalid ? rangeNote : `· ${rangeNote}` }}</span>
            </div>
            <div class="quick-actions">
              <button
                v-if="timerEnabled"
                type="button"
                class="quick-button"
                @click="emit('start')"
              >
                <TimerIcon name="play" />开始计时
              </button>
              <button
                type="button"
                class="quick-button is-primary"
                :class="{ 'is-done': recording === 'done' }"
                :disabled="!(canRecord && rangeReady) && recording !== 'done'"
                @click="emit('record')"
              >
                <template v-if="recording === 'done'">
                  <Check />已记录
                </template>
                <template v-else>
                  记录 {{ spokenDuration(durationMs) || '…' }}
                </template>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.quick-item{display:grid;grid-template-rows:1fr}
.quick-item__inner,.quick-composer-wrap>div{min-height:0;overflow:hidden}
button{font:inherit;color:inherit;cursor:pointer;border:0;background:none;padding:0}
button:disabled{cursor:default;opacity:.45}
button:focus-visible,.quick-row:focus-visible{outline:2px solid var(--yp-timer-accent);outline-offset:-2px}
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
.quick-composer{display:flex;flex-direction:column;gap:8px;padding:8px 6px 12px 20px}
.quick-chips{display:flex;flex-wrap:wrap;align-items:center;gap:6px}
.quick-chip{display:inline-flex;align-items:center;height:24px;padding:0 9px;border:1px solid var(--yp-timer-hairline);border-radius:999px;color:var(--yp-text-secondary);font-size:12px;white-space:nowrap}
.quick-chip.is-active,.quick-stepper.is-active{border-color:var(--yp-timer-accent);background:color-mix(in srgb,var(--yp-timer-accent) 12%,transparent);color:var(--yp-timer-accent)}
.quick-stepper{display:inline-flex;align-items:center;height:24px;border:1px solid var(--yp-timer-hairline);border-radius:999px;color:var(--yp-text-secondary);font-size:12px}
.quick-stepper button{width:22px}
.quick-stepper__label{font-variant-numeric:tabular-nums}
.quick-custom{box-sizing:border-box;width:84px;height:24px;padding:0 8px;border:1px solid var(--yp-input-border);border-radius:999px;background:var(--yp-input-bg);color:var(--yp-text-primary);font:12px var(--yp-font-family);outline:0}
.quick-custom:focus{border-color:var(--yp-timer-accent)}
.quick-hint{display:flex;align-items:center;gap:6px;margin:0;color:var(--yp-text-secondary);font-size:12px;font-variant-numeric:tabular-nums}
.quick-hint svg{width:14px;height:14px;flex-shrink:0}
.quick-hint__text{min-width:0}
.quick-hint__action{display:inline-flex;align-items:center;gap:2px;margin-left:auto;padding:0 2px;border-radius:4px;color:var(--yp-timer-accent);font-size:12px;white-space:nowrap;flex-shrink:0}
.quick-hint__action svg{width:12px;height:12px}
.quick-hint__action:hover{text-decoration:underline}
.quick-range{flex-wrap:wrap;row-gap:4px}
.quick-range :deep(.time-field){width:56px;--el-input-height:24px}
.quick-range__note{flex-basis:100%;color:var(--yp-text-muted);font-size:11.5px}
.quick-range__note.is-problem{color:var(--yp-status-red)}
.quick-actions{display:flex;justify-content:flex-end;gap:8px}
.quick-button{display:inline-flex;align-items:center;gap:4px;height:30px;padding:0 10px;border:1px solid var(--yp-border-strong);border-radius:8px;color:var(--yp-text-primary);font-size:12.5px}
.quick-button svg{width:14px;height:14px}
.quick-button.is-primary{padding:0 14px;border-color:transparent;background:var(--yp-timer-accent);color:#fff;transition:background .2s}
.quick-button.is-done{background:var(--yp-timer-saved);color:#0b2b20}
</style>
