<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { ElMessage, ElTooltip } from 'element-plus'
import { TimeSessionFillMode } from '@yumpoo/api-client'
import '../../projects/workItemAccentBar.css'
import { workItemLabelColorValue } from '../../projects/workItemLabelColors'
import { blockEnd, clampMove, clampResize, durationLabel, FINE_SNAP_MS, formatClock, freeInterval, gaps, HOUR, MIN_BLOCK_MS, MIN_VISUAL_MS, MINUTE,
  PX_PER_MINUTE, rangeInGap, snap, snapDown, SNAP_MS, type BlockLayout, type BlockShare, type DayBlock, type Range } from './dayModel'
import { draggedItem, type ShelfItem } from './shelfDrag'
import { MIGRATED_TIME_NOTE, timeCategory } from '../../timer/timeCategories'

/** Space kept around every card, so stacked and side-by-side records read as separate cards. */
const GAP_Y = 2, GAP_X = 4
const DRAG_THRESHOLD = 6, EDGE_HIT_SIZE = 8
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
  busy?: boolean
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
  drop: [item: ShelfItem, range: Range, mode: TimeSessionFillMode.Gaps | TimeSessionFillMode.Parallel]
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
  duration: string; split: number; splitTitle: string; continuesBefore: boolean; continuesAfter: boolean; migrated: boolean
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
    block: original, range, duration, split, splitTitle, migrated: Boolean(block.migrated),
    movable: editable && startInDay && endInDay, startHandle: editable && startInDay, endHandle: editable && endInDay,
    tier: height >= 64 ? 'full' : height >= 45 ? 'medium' : 'compact',
    continuesBefore: block.start < props.bounds.start, continuesAfter: end > props.bounds.end,
    label: [`${clock(block.start)}–${block.end === null ? '计时中' : clock(end)}`, block.title, block.projectName ?? '', duration, splitTitle,
      Boolean(block.migrated) ? MIGRATED_TIME_NOTE : '']
      .filter(Boolean).join('，'),
    style: { ...box(range), left: `calc(${place.lane / place.lanes * 100}% + ${GAP_X}px)`, width: `calc(${100 / place.lanes}% - ${2 * GAP_X}px)`,
      '--day-block-color': color(block.colorToken) },
  }
}))
const hoveredId = ref<string>()
const hoveredEdge = ref<'start' | 'end'>()
const blockElements = new Map<string, HTMLElement>()
function registerBlock(id: string, element: unknown) {
  if (element instanceof HTMLElement) blockElements.set(id, element)
  else blockElements.delete(id)
}

interface BlockHit { view: BlockView; edge: 'move' | 'start' | 'end'; element: HTMLElement }
/** Include the card's vertical inset in its hit area; it is spacing, not an empty time slot. */
function hitBlock(event: MouseEvent): BlockHit | undefined {
  const direct = event.target instanceof Element ? event.target.closest<HTMLElement>('[data-session-id]') : null
  let closest: (BlockHit & { distance: number }) | undefined
  for (const view of views.value) {
    const element = blockElements.get(view.block.id)
    if (!element) continue
    const rect = element.getBoundingClientRect()
    if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top - GAP_Y || event.clientY > rect.bottom + GAP_Y) continue
    const band = Math.min(EDGE_HIT_SIZE, rect.height / 3) - GAP_Y
    const edge = event.clientY <= rect.top + band ? 'start' : event.clientY >= rect.bottom - band ? 'end' : 'move'
    const hit = { view, edge, element } as BlockHit
    if (element === direct) return hit
    const distance = Math.min(Math.abs(event.clientY - rect.top), Math.abs(event.clientY - rect.bottom))
    if (!closest || distance < closest.distance) closest = { ...hit, distance }
  }
  return closest
}
const canDrag = (hit: BlockHit) => hit.edge === 'move' ? hit.view.movable : hit.edge === 'start' ? hit.view.startHandle : hit.view.endHandle
function clearHover() { hoveredId.value = undefined; hoveredEdge.value = undefined; hover.value = null }


