<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { workItemLabelColorValue } from '../../projects/workItemLabelColors'
import { blockEnd, durationLabel, formatClock, HOUR, MINUTE, type BlockLayout, type BlockShare, type DayBlock, type Range } from './dayModel'

const PX_PER_MINUTE = 1

const props = defineProps<{
  blocks: DayBlock[]
  layout: Map<string, BlockLayout>
  shares: Map<string, BlockShare>
  bounds: Range
  workday: Range
  now: number
  timezone: string
  selectedId?: string | undefined
  fresh?: ReadonlySet<string>
}>()
const emit = defineEmits<{ open: [block: DayBlock, anchor: HTMLElement] }>()

const scroller = ref<HTMLElement>()
const px = (ms: number) => `${ms / MINUTE * PX_PER_MINUTE}px`
const offset = (ms: number) => Math.min(Math.max(ms, props.bounds.start), props.bounds.end) - props.bounds.start
const clock = (ms: number) => formatClock(ms, props.timezone, props.bounds.end)
const nowInDay = computed(() => props.now > props.bounds.start && props.now < props.bounds.end)
const hours = computed(() => {
  const ticks: { at: number; label: string }[] = []
  for (let at = props.bounds.start; at < props.bounds.end; at += HOUR) ticks.push({ at, label: clock(at) })
  return [...ticks, { at: props.bounds.end, label: '24:00' }]
})

interface BlockView { block: DayBlock; style: Record<string, string>; tier: 'full' | 'medium' | 'compact'; label: string
  range: string; duration: string; split: number; splitTitle: string; continuesBefore: boolean; continuesAfter: boolean }
const views = computed<BlockView[]>(() => props.blocks.map(block => {
  const end = blockEnd(block, props.now), top = offset(block.start), height = offset(end) - top
  const place = props.layout.get(block.id) ?? { lane: 0, lanes: 1 }
  const share = props.shares.get(block.id) ?? { allocatedMs: 0, maxParallel: 1 }
  const minutes = height / MINUTE * PX_PER_MINUTE
  const range = `${clock(block.start)}–${block.end === null ? '计时中' : clock(end)}`
  const duration = durationLabel(end - block.start)
  const split = share.maxParallel
  const splitTitle = split > 1 ? `与其他 ${split - 1} 项并行，当天计入 ${durationLabel(share.allocatedMs)}（实际 ${duration}）` : ''
  return {
    block, range, duration, split, splitTitle,
    tier: minutes >= 46 ? 'full' : minutes >= 28 ? 'medium' : 'compact',
    continuesBefore: block.start < props.bounds.start, continuesAfter: end > props.bounds.end,
    label: [range, block.itemNo ? `#${block.itemNo}` : '', block.title, block.projectName ?? '', splitTitle,
      block.end === null ? '计时中' : ''].filter(Boolean).join('，'),
    style: {
      top: px(top), height: px(Math.max(height, 3 * MINUTE)),
      left: `calc(${place.lane / place.lanes * 100}% + 2px)`, width: `calc(${100 / place.lanes}% - 4px)`,
      '--day-block-color': workItemLabelColorValue(block.colorToken ?? undefined),
    },
  }
}))

function open(view: BlockView, event: MouseEvent) {
  emit('open', view.block, event.currentTarget as HTMLElement)
}

function scrollToWorkday() {
  if (scroller.value) scroller.value.scrollTop = Math.max(0, offset(props.workday.start - 30 * MINUTE) / MINUTE * PX_PER_MINUTE)
}
onMounted(scrollToWorkday)
watch(() => props.bounds.start, scrollToWorkday, { flush: 'post' })
defineExpose({ scrollToWorkday })
</script>

