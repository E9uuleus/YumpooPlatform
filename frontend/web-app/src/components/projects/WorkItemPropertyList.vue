<script setup lang="ts">
import type {
  ProjectContentCatalog, ProjectWorkItemListItem, WorkItemAssignee, WorkItemLabelCatalog, WorkItemLabelColorToken,
} from '@yumpoo/api-client'
import { ElPopover } from 'element-plus'
import { computed, ref } from 'vue'
import YpAssigneeStack from '../yp/YpAssigneeStack.vue'
import { useWorkItemCellPopover } from './useWorkItemCellPopover'
import type { WorkItemPatchField, WorkItemPatchValue } from './useWorkItemEdits'
import WorkItemAssigneePicker from './WorkItemAssigneePicker.vue'
import WorkItemContentPopoverContent from './WorkItemContentPopoverContent.vue'
import WorkItemDueDateCell from './WorkItemDueDateCell.vue'
import type { DueDateValue, WorkItemDueDateFacts } from './workItemDueDate'
import WorkItemFieldGlyph from './WorkItemFieldGlyph.vue'
import { workItemFieldIdentity } from './workItemFieldIdentity'
import WorkItemLabelPopoverContent from './WorkItemLabelPopoverContent.vue'
import { useWorkItemPresentation } from './workItemPresentation'
import WorkItemTimerCell from './WorkItemTimerCell.vue'
import WorkItemUpdatedCell from './WorkItemUpdatedCell.vue'

/** 字段列表的取值：详情来自工作项本身，新建来自草稿。 */
export interface WorkItemPropertyValues extends WorkItemDueDateFacts {
  statusCode: string
  priority: string | null
  contentId: string
  contentName: string
  contentColorToken: WorkItemLabelColorToken
  assignees: WorkItemAssignee[]
}

const props = defineProps<{
  values: WorkItemPropertyValues
  projectId: string
  labelCatalog?: WorkItemLabelCatalog | undefined
  contentCatalog?: ProjectContentCatalog | undefined
  /** 状态弹层可选的目标状态；新建草稿传入全部启用状态。 */
  availableTransitions: Array<{ toStatus: string }>
  canEdit: boolean
  busy: boolean
  /** 已存在的工作项：额外显示时长追踪与最后更新时间。 */
  row?: ProjectWorkItemListItem | undefined
}>()
const emit = defineEmits<{
  patch: [field: WorkItemPatchField, value: WorkItemPatchValue]
  transition: [statusCode: string]
  deadline: [value: DueDateValue]
  labelsUpdated: [catalog: WorkItemLabelCatalog]
  contentsUpdated: [catalog: ProjectContentCatalog]
  timerChanged: []
  openActivity: []
}>()

const contentsById = computed(() => new Map((props.contentCatalog?.items ?? []).map(item => [item.id, item])))
const workflowStatuses = computed(() => [...(props.labelCatalog?.statuses ?? [])]
  .sort((left, right) => left.sortOrder - right.sortOrder)
  .map(status => ({ ...status, statusCode: status.code })))
const priorityOptions = computed(() => [...(props.labelCatalog?.priorities ?? [])]
  .sort((left, right) => left.sortOrder - right.sortOrder))
const { contentLabel, statusLabel, labelCellStyle, getStatusCellStyle, getPriorityCellStyle, getPriorityPresentation }
  = useWorkItemPresentation({ contentsById, workflowStatuses, priorityOptions })
const { openCell, setCellOpen, closeCell } = useWorkItemCellPopover()
const popoverBusy = ref<Record<string, boolean>>({})
const labelEditors = new Map<string, { resetEditor?: () => void }>()
const disabled = computed(() => !props.canEdit || props.busy)

function cellKey(field: string): string { return `property:${field}` }
function setLabelEditor(kind: string, value: unknown): void {
  if (value) labelEditors.set(kind, value as { resetEditor?: () => void })
  else labelEditors.delete(kind)
}
function select(field: 'priority' | 'content', value: WorkItemPatchValue): void {
  closeCell()
  emit('patch', field, value)
}
</script>