let suppressClick = false
function open(view: BlockView, event: MouseEvent) {
  if (suppressClick && event.detail !== 0) { suppressClick = false; return }
  suppressClick = false
  if (props.busy) return
  emit('open', view.block, event.currentTarget as HTMLElement)
}

interface Drag { block: DayBlock; edge: 'move' | 'start' | 'end'; pointerId: number; startY: number; bounds: Range; moved: boolean; range: Range
  target: HTMLElement }
const drag = ref<Drag>()
function dragStart(hit: BlockHit, event: PointerEvent) {
  if (event.pointerType === 'touch' || !canDrag(hit)) return
  const { view, edge, element: target } = hit
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
  if (!current.moved && Math.abs(dy) < DRAG_THRESHOLD) return
  if (!current.moved) { current.moved = true; emit('dragging', true) }
  const step = event.altKey ? FINE_SNAP_MS : SNAP_MS, block = current.block
  const delta = snap(dy / PX_PER_MINUTE * MINUTE, 0, step)
  current.range = delta === 0 ? { start: block.start, end: block.end! }
    : current.edge === 'move' ? clampMove(block, delta, current.bounds)
      : clampResize(block, current.edge, (current.edge === 'start' ? block.start : block.end!) + delta, current.bounds)
  drafts.value = new Map(drafts.value).set(block.id, current.range)
}
function dragEnd(event: PointerEvent, cancelled = false) {
  const current = drag.value
  if (!current || current.pointerId !== event.pointerId) return
  drag.value = undefined
  if (current.target.hasPointerCapture(event.pointerId)) current.target.releasePointerCapture(event.pointerId)
  if (!current.moved) return
  suppressClick = true
  const { range, block } = current
  if (cancelled || (range.start === block.start && range.end === block.end)) {
    discardDraft(block.id)
  } else emit('change', block, range)
  emit('dragging', false)
}
function discardDraft(id: string) {
  const next = new Map(drafts.value); next.delete(id); drafts.value = next
}

interface DropProposal { range: Range; mode: TimeSessionFillMode.Gaps | TimeSessionFillMode.Parallel; targetId?: string; problem?: string }
const dropGhost = ref<DropProposal | null>(null)
function dropProposal(event: DragEvent): DropProposal | null {
  if (props.busy || !draggedItem.value) return null
  const hit = hitBlock(event)
  if (hit) {
    // Freeze a running record's preview until the pointer chooses a different target.
    if (dropGhost.value?.targetId === hit.view.block.id) return dropGhost.value
    const range = { start: Math.max(hit.view.range.start, props.bounds.start), end: Math.min(hit.view.range.end, props.limit, props.bounds.end) }
    return { range, mode: TimeSessionFillMode.Parallel, targetId: hit.view.block.id,
      ...(range.end - range.start < MIN_BLOCK_MS ? { problem: '至少记录 15 分钟，目标时段不足 15 分钟。' } : {}) }
  }
  const range = emptyRange(event)
  return range ? { range, mode: TimeSessionFillMode.Gaps } : null
}
const dropStyle = computed(() => {
  const proposal = dropGhost.value
  if (!proposal) return {}
  const target = views.value.find(view => view.block.id === proposal.targetId)
  return target ? { ...target.style, ...box(proposal.range), '--day-block-color': color(draggedItem.value?.colorToken ?? null) }
    : ghostStyle(proposal.range, draggedItem.value?.colorToken ?? null)
})
function dragOver(event: DragEvent) {
  if (!draggedItem.value) return
  clearHover()
  const proposal = dropProposal(event)
  dropGhost.value = proposal
  if (!proposal || proposal.problem) { if (event.dataTransfer) event.dataTransfer.dropEffect = 'none'; return }
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
}
function dragLeave(event: DragEvent) {
  if (event.relatedTarget instanceof Node && canvas.value?.contains(event.relatedTarget)) return
  const rect = canvas.value!.getBoundingClientRect()
  if (event.clientX > rect.left && event.clientX < rect.right && event.clientY > rect.top && event.clientY < rect.bottom) return
  dropGhost.value = null
}
function drop(event: DragEvent) {
  const item = draggedItem.value, proposal = dropProposal(event)
  dropGhost.value = null
  if (item && proposal) {
    event.preventDefault()
    if (proposal.problem) ElMessage.warning(proposal.problem)
    else emit('drop', item, proposal.range, proposal.mode)
  }
  draggedItem.value = null
}

