<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { TimerCandidate, WorkItemDetail } from '@yumpoo/api-client'
import { ElPopover } from 'element-plus'
import { problemMessage } from '../../api/problems'
import WorkItemLabelPopoverContent from '../projects/WorkItemLabelPopoverContent.vue'
import { useConnectedItem } from '../projects/connect/useConnectedItem'
import { workItemLabelColorValue } from '../projects/workItemLabelColors'
import { STATUS_PILLS } from './quickTime'

const props = defineProps<{ item: TimerCandidate }>()
const emit = defineEmits<{ changed: [detail: WorkItemDetail, statusName: string]; failed: [message: string] }>()
const open = ref(false)
const popover = ref<InstanceType<typeof ElPopover>>()
const connected = useConnectedItem({ changed: () => undefined })
const { detail, loading, forbidden, problem, editable, statuses } = connected

const pill = computed(() => STATUS_PILLS[props.item.statusCategory] ?? { label: props.item.statusCategory, tone: 'neutral' as const })
/** The project's own status once loaded; a loaded status of another category is stale and falls back to the category until the next load. */
const own = computed(() => {
  const status = detail.value && statuses.value.find(value => value.code === detail.value!.statusCode)
  return status && status.statusCategory === props.item.statusCategory ? status : undefined
})
const options = computed(() => statuses.value.filter(status => status.active || status.code === detail.value?.statusCode))

// Every open reads the item again, so the transition runs against its latest version and capabilities.
watch(open, value => { if (value) void connected.load(props.item.workItemId) })
// The popover is placed around the short loading note; the status list that replaces it needs a fresh flip decision.
watch(loading, value => { if (!value && open.value) void nextTick(() => popover.value?.popperRef?.popperInstanceRef?.update()) })

async function choose(statusCode: string) {
  open.value = false
  if (!detail.value || statusCode === detail.value.statusCode) return
  const name = statuses.value.find(status => status.code === statusCode)?.displayName ?? statusCode
  if (await connected.transition(statusCode)) { emit('changed', detail.value, name); return }
  // A cancelled explanation prompt leaves no problem behind; anything else is reported and the item read again.
  if (!problem.value) return
  emit('failed', problemMessage(problem.value))
  void connected.load(props.item.workItemId)
}
</script>

<template>
  <el-popover
    ref="popover"
    v-model:visible="open"
    trigger="click"
    placement="bottom-start"
    width="auto"
    teleported
    popper-class="work-items-label-popover status-popover quick-status-popover"
  >
    <template #reference>
      <button
        type="button"
        class="quick-status"
        :class="{ 'is-open': open, 'is-own': own }"
        :data-tone="own ? undefined : pill.tone"
        :style="own ? { '--quick-status-color': workItemLabelColorValue(own.colorToken) } : undefined"
        aria-haspopup="true"
        :aria-expanded="open ? 'true' : 'false'"
        :aria-label="`状态：${own?.displayName ?? pill.label}，点击修改`"
        @click.stop
        @keydown.enter.stop
        @keydown.space.stop
      >
        {{ own?.displayName ?? pill.label }}<span
          class="quick-status__caret"
          aria-hidden="true"
        >▾</span>
      </button>
    </template>
    <p
      v-if="loading"
      class="quick-status-note"
    >
      正在读取…
    </p>
    <p
      v-else-if="forbidden"
      class="quick-status-note"
    >
      无法读取这个工作项
    </p>
    <p
      v-else-if="!detail"
      class="quick-status-note"
      role="alert"
    >
      {{ problem ? problemMessage(problem) : '正在读取…' }}
    </p>
    <template v-else>
      <WorkItemLabelPopoverContent
        kind="status"
        :project-id="detail.projectId"
        :catalog="connected.labels.value"
        :workflow-statuses="options"
        :current-value="detail.statusCode"
        :can-manage="false"
        :available-transitions="editable ? detail.capabilities.availableTransitions : []"
        @select-status="choose"
      />
      <p
        v-if="!editable"
        class="quick-status-note is-hint"
      >
        你没有修改权限
      </p>
    </template>
  </el-popover>
</template>

<style scoped>
.quick-status{display:inline-flex;align-items:center;gap:2px;padding:1px 6px;border:1px solid transparent;border-radius:5px;font:inherit;font-size:11px;cursor:pointer;
  background:var(--yp-timer-sunken);color:var(--yp-text-secondary);transition:opacity .2s,border-color .15s}
.quick-status.is-own{background:color-mix(in srgb,var(--quick-status-color) 16%,transparent);color:color-mix(in srgb,var(--quick-status-color) 75%,var(--yp-text-primary))}
.quick-status[data-tone="accent"]{background:color-mix(in srgb,var(--yp-status-blue) 14%,transparent);color:var(--yp-status-blue)}
.quick-status[data-tone="warning"]{background:color-mix(in srgb,var(--yp-status-orange) 14%,transparent);color:color-mix(in srgb,var(--yp-status-orange) 75%,var(--yp-text-primary))}
.quick-status[data-tone="success"]{background:color-mix(in srgb,var(--yp-status-green) 16%,transparent);color:color-mix(in srgb,var(--yp-status-green) 70%,var(--yp-text-primary))}
.quick-status:hover,.quick-status:focus-visible,.quick-status.is-open{border-color:currentColor}
.quick-status:focus-visible{outline:0}
.quick-status__caret{width:0;overflow:hidden;font-size:9px;line-height:1;transition:width .15s}
.quick-status:hover .quick-status__caret,.quick-status:focus-visible .quick-status__caret,.quick-status.is-open .quick-status__caret{width:9px}
.quick-status-note{margin:0;padding:10px 12px;color:var(--yp-text-muted);font-size:12px;white-space:nowrap}
.quick-status-note.is-hint{padding:0 12px 10px}
</style>

<style>
/* The quick panel can be shorter than the status list; it scrolls instead of leaving the window. */
.el-popover.quick-status-popover .yp-label-popover-root--select{max-height:calc(50vh - 24px);overflow-y:auto;overscroll-behavior:contain}
</style>
