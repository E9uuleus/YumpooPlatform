<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import '../../projects/workItemAccentBar.css'
import { workItemLabelColorValue } from '../../projects/workItemLabelColors'
import { blockEnd, clampMove, clampResize, durationLabel, FINE_SNAP_MS, formatClock, freeInterval, gaps, HOUR, MIN_BLOCK_MS, MIN_VISUAL_MS, MINUTE,
  PX_PER_MINUTE, rangeInGap, snap, snapDown, SNAP_MS, type BlockLayout, type BlockShare, type DayBlock, type Range } from './dayModel'
import { draggedItem, type ShelfItem } from './shelfDrag'

/** Space kept around every card, so stacked and side-by-side records read as separate cards. */
const GAP_Y = 2, GAP_X = 4
const PRIMARY = 'var(--yp-action-primary)'

const props = defineProps<{
  blocks: DayBlock[]
  layout: Map<string, BlockLayout>
  shares: Map<string, BlockShare>
  bounds: Range
  workday: Range
  now: number
  /** Latest instant a record may end at: the day's end or now. */
  limit: number
  timezone: string
  selectedId?: string | undefined
  fresh?: ReadonlySet<string>
  pending?: Range | null | undefined
  /** Dashed ghosts of what a quick add would create. */
  preview?: (Range & { title: string; colorToken: string | null })[]
}>()
const emit = defineEmits<{
  open: [block: DayBlock, anchor: HTMLElement]
  create: [range: Range]
  change: [block: DayBlock, range: Range]
  drop: [item: ShelfItem, range: Range]
  dragging: [active: boolean]
}>()

const scroller = ref<HTMLElement>()
const canvas = ref<HTMLElement>()
const pendingEl = ref<HTMLElement>()
const toPx = (ms: number) => ms / MINUTE * PX_PER_MINUTE
const px = (ms: number) => `${toPx(ms)}px`
const offset = (ms: number) => Math.min(Math.max(ms, props.bounds.start), props.bounds.end) - props.bounds.start
const clock = (ms: number) => formatClock(ms, props.timezone, props.bounds.end)
const color = (token: string | null) => workItemLabelColorValue(token ?? undefined)
const nowInDay = computed(() => props.now > props.bounds.start && props.now < props.bounds.end)
const hours = computed(() => {
  const ticks: { at: number; label: string }[] = []
  for (let at = props.bounds.start; at < props.bounds.end; at += HOUR) ticks.push({ at, label: clock(at) })
  return [...ticks, { at: props.bounds.end, label: '24:00' }]
})
const cardHeight = (range: Range) => toPx(Math.max(offset(range.end) - offset(range.start), MIN_VISUAL_MS)) - 2 * GAP_Y
const box = (range: Range) => ({ top: `${toPx(offset(range.start)) + GAP_Y}px`, height: `${cardHeight(range)}px` })
const ghostStyle = (range: Range, colorToken: string | null) =>
  ({ ...box(range), left: `${GAP_X}px`, right: `${GAP_X}px`, '--day-block-color': color(colorToken) })

/** Ranges shown while a move or resize is dragged and then saved; cleared when fresh data arrives. */
const drafts = ref(new Map<string, Range>())
watch(() => props.blocks, () => { if (!drag.value) drafts.value = new Map() })

interface BlockView { block: DayBlock; range: Range; style: Record<string, string>; tier: 'full' | 'medium' | 'compact'; label: string
  duration: string; split: number; splitTitle: string; continuesBefore: boolean; continuesAfter: boolean
  movable: boolean; startHandle: boolean; endHandle: boolean }
