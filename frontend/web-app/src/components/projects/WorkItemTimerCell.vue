<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { ProjectWorkItemListItem } from '@yumpoo/api-client'
import { ElPopover } from 'element-plus'
import { formatTrackingDuration, observeTimer, useTimeTracker } from '../../composables/useTimeTracker'
import TimeSessionDialog from '../timer/TimeSessionDialog.vue'
import { timeLogPlacement, type TimeLogPlacement } from '../timer/timeLogPlacement'
import './workItemTimer.css'

const props = defineProps<{ item: ProjectWorkItemListItem; projectId: string; disabled?: boolean }>()
const emit = defineEmits<{ changed: [] }>()
async function toggle() { if (await tracker.toggle(props.item.id)) emit('changed') }
const tracker = useTimeTracker()
const open = ref(false)
const anchor = ref<HTMLElement>()
const popover = ref<InstanceType<typeof ElPopover>>()
const panelHeight = ref(400)
const placement = ref<TimeLogPlacement>('bottom')
const maxHeight = ref(400)
function place(keep?: TimeLogPlacement) {
  const rect = anchor.value?.getBoundingClientRect()
  if (!rect) return
  const next = timeLogPlacement(rect, window.innerHeight, panelHeight.value, keep)
  placement.value = next.placement
  maxHeight.value = next.maxHeight
  void nextTick(() => { void popover.value?.popperRef?.popperInstanceRef?.update() })
}
let placementFrame: number | undefined
function schedulePlace(event: Event) {
  if (event.target instanceof Element && event.target.closest('.time-log-popover')) return
  if (placementFrame !== undefined) return
  placementFrame = requestAnimationFrame(() => {
    placementFrame = undefined
    if (open.value) place()
  })
}
watch(open, (visible, _previous, onCleanup) => {
  if (!visible) return
  panelHeight.value = 400
  place()
  window.addEventListener('scroll', schedulePlace, { capture: true, passive: true })
  window.addEventListener('resize', schedulePlace)
  onCleanup(() => {
    window.removeEventListener('scroll', schedulePlace, true)
    window.removeEventListener('resize', schedulePlace)
    if (placementFrame !== undefined) cancelAnimationFrame(placementFrame)
    placementFrame = undefined
  })
})
watch(panelHeight, () => { if (open.value) place(placement.value) })
let release: (() => void) | undefined
watch(() => [props.item.id, props.projectId] as const, ([id, project]) => {
  release?.(); release = observeTimer(id, project, props.item.timeTracking)
}, { immediate: true })
onBeforeUnmount(() => release?.())
const summary = computed(() => tracker.summaries.value[props.item.id])
const running = computed(() => tracker.isRunning(props.item.id))
const unavailable = computed(() => (props.disabled || !props.item.capabilities.canEditFields) && !running.value)
const total = computed(() => {
  const entry = summary.value
  return entry ? entry.value.rollupDurationMs + (tracker.now.value - entry.at) * entry.value.rollupRunningRate : props.item.timeTracking?.rollupDurationMs ?? 0
})
const durationTitle = computed(() => {
  const value = summary.value?.value ?? props.item.timeTracking
  const rollup = value?.rollupDurationMs ?? 0, allocated = value?.allocatedDurationMs ?? 0
  return `合计 ${formatTrackingDuration(rollup)} · 本项 ${formatTrackingDuration(allocated)} · 子项 ${formatTrackingDuration(rollup - allocated)}`
})
</script>

<template>
  <el-popover
    ref="popover"
    v-model:visible="open"
    trigger="click"
    :placement="placement"
    :width="420"
    :persistent="false"
    :show-arrow="true"
    :popper-options="{ modifiers: [{ name: 'flip', enabled: false }, { name: 'preventOverflow', options: { padding: 12, altAxis: true, tether: false } }] }"
    popper-class="time-log-popover"
    :popper-style="{ '--time-log-max-height': `${maxHeight}px` }"
  >
    <template #reference>
      <div
        ref="anchor"
        class="timer-cell"
        data-tour="work-item-timer"
        role="button"
        tabindex="0"
        aria-label="查看计时日志"
        :aria-expanded="open"
        @click.stop
        @pointerdown.stop
        @dblclick.stop
        @keydown.stop
        @keydown.esc="open = false"
      >
        <button
          v-if="tracker.timerEnabled.value || running"
          type="button"
          class="timer-toggle"
          :class="{ 'is-unavailable': unavailable }"
          :disabled="tracker.busy.value || unavailable"
          :aria-label="running ? '暂停计时' : '开始计时'"
          :title="running ? '暂停本人计时' : '开始本人计时'"
          @click.stop="toggle"
          @keydown.stop
        >
          <svg
            v-if="running"
            viewBox="0 0 16 16"
            aria-hidden="true"
          ><path d="M4 3h3v10H4zM9 3h3v10H9z" /></svg>
          <svg
            v-else
            viewBox="0 0 16 16"
            aria-hidden="true"
          ><path d="M5 2.8v10.4L13 8z" /></svg>
        </button>
        <span
          class="duration"
          :title="durationTitle"
        >{{ formatTrackingDuration(total) }}</span>
      </div>
    </template>
    <TimeSessionDialog
      :work-item-id="item.id"
      :title="item.title"
      :include-children="item.subitemCount > 0"
      @close="open = false"
      @resize="panelHeight = $event"
      @changed="emit('changed')"
    />
  </el-popover>
</template>

<style scoped>
.timer-cell,.timer-toggle{cursor:pointer}
.timer-toggle:hover{background:var(--el-color-primary-dark-2)}.timer-toggle:disabled{cursor:default}.timer-toggle.is-unavailable{opacity:.45}
.timer-cell:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:-2px}
:global(.time-log-popover.el-popover){padding:0;max-width:calc(100vw - 24px);box-sizing:border-box;}
</style>