const free = computed(() => gaps(props.blocks, props.now, { start: props.bounds.start, end: props.limit }))
/** Empty stretches inside working hours worth a one-click shortcut. */
const workGaps = computed(() => gaps(props.blocks, props.now,
  { start: Math.max(props.workday.start, props.bounds.start), end: Math.min(props.workday.end, props.limit) })
  .filter(gap => gap.end - gap.start >= MIN_BLOCK_MS)
  .map(gap => {
    const visualStart = Math.min(gap.end, Math.max(gap.start, ...views.value
      .filter(view => view.range.start < gap.start && view.range.end <= gap.start)
      .map(view => Math.max(view.range.end, Math.max(view.range.start, props.bounds.start) + MIN_VISUAL_MS))))
    // A small shortcut is 16px tall; keep it inside the real gap even if the visible part is shorter.
    const buttonAt = Math.min((visualStart + gap.end) / 2, gap.end - 8 / PX_PER_MINUTE * MINUTE)
    return { ...gap, visualStart, buttonAt }
  }))

interface Selection { pointerId: number; anchor: number; point: number; raw: number; startY: number; moved: boolean; touch: boolean; clickRange: Range | null }
const selection = ref<Selection>()
const hover = ref<Range | null>(null)
/** Extend short drags inside their gap, shifting back from a neighbour or now when needed. */
const selected = computed(() => {
  const current = selection.value
  if (!current?.moved || current.touch) return undefined
  const start = Math.min(current.anchor, current.point)
  const end = Math.max(current.anchor, current.point)
  if (end - start >= MIN_BLOCK_MS) return { start, end }
  const gap = free.value.find(range => range.start <= current.raw && current.raw < range.end)
  return gap ? rangeInGap(Math.max(gap.start, Math.min(start, gap.end - 1)), MIN_BLOCK_MS, [gap]) ?? undefined : undefined
})
const timeAt = (event: MouseEvent) => props.bounds.start + (event.clientY - canvas.value!.getBoundingClientRect().top) / PX_PER_MINUTE * MINUTE
const clampTime = (ms: number) => Math.min(Math.max(ms, props.bounds.start), props.limit)
const snapped = (event: PointerEvent) => clampTime(snap(timeAt(event), props.bounds.start, event.altKey ? FINE_SNAP_MS : SNAP_MS))
const gapAt = (raw: number) => free.value.find(range => range.start <= raw && raw < range.end)
function emptyRange(event: MouseEvent): Range | null {
  const raw = timeAt(event), gap = gapAt(raw)
  if (!gap) return null
  const at = Math.max(gap.start, snapDown(raw, props.bounds.start, event.altKey ? FINE_SNAP_MS : SNAP_MS))
  return rangeInGap(at, HOUR, [gap])
}
const onGapButton = (event: Event) => event.target instanceof Element && !!event.target.closest('.day-gap')