const views = computed<BlockView[]>(() => props.blocks.map(original => {
  const draft = drafts.value.get(original.id)
  const block = draft ? { ...original, start: draft.start, end: draft.end } : original
  const end = blockEnd(block, props.now), range = { start: block.start, end }, height = cardHeight(range)
  const editable = block.canEdit && block.end !== null
  const startInDay = block.start >= props.bounds.start, endInDay = end <= props.bounds.end
  const place = props.layout.get(block.id) ?? { lane: 0, lanes: 1 }
  const share = props.shares.get(block.id) ?? { allocatedMs: 0, maxParallel: 1 }
  const duration = durationLabel(end - block.start)
  const split = share.maxParallel
  const splitTitle = split > 1 ? `与其他 ${split - 1} 项并行，当天计入 ${durationLabel(share.allocatedMs)}（实际 ${duration}）` : ''
  return {
    block: original, range, duration, split, splitTitle,
    movable: editable && startInDay && endInDay, startHandle: editable && startInDay, endHandle: editable && endInDay,
    tier: height >= 64 ? 'full' : height >= 42 ? 'medium' : 'compact',
    continuesBefore: block.start < props.bounds.start, continuesAfter: end > props.bounds.end,
    label: [`${clock(block.start)}–${block.end === null ? '计时中' : clock(end)}`, block.title, block.projectName ?? '', duration, splitTitle]
      .filter(Boolean).join('，'),
    style: { ...box(range), left: `calc(${place.lane / place.lanes * 100}% + ${GAP_X}px)`, width: `calc(${100 / place.lanes}% - ${2 * GAP_X}px)`,
      '--day-block-color': color(block.colorToken) },
  }
}))
const hoveredId = ref<string>()

let suppressClick = false
function open(view: BlockView, event: MouseEvent) {
  if (suppressClick) { suppressClick = false; return }
  emit('open', view.block, event.currentTarget as HTMLElement)
}

interface Drag { block: DayBlock; edge: 'move' | 'start' | 'end'; pointerId: number; startY: number; bounds: Range; moved: boolean; range: Range
  target: HTMLElement }
const drag = ref<Drag>()
function dragStart(view: BlockView, edge: Drag['edge'], event: PointerEvent) {
  const allowed = edge === 'move' ? view.movable : edge === 'start' ? view.startHandle : view.endHandle
  suppressClick = false
  if (event.button !== 0 || event.pointerType === 'touch' || !allowed) return
  event.stopPropagation()
  const target = event.currentTarget as HTMLElement
  target.setPointerCapture(event.pointerId)
  const block = view.block
  drag.value = { block, edge, pointerId: event.pointerId, startY: event.clientY, moved: false, target,
    bounds: freeInterval(block, props.blocks, props.limit, { start: props.bounds.start, end: props.limit }),
    range: { start: block.start, end: blockEnd(block, props.limit) } }
}
function dragMove(event: PointerEvent) {
  const current = drag.value
  if (!current || current.pointerId !== event.pointerId) return
  const dy = event.clientY - current.startY
  if (!current.moved && Math.abs(dy) < 4) return
  if (!current.moved) { current.moved = true; emit('dragging', true) }
  const delta = dy / PX_PER_MINUTE * MINUTE, step = event.altKey ? FINE_SNAP_MS : SNAP_MS, block = current.block
  current.range = current.edge === 'move'
    ? clampMove(block, snap(block.start + delta, props.bounds.start, step) - block.start, current.bounds)
    : clampResize(block, current.edge, snap((current.edge === 'start' ? block.start : block.end!) + delta, props.bounds.start, step), current.bounds)
  drafts.value = new Map(drafts.value).set(block.id, current.range)
}
function dragEnd(event: PointerEvent, cancelled = false) {
  const current = drag.value
  if (!current || current.pointerId !== event.pointerId) return
  drag.value = undefined
  if (current.target.hasPointerCapture(event.pointerId)) current.target.releasePointerCapture(event.pointerId)
  if (!current.moved) return
  suppressClick = true
  emit('dragging', false)
  const { range, block } = current
  if (cancelled || (range.start === block.start && range.end === block.end)) {
    const next = new Map(drafts.value); next.delete(block.id); drafts.value = next
  } else emit('change', block, range)
}

const dropGhost = ref<Range | null>(null)
function dragOver(event: DragEvent) {
  if (!draggedItem.value) return
  const range = rangeInGap(snapDown(timeAt(event), props.bounds.start), HOUR, free.value)
  dropGhost.value = range
  if (!range) { if (event.dataTransfer) event.dataTransfer.dropEffect = 'none'; return }
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
}
function drop(event: DragEvent) {
  const item = draggedItem.value, range = dropGhost.value
  dropGhost.value = null
  draggedItem.value = null
  if (!item || !range) return
  event.preventDefault()
  emit('drop', item, range)
}

