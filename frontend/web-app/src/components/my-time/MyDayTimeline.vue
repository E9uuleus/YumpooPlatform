<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { workItemLabelColorValue } from '../projects/workItemLabelColors'
import { companyDateTime, parseCompanyDateTime } from '../timer/timeFormat'
import { lanes, snap, type DayBlock } from './myDayModel'

const props = withDefaults(defineProps<{ blocks: DayBlock[]; preview?: DayBlock[]; dayStart: number; dayEnd: number; now: number; timezone: string }>(), { preview: () => [] })
const emit = defineEmits<{ select: [start: number, end: number]; edit: [block: DayBlock] }>()
const scroll = ref<HTMLElement>()
const canvas = ref<HTMLElement>()
const selected = ref<{ anchor: number; point: number }>()
const laneMap = computed(() => lanes([...props.blocks, ...props.preview], props.now))
const laneCount = computed(() => Math.max(1, ...[...laneMap.value.values()].map(lane => lane + 1)))
const selection = computed(() => selected.value ? { start: Math.min(selected.value.anchor, selected.value.point), end: Math.max(selected.value.anchor, selected.value.point) } : undefined)
const position = (start: number, end: number) => ({ top: `${(Math.max(start, props.dayStart) - props.dayStart) / 60_000}px`, height: `${(Math.min(end, props.dayEnd) - Math.max(start, props.dayStart)) / 60_000}px` })
function blockStyle(block: DayBlock) {
  return { ...position(block.start, block.end ?? props.now), left: `calc(${(laneMap.value.get(block.id) ?? 0) / laneCount.value * 100}% + 3px)`,
    width: `calc(${100 / laneCount.value}% - 6px)`, '--day-block-color': workItemLabelColorValue(block.colorToken ?? undefined) }
}
const clock = (ms: number) => companyDateTime(new Date(ms), props.timezone).slice(11, 16)
const hours = computed(() => {
  const ticks = Array.from({ length: Math.ceil((props.dayEnd - props.dayStart) / 3_600_000) }, (_, index) => {
    const at = props.dayStart + index * 3_600_000
    return { at, label: clock(at) }
  })
  return [...ticks, { at: props.dayEnd, label: '24:00' }]
})
function point(event: PointerEvent) {
  const minute = event.clientY - canvas.value!.getBoundingClientRect().top
  return Math.max(props.dayStart, Math.min(Math.min(props.dayEnd, props.now), snap(props.dayStart + minute * 60_000)))
}
function begin(event: PointerEvent) {
  if (event.button !== 0 || props.dayStart >= props.now) return
  const at = point(event)
  selected.value = { anchor: at, point: at }
  canvas.value?.setPointerCapture(event.pointerId)
}
function move(event: PointerEvent) { if (selected.value) selected.value.point = point(event) }
function finish(event: PointerEvent) {
  const range = selection.value
  selected.value = undefined
  if (canvas.value?.hasPointerCapture(event.pointerId)) canvas.value.releasePointerCapture(event.pointerId)
  if (range && range.end > range.start) emit('select', range.start, range.end)
}
function scrollToMorning() {
  if (scroll.value) {
    const date = companyDateTime(new Date(props.dayStart), props.timezone).slice(0, 10)
    scroll.value.scrollTop = (parseCompanyDateTime(`${date}T08:00:00`, props.timezone).getTime() - props.dayStart) / 60_000
  }
}
onMounted(scrollToMorning)
watch(() => props.dayStart, scrollToMorning, { flush: 'post' })
defineExpose({ scrollToMorning })
</script>

<template>
  <div
    ref="scroll"
    class="my-day-timeline"
    aria-label="每日工时时间线"
  >
    <div
      class="my-day-timeline__hours"
      aria-hidden="true"
    >
      <span
        v-for="hour in hours"
        :key="hour.at"
        :style="{ top: `${(hour.at - dayStart) / 60_000}px` }"
      >{{ hour.label }}</span>
    </div>
    <div
      ref="canvas"
      class="my-day-timeline__canvas"
      :style="{ height: `${(dayEnd - dayStart) / 60_000}px` }"
      @pointerdown="begin"
      @pointermove="move"
      @pointerup="finish"
      @pointercancel="selected = undefined"
    >
      <i
        v-for="hour in hours"
        :key="hour.at"
        class="my-day-timeline__line"
        :style="{ top: `${(hour.at - dayStart) / 60_000}px` }"
        aria-hidden="true"
      />
      <button
        v-for="block in blocks"
        :key="block.id"
        type="button"
        class="day-block"
        :class="{ 'is-running': block.end === null }"
        :style="blockStyle(block)"
        :aria-disabled="!block.canEdit"
        :title="`${clock(block.start)}–${block.end === null ? '计时中' : clock(block.end)} ${block.title}`"
        @pointerdown.stop
        @click.stop="block.canEdit && emit('edit', block)"
      >
        <strong>{{ block.itemNo ? `#${block.itemNo} ` : '' }}{{ block.title }}</strong>
        <small>{{ clock(block.start) }}–{{ block.end === null ? '计时中' : clock(block.end) }}</small>
        <small v-if="block.projectName">{{ block.projectName }}</small>
      </button>
      <div
        v-for="block in preview"
        :key="block.id"
        class="day-block is-preview"
        :style="blockStyle(block)"
      >
        <strong>{{ block.title }}</strong><small>{{ clock(block.start) }}–{{ clock(block.end!) }}</small>
      </div>
      <div
        v-if="selection"
        class="day-selection"
        :style="position(selection.start, selection.end)"
      >
        {{ clock(selection.start) }}–{{ clock(selection.end) }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.my-day-timeline{position:relative;min-height:240px;height:100%;overflow:auto;padding:12px 12px 18px 58px;background:var(--yp-bg-surface);border:1px solid var(--yp-border-subtle);border-radius:var(--yp-radius-md);box-sizing:border-box}
.my-day-timeline__hours{position:absolute;top:12px;left:0;width:50px;font:11px var(--yp-font-mono);color:var(--yp-text-muted)}.my-day-timeline__hours span{position:absolute;right:6px;transform:translateY(-50%)}
.my-day-timeline__canvas{position:relative;touch-action:none;user-select:none}.my-day-timeline__line{position:absolute;left:0;right:0;border-top:1px solid var(--yp-border-subtle);pointer-events:none}
.day-block{position:absolute;display:flex;flex-direction:column;gap:3px;box-sizing:border-box;overflow:hidden;min-height:3px;padding:5px 7px;border:1px solid color-mix(in srgb,var(--day-block-color) 48%,var(--yp-border-subtle));border-left:3px solid var(--day-block-color);border-radius:5px;background:color-mix(in srgb,var(--day-block-color) 15%,var(--yp-bg-surface));color:var(--yp-text-primary);font:12px var(--yp-font-family);text-align:left;cursor:pointer}.day-block strong{font-weight:600}.day-block small{color:var(--yp-text-secondary);font-size:11px}.day-block[aria-disabled=true]{cursor:default}.day-block:focus-visible{outline:2px solid var(--yp-focus-ring);outline-offset:1px}.day-block.is-running{border-bottom-style:dashed}.day-block.is-preview{border:2px dashed var(--day-block-color);pointer-events:none;background:color-mix(in srgb,var(--day-block-color) 8%,var(--yp-bg-surface))}
.day-selection{position:absolute;inset-inline:0;z-index:2;box-sizing:border-box;border:1px dashed var(--yp-action-primary);background:color-mix(in srgb,var(--yp-action-primary) 15%,transparent);color:var(--yp-text-primary);font-size:12px;padding:3px;pointer-events:none}
</style>