function pointerdown(event: PointerEvent) {
  if (event.button !== 0 || props.busy || drag.value || selection.value || draggedItem.value || onGapButton(event)) return
  suppressClick = false
  const hit = hitBlock(event)
  if (hit) { hover.value = null; dragStart(hit, event); return }
  const raw = timeAt(event), gap = gapAt(raw)
  if (!gap) return
  const at = Math.max(gap.start, snapDown(raw, props.bounds.start, event.altKey ? FINE_SNAP_MS : SNAP_MS))
  selection.value = { pointerId: event.pointerId, anchor: at, point: at, raw, startY: event.clientY, moved: false,
    touch: event.pointerType === 'touch', clickRange: emptyRange(event) }
  // Touch keeps native scrolling; a tap still opens an hour, and the composer's time fields adjust it.
  if (event.pointerType !== 'touch') canvas.value!.setPointerCapture(event.pointerId)
}
function pointermove(event: PointerEvent) {
  if (drag.value) { hover.value = null; dragMove(event); return }
  if (props.busy || draggedItem.value) { clearHover(); return }
  const current = selection.value
  if (!current) {
    clearHover()
    if (event.pointerType !== 'mouse' || onGapButton(event)) return
    const hit = hitBlock(event)
    if (hit) {
      hoveredId.value = hit.view.block.id
      if (hit.edge !== 'move' && canDrag(hit)) hoveredEdge.value = hit.edge
    } else hover.value = emptyRange(event)
    return
  }
  if (current.pointerId !== event.pointerId) return
  if (!current.moved) {
    if (Math.abs(event.clientY - current.startY) < DRAG_THRESHOLD) return
    if (current.touch) { selection.value = undefined; return }
    current.moved = true
    emit('dragging', true)
  }
  current.point = snapped(event)
  hover.value = null
}
function pointerup(event: PointerEvent) {
  if (drag.value) { dragEnd(event); return }
  const current = selection.value, range = selected.value
  if (!current || current.pointerId !== event.pointerId) return
  selection.value = undefined
  hover.value = null
  if (canvas.value?.hasPointerCapture(event.pointerId)) canvas.value.releasePointerCapture(event.pointerId)
  if (!current.moved) {
    if (current.clickRange) emit('create', current.clickRange)
    else ElMessage.warning('至少记录 15 分钟，当前空白不足 15 分钟。')
    return
  }
  if (range && range.end - range.start >= MIN_BLOCK_MS) emit('create', range)
  else ElMessage.warning('至少记录 15 分钟，当前空白不足 15 分钟。')
  emit('dragging', false)
}

function cancelInteraction(event?: PointerEvent) {
  if (drag.value && (!event || event.pointerId === drag.value.pointerId)) dragEnd({ pointerId: drag.value.pointerId } as PointerEvent, true)
  const current = selection.value
  if (current && (!event || event.pointerId === current.pointerId)) {
    selection.value = undefined
    if (canvas.value?.hasPointerCapture(current.pointerId)) canvas.value.releasePointerCapture(current.pointerId)
    if (current.moved) emit('dragging', false)
  }
  clearHover()
  dropGhost.value = null
}
function cancelOnEscape(event: KeyboardEvent) {
  if (event.key === 'Escape') { cancelInteraction(); draggedItem.value = null }
}
function cancelOnBlur() { cancelInteraction(); draggedItem.value = null }
watch(() => props.bounds.start, () => { cancelInteraction(); draggedItem.value = null; drafts.value = new Map() })
watch(() => props.busy, busy => { if (busy) cancelInteraction() })
watch(draggedItem, item => {
  clearHover()
  if (!item) dropGhost.value = null
  emit('dragging', !!item)
}, { flush: 'sync' })

interface AxisMark extends Range { key: string; color: string; endLabel?: string | undefined }
/**
 * One hover tooltip for every card, in the project's common style: it follows the hovered card after a short pause and
 * steps aside while a card is dragged, opened or the day is busy, so it never covers what is being edited.
 */
