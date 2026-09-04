<script setup lang="ts">
import { ElButton, ElDatePicker, ElPopover, ElTimePicker, ElTooltip } from 'element-plus'
import { computed, ref } from 'vue'
import { useSession } from '../../composables/useSession'
import { companyDate, dueDateKey, presentDueDate, type DueDateValue, type WorkItemDueDateFacts } from './workItemDueDate'
import { useWorkItemDueClock } from './useWorkItemDueClock'

const props = defineProps<{ item: WorkItemDueDateFacts; canEdit: boolean; busy?: boolean }>()
const emit = defineEmits<{ select: []; change: [value: DueDateValue] }>()
const session = useSession()
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'Asia/Shanghai')
const now = useWorkItemDueClock()
const presentation = computed(() => presentDueDate(props.item, timezone.value, now.value))
const date = computed(() => dueDateKey(props.item.dueDate))
const disabled = computed(() => !props.canEdit || Boolean(props.busy))
const visible = ref(false)
const timeOpen = ref(false)
const draftTime = ref<string | null>(null)

function resetEditor(): void {
  timeOpen.value = Boolean(props.item.dueTime)
  draftTime.value = props.item.dueTime ?? null
}

function commitDate(value: string | null): void {
  if (disabled.value || !value || value === date.value) return
  emit('change', { dueDate: value, dueTime: props.item.dueTime ?? null })
}

function commitTime(value: string | null | undefined): void {
  if (disabled.value || !date.value) return
  const time = value || null
  if (time !== null && !/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(time)) return
  if (time === (props.item.dueTime ?? null)) return
  emit('change', { dueDate: date.value, dueTime: time })
}

function clearDate(): void {
  if (disabled.value || !date.value) return
  visible.value = false
  emit('change', { dueDate: null, dueTime: null })
}
</script>

<template>
  <span class="work-item-due-date" @pointerdown.stop @click.stop @keydown.stop>
    <el-popover
      v-model:visible="visible" placement="bottom" :width="300" trigger="click"
      :disabled="disabled" popper-class="work-items-popover work-item-deadline-popover"
      @show="resetEditor" @hide="resetEditor"
    >
      <template #reference>
        <button
          type="button" class="monday-due-date-cell cell-editor-trigger" :disabled="disabled"
          :aria-description="presentation.tooltip" aria-label="编辑截止日期" @click="emit('select')"
        >
          <el-tooltip v-if="presentation.tone !== 'none'" :content="presentation.tooltip" placement="top" :show-after="200">
            <span class="deadline-indicator" :class="`deadline-indicator--${presentation.tone}`">
              <svg v-if="presentation.tone === 'today'" width="15" height="15" viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="3.5" fill="currentColor" /></svg>
              <svg v-else width="13" height="13" viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" stroke-width="1.2" /><path d="M8 4.2V8.5M8 11.2V11.8" class="deadline-exclamation" stroke-width="1.2" stroke-linecap="round" /></svg>
            </span>
          </el-tooltip>
          <span class="deadline-text" :class="{ 'deadline-text--late': presentation.tone === 'red', 'deadline-text--done': presentation.strike }">{{ presentation.text }}</span>
        </button>
      </template>
      <div class="deadline-editor" @pointerdown.stop @click.stop @keydown.stop>
        <div class="deadline-editor__toolbar">
          <el-button :disabled="disabled" @click="commitDate(companyDate(now, timezone))">Today</el-button>
          <button type="button" class="deadline-clock" :class="{ 'is-active': timeOpen }" :disabled="disabled || !date" :aria-expanded="timeOpen" aria-label="设置截止时间" :title="date ? '设置截止时间' : '请先选择截止日期'" @click="timeOpen = !timeOpen">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true"><circle cx="12" cy="12" r="8.5" /><path d="M12 6.5V12l3.5 2" /></svg>
          </button>
        </div>
        <!-- 内层面板留在编辑弹窗内，避免点击选项被判定为外部点击。 -->
        <div class="deadline-editor__fields">
          <el-date-picker class="deadline-editor__date" :model-value="date" :disabled="disabled" :clearable="false" :teleported="false" type="date" value-format="YYYY-MM-DD" placeholder="选择截止日期" @update:model-value="commitDate($event as string | null)" />
          <span v-if="timeOpen" class="deadline-editor__divider" aria-hidden="true" />
          <el-time-picker v-if="timeOpen" v-model="draftTime" class="deadline-editor__time" :disabled="disabled || !date" :save-on-blur="false" :teleported="false" format="HH:mm" value-format="HH:mm" aria-label="截止时间" placeholder="00:00" @change="commitTime($event as string | null)" />
        </div>
      </div>
    </el-popover>
    <button v-if="date && canEdit" type="button" class="deadline-clear" :disabled="busy" aria-label="清空截止日期" title="清空截止日期" @click.stop="clearDate">
      <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="m4 4 8 8M12 4l-8 8" /></svg>
    </button>
  </span>
