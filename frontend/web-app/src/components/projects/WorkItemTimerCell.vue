<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { ProjectWorkItemListItem } from '@yumpoo/api-client'
import { formatDuration, observeTimer, useTimeTracker } from '../../composables/useTimeTracker'
import TimeSessionDialog from '../timer/TimeSessionDialog.vue'

const props = defineProps<{ item: ProjectWorkItemListItem; projectId: string; disabled?: boolean }>()
const tracker = useTimeTracker()
const open = ref(false)
let release: (() => void) | undefined
watch(() => [props.item.id, props.projectId] as const, ([id, project]) => {
  release?.(); release = observeTimer(id, project, props.item.timeTracking)
}, { immediate: true })
onBeforeUnmount(() => release?.())
const summary = computed(() => tracker.summaries.value[props.item.id])
const running = computed(() => tracker.current.value?.session?.workItemId === props.item.id)
const total = computed(() => {
  const entry = summary.value
  return entry ? entry.value.totalDurationMs + Math.max(0, tracker.now.value - entry.at) * entry.value.runningSessions.length : props.item.timeTracking?.totalDurationMs ?? 0
})
</script>

<template>
  <div class="timer-cell" @click.stop @pointerdown.stop @dblclick.stop @keydown.stop>
    <button type="button" :disabled="tracker.busy.value || ((disabled || !item.capabilities.canEditFields) && !running)" :class="{ running }"
      :aria-label="running ? '停止计时' : '开始计时'" :title="running ? '停止本人计时' : '开始本人计时'" @click="tracker.toggle(item.id)">
      {{ running ? '■' : '▶' }} <span>{{ running ? '计时中' : '开始' }}</span>
    </button>
    <button type="button" class="duration" aria-label="查看计时明细" @click="open = true">{{ summary || item.timeTracking ? formatDuration(total) : '—' }}</button>
    <TimeSessionDialog v-if="open" :work-item-id="item.id" :title="item.title" @close="open = false" />
  </div>
</template>

<style scoped>
.timer-cell{display:flex;align-items:center;gap:8px;font-variant-numeric:tabular-nums;white-space:nowrap}
button{border:0;background:transparent;color:inherit;cursor:pointer;padding:4px;font:inherit;font-size:12px;border-radius:5px}
button:hover{background:var(--el-fill-color-light)}button:disabled{opacity:.45;cursor:default}
.running{color:var(--el-color-success);background:var(--el-color-success-light-9)}.duration{text-decoration:underline;text-underline-offset:3px}
</style>
