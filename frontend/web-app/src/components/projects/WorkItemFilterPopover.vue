<script setup lang="ts">
import { ElDatePicker, ElIcon, ElPopover } from 'element-plus'
import { Filter as FilterIcon } from '@element-plus/icons-vue'
import type { ProjectContentCatalog, ProjectMember, WorkItemPriorityLabel, WorkItemStatusLabel } from '@yumpoo/api-client'
import YpAssignee from '../yp/YpAssignee.vue'
import { workItemLabelColorValue } from './workItemLabelColors'
import type { useWorkItemFilters } from './useWorkItemFilters'

const props = defineProps<{
  filter: ReturnType<typeof useWorkItemFilters>
  activeMembers: ProjectMember[]
  workflowStatuses: (WorkItemStatusLabel & { statusCode: string })[]
  priorityOptions: WorkItemPriorityLabel[]
  catalog: ProjectContentCatalog | undefined
}>()
const { filterPopoverOpen, activeFilterCount, filters, toggleSet, countBy, loadFilterOptions, clearFilters, syncUrl } = props.filter
</script>

<template>
  <el-popover v-model:visible="filterPopoverOpen" placement="bottom-start" :width="760" trigger="click" popper-class="work-items-popover work-items-filter-popover" @show="loadFilterOptions">
    <template #reference>
      <button class="toolbar-button" :class="{ active: activeFilterCount }">
        <el-icon><filter-icon /></el-icon><span>筛选</span><span v-if="activeFilterCount" class="toolbar-button__count">{{ activeFilterCount }}</span>
      </button>
    </template>
    <div class="filter-popover">
      <header class="filter-popover__header">
        <strong>快速筛选</strong>
        <span v-if="activeFilterCount" class="filter-popover__count" :aria-label="`已选 ${activeFilterCount} 个条件`">{{ activeFilterCount }}</span>
        <button class="text-button filter-popover__clear" :disabled="!activeFilterCount" @click="clearFilters">清除全部</button>
      </header>
      <div class="filter-columns">
        <section class="filter-section">
          <h4>处理人<small v-if="filters.assignees.size">已选 {{ filters.assignees.size }}</small></h4>
          <div class="filter-section__values">
            <button v-for="member in activeMembers" :key="member.userId" class="filter-value" :aria-pressed="filters.assignees.has(member.userId)" @click="toggleSet(filters.assignees, member.userId, !filters.assignees.has(member.userId))">
              <yp-assignee class="filter-value__label" :user-id="member.userId" :display-name="member.displayName" />
              <small :class="{ 'filter-value__count--zero': !countBy('assigneeUserId', member.userId) }">{{ countBy('assigneeUserId', member.userId) }}</small>
            </button>
          </div>
        </section>
        <section class="filter-section">
          <h4>状态<small v-if="filters.statuses.size">已选 {{ filters.statuses.size }}</small></h4>
          <div class="filter-section__values">
            <button v-for="status in workflowStatuses" :key="status.statusCode" class="filter-value" :aria-pressed="filters.statuses.has(status.statusCode)" @click="toggleSet(filters.statuses, status.statusCode, !filters.statuses.has(status.statusCode))">
              <span class="filter-swatch" :style="{ background: workItemLabelColorValue(status.colorToken) }" aria-hidden="true" />
              <span class="filter-value__label">{{ status.displayName }}</span><small :class="{ 'filter-value__count--zero': !countBy('statusCode', status.statusCode) }">{{ countBy('statusCode', status.statusCode) }}</small>
            </button>
          </div>
        </section>
        <section class="filter-section">
          <h4>优先级<small v-if="filters.priorities.size">已选 {{ filters.priorities.size }}</small></h4>
          <div class="filter-section__values">
            <button v-for="priority in priorityOptions" :key="priority.code" class="filter-value" :aria-pressed="filters.priorities.has(priority.code)" @click="toggleSet(filters.priorities, priority.code, !filters.priorities.has(priority.code))">
              <span class="filter-swatch" :style="{ background: workItemLabelColorValue(priority.colorToken) }" aria-hidden="true" />
              <span class="filter-value__label">{{ priority.displayName }}</span><small :class="{ 'filter-value__count--zero': !countBy('priority', priority.code) }">{{ countBy('priority', priority.code) }}</small>
            </button>
          </div>
        </section>
        <section class="filter-section">
          <h4>工作项类别<small v-if="filters.contents.size">已选 {{ filters.contents.size }}</small></h4>
          <div class="filter-section__values">
            <button v-for="content in catalog?.items ?? []" :key="content.id" class="filter-value" :aria-pressed="filters.contents.has(content.id)" @click="toggleSet(filters.contents, content.id, !filters.contents.has(content.id))">
              <span class="filter-swatch" :style="{ background: workItemLabelColorValue(content.colorToken) }" aria-hidden="true" />
              <span class="filter-value__label">{{ content.name }}</span><small :class="{ 'filter-value__count--zero': !countBy('contentId', content.id) }">{{ countBy('contentId', content.id) }}</small>
            </button>
          </div>
        </section>
      </div>
      <div class="filter-dates">
        <div class="filter-field">
          <span class="filter-field-label">截止日期</span>
          <el-date-picker v-model="filters.dueRange" type="daterange" start-placeholder="开始日期" end-placeholder="结束日期" @change="syncUrl" />
        </div>
        <div class="filter-field">
          <span class="filter-field-label">最后更新时间</span>
          <el-date-picker v-model="filters.updatedAfter" type="date" aria-label="最后更新时间晚于" placeholder="选择日期之后" @change="syncUrl" />
        </div>
      </div>
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
.filter-popover { display: grid; gap: var(--yp-space-3); }
.filter-value, .text-button {
  display: flex; align-items: center; gap: var(--yp-space-2); width: 100%; padding: 7px 8px; border: 0;
  border-radius: var(--yp-radius-sm); color: var(--yp-text-primary); background: transparent; text-align: left; cursor: pointer;
}
.filter-value:hover { background: var(--yp-bg-hover); }
.filter-value small { margin-left: auto; color: var(--yp-text-muted); }
.filter-popover > header { display: flex; align-items: center; justify-content: space-between; }
.text-button { width: auto; color: var(--yp-action-primary); }
.toolbar-button__count {
  display: inline-grid; min-width: 18px; height: 18px; padding: 0 5px; box-sizing: border-box; place-items: center;
  border-radius: var(--yp-radius-pill); background: var(--yp-action-primary); color: var(--yp-on-solid);
  font-size: 11px; font-weight: 600; font-variant-numeric: tabular-nums; line-height: 1;
}
.filter-popover {
  gap: var(--yp-space-4); min-width: 0; max-height: min(720px, calc(100dvh - 96px));
  overflow: hidden auto; padding: 0 var(--yp-space-5) var(--yp-space-5);
}
.filter-popover__header {
  position: sticky; z-index: 1; top: 0; display: flex; align-items: center; gap: var(--yp-space-2);
  margin: 0 calc(-1 * var(--yp-space-5)); padding: var(--yp-space-4) var(--yp-space-5) var(--yp-space-3);
   background: var(--yp-bg-raised);
}
.filter-popover__header strong { color: var(--yp-text-primary); font-size: 15px; font-weight: 600; }
.filter-popover__count {
  display: inline-grid; min-width: 20px; height: 20px; padding: 0 6px; box-sizing: border-box; place-items: center;
  border-radius: var(--yp-radius-pill); background: var(--yp-action-primary); color: var(--yp-on-solid);
  font-size: 12px; font-weight: 600; font-variant-numeric: tabular-nums;
}
.filter-popover__clear { margin-left: auto; padding: 4px 8px; }
.filter-popover__clear:disabled { color: var(--yp-text-muted); background: transparent; cursor: default; }
.filter-section { display: grid; align-content: start; gap: 4px; min-width: 0; }
.filter-section h4 {
  display: flex; align-items: baseline; justify-content: space-between; gap: var(--yp-space-2);
  margin: 0 0 var(--yp-space-2); color: var(--yp-text-secondary); font-size: 12px; font-weight: 600; letter-spacing: .02em;
}
.filter-section h4 small { color: var(--yp-action-primary); font-size: 12px; font-weight: 500; letter-spacing: 0; }
.filter-section__values { display: grid; align-content: start; gap: 4px; max-height: 248px; overflow: hidden auto; }
.filter-columns {
  display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--yp-space-4) var(--yp-space-4); min-width: 0;
  padding-top: var(--yp-space-4);
}
.filter-value {
  min-width: 0; min-height: 32px; gap: 8px; padding: 5px 10px; background: var(--yp-bg-sunken);
  transition: background-color var(--yp-motion-fast) var(--yp-ease-standard);
}
.filter-value[aria-pressed='true'] { background: var(--yp-bg-selected-strong); }
.filter-value[aria-pressed='true'] .filter-value__label { font-weight: 500; }
.filter-value__label { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.filter-swatch { flex: none; width: 8px; height: 8px; border-radius: 50%; box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--yp-text-primary) 12%, transparent); }
.filter-value small { flex: 0 0 auto; color: var(--yp-text-secondary); font-size: 12px; font-variant-numeric: tabular-nums; }
.filter-value small.filter-value__count--zero { opacity: .55; }
.filter-dates {
  display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--yp-space-4) var(--yp-space-5); min-width: 0;
  padding-top: var(--yp-space-4);
}
.filter-field { display: grid; gap: var(--yp-space-2); min-width: 0; }
.filter-field-label { color: var(--yp-text-secondary); font-size: 12px; font-weight: 600; letter-spacing: .02em; }
.filter-field :deep(.el-date-editor) { width: 100%; min-width: 0; box-sizing: border-box; }
:global(.work-items-filter-popover.el-popover) { width: min(760px, calc(100vw - 32px)) !important; box-sizing: border-box; padding: 0; overflow: hidden; }
@media (max-width: 560px) {
  .filter-columns { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .filter-dates { grid-template-columns: minmax(0, 1fr); }
}
</style>