<template>
  <dl class="work-item-properties">
    <div
      class="work-item-property"
      style="--i: 0"
    >
      <dt><work-item-field-glyph field="assignee" /><span>{{ workItemFieldIdentity('assignee').label }}</span></dt>
      <dd>
        <el-popover
          placement="bottom"
          :width="360"
          trigger="click"
          popper-class="work-items-popover"
          :persistent="false"
          :visible="openCell === cellKey('assignee')"
          @update:visible="setCellOpen(cellKey('assignee'), $event)"
        >
          <template #reference>
            <button
              type="button"
              class="work-item-property__value"
              :disabled="disabled"
              :aria-label="values.assignees.length ? `处理人：${values.assignees.map(person => person.displayName).join('、')}` : '设置处理人'"
            >
              <yp-assignee-stack
                v-if="values.assignees.length"
                :assignees="values.assignees"
                tooltip-placement="right"
                :tooltip-disabled="openCell === cellKey('assignee')"
              />
              <work-item-field-glyph
                v-else
                field="assignee"
                plain
              />
            </button>
          </template>
          <work-item-assignee-picker
            :project-id="projectId"
            :selected="values.assignees"
            :busy="busy"
            :batch-count="0"
            @change="emit('patch', 'assignees', $event)"
            @done="closeCell"
          />
        </el-popover>
      </dd>
    </div>

    <div
      class="work-item-property"
      style="--i: 1"
    >
      <dt><work-item-field-glyph field="status" /><span>{{ workItemFieldIdentity('status').label }}</span></dt>
      <dd>
        <el-popover
          placement="bottom"
          width="auto"
          trigger="click"
          popper-class="work-items-label-popover status-popover"
          :persistent="Boolean(popoverBusy.status)"
          :visible="openCell === cellKey('status')"
          @update:visible="setCellOpen(cellKey('status'), $event)"
          @hide="labelEditors.get('status')?.resetEditor?.()"
        >
          <template #reference>
            <button
              type="button"
              class="work-item-property__value work-item-property__bar"
              :style="getStatusCellStyle(values.statusCode)"
              :disabled="busy"
            >
              <span>{{ statusLabel(values.statusCode) }}</span>
            </button>
          </template>
          <work-item-label-popover-content
            :ref="value => setLabelEditor('status', value)"
            kind="status"
            :project-id="projectId"
            :catalog="labelCatalog"
            :workflow-statuses="workflowStatuses.filter(option => option.active || option.statusCode === values.statusCode)"
            :current-value="values.statusCode"
            :can-manage="Boolean(labelCatalog?.canManage)"
            :available-transitions="availableTransitions"
            @busy-change="popoverBusy.status = $event"
            @select-status="closeCell(); emit('transition', $event)"
            @updated="emit('labelsUpdated', $event)"
          />
        </el-popover>
      </dd>
    </div>

    <div
      class="work-item-property"
      style="--i: 2"
    >
      <dt><work-item-field-glyph field="priority" /><span>{{ workItemFieldIdentity('priority').label }}</span></dt>
      <dd>
        <el-popover
          placement="bottom"
          width="auto"
          trigger="click"
          popper-class="work-items-label-popover priority-popover"
          :persistent="Boolean(popoverBusy.priority)"
          :visible="openCell === cellKey('priority')"
          @update:visible="setCellOpen(cellKey('priority'), $event)"
          @hide="labelEditors.get('priority')?.resetEditor?.()"
        >
          <template #reference>
            <button
              type="button"
              class="work-item-property__value work-item-property__bar"
              :style="getPriorityCellStyle(values.priority)"
              :disabled="disabled"
            >
              <span>{{ getPriorityPresentation(values.priority).label }}</span>
            </button>
          </template>
          <work-item-label-popover-content
            :ref="value => setLabelEditor('priority', value)"
            kind="priority"
            :project-id="projectId"
            :catalog="labelCatalog"
            :priority-options="priorityOptions.filter(option => option.active)"
            :current-value="values.priority"
            :can-manage="Boolean(labelCatalog?.canManage)"
            @busy-change="popoverBusy.priority = $event"
            @select-priority="select('priority', $event)"
            @updated="emit('labelsUpdated', $event)"
          />
        </el-popover>
      </dd>
    </div>

    <div
      class="work-item-property"
      style="--i: 3"
    >
      <dt><work-item-field-glyph field="content" /><span>{{ workItemFieldIdentity('content').label }}</span></dt>
      <dd>
        <el-popover
          placement="bottom"
          width="auto"
          trigger="click"
          popper-class="work-items-label-popover content-popover"
          :persistent="Boolean(popoverBusy.content)"
          :visible="openCell === cellKey('content')"
          @update:visible="setCellOpen(cellKey('content'), $event)"
          @hide="labelEditors.get('content')?.resetEditor?.()"
        >
          <template #reference>
            <button
              type="button"
              class="work-item-property__value work-item-property__bar"
              :style="labelCellStyle(contentLabel(values).colorToken)"
              :disabled="disabled"
            >
              <span>{{ contentLabel(values).name || '—' }}</span>
            </button>
          </template>
          <work-item-content-popover-content
            :ref="value => setLabelEditor('content', value)"
            :project-id="projectId"
            :catalog="contentCatalog"
            :current-value="values.contentId"
            :can-manage="Boolean(contentCatalog?.canManage)"
            @busy-change="popoverBusy.content = $event"
            @select="select('content', $event)"
            @updated="emit('contentsUpdated', $event)"
          />
        </el-popover>
      </dd>
    </div>

    <div
      class="work-item-property"
      style="--i: 4"
    >
      <dt><work-item-field-glyph field="dueDate" /><span>{{ workItemFieldIdentity('dueDate').label }}</span></dt>
      <dd>
        <work-item-due-date-cell
          :item="values"
          :can-edit="canEdit"
          :busy="busy"
          @change="emit('deadline', $event)"
        />
      </dd>
    </div>

    <template v-if="row">
      <div
        class="work-item-property"
        style="--i: 5"
      >
        <dt><work-item-field-glyph field="timeTracking" /><span>{{ workItemFieldIdentity('timeTracking').label }}</span></dt>
        <dd>
          <work-item-timer-cell
            :item="row"
            :project-id="projectId"
            @changed="emit('timerChanged')"
          />
        </dd>
      </div>
      <div
        class="work-item-property"
        style="--i: 6"
      >
        <dt><work-item-field-glyph field="updatedAt" /><span>{{ workItemFieldIdentity('updatedAt').label }}</span></dt>
        <dd>
          <work-item-updated-cell
            :item="row"
            @open-activity="emit('openActivity')"
          />
        </dd>
      </div>
    </template>
  </dl>