</template>

<style scoped>
.work-item-due-date { position: relative; display: flex; align-items: stretch; width: 100%; height: 100%; min-width: 0; }
.monday-due-date-cell { position: relative; display: flex; align-items: center; justify-content: center; width: 100%; min-width: 0; min-height: var(--deadline-cell-height, var(--work-item-table-row-height, 36px)); padding: 0 24px; border: 0; background: transparent; color: var(--yp-text-primary); font: inherit; font-size: 13px; cursor: pointer; }
.monday-due-date-cell:disabled { cursor: default; }
.monday-due-date-cell:focus-visible, .deadline-clear:focus-visible, .deadline-clock:focus-visible { outline: 2px solid var(--yp-action-primary); outline-offset: -2px; }
.deadline-indicator { position: absolute; top: 50%; left: 3px; transform: translateY(-50%); display: inline-flex; }
.deadline-indicator--red, .deadline-text--late { color: var(--yp-status-red); }
.deadline-indicator--green { color: var(--yp-status-green); }
.deadline-indicator--today { color: var(--yp-text-muted); }
.deadline-exclamation { stroke: currentColor; }
.deadline-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.deadline-text--done { text-decoration: line-through; }
.deadline-text--late { font-weight: 500; }
.deadline-clear { position: absolute; top: 50%; right: 3px; transform: translateY(-50%); display: inline-flex; align-items: center; justify-content: center; width: 17px; height: 20px; padding: 0; border: 0; border-radius: 3px; background: transparent; color: var(--yp-text-muted); opacity: 0; cursor: pointer; }
.work-item-due-date:hover .deadline-clear, .work-item-due-date:focus-within .deadline-clear { opacity: 1; }
.deadline-clear:hover, .deadline-clock:hover:not(:disabled) { color: var(--yp-text-primary); background: var(--yp-bg-hover); }
.deadline-clear:disabled { cursor: default; }
.deadline-clear svg, .deadline-clock svg { stroke: currentColor; stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; }
.deadline-editor { display: grid; gap: 10px; }
.deadline-editor__toolbar { display: flex; justify-content: space-between; align-items: center; }
.deadline-clock { display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px; padding: 0; border: 0; border-radius: 4px; color: var(--yp-text-secondary); background: transparent; cursor: pointer; }
.deadline-clock.is-active { color: var(--yp-action-primary); background: var(--yp-bg-hover); }
.deadline-clock:disabled { opacity: .45; cursor: default; }
.deadline-editor__fields { display: flex; align-items: center; min-width: 0; border-radius: var(--el-border-radius-base); background: var(--el-fill-color-blank); box-shadow: 0 0 0 1px var(--el-border-color) inset; }
.deadline-editor__fields:hover { box-shadow: 0 0 0 1px var(--el-border-color-hover) inset; }
.deadline-editor__fields:focus-within { box-shadow: 0 0 0 1px var(--el-color-primary) inset; }
.deadline-editor__fields :deep(.el-date-editor) { width: 0; min-width: 0; }
.deadline-editor__fields :deep(.deadline-editor__date) { flex: 1; }
.deadline-editor__fields :deep(.deadline-editor__time) { flex: 0 0 104px; }
.deadline-editor__divider { flex: 0 0 1px; height: 18px; background: var(--el-border-color); }
.deadline-editor__fields :deep(.el-input__wrapper) { min-width: 0; padding: 1px 10px; background: transparent; box-shadow: none; outline: none; }
.deadline-editor__fields :deep(.el-input__prefix) { display: none; }
</style>