<template>
  <div
    ref="scroller"
    class="day-timeline"
    role="region"
    aria-label="当天时间线"
  >
    <div
      class="day-timeline__grid"
      :style="{ height: px(bounds.end - bounds.start) }"
    >
      <ol
        class="day-timeline__hours"
        aria-hidden="true"
      >
        <li
          v-for="hour in hours"
          :key="hour.at"
          :style="{ top: px(hour.at - bounds.start) }"
        >
          {{ hour.label }}
        </li>
      </ol>
      <div class="day-timeline__canvas">
        <div
          class="day-timeline__offhours"
          :style="{ top: '0', height: px(offset(workday.start)) }"
          aria-hidden="true"
        />
        <div
          class="day-timeline__offhours"
          :style="{ top: px(offset(workday.end)), bottom: '0' }"
          aria-hidden="true"
        />
        <i
          v-for="hour in hours"
          :key="hour.at"
          class="day-timeline__line"
          :style="{ top: px(hour.at - bounds.start) }"
          aria-hidden="true"
        />
        <div
          v-if="now < bounds.end"
          class="day-timeline__future"
          :style="{ top: px(offset(now)), bottom: '0' }"
          aria-hidden="true"
        />
        <button
          v-for="view in views"
          :key="view.block.id"
          type="button"
          class="day-block"
          :class="[`is-${view.tier}`, {
            'is-running': view.block.end === null,
            'is-locked': !view.block.canEdit,
            'is-selected': view.block.id === selectedId,
            'is-fresh': fresh?.has(view.block.id),
            'continues-before': view.continuesBefore,
            'continues-after': view.continuesAfter,
          }]"
          :style="view.style"
          :aria-label="view.label"
          :title="view.label"
          @click="open(view, $event)"
        >
          <span class="day-block__title">
            <strong>{{ view.block.title }}</strong>
            <span
              v-if="view.tier === 'compact'"
              class="day-block__meta"
            >{{ view.duration }}</span>
          </span>
          <span
            v-if="view.tier !== 'compact'"
            class="day-block__meta"
          >{{ view.range }}<template v-if="view.tier === 'full'"> · {{ view.duration }}</template></span>
          <span
            v-if="view.tier === 'full' && view.block.projectName"
            class="day-block__meta"
          >{{ view.block.itemNo ? `#${view.block.itemNo} · ` : '' }}{{ view.block.projectName }}</span>
          <span
            v-if="view.split > 1"
            class="day-block__split"
            aria-hidden="true"
          >÷{{ view.split }}</span>
        </button>
        <div
          v-if="nowInDay"
          class="day-timeline__now"
          :style="{ top: px(offset(now)) }"
          aria-hidden="true"
        >
          <span>{{ clock(now) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.day-timeline{position:relative;height:100%;min-height:320px;overflow:auto;overscroll-behavior:contain;background:var(--yp-bg-surface);border:1px solid var(--yp-border-subtle);border-radius:var(--yp-radius-md)}
.day-timeline__grid{position:relative;margin:12px 16px 16px 60px}
.day-timeline__hours{position:absolute;top:0;bottom:0;left:-60px;width:52px;margin:0;padding:0;list-style:none;color:var(--yp-text-muted);font:11px/1 var(--yp-font-mono);font-variant-numeric:tabular-nums}
.day-timeline__hours li{position:absolute;right:0;transform:translateY(-50%)}
.day-timeline__canvas{position:absolute;inset:0;user-select:none}
.day-timeline__offhours{position:absolute;left:0;right:0;background:color-mix(in srgb,var(--yp-bg-sunken) 70%,transparent);pointer-events:none}
.day-timeline__line{position:absolute;left:0;right:0;border-top:1px solid var(--yp-border-subtle);pointer-events:none}
.day-timeline__future{position:absolute;left:0;right:0;background:repeating-linear-gradient(135deg,transparent 0 6px,color-mix(in srgb,var(--yp-border-subtle) 60%,transparent) 6px 7px);pointer-events:none}
.day-timeline__now{position:absolute;left:-6px;right:0;z-index:4;height:0;border-top:2px solid var(--yp-status-red);pointer-events:none}
.day-timeline__now::before{content:'';position:absolute;top:-5px;left:0;width:8px;height:8px;border-radius:50%;background:var(--yp-status-red)}
.day-timeline__now span{position:absolute;top:-9px;right:0;padding:1px 5px;border-radius:var(--yp-radius-xs);background:var(--yp-status-red);color:var(--yp-text-inverse);font:600 10px/14px var(--yp-font-mono)}

.day-block{position:absolute;z-index:2;display:flex;flex-direction:column;gap:2px;box-sizing:border-box;min-width:0;padding:4px 8px;overflow:hidden;border:1px solid color-mix(in srgb,var(--day-block-color) 42%,var(--yp-border-subtle));border-left:3px solid var(--day-block-color);border-radius:var(--yp-radius-sm);background:color-mix(in srgb,var(--day-block-color) 14%,var(--yp-bg-surface));color:var(--yp-text-primary);font:12px/16px var(--yp-font-family);text-align:left;cursor:pointer;transition:box-shadow var(--yp-motion-fast) ease-out,transform var(--yp-motion-fast) ease-out}
.day-block:hover{box-shadow:var(--yp-shadow-hover)}
.day-block:focus-visible,.day-block.is-selected{outline:2px solid var(--yp-focus-ring);outline-offset:1px;z-index:3}
.day-block.is-compact{flex-direction:row;align-items:center;padding-block:0;font-size:11px;line-height:14px}
.day-block.is-running{border-bottom-style:dashed;background:color-mix(in srgb,var(--day-block-color) 22%,var(--yp-bg-surface))}
.day-block.is-locked{cursor:default;background:repeating-linear-gradient(135deg,color-mix(in srgb,var(--day-block-color) 10%,var(--yp-bg-surface)) 0 6px,color-mix(in srgb,var(--day-block-color) 16%,var(--yp-bg-surface)) 6px 12px)}
.day-block.continues-before{border-top-style:dashed;border-top-left-radius:0;border-top-right-radius:0}
.day-block.continues-after{border-bottom-style:dashed;border-bottom-left-radius:0;border-bottom-right-radius:0}
.day-block.is-fresh{animation:day-block-fresh 1.4s var(--yp-ease-standard)}
.day-block__title{display:flex;align-items:baseline;gap:6px;min-width:0}
.day-block__title strong{overflow:hidden;font-weight:600;white-space:nowrap;text-overflow:ellipsis}
.day-block__meta{overflow:hidden;color:var(--yp-text-secondary);font-size:11px;white-space:nowrap;text-overflow:ellipsis;font-variant-numeric:tabular-nums}
.day-block__split{position:absolute;top:3px;right:4px;padding:0 4px;border-radius:var(--yp-radius-xs);background:color-mix(in srgb,var(--day-block-color) 30%,var(--yp-bg-surface));color:var(--yp-text-primary);font:600 10px/14px var(--yp-font-mono)}
.day-block.is-compact .day-block__split{top:50%;transform:translateY(-50%)}
@keyframes day-block-fresh{0%{box-shadow:0 0 0 0 color-mix(in srgb,var(--day-block-color) 70%,transparent);transform:scale(1.02)}100%{box-shadow:0 0 0 10px transparent;transform:none}}
@media (prefers-reduced-motion:reduce){.day-block{transition:none}.day-block.is-fresh{animation:none}}
</style>