const tipId = ref<string>()
let tipTimer: ReturnType<typeof setTimeout> | undefined
watch([hoveredId, () => !!drag.value, () => props.selectedId, () => props.busy, () => !!draggedItem.value], ([id, dragging, selectedId, busy, shelf]) => {
  clearTimeout(tipTimer)
  tipId.value = undefined
  if (!id || dragging || busy || shelf || id === selectedId) return
  tipTimer = setTimeout(() => { tipId.value = id }, 300)
})
const tip = computed(() => {
  const view = views.value.find(candidate => candidate.block.id === tipId.value)
  const anchor = tipId.value ? blockElements.get(tipId.value) : undefined
  if (!view || !anchor) return undefined
  const meta = [`${clock(view.range.start)}–${view.block.end === null ? '计时中' : clock(view.range.end)}`, view.block.projectName,
    view.duration, view.split > 1 ? `与其他 ${view.split - 1} 项并行` : '', view.migrated ? MIGRATED_TIME_NOTE : ''].filter(Boolean).join(' · ')
  return { title: view.block.title, meta, anchor }
})
/** The last anchor stays after hiding, so the tooltip fades out where it was instead of jumping. */
const tipAnchor = shallowRef<HTMLElement>()
watch(tip, value => { if (value) tipAnchor.value = value.anchor })

/** Times shown on the axis: what is being dragged or selected first, then the hovered card, the open composer, or the hover line. */
const axisMarks = computed<AxisMark[]>(() => {
  const moving = drag.value?.moved ? drag.value : undefined
  if (moving) return [{ key: 'drag', ...moving.range, color: color(moving.block.colorToken) }]
  if (selected.value) return [{ key: 'select', ...selected.value, color: PRIMARY }]
  if (dropGhost.value) return [{ key: 'drop', ...dropGhost.value.range, color: color(draggedItem.value?.colorToken ?? null) }]
  if (props.preview?.length) return props.preview.map(ghost => ({ key: `preview-${ghost.start}`, start: ghost.start, end: ghost.end, color: color(ghost.colorToken) }))
  const hovered = views.value.find(view => view.block.id === hoveredId.value)
  if (hovered) return [{ key: 'block', ...hovered.range, color: color(hovered.block.colorToken), endLabel: hovered.block.end === null ? '计时中' : undefined }]
  if (props.pending) return [{ key: 'pending', ...props.pending, color: PRIMARY }]
  if (hover.value) return [{ key: 'hover', ...hover.value, color: PRIMARY }]
  return []
})

function scrollToWorkday() {
  if (scroller.value) scroller.value.scrollTop = Math.max(0, toPx(offset(props.workday.start - 30 * MINUTE)))
}
onMounted(() => {
  scrollToWorkday()
  window.addEventListener('keydown', cancelOnEscape)
  window.addEventListener('blur', cancelOnBlur)
})
onBeforeUnmount(() => {
  clearTimeout(tipTimer)
  cancelInteraction()
  draggedItem.value = null
  window.removeEventListener('keydown', cancelOnEscape)
  window.removeEventListener('blur', cancelOnBlur)
})
watch(() => props.bounds.start, scrollToWorkday, { flush: 'post' })
defineExpose({ scrollToWorkday, pendingEl, discardDraft })
</script>