const free = computed(() => gaps(props.blocks, props.now, { start: props.bounds.start, end: props.limit }))
/** Empty stretches inside working hours worth a one-click shortcut. */
const workGaps = computed(() => gaps(props.blocks, props.now,
  { start: Math.max(props.workday.start, props.bounds.start), end: Math.min(props.workday.end, props.limit) })
  .filter(gap => gap.end - gap.start >= MIN_BLOCK_MS))

interface Selection { pointerId: number; anchor: number; point: number; raw: number; startY: number; moved: boolean; touch: boolean }
const selection = ref<Selection>()
const hover = ref<number | null>(null)
/** Manual entries last at least 15 minutes, so a shorter drag already shows the 15 minutes it will open. */
const selected = computed(() => {
  const current = selection.value
  if (!current?.moved || current.touch) return undefined
  const start = Math.min(current.anchor, current.point)
  return { start, end: Math.min(Math.max(current.anchor, current.point, start + MIN_BLOCK_MS), props.limit) }
})
const timeAt = (event: MouseEvent) => props.bounds.start + (event.clientY - canvas.value!.getBoundingClientRect().top) / PX_PER_MINUTE * MINUTE
const clampTime = (ms: number) => Math.min(Math.max(ms, props.bounds.start), props.limit)
const snapped = (event: PointerEvent) => clampTime(snap(timeAt(event), props.bounds.start, event.altKey ? FINE_SNAP_MS : SNAP_MS))
const onEmpty = (event: Event) => !(event.target as HTMLElement).closest('.day-block, .day-gap')

function pointerdown(event: PointerEvent) {
  if (event.button !== 0 || props.limit <= props.bounds.start || !onEmpty(event)) return
  const at = snapped(event)
  selection.value = { pointerId: event.pointerId, anchor: at, point: at, raw: timeAt(event), startY: event.clientY, moved: false,
    touch: event.pointerType === 'touch' }
  // Touch keeps native scrolling; a tap still opens an hour, and the composer's time fields adjust it.
  if (event.pointerType !== 'touch') canvas.value!.setPointerCapture(event.pointerId)
}
function pointermove(event: PointerEvent) {
  const current = selection.value
  if (!current) {
    hover.value = event.pointerType === 'mouse' && onEmpty(event) && timeAt(event) < props.limit
      ? snapDown(timeAt(event), props.bounds.start, event.altKey ? FINE_SNAP_MS : SNAP_MS) : null
    return
  }
  if (Math.abs(event.clientY - current.startY) > 4) current.moved = true
  if (current.touch) { if (current.moved) selection.value = undefined; return }
  current.point = snapped(event)
  hover.value = null
}
function pointerup(event: PointerEvent) {
  const current = selection.value, range = selected.value
  selection.value = undefined
  if (!current || current.pointerId !== event.pointerId) return
  if (canvas.value?.hasPointerCapture(event.pointerId)) canvas.value.releasePointerCapture(event.pointerId)
  if (!current.moved) {
    const hour = rangeInGap(snapDown(current.raw, props.bounds.start), HOUR, free.value)
    if (hour) emit('create', hour)
    return
  }
  if (range && range.end - range.start >= MIN_BLOCK_MS) emit('create', range)
}

interface AxisMark extends Range { key: string; color: string; endLabel?: string | undefined }
/** Times shown on the axis: what is being dragged or selected first, then the hovered card, the open composer, or the hover line. */
const axisMarks = computed<AxisMark[]>(() => {
  const moving = drag.value?.moved ? drag.value : undefined
  if (moving) return [{ key: 'drag', ...moving.range, color: color(moving.block.colorToken) }]
  if (selected.value) return [{ key: 'select', ...selected.value, color: PRIMARY }]
  if (dropGhost.value) return [{ key: 'drop', ...dropGhost.value, color: color(draggedItem.value?.colorToken ?? null) }]
  if (props.preview?.length) return props.preview.map(ghost => ({ key: `preview-${ghost.start}`, start: ghost.start, end: ghost.end, color: color(ghost.colorToken) }))
  const hovered = views.value.find(view => view.block.id === hoveredId.value)
  if (hovered) return [{ key: 'block', ...hovered.range, color: color(hovered.block.colorToken), endLabel: hovered.block.end === null ? '计时中' : undefined }]
  if (props.pending) return [{ key: 'pending', ...props.pending, color: PRIMARY }]
  if (hover.value !== null) return [{ key: 'hover', start: hover.value, end: hover.value, color: PRIMARY }]
  return []
})

