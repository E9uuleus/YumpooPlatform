<script setup lang="ts">
import { ElDropdown, ElDropdownItem, ElDropdownMenu, ElPopover } from 'element-plus'
import type { WorkItemBatchAction } from './useWorkItemBatchActions'
import WorkItemActionIcon from './WorkItemActionIcon.vue'

defineProps<{
  count: number
  subitemCount: number
  busy: boolean
  stopping: boolean
  progressLabel: string
  canCreate: boolean
  canDelete: boolean
  canConvert: boolean
  moveDisabled: boolean
  choosingParent: boolean
  embedded: boolean
  left: number
}>()
const emit = defineEmits<{
  action: [action: WorkItemBatchAction]
  chooseParent: [visible: boolean]
  clear: []
  stop: []
}>()
</script>

<template>
  <div class="work-item-batch-bar-host" :class="{ 'work-item-batch-bar-host--embedded': embedded }"
    :style="embedded ? undefined : { left: `${left}px` }">
    <div class="work-item-batch-bar" role="toolbar" aria-label="勾选工作项批量操作" :aria-busy="busy">
      <div class="work-item-batch-count" aria-live="polite" aria-atomic="true">
        <strong>{{ busy ? progressLabel || '正在准备操作…' : `已选择 ${count} 项` }}</strong>
        <span>含 {{ subitemCount }} 个子工作项</span>
      </div>
      <div class="work-item-batch-actions">
        <button type="button" :disabled="busy || !canCreate" @click="emit('action', 'duplicate')"><work-item-action-icon name="duplicate" />复制</button>
        <button type="button" :disabled="busy || !canDelete" @click="emit('action', 'archive')"><work-item-action-icon name="archive" />归档</button>
        <button type="button" :disabled="busy || !canDelete" @click="emit('action', 'delete')"><work-item-action-icon name="delete" />删除</button>
        <el-popover :visible="choosingParent" :persistent="false" :disabled="busy || !canConvert" trigger="click"
          placement="top" :width="340" popper-class="work-item-parent-popover" @update:visible="emit('chooseParent', $event)">
          <template #reference><button type="button" :disabled="busy || !canConvert" aria-haspopup="dialog" :aria-expanded="choosingParent"><work-item-action-icon name="convert" />转为子工作项</button></template>
          <slot name="parent-picker" />
        </el-popover>
        <el-dropdown trigger="click" placement="top" :disabled="busy || moveDisabled" @command="emit('action', $event)">
          <button type="button" :disabled="busy || moveDisabled" :title="moveDisabled ? '请清除排序并选择有移动权限的工作项' : undefined" aria-haspopup="menu"><work-item-action-icon name="move" />移动 ▾</button>
          <template #dropdown><el-dropdown-menu>
            <el-dropdown-item command="top"><work-item-action-icon name="top" />置顶</el-dropdown-item>
            <el-dropdown-item command="bottom"><work-item-action-icon name="bottom" />置底</el-dropdown-item>
          </el-dropdown-menu></template>
        </el-dropdown>
      </div>
      <button v-if="busy" type="button" class="work-item-batch-close" :disabled="stopping" @click="emit('stop')">{{ stopping ? '正在停止…' : '停止' }}</button>
      <button v-else type="button" class="work-item-batch-close" aria-label="清空勾选" @click="emit('clear')">✕</button>
    </div>
  </div>
</template>

<style scoped>
.work-item-batch-bar-host { position: fixed; right: var(--yp-work-items-drawer-inset, 0px); bottom: 28px; z-index: 1800; display: flex; justify-content: center; padding: 0 12px; pointer-events: none; }
.work-item-batch-bar-host--embedded { position: sticky; bottom: 20px; left: auto; right: auto; z-index: 5; flex: 0 0 0; height: 0; overflow: visible; align-items: flex-end; }
.work-item-batch-bar { display: flex; align-items: center; gap: 14px; box-sizing: border-box; max-width: 100%; padding: 10px 12px; border: 1px solid var(--yp-border-default); border-radius: var(--yp-radius-md); background: var(--yp-bg-surface); color: var(--yp-text-primary); box-shadow: var(--yp-shadow-popover); pointer-events: auto; }
.work-item-batch-count { display: grid; flex: 0 0 auto; gap: 2px; font-size: 13px; }
.work-item-batch-count span { color: var(--yp-text-secondary); font-size: 12px; }
.work-item-batch-actions { display: flex; flex-wrap: wrap; gap: 4px; }
.work-item-batch-bar button { display: inline-flex; align-items: center; justify-content: center; gap: 5px; min-height: 34px; padding: 0 8px; border: 0; border-radius: var(--yp-radius-sm); background: transparent; color: inherit; font: inherit; font-size: 13px; white-space: nowrap; cursor: pointer; }
.work-item-batch-bar button:hover:not(:disabled) { background: var(--yp-bg-hover); }
.work-item-batch-bar button:focus-visible { outline: 2px solid var(--yp-focus-ring); outline-offset: -2px; }
.work-item-batch-bar button:disabled { color: var(--yp-text-muted); cursor: not-allowed; }
.work-item-batch-close { flex: 0 0 auto; margin-left: auto; }
@media (max-width: 720px) {
  .work-item-batch-bar { flex-wrap: wrap; gap: 6px; }
  .work-item-batch-actions { order: 3; width: 100%; }
}
</style>