<template>
  <div
    ref="scroller"
    class="day-timeline"
    role="region"
    data-tour="day-timeline"
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
        :class="{ 'is-selecting': selected, 'is-receiving': draggedItem && !busy, 'is-edge-hover': hoveredEdge }"
        @pointerdown="pointerdown"
        @pointermove="pointermove"
        @pointerup="pointerup"
        @pointercancel="cancelInteraction"
        @lostpointercapture="cancelInteraction"
        @pointerleave="clearHover"
        @dragover="dragOver"
        @dragleave="dragLeave"
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
            :style="{ top: px(offset(gap.visualStart)), height: px(offset(gap.end) - offset(gap.visualStart)) }"
            aria-hidden="true"
          />
          <button
            v-for="gap in workGaps"
            :key="`gap-${gap.start}`"
            type="button"
            class="day-gap"
            :class="{ 'is-small': gap.end - gap.start < 40 * MINUTE }"
            :style="{ top: px(offset(gap.buttonAt)) }"
            :aria-label="`补录 ${clock(gap.start)} 到 ${clock(gap.end)} 的空白`"
            :disabled="busy"
            @click="emit('create', { start: gap.start, end: gap.end })"
          >
            {{ gap.end - gap.start >= 40 * MINUTE ? `空白 ${durationLabel(gap.end - gap.start)} · 补录` : `+ ${durationLabel(gap.end - gap.start)}` }}
          </button>
        </template>
        <button
          v-for="view in views"
          :key="view.block.id"
          :ref="element => registerBlock(view.block.id, element)"
          type="button"
          class="day-block work-item-accent-bar"
          :class="[`is-${view.tier}`, {
            'is-running': view.block.end === null,
            'is-locked': !view.block.canEdit,
            'is-migrated': view.migrated,
            'is-selected': view.block.id === selectedId,
            'is-dragging': drag?.moved && drag.block.id === view.block.id,
            'is-fresh': fresh?.has(view.block.id),
            'continues-before': view.continuesBefore,
            'continues-after': view.continuesAfter,
            'is-edge-start': (hoveredId === view.block.id && hoveredEdge === 'start') || (drag?.block.id === view.block.id && drag.edge === 'start'),
            'is-edge-end': (hoveredId === view.block.id && hoveredEdge === 'end') || (drag?.block.id === view.block.id && drag.edge === 'end'),
            'is-drop-target': dropGhost?.targetId === view.block.id,
          }]"
          :style="view.style"
          :aria-label="view.label"
          :data-session-id="view.block.id"
          :aria-disabled="busy || undefined"
          @click="open(view, $event)"
          @focus="clearHover(); hoveredId = view.block.id"
          @blur="clearHover"
        >
          <span
            v-if="view.startHandle"
            class="day-block__handle is-start"
            aria-hidden="true"
          />
          <span
            v-if="view.endHandle"
            class="day-block__handle is-end"
            aria-hidden="true"
          />
          <span class="day-block__head">
            <component
              :is="timeCategory(view.block.timeCategory)!.icon"
              v-if="view.block.timeCategory"
              class="day-block__icon"
              aria-hidden="true"
            />
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
            <span class="day-block__project">{{ view.block.projectName ?? (view.migrated ? MIGRATED_TIME_NOTE : '') }}</span>
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
          :style="dropStyle"
          aria-hidden="true"
        >
          <span class="day-block__head">
            <strong class="day-block__title">{{ dropGhost.problem || `${dropGhost.mode === TimeSessionFillMode.Parallel ? '添加并行工作项：' : ''}${draggedItem.title}` }}</strong>
            <span class="day-block__duration">{{ durationLabel(dropGhost.range.end - dropGhost.range.start) }}</span>
          </span>
        </div>
        <div
          v-if="hover && !pending && !drag && !busy && !draggedItem"
          class="day-hover"
          :style="{ top: px(offset(hover.start)) }"
          aria-hidden="true"
        >
          <span>点击补录 {{ clock(hover.start) }}–{{ clock(hover.end) }}（{{ durationLabel(hover.end - hover.start) }}），或拖选时段</span>
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
    <el-tooltip
      v-if="tipAnchor"
      :visible="!!tip"
      :virtual-ref="tipAnchor"
      virtual-triggering
      placement="top"
      :enterable="false"
      :hide-after="0"
      popper-class="day-block-tip"
    >
      <template #content>
        <span
          v-if="tip"
          class="day-block-tip__body"
        ><strong>{{ tip.title }}</strong><small>{{ tip.meta }}</small></span>
      </template>
    </el-tooltip>
  </div>
</template>