function scrollToWorkday() {
  if (scroller.value) scroller.value.scrollTop = Math.max(0, toPx(offset(props.workday.start - 30 * MINUTE)))
}
onMounted(scrollToWorkday)
watch(() => props.bounds.start, scrollToWorkday, { flush: 'post' })
defineExpose({ scrollToWorkday, pendingEl })
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
      :style="{ height: px(bounds.end - bounds.start), '--day-quarter': px(SNAP_MS) }"
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
      <div
        v-for="mark in axisMarks"
        :key="mark.key"
        class="day-axis-mark"
        :class="{ 'is-point': mark.end <= mark.start }"
        :style="{ top: px(offset(mark.start)), height: px(offset(mark.end) - offset(mark.start)), '--day-axis-color': mark.color }"
        aria-hidden="true"
      >
        <span class="day-axis-mark__time is-start">{{ clock(mark.start) }}</span>
        <span
          v-if="mark.end > mark.start"
          class="day-axis-mark__time is-end"
        >{{ mark.endLabel ?? clock(mark.end) }}</span>
      </div>
      <div
        ref="canvas"
        class="day-timeline__canvas"
        :class="{ 'is-selecting': selected, 'is-receiving': draggedItem }"
        @pointerdown="pointerdown"
        @pointermove="pointermove"
        @pointerup="pointerup"
        @pointercancel="selection = undefined"
        @pointerleave="hover = null"
        @dragover="dragOver"
        @dragleave="dropGhost = null"
        @drop="drop"
      >
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
        <template v-if="!selected">
          <div
            v-for="gap in workGaps"
            :key="`zone-${gap.start}`"
            class="day-gap-zone"
            :style="{ top: px(offset(gap.start)), height: px(offset(gap.end) - offset(gap.start)) }"
            aria-hidden="true"
          />
          <button
            v-for="gap in workGaps"
            :key="`gap-${gap.start}`"
            type="button"
            class="day-gap"
            :class="{ 'is-small': gap.end - gap.start < 40 * MINUTE }"
            :style="{ top: px(offset(gap.start) + (gap.end - gap.start) / 2) }"
            :aria-label="`补录 ${clock(gap.start)} 到 ${clock(gap.end)} 的空白`"
            @click="emit('create', gap)"
          >
            {{ gap.end - gap.start >= 40 * MINUTE ? `空白 ${durationLabel(gap.end - gap.start)} · 补录` : `+ ${durationLabel(gap.end - gap.start)}` }}
          </button>
        </template>
        <button
          v-for="view in views"
          :key="view.block.id"
          type="button"
          class="day-block work-item-accent-bar"
          :class="[`is-${view.tier}`, {
            'is-running': view.block.end === null,
            'is-locked': !view.block.canEdit,
            'is-selected': view.block.id === selectedId,
            'is-dragging': drag?.moved && drag.block.id === view.block.id,
            'is-fresh': fresh?.has(view.block.id),
            'continues-before': view.continuesBefore,
            'continues-after': view.continuesAfter,
          }]"
          :style="view.style"
          :aria-label="view.label"
          :title="view.label"
          @click="open(view, $event)"
          @pointerdown="dragStart(view, 'move', $event)"
          @pointermove="dragMove"
          @pointerup="dragEnd($event)"
          @pointercancel="dragEnd($event, true)"
          @pointerenter="hoveredId = view.block.id"
          @pointerleave="hoveredId = undefined"
          @focus="hoveredId = view.block.id"
          @blur="hoveredId = undefined"
        >
          <span
            v-if="view.startHandle"
            class="day-block__handle is-start"
            aria-hidden="true"
            @pointerdown="dragStart(view, 'start', $event)"
          />
          <span
            v-if="view.endHandle"
            class="day-block__handle is-end"
            aria-hidden="true"
            @pointerdown="dragStart(view, 'end', $event)"
          />
          <span class="day-block__head">
            <strong class="day-block__title">{{ view.block.title }}</strong>
            <span
              v-if="view.tier === 'compact'"
              class="day-block__duration"
            >{{ view.duration }}</span>
            <span
              v-if="view.split > 1"
              class="day-block__split"
              aria-hidden="true"
            >÷{{ view.split }}</span>
          </span>
          <span
            v-if="view.tier !== 'compact'"
            class="day-block__meta"
          >
            <span class="day-block__project">{{ view.block.projectName ?? '' }}</span>
            <span class="day-block__duration">{{ view.block.end === null ? `计时中 · ${view.duration}` : view.duration }}</span>
          </span>
        </button>
        <div
          v-for="ghost in preview ?? []"
          :key="`preview-${ghost.start}`"
          class="day-block work-item-accent-bar is-preview"
          :style="ghostStyle(ghost, ghost.colorToken)"
          aria-hidden="true"
        >
          <span class="day-block__head">
            <strong class="day-block__title">{{ ghost.title }}</strong>
            <span class="day-block__duration">{{ durationLabel(ghost.end - ghost.start) }}</span>
          </span>
        </div>
        <div
          v-if="dropGhost && draggedItem"
          class="day-block work-item-accent-bar is-preview is-drop"
          :style="ghostStyle(dropGhost, draggedItem.colorToken)"
          aria-hidden="true"
        >
          <span class="day-block__head">
            <strong class="day-block__title">{{ draggedItem.title }}</strong>
            <span class="day-block__duration">{{ durationLabel(dropGhost.end - dropGhost.start) }}</span>
          </span>
        </div>
        <div
          v-if="hover !== null && !pending && !drag"
          class="day-hover"
          :style="{ top: px(offset(hover)) }"
          aria-hidden="true"
        >
          <span>点击补录 1 小时，或拖选时段</span>
        </div>
        <div
          v-if="selected"
          class="day-selection"
          :style="{ top: px(offset(selected.start)), height: px(selected.end - selected.start) }"
          aria-hidden="true"
        >
          {{ durationLabel(selected.end - selected.start) }}
        </div>
        <div
          v-else-if="pending"
          ref="pendingEl"
          class="day-selection is-pending"
          :style="{ top: px(offset(pending.start)), height: px(Math.max(pending.end - pending.start, 0)) }"
        >
          {{ pending.end > pending.start ? durationLabel(pending.end - pending.start) : '' }}
        </div>
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
.day-timeline__grid{position:relative;margin:14px 16px 18px 76px}
.day-timeline__hours{position:absolute;top:0;bottom:0;left:-76px;width:60px;margin:0;padding:0;list-style:none;color:var(--yp-text-muted);font:12px/1 var(--yp-font-mono);font-variant-numeric:tabular-nums}
.day-timeline__hours li{position:absolute;right:0;transform:translateY(-50%)}
.day-axis-mark{position:absolute;left:-76px;z-index:1;width:70px;pointer-events:none}
.day-axis-mark::after{content:'';position:absolute;top:0;right:0;bottom:0;width:3px;border-radius:2px;background:var(--day-axis-color)}
.day-axis-mark.is-point::after{content:none}
.day-axis-mark__time{position:absolute;right:8px;padding:1px 6px;border:1px solid color-mix(in srgb,var(--day-axis-color) 55%,var(--yp-border-subtle));border-radius:var(--yp-radius-xs);background:color-mix(in srgb,var(--day-axis-color) 16%,var(--yp-bg-surface));box-shadow:var(--yp-shadow-card);color:var(--yp-text-primary);font:600 12px/16px var(--yp-font-mono);font-variant-numeric:tabular-nums;white-space:nowrap}
.day-axis-mark__time.is-start{top:0;transform:translateY(-50%)}
.day-axis-mark__time.is-end{bottom:0;transform:translateY(50%)}
.day-timeline__canvas{position:absolute;inset:0;border-radius:var(--yp-radius-sm);background:radial-gradient(circle,color-mix(in srgb,var(--yp-border-strong) 45%,transparent) 1px,transparent 1.5px) 0 calc(var(--day-quarter) / -2) / var(--day-quarter) var(--day-quarter);user-select:none;cursor:crosshair;touch-action:pan-y;transition:box-shadow var(--yp-motion-fast) ease-out}
.day-timeline__canvas.is-selecting{cursor:ns-resize}
.day-timeline__canvas.is-receiving{box-shadow:inset 0 0 0 2px color-mix(in srgb,var(--yp-action-primary) 40%,transparent)}
.day-gap-zone{position:absolute;left:4px;right:4px;border:1px dashed color-mix(in srgb,var(--yp-action-primary) 30%,var(--yp-border-subtle));border-radius:var(--yp-radius-sm);background:color-mix(in srgb,var(--yp-action-primary) 3%,transparent);pointer-events:none;transition:border-color var(--yp-motion-fast) ease-out,background var(--yp-motion-fast) ease-out}
.day-timeline__canvas.is-receiving .day-gap-zone{border-color:color-mix(in srgb,var(--yp-action-primary) 65%,var(--yp-border-subtle));background:color-mix(in srgb,var(--yp-action-primary) 8%,transparent)}
.day-gap{position:absolute;left:50%;z-index:1;transform:translate(-50%,-50%);padding:3px 10px;border:1px solid color-mix(in srgb,var(--yp-action-primary) 35%,var(--yp-border-subtle));border-radius:999px;background:var(--yp-bg-surface);color:var(--yp-action-primary);font:500 12px/16px var(--yp-font-family);white-space:nowrap;cursor:pointer;opacity:.8;transition:opacity var(--yp-motion-fast) ease-out,box-shadow var(--yp-motion-fast) ease-out}
.day-gap.is-small{padding:0 8px;font-size:11px;line-height:14px}
.day-gap:hover,.day-gap:focus-visible{opacity:1;box-shadow:var(--yp-shadow-hover)}
.day-gap:focus-visible{outline:2px solid var(--yp-focus-ring);outline-offset:1px}
.day-hover{position:absolute;left:0;right:0;z-index:6;height:0;border-top:1px dashed var(--yp-action-primary);pointer-events:none}
.day-hover span{position:absolute;top:5px;left:8px;padding:2px 8px;border:1px solid color-mix(in srgb,var(--yp-action-primary) 30%,var(--yp-border-subtle));border-radius:var(--yp-radius-xs);background:var(--yp-bg-surface);box-shadow:var(--yp-shadow-hover);color:var(--yp-action-primary);font:12px/16px var(--yp-font-family);white-space:nowrap}
.day-selection{position:absolute;left:4px;right:4px;z-index:3;box-sizing:border-box;padding:4px 10px;border:1.5px solid var(--yp-action-primary);border-radius:var(--yp-radius-sm);background:color-mix(in srgb,var(--yp-action-primary) 14%,transparent);color:var(--yp-text-primary);font:600 13px/18px var(--yp-font-mono);font-variant-numeric:tabular-nums;pointer-events:none}
.day-selection.is-pending{border-style:dashed;box-shadow:0 0 0 4px color-mix(in srgb,var(--yp-action-primary) 12%,transparent)}
.day-timeline__offhours{position:absolute;left:0;right:0;background:color-mix(in srgb,var(--yp-bg-sunken) 70%,transparent);pointer-events:none}
.day-timeline__line{position:absolute;left:0;right:0;border-top:1px solid var(--yp-border-subtle);pointer-events:none}
.day-timeline__future{position:absolute;left:0;right:0;background:repeating-linear-gradient(135deg,transparent 0 6px,color-mix(in srgb,var(--yp-border-subtle) 60%,transparent) 6px 7px);pointer-events:none}
.day-timeline__now{position:absolute;left:-6px;right:0;z-index:4;height:0;border-top:2px solid var(--yp-status-red);pointer-events:none}
.day-timeline__now::before{content:'';position:absolute;top:-5px;left:0;width:8px;height:8px;border-radius:50%;background:var(--yp-status-red)}
.day-timeline__now span{position:absolute;top:-9px;right:0;padding:1px 5px;border-radius:var(--yp-radius-xs);background:var(--yp-status-red);color:var(--yp-text-inverse);font:600 11px/14px var(--yp-font-mono)}

