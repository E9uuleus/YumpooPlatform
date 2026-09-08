<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { ProjectWorkItemListItem } from '@yumpoo/api-client'
import { ElPopover } from 'element-plus'
import { formatTrackingDuration, observeTimer, useTimeTracker } from '../../composables/useTimeTracker'
import TimeSessionDialog from '../timer/TimeSessionDialog.vue'

const props = defineProps<{ item: ProjectWorkItemListItem; projectId: string; disabled?: boolean }>()
const tracker = useTimeTracker()
const open = ref(false)
const anchor = ref<HTMLElement>()
const panelHeight = ref(400)
const reservePanelSpace = ref(false)
watch([open, panelHeight], async ([visible]) => {
  if (!visible) return
  reservePanelSpace.value = true
  await nextTick()
  const cell = anchor.value
  if (cell && cell.getBoundingClientRect().bottom + panelHeight.value + 26 > window.innerHeight) {
    cell.scrollIntoView({ block: 'center', inline: 'nearest' })
  }
})
function afterLeave() {
  if (open.value) return
  reservePanelSpace.value = false
  panelHeight.value = 400
}
let release: (() => void) | undefined
watch(() => [props.item.id, props.projectId] as const, ([id, project]) => {
  release?.(); release = observeTimer(id, project, props.item.timeTracking)
}, { immediate: true })
onBeforeUnmount(() => release?.())
const summary = computed(() => tracker.summaries.value[props.item.id])
const running = computed(() => tracker.current.value?.session?.workItemId === props.item.id)
const unavailable = computed(() => (props.disabled || !props.item.capabilities.canEditFields) && !running.value)
const total = computed(() => {
  const entry = summary.value
  return entry ? entry.value.totalDurationMs + Math.max(0, tracker.now.value - entry.at) * entry.value.runningSessions.length : props.item.timeTracking?.totalDurationMs ?? 0
})
</script>

<template>
  <el-popover
    v-model:visible="open"
    trigger="click"
    placement="bottom"
    :width="420"
    :persistent="false"
    :show-arrow="true"
    :popper-options="{ modifiers: [{ name: 'flip', enabled: false }, { name: 'preventOverflow', options: { padding: 12 } }] }"
    popper-class="time-log-popover"
    @after-leave="afterLeave"
  >
    <template #reference>
      <div
        ref="anchor"
        class="timer-cell"
        role="button"
        tabindex="0"
        aria-label="查看计时日志"
        :aria-expanded="open"
        :data-log-height="panelHeight"
        :data-log-space="reservePanelSpace"
        @click.stop
        @pointerdown.stop
        @dblclick.stop
        @keydown.stop
        @keydown.esc="open = false"
      >
        <button
          type="button"
          class="timer-toggle"
          :class="{ 'is-unavailable': unavailable }"
          :disabled="tracker.busy.value || unavailable"
          :aria-label="running ? '暂停计时' : '开始计时'"
          :title="running ? '暂停本人计时' : '开始本人计时'"
          @click.stop="tracker.toggle(item.id)"
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
        <span class="duration">{{ formatTrackingDuration(total) }}</span>
      </div>
    </template>
    <TimeSessionDialog
      :work-item-id="item.id"
      :title="item.title"
      @close="open = false"
      @resize="panelHeight = $event"
    />
  </el-popover>
</template>

<style scoped>
.timer-cell{position:relative;display:flex;align-items:center;justify-content:center;width:100%;height:var(--work-item-table-row-height,40px);padding:0 0 0 34px;box-sizing:border-box;font-variant-numeric:tabular-nums;white-space:nowrap;cursor:pointer}
.timer-toggle{position:absolute;left:8px;display:grid;place-items:center;width:20px;height:20px;border:0;border-radius:5px;background:var(--el-color-primary);color:var(--yp-text-inverse);cursor:pointer;padding:3px}
.timer-toggle:hover{background:var(--el-color-primary-dark-2)}.timer-toggle:disabled{cursor:default}.timer-toggle.is-unavailable{opacity:.45}
.timer-toggle svg{width:14px;height:14px;fill:currentColor}.duration{font-size:13px;text-align:center}
.timer-cell:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:-2px}
:global(.time-log-popover.el-popover){padding:0;max-width:calc(100vw - 24px);box-sizing:border-box;border-radius:10px}
</style>