</template>

<style scoped>
/* 单元格组件沿用表格行高变量。 */
.work-item-properties { --work-item-table-row-height: 40px; display: grid; gap: var(--yp-space-2); margin: 0; }
.work-item-property {
  display: grid;
  grid-template-columns: minmax(112px, 36%) minmax(0, 1fr);
  align-items: center;
  gap: var(--yp-space-4);
  animation: work-item-property-rise 280ms var(--yp-ease-standard) backwards;
  animation-delay: calc(var(--i) * 30ms);
}
.work-item-property dt { display: flex; min-width: 0; align-items: center; gap: 10px; color: var(--yp-text-primary); font-size: 14px; }
.work-item-property dt span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.work-item-property dd { display: flex; min-width: 0; height: 40px; margin: 0; overflow: hidden; border-radius: var(--yp-radius-xs); background: var(--yp-bg-sunken); }
.work-item-property dd > * { flex: 1 1 auto; min-width: 0; }
.work-item-property__value {
  display: flex; width: 100%; height: 100%; align-items: center; justify-content: center; gap: 6px;
  padding: 0 var(--yp-space-3); border: 0; background: transparent; color: var(--yp-text-primary);
  font: inherit; font-size: 13px; cursor: pointer;
  transition: background-color var(--yp-motion-fast) var(--yp-ease-standard), opacity var(--yp-motion-fast) var(--yp-ease-standard);
}
.work-item-property__value:not(.work-item-property__bar):hover:not(:disabled) { background: var(--yp-bg-hover); }
.work-item-property__value:disabled { cursor: default; }
.work-item-property__value:focus-visible { outline: 2px solid var(--yp-focus-ring); outline-offset: -2px; }
.work-item-property__bar { background: var(--yp-priority-empty); color: var(--yp-text-inverse); font-weight: 600; }
.work-item-property__bar:hover:not(:disabled) { opacity: .82; }
.work-item-property__bar span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 599.98px) {
  .work-item-property { grid-template-columns: minmax(96px, 38%) minmax(0, 1fr); gap: var(--yp-space-3); }
  .work-item-property dt { font-size: 13px; }
}
@keyframes work-item-property-rise { from { opacity: 0; transform: translateY(4px); } }
</style>