.day-block{--work-item-accent:var(--day-block-color);position:absolute;z-index:2;display:flex;flex-direction:column;gap:1px;box-sizing:border-box;min-width:0;padding:4px 10px 4px 14px;overflow:hidden;border:1px solid color-mix(in srgb,var(--day-block-color) 35%,var(--yp-border-subtle));border-radius:var(--yp-radius-sm);background:color-mix(in srgb,var(--day-block-color) 10%,var(--yp-bg-surface));box-shadow:var(--yp-shadow-card);color:var(--yp-text-primary);font:14px/18px var(--yp-font-family);text-align:left;cursor:pointer;transition:box-shadow var(--yp-motion-fast) ease-out,border-color var(--yp-motion-fast) ease-out}
.day-block.work-item-accent-bar::before{top:-1px;bottom:-1px;left:-1px}
.day-block:hover{border-color:color-mix(in srgb,var(--day-block-color) 60%,var(--yp-border-subtle));box-shadow:var(--yp-shadow-hover)}
.day-block:focus-visible,.day-block.is-selected{outline:2px solid var(--yp-focus-ring);outline-offset:1px;z-index:3}
.day-block.is-compact{justify-content:center;padding-block:0}
.day-block.is-running{border-bottom-style:dashed;background:color-mix(in srgb,var(--day-block-color) 18%,var(--yp-bg-surface))}
.day-block.is-locked{cursor:default;background:repeating-linear-gradient(135deg,color-mix(in srgb,var(--day-block-color) 10%,var(--yp-bg-surface)) 0 6px,color-mix(in srgb,var(--day-block-color) 16%,var(--yp-bg-surface)) 6px 12px)}
.day-block.continues-before{border-top-style:dashed;border-top-left-radius:0;border-top-right-radius:0}
.day-block.continues-after{border-bottom-style:dashed;border-bottom-left-radius:0;border-bottom-right-radius:0}
.day-block__handle{position:absolute;left:0;right:0;z-index:1;height:6px;cursor:ns-resize}
.day-block__handle.is-start{top:-1px}
.day-block__handle.is-end{bottom:-1px}
.day-block:has(.day-block__handle){cursor:grab}
.day-block:active:has(.day-block__handle){cursor:grabbing}
.day-block.is-dragging{z-index:5;box-shadow:var(--yp-shadow-popover);transition:none}
.day-block.is-preview{z-index:3;justify-content:center;border:1.5px dashed var(--day-block-color);background:color-mix(in srgb,var(--day-block-color) 12%,var(--yp-bg-surface));box-shadow:var(--yp-shadow-hover);opacity:.92;pointer-events:none}
.day-block.is-preview.is-drop{box-shadow:var(--yp-shadow-popover)}
.day-block.is-fresh{animation:day-block-fresh 1.4s var(--yp-ease-standard)}
.day-block__head{display:flex;align-items:center;gap:8px;min-width:0}
.day-block__title{flex:1;min-width:0;overflow:hidden;font-weight:600;white-space:nowrap;text-overflow:ellipsis}
.day-block.is-compact .day-block__title{font-size:13px}
.day-block.is-full .day-block__title{display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:2;white-space:normal}
.day-block__meta{display:flex;align-items:center;gap:8px;min-width:0;color:var(--yp-text-secondary);font-size:13px;line-height:16px}
.day-block__project{flex:1;min-width:0;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
.day-block__duration{flex:none;margin-left:auto;color:var(--yp-text-primary);font:600 13px/16px var(--yp-font-mono);font-variant-numeric:tabular-nums}
.day-block__split{flex:none;align-self:flex-start;padding:0 6px;border-radius:var(--yp-radius-pill);background:color-mix(in srgb,var(--day-block-color) 28%,var(--yp-bg-surface));color:var(--yp-text-primary);font:600 12px/18px var(--yp-font-mono)}
.day-block.is-compact .day-block__split{align-self:center;line-height:16px}
@keyframes day-block-fresh{0%{box-shadow:0 0 0 0 color-mix(in srgb,var(--day-block-color) 70%,transparent);transform:scale(1.02)}100%{box-shadow:0 0 0 10px transparent;transform:none}}
@media (prefers-reduced-motion:reduce){.day-block,.day-gap-zone,.day-timeline__canvas{transition:none}.day-block.is-fresh{animation:none}}
</style>
