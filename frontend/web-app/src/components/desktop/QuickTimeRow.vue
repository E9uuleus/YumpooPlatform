<script setup lang="ts">
import { computed, type DefineComponent } from 'vue'
import type { TimerCandidate } from '@yumpoo/api-client'
import { ElDatePicker as ElDatePickerRaw } from 'element-plus'
import { Calendar, Check, Plus } from '@element-plus/icons-vue'
import TimerIcon from '../timer/TimerIcon.vue'
import { workItemLabelColorValue } from '../projects/workItemLabelColors'
import { QUICK_DURATIONS, shortDuration, spokenDuration, STATUS_PILLS, type DateChoice } from './quickTime'

const ElDatePicker = ElDatePickerRaw as unknown as DefineComponent

const props = defineProps<{
  item: TimerCandidate
  todayMs: number
  expanded: boolean
  /** Completed while "hide completed" is on: shown only in search results, struck through and faded. */
  faded: boolean
  flashing: boolean
  today: string
  hint: string
  canRecord: boolean
  recording: 'idle' | 'saving' | 'done'
  timerEnabled: boolean
}>()
const emit = defineEmits<{ toggle: []; record: []; start: [] }>()
const dateChoice = defineModel<DateChoice>('dateChoice', { required: true })
const pickedDate = defineModel<string>('pickedDate', { required: true })
const durationMs = defineModel<number>('durationMs', { required: true })
const custom = defineModel<boolean>('custom', { required: true })
const customText = defineModel<string>('customText', { required: true })

const pill = computed(() => STATUS_PILLS[props.item.statusCategory] ?? { label: props.item.statusCategory, tone: 'neutral' as const })
const dates: Array<{ value: DateChoice; label: string }> = [{ value: 'today', label: '今天' }, { value: 'yesterday', label: '昨天' }, { value: 'pick', label: '选择日期' }]
function chooseDuration(ms: number) { custom.value = false; durationMs.value = ms }
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
            <span
              class="quick-row__status"
              :data-tone="pill.tone"
            >{{ pill.label }}</span>
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
              <el-date-picker
                v-if="expanded && dateChoice === 'pick'"
                v-model="pickedDate"
                class="quick-date"
                type="date"
                size="small"
                value-format="YYYY-MM-DD"
                :clearable="false"
                :disabled-date="(date: Date) => date.getTime() > Date.parse(`${today}T23:59:59`)"
                aria-label="选择日期"
              />
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
              class="quick-hint"
              role="status"
            >
              <Calendar />{{ hint }}
            </p>
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
                :disabled="!canRecord && recording !== 'done'"
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
.quick-row__status{padding:1px 6px;border-radius:5px;font-size:11px;transition:opacity .2s;background:var(--yp-timer-sunken);color:var(--yp-text-secondary)}
.quick-row__status[data-tone="accent"]{background:color-mix(in srgb,var(--yp-status-blue) 14%,transparent);color:var(--yp-status-blue)}
.quick-row__status[data-tone="warning"]{background:color-mix(in srgb,var(--yp-status-orange) 14%,transparent);color:color-mix(in srgb,var(--yp-status-orange) 75%,var(--yp-text-primary))}
.quick-row__status[data-tone="success"]{background:color-mix(in srgb,var(--yp-status-green) 16%,transparent);color:color-mix(in srgb,var(--yp-status-green) 70%,var(--yp-text-primary))}
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
.quick-chip.is-active{border-color:var(--yp-timer-accent);background:color-mix(in srgb,var(--yp-timer-accent) 12%,transparent);color:var(--yp-timer-accent)}
.quick-date{--el-date-editor-width:132px}
.quick-custom{box-sizing:border-box;width:84px;height:24px;padding:0 8px;border:1px solid var(--yp-input-border);border-radius:999px;background:var(--yp-input-bg);color:var(--yp-text-primary);font:12px var(--yp-font-family);outline:0}
.quick-custom:focus{border-color:var(--yp-timer-accent)}
.quick-hint{display:flex;align-items:center;gap:6px;margin:0;color:var(--yp-text-secondary);font-size:12px;font-variant-numeric:tabular-nums}
.quick-hint svg{width:14px;height:14px;flex-shrink:0}
.quick-actions{display:flex;justify-content:flex-end;gap:8px}
.quick-button{display:inline-flex;align-items:center;gap:4px;height:30px;padding:0 10px;border:1px solid var(--yp-border-strong);border-radius:8px;color:var(--yp-text-primary);font-size:12.5px}
.quick-button svg{width:14px;height:14px}
.quick-button.is-primary{padding:0 14px;border-color:transparent;background:var(--yp-timer-accent);color:#fff;transition:background .2s}
.quick-button.is-done{background:var(--yp-timer-saved);color:#0b2b20}
</style>
