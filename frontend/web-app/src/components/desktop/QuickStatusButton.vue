<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { TimerCandidate, WorkItemDetail } from '@yumpoo/api-client'
import { ElPopover } from 'element-plus'
import { problemMessage } from '../../api/problems'
import { useConnectedItem } from '../projects/connect/useConnectedItem'
import { workItemLabelColorValue } from '../projects/workItemLabelColors'
import { itemStatus, type QuickItemStatus } from './quickTime'

const props = defineProps<{ item: TimerCandidate }>()
const emit = defineEmits<{
  changed: [detail: WorkItemDetail, status: QuickItemStatus]
  resolved: [detail: WorkItemDetail, status: QuickItemStatus]
  failed: [message: string]
}>()
const open = ref(false)
const popover = ref<InstanceType<typeof ElPopover>>()
const connected = useConnectedItem({ changed: () => undefined })
const { detail, loading, forbidden, problem, editable, statuses } = connected

const name = computed(() => props.item.statusName ?? '查看状态')
const options = computed(() => statuses.value.filter(status => status.active || status.code === detail.value?.statusCode))
/** Statuses the workflow lets this item move to; the current one is always shown. */
const allowed = (code: string) => editable.value && Boolean(detail.value?.capabilities.availableTransitions.some(transition => transition.toStatus === code))

// Every open reads the item again, so the transition runs against its latest version and capabilities.
async function load() {
  const item = props.item
  await connected.load(item.workItemId)
  if (item === props.item && detail.value) emit('resolved', detail.value, itemStatus(detail.value, connected.labels.value))
}
watch(open, value => { if (value) void load() })
// The popover is placed around the short loading note; the status list that replaces it needs a fresh flip decision.
watch(loading, value => { if (!value && open.value) void nextTick(() => popover.value?.popperRef?.popperInstanceRef?.update()) })

async function choose(statusCode: string) {
  open.value = false
  if (!detail.value || statusCode === detail.value.statusCode) return
  if (await connected.transition(statusCode)) { emit('changed', detail.value, itemStatus(detail.value, connected.labels.value)); return }
  // A cancelled explanation prompt leaves no problem behind; anything else is reported and the item read again.
  if (!problem.value) return
  emit('failed', problemMessage(problem.value))
  void load()
}
</script>

<template>
  <el-popover
    ref="popover"
    v-model:visible="open"
    trigger="click"
    placement="bottom-start"
    :width="196"
    :offset="4"
    :show-arrow="false"
    teleported
    popper-class="quick-status-menu"
  >
    <template #reference>
      <button
        type="button"
        class="quick-status"
        :class="{ 'is-open': open, 'is-own': item.statusColorToken }"
        :style="item.statusColorToken ? { '--quick-status-color': workItemLabelColorValue(item.statusColorToken) } : undefined"
        aria-haspopup="true"
        :aria-expanded="open ? 'true' : 'false'"
        :aria-label="`状态：${name}，点击修改`"
        @click.stop
        @keydown.enter.stop
        @keydown.space.stop
      >
        {{ name }}<span
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
      <div
        class="quick-status-list"
        role="menu"
        :aria-label="`修改「${item.title}」的状态`"
      >
        <button
          v-for="status in options"
          :key="status.code"
          type="button"
          role="menuitemradio"
          class="quick-status-option"
          :class="{ 'is-current': status.code === detail.statusCode }"
          :aria-checked="status.code === detail.statusCode"
          :disabled="status.code !== detail.statusCode && !allowed(status.code)"
          :style="{ '--quick-status-color': workItemLabelColorValue(status.colorToken) }"
          @click="choose(status.code)"
        >
          <i
            class="quick-status-option__dot"
            aria-hidden="true"
          /><span class="quick-status-option__name">{{ status.displayName }}</span><svg
            v-if="status.code === detail.statusCode"
            class="quick-status-option__check"
            viewBox="0 0 12 12"
            aria-hidden="true"
          ><path
            d="M2.5 6.2 5 8.5l4.5-5"
            fill="none"
            stroke="currentColor"
            stroke-width="1.6"
            stroke-linecap="round"
            stroke-linejoin="round"
          /></svg>
        </button>
      </div>
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
.quick-status:hover,.quick-status:focus-visible,.quick-status.is-open{border-color:currentColor}
.quick-status:focus-visible{outline:0}
.quick-status__caret{width:0;overflow:hidden;font-size:9px;line-height:1;transition:width .15s}
.quick-status:hover .quick-status__caret,.quick-status:focus-visible .quick-status__caret,.quick-status.is-open .quick-status__caret{width:9px}
.quick-status-note{margin:0;padding:10px 12px;color:var(--yp-text-muted);font-size:12px;white-space:nowrap}
</style>

<style>
/* A compact menu in the quick panel's own surface; it scrolls instead of leaving a short window. */
.el-popover.el-popper.quick-status-menu{min-width:0;padding:4px}
.quick-status-menu .quick-status-list{display:grid;gap:1px;max-height:calc(50vh - 24px);overflow-y:auto;overscroll-behavior:contain}
.quick-status-option{display:flex;align-items:center;gap:8px;width:100%;height:28px;padding:0 8px;border:0;border-radius:6px;background:none;
  color:var(--yp-text-primary);font:inherit;font-size:12px;text-align:left;cursor:pointer;transition:background-color .12s}
.quick-status-option:hover:not(:disabled),.quick-status-option:focus-visible{background:var(--yp-timer-hover);outline:0}
.quick-status-option.is-current{background:color-mix(in srgb,var(--quick-status-color) 14%,transparent);font-weight:500;cursor:default}
.quick-status-option:disabled{color:var(--yp-text-disabled);cursor:not-allowed}
.quick-status-option__dot{flex:none;width:8px;height:8px;border-radius:50%;background:var(--quick-status-color)}
.quick-status-option:disabled .quick-status-option__dot{opacity:.4}
.quick-status-option__name{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.quick-status-option__check{flex:none;width:12px;height:12px;color:color-mix(in srgb,var(--quick-status-color) 75%,var(--yp-text-primary))}
.quick-status-menu .quick-status-note.is-hint{padding:6px 8px 4px}
</style>
