<script setup lang="ts">
import { ElCheckbox, ElIcon, ElInput, ElPopover } from 'element-plus'
import { User } from '@element-plus/icons-vue'
import YpAssignee from '../yp/YpAssignee.vue'
import type { useWorkItemFilters } from './useWorkItemFilters'

const props = defineProps<{ filter: ReturnType<typeof useWorkItemFilters> }>()
const { assigneeFilterOpen, assigneeSearch, filteredMembers, filters, toggleSet, countBy, loadFilterOptions } = props.filter
</script>

<template>
  <el-popover v-model:visible="assigneeFilterOpen" placement="bottom-start" :width="360" trigger="click" popper-class="work-items-popover work-item-view-control" @show="loadFilterOptions">
    <template #reference>
      <button class="toolbar-button" :class="{ active: filters.assignees.size }">
        <el-icon><user /></el-icon><span>处理人</span>
        <span v-if="filters.assignees.size" class="toolbar-count">{{ filters.assignees.size }}</span>
      </button>
    </template>
    <div class="popover-stack">
      <el-input v-model="assigneeSearch" clearable autofocus placeholder="搜索项目成员" />
      <button
        v-for="member in filteredMembers"
        :key="member.userId"
        class="popover-option"
        @click="toggleSet(filters.assignees, member.userId, !filters.assignees.has(member.userId))"
      >
        <el-checkbox :model-value="filters.assignees.has(member.userId)" @click.stop />
        <yp-assignee :user-id="member.userId" :display-name="member.displayName" />
        <span class="option-count">{{ countBy('assigneeUserId', member.userId) }}</span>
      </button>
    </div>
  </el-popover>
</template>

<style scoped>
.toolbar-button {
  display: inline-flex;
  height: 36px;
  flex: 0 0 auto;
  align-items: center;
  gap: 7px;
  padding: 0 var(--yp-space-3);
  border: 0;
  border-radius: var(--yp-radius-sm);
  color: var(--yp-text-primary);
  background: transparent;
  font: inherit;
  cursor: pointer;
  transition: background var(--yp-motion-fast) var(--yp-ease-standard), color var(--yp-motion-fast) var(--yp-ease-standard);
}
.toolbar-button:hover:not(:disabled), .toolbar-button.active { background: var(--yp-bg-hover); color: var(--yp-action-primary); }
.toolbar-button:disabled { color: var(--yp-text-disabled); cursor: not-allowed; }
.toolbar-count { min-width: 18px; padding: 1px 5px; border-radius: var(--yp-radius-pill); color: var(--yp-priority-foreground); background: var(--yp-action-primary); font-size: 11px; }
.popover-stack { display: grid; gap: var(--yp-space-3); }
.popover-option {
  display: flex; align-items: center; gap: var(--yp-space-2); width: 100%; padding: 7px 8px; border: 0;
  border-radius: var(--yp-radius-sm); color: var(--yp-text-primary); background: transparent; text-align: left; cursor: pointer;
}
.popover-option:hover { background: var(--yp-bg-hover); }
.option-count { margin-left: auto; color: var(--yp-text-muted); }
</style>