<style>
/* Teleported with the tooltip, so outside the scoped block; the bubble itself is the shared dark tooltip. */
.day-block-tip__body{display:grid;gap:2px}
.day-block-tip__body strong{font-weight:600;overflow-wrap:anywhere}
.day-block-tip__body small{font-size:inherit;opacity:.72;font-variant-numeric:tabular-nums}
</style>

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
.day-timeline__canvas.is-edge-hover{cursor:ns-resize}
.day-timeline__canvas.is-receiving{box-shadow:inset 0 0 0 2px color-mix(in srgb,var(--yp-action-primary) 40%,transparent)}
.day-gap-zone{position:absolute;left:4px;right:4px;border:1px dashed color-mix(in srgb,var(--yp-action-primary) 30%,var(--yp-border-subtle));border-radius:var(--yp-radius-sm);background:color-mix(in srgb,var(--yp-action-primary) 3%,transparent);pointer-events:none;transition:border-color var(--yp-motion-fast) ease-out,background var(--yp-motion-fast) ease-out}
.day-timeline__canvas.is-receiving .day-gap-zone{border-color:color-mix(in srgb,var(--yp-action-primary) 65%,var(--yp-border-subtle));background:color-mix(in srgb,var(--yp-action-primary) 8%,transparent)}
.day-gap{position:absolute;left:50%;z-index:3;transform:translate(-50%,-50%);padding:3px 10px;border:1px solid color-mix(in srgb,var(--yp-action-primary) 35%,var(--yp-border-subtle));border-radius:999px;background:var(--yp-bg-surface);color:var(--yp-action-primary);font:500 12px/16px var(--yp-font-family);white-space:nowrap;cursor:pointer;opacity:.8;transition:opacity var(--yp-motion-fast) ease-out,box-shadow var(--yp-motion-fast) ease-out}
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
.day-block.is-migrated{border-style:dashed;background:repeating-linear-gradient(135deg,color-mix(in srgb,var(--day-block-color) 6%,var(--yp-bg-surface)) 0 5px,color-mix(in srgb,var(--day-block-color) 22%,var(--yp-bg-surface)) 5px 7px)}
.day-block.continues-before{border-top-style:dashed;border-top-left-radius:0;border-top-right-radius:0}
.day-block.continues-after{border-bottom-style:dashed;border-bottom-left-radius:0;border-bottom-right-radius:0}
.day-block__handle{position:absolute;left:0;right:0;z-index:1;height:min(8px,33.333%);pointer-events:none}
.day-block__handle.is-start{top:0}
.day-block__handle.is-end{bottom:0}
.day-block:has(.day-block__handle){cursor:grab}
.day-block:active:has(.day-block__handle):not(.is-edge-start,.is-edge-end){cursor:grabbing}
.day-block.is-edge-start{cursor:ns-resize;border-color:var(--day-block-color);box-shadow:inset 0 3px 0 var(--day-block-color),var(--yp-shadow-hover)}
.day-block.is-edge-end{cursor:ns-resize;border-color:var(--day-block-color);box-shadow:inset 0 -3px 0 var(--day-block-color),var(--yp-shadow-hover)}
.day-block.is-drop-target{border-color:var(--yp-action-primary)}
.day-block[aria-disabled="true"]{cursor:default}
.day-block.is-dragging{z-index:5;box-shadow:var(--yp-shadow-popover);transition:none}
.day-block.is-preview{z-index:3;justify-content:center;border:1.5px dashed var(--day-block-color);background:color-mix(in srgb,var(--day-block-color) 12%,var(--yp-bg-surface));box-shadow:var(--yp-shadow-hover);opacity:.92;pointer-events:none}
.day-block.is-preview.is-drop{box-shadow:var(--yp-shadow-popover)}
.day-block.is-fresh{animation:day-block-fresh 1.4s var(--yp-ease-standard)}
.day-block__head{display:flex;align-items:center;gap:8px;min-width:0}
.day-block__icon{flex:none;width:13px;height:13px;color:var(--day-block-color)}
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
