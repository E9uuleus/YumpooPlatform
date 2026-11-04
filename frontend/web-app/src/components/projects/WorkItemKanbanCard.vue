<script setup lang="ts">
import './workItemKanban.css'
import {
  readCsrfToken,
  type ProjectContentCatalog,
  type ProjectWorkItemListItem,
  type WorkItemDetail,
  type WorkItemLabelCatalog,
  type WorkItemLabelColorToken,
} from '@yumpoo/api-client'
import { ElInput, ElPopover } from 'element-plus'
import { computed, nextTick, ref, type CSSProperties } from 'vue'
import { workItemsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../InlineProblem.vue'
import YpAssigneeStack from '../yp/YpAssigneeStack.vue'
import { useWorkItemCellPopover } from './useWorkItemCellPopover'
import type { WorkItemPatchField, WorkItemPatchValue } from './useWorkItemEdits'
import WorkItemAssigneePicker from './WorkItemAssigneePicker.vue'
import { workItemAssignees } from './workItemAssignees'
import WorkItemContentPopoverContent from './WorkItemContentPopoverContent.vue'
import WorkItemDiscussionIcon from './WorkItemDiscussionIcon.vue'
import WorkItemDueDateCell from './WorkItemDueDateCell.vue'
import type { DueDateValue } from './workItemDueDate'
import WorkItemFieldGlyph from './WorkItemFieldGlyph.vue'
import { workItemFieldIdentity } from './workItemFieldIdentity'
import WorkItemLabelPopoverContent from './WorkItemLabelPopoverContent.vue'
import { workItemLabelColorValue } from './workItemLabelColors'
import WorkItemNameCell from './WorkItemNameCell.vue'
import WorkItemRowActions from './WorkItemRowActions.vue'
import WorkItemTimerCell from './WorkItemTimerCell.vue'

export type KanbanCardField = 'content' | 'status' | 'priority' | 'dueDate' | 'timeTracking'
export interface KanbanSubitems { items: ProjectWorkItemListItem[]; loading: boolean; loaded: boolean; error?: ApiProblem | undefined }
interface StatusOption { statusCode: string; displayName: string; colorToken?: WorkItemLabelColorToken; active: boolean; statusCategory: string; sortOrder: number }
interface PriorityOption { code: string; displayName: string; colorToken: WorkItemLabelColorToken; active: boolean; sortOrder: number }
interface ContentOption { id: string; name: string; colorToken: WorkItemLabelColorToken }

const props = defineProps<{
  item: ProjectWorkItemListItem
  projectId: string
  /** 「隐藏」菜单中可见的字段，按表格列顺序显示为标签。 */
  fields: readonly KanbanCardField[]
  showAssignee: boolean
  labelCatalog?: WorkItemLabelCatalog | undefined
  contentCatalog?: ProjectContentCatalog | undefined
  workflowStatuses: readonly StatusOption[]
  priorityOptions: readonly PriorityOption[]
  activeContents: readonly ContentOption[]
  canCreate: boolean
  busy: boolean
  subitemCount: number
  expanded: boolean
  subitems?: KanbanSubitems | undefined
  autoEdit?: boolean
}>()
const emit = defineEmits<{
  open: [item: ProjectWorkItemListItem, tab: 'details' | 'discussion']
  updated: [id: string, detail: WorkItemDetail]
  editing: [active: boolean]
  transition: [item: ProjectWorkItemListItem, statusCode: string]
  patch: [item: ProjectWorkItemListItem, field: WorkItemPatchField, value: WorkItemPatchValue]
  deadline: [item: ProjectWorkItemListItem, value: DueDateValue]
  labelsUpdated: [catalog: WorkItemLabelCatalog]
  contentsUpdated: [catalog: ProjectContentCatalog]
  timerChanged: []
  toggleSubitems: [item: ProjectWorkItemListItem]
  subitemCreated: [parent: ProjectWorkItemListItem]
  duplicate: [item: ProjectWorkItemListItem]
  moved: [item: ProjectWorkItemListItem]
  changed: [affectedIds: string[]]
  removed: [item: ProjectWorkItemListItem]
  createBelow: [item: ProjectWorkItemListItem]
  addSubitem: [item: ProjectWorkItemListItem]
  dragstart: [item: ProjectWorkItemListItem]
  dragend: []
}>()

const { openCell, setCellOpen, closeCell } = useWorkItemCellPopover()
const popoverBusy = ref<Record<string, boolean>>({})
const labelEditors = new Map<string, { resetEditor?: () => void }>()
const nameEditing = ref(false)
const active = computed(() => Boolean(openCell.value) || nameEditing.value || subitemOpen.value)

const status = computed(() => props.workflowStatuses.find(option => option.statusCode === props.item.statusCode))
const priority = computed(() => props.priorityOptions.find(option => option.code === props.item.priority))
const content = computed(() => props.contentCatalog?.items.find(option => option.id === props.item.contentId)
  ?? { name: props.item.contentName, colorToken: props.item.contentColorToken })
const assignees = computed(() => workItemAssignees(props.item))
const canEdit = computed(() => props.item.capabilities.canEditFields && !props.busy)

function tagColor(token?: string): CSSProperties {
  return { '--kanban-tag-color': workItemLabelColorValue(token) } as CSSProperties
}
function cellKey(field: string): string { return `${props.item.id}:${field}` }
function setLabelEditor(kind: string, value: unknown): void {
  if (value) labelEditors.set(kind, value as { resetEditor?: () => void })
  else labelEditors.delete(kind)
}
function select(field: 'priority' | 'content', value: WorkItemPatchValue): void {
  closeCell()
  emit('patch', props.item, field, value)
}

function onCardClick(event: MouseEvent): void {
  if (event.target instanceof Element && event.target.closest('button, input, a, [role="button"], .work-item-row-actions')) return
  emit('open', props.item, 'details')
}

function onDragStart(event: DragEvent): void {
  if (nameEditing.value || openCell.value) { event.preventDefault(); return }
  event.dataTransfer?.setData('text/plain', props.item.id)
  emit('dragstart', props.item)
}

// 卡片下展开的子项：只显示名称，末尾可快速添加子项。
const subitemOpen = ref(false)
const subitemTitle = ref('')
const subitemSaving = ref(false)
const subitemError = ref<ApiProblem>()
const subitemInput = ref<InstanceType<typeof ElInput>>()
const subitemContentId = computed(() => props.activeContents.find(option => option.id === props.item.contentId)?.id
  ?? props.activeContents[0]?.id)

async function openSubitemInput(): Promise<void> {
  if (!props.canCreate || !subitemContentId.value) return
  if (!props.expanded) emit('toggleSubitems', props.item)
  subitemOpen.value = true
  await nextTick()
  subitemInput.value?.focus()
}

function closeSubitemInput(): void {
  if (subitemSaving.value) return
  subitemOpen.value = false
  subitemTitle.value = ''
  subitemError.value = undefined
}

async function createSubitem(): Promise<void> {
  const title = subitemTitle.value.trim()
  if (subitemSaving.value || !title || !subitemContentId.value) return
  const csrf = readCsrfToken()
  if (!csrf) { subitemError.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  subitemSaving.value = true
  subitemError.value = undefined
  try {
    await workItemsApi.createWorkItemSubitem({
      parentWorkItemId: props.item.id, xXSRFTOKEN: csrf, idempotencyKey: globalThis.crypto.randomUUID(),
      workItemSubitemCreateRequest: {
        contentId: subitemContentId.value, title, priority: null, assigneeUserId: null, description: null, notes: null,
        timelineStartDate: null, timelineEndDate: null, dueDate: null,
      },
    })
    subitemTitle.value = ''
    emit('subitemCreated', props.item)
  } catch (reason) {
    subitemError.value = await toApiProblem(reason)
  } finally {
    subitemSaving.value = false
    await nextTick()
    if (subitemOpen.value) subitemInput.value?.focus()
  }
}

function onSubitemKeydown(rawEvent: Event | KeyboardEvent): void {
  const event = rawEvent as KeyboardEvent
  if (event.isComposing || event.keyCode === 229) return
  if (event.key === 'Escape') closeSubitemInput()
  if (event.key === 'Enter') { event.preventDefault(); void createSubitem() }
}

defineExpose({ openSubitemInput })
</script>

<template>
  <div class="kanban-card-stack">
    <article
      class="kanban-card"
      :class="{ 'kanban-card--active': active }"
      :data-work-item-id="item.id"
      draggable="true"
      @dragstart="onDragStart"
      @dragend="emit('dragend')"
      @click="onCardClick"
    >
      <div class="kanban-card__head">
        <work-item-name-cell
          class="kanban-card__name"
          :item="item"
          :disabled="busy"
          :auto-edit="autoEdit"
          @open="emit('open', item, 'details')"
          @updated="(id, detail) => emit('updated', id, detail)"
          @editing="active => { nameEditing = active; emit('editing', active) }"
        />
        <work-item-row-actions
          class="kanban-card__menu"
          :item="item"
          :can-create="canCreate"
          :sorted="false"
          :disabled="busy"
          @open="emit('open', $event, 'details')"
          @add-subitem="emit('addSubitem', $event)"
          @duplicate="emit('duplicate', $event)"
          @create-below="emit('createBelow', $event)"
          @moved="emit('moved', $event)"
          @changed="emit('changed', $event)"
          @removed="emit('removed', $event)"
        />
      </div>

      <div
        v-if="fields.length"
        class="kanban-card__tags"
      >
        <template
          v-for="field in fields"
          :key="field"
        >
          <el-popover
            v-if="field === 'status'"
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
                class="work-item-kanban-tag work-item-kanban-tag--bar"
                :style="tagColor(status?.colorToken)"
                :disabled="busy"
                :aria-expanded="openCell === cellKey('status')"
                :aria-label="`状态：${status?.displayName ?? item.statusCode}`"
              >
                <span>{{ status?.displayName ?? item.statusCode }}</span>
              </button>
            </template>
            <work-item-label-popover-content
              :ref="value => setLabelEditor('status', value)"
              kind="status"
              :project-id="projectId"
              :catalog="labelCatalog"
              :workflow-statuses="workflowStatuses.filter(option => option.active || option.statusCode === item.statusCode)"
              :current-value="item.statusCode"
              :can-manage="Boolean(labelCatalog?.canManage)"
              :available-transitions="item.capabilities.availableTransitions"
              @busy-change="popoverBusy.status = $event"
              @select-status="closeCell(); emit('transition', item, $event)"
              @updated="emit('labelsUpdated', $event)"
            />
          </el-popover>

          <el-popover
            v-else-if="field === 'priority'"
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
              <span :class="{ 'kanban-card__field--empty': !priority }">
                <button
                  type="button"
                  class="work-item-kanban-tag"
                  :class="priority ? 'work-item-kanban-tag--bar' : 'work-item-kanban-tag--placeholder'"
                  :style="priority ? tagColor(priority.colorToken) : undefined"
                  :disabled="!canEdit"
                  :aria-expanded="openCell === cellKey('priority')"
                  :aria-label="priority ? `优先级：${priority.displayName}` : '设置优先级'"
                >
                  <work-item-field-glyph
                    v-if="!priority"
                    field="priority"
                    plain
                  />
                  <span>{{ priority?.displayName ?? workItemFieldIdentity('priority').label }}</span>
                </button>
              </span>
            </template>
            <work-item-label-popover-content
              :ref="value => setLabelEditor('priority', value)"
              kind="priority"
              :project-id="projectId"
              :catalog="labelCatalog"
              :priority-options="priorityOptions.filter(option => option.active)"
              :current-value="item.priority"
              :can-manage="Boolean(labelCatalog?.canManage)"
              @busy-change="popoverBusy.priority = $event"
              @select-priority="select('priority', $event)"
              @updated="emit('labelsUpdated', $event)"
            />
          </el-popover>

          <el-popover
            v-else-if="field === 'content'"
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
                class="work-item-kanban-tag work-item-kanban-tag--bar"
                :style="tagColor(content.colorToken)"
                :disabled="!canEdit"
                :aria-expanded="openCell === cellKey('content')"
                :aria-label="`工作项类别：${content.name}`"
              >
                <span>{{ content.name || '—' }}</span>
              </button>
            </template>
            <work-item-content-popover-content
              :ref="value => setLabelEditor('content', value)"
              :project-id="projectId"
              :catalog="contentCatalog"
              :current-value="item.contentId"
              :can-manage="Boolean(contentCatalog?.canManage)"
              @busy-change="popoverBusy.content = $event"
              @select="select('content', $event)"
              @updated="emit('contentsUpdated', $event)"
            />
          </el-popover>

          <span
            v-else-if="field === 'dueDate'"
            :class="{ 'kanban-card__field--empty': !item.dueDate }"
          >
            <work-item-due-date-cell
              variant="tag"
              :item="item"
              :can-edit="item.capabilities.canEditFields"
              :busy="busy"
              :placeholder="item.dueDate ? undefined : workItemFieldIdentity('dueDate').label"
              @change="emit('deadline', item, $event)"
            />
          </span>

          <span
            v-else-if="field === 'timeTracking'"
            :class="{ 'kanban-card__field--empty': !item.timeTracking?.rollupDurationMs }"
          >
            <work-item-timer-cell
              variant="tag"
              :item="item"
              :project-id="projectId"
              @changed="emit('timerChanged')"
            />
          </span>
        </template>
      </div>

      <slot name="connections" />

      <footer class="kanban-card__foot">
        <el-popover
          v-if="showAssignee"
          placement="bottom-start"
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
              class="kanban-card__assignees"
              :class="{ 'kanban-card__field--empty': !assignees.length }"
              :disabled="!canEdit"
              :aria-label="assignees.length ? `处理人：${assignees.map(person => person.displayName).join('、')}` : '设置处理人'"
            >
              <yp-assignee-stack
                v-if="assignees.length"
                :assignees="assignees"
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
            :selected="assignees"
            :busy="busy"
            :batch-count="0"
            @change="emit('patch', item, 'assignees', $event)"
            @done="closeCell"
          />
        </el-popover>
        <span class="kanban-card__foot-actions">
          <button
            type="button"
            class="kanban-card__icon-button"
            :aria-label="item.discussionCount ? `打开协作讨论，${item.discussionCount}条讨论` : '打开协作讨论'"
            @click.stop="emit('open', item, 'discussion')"
          >
            <work-item-discussion-icon :count="item.discussionCount" />
          </button>
          <button
            type="button"
            class="kanban-card__icon-button kanban-card__subitems-button"
            :class="{ 'kanban-card__icon-button--active': expanded }"
            :aria-expanded="expanded"
            :aria-label="subitemCount ? `${expanded ? '收起' : '展开'}子项，共 ${subitemCount} 个` : '添加子项'"
            @click.stop="subitemCount ? emit('toggleSubitems', item) : openSubitemInput()"
          >
            <span v-if="subitemCount">{{ subitemCount }}</span>
            <svg
              width="18"
              height="18"
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              stroke-width="1.4"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <rect
                x="3"
                y="3"
                width="7"
                height="5"
                rx="1.2"
              />
              <rect
                x="10"
                y="12"
                width="7"
                height="5"
                rx="1.2"
              />
              <path d="M6.5 8v4.5a1 1 0 0 0 1 1H10" />
            </svg>
          </button>
        </span>
      </footer>
    </article>

    <div
      v-if="expanded || subitemOpen"
      class="kanban-subitems"
      :aria-label="`${item.title} 的子项`"
    >
      <inline-problem
        v-if="subitems?.error"
        :problem="subitems.error"
      />
      <div
        v-if="subitems?.loading && !subitems.items.length"
        class="kanban-subitem kanban-subitem--loading"
        role="status"
      >
        正在加载子项…
      </div>
      <button
        v-for="child in subitems?.items ?? []"
        :key="child.id"
        type="button"
        class="kanban-subitem"
        @click.stop="emit('open', child, 'details')"
      >
        <span>{{ child.title }}</span>
      </button>
      <div
        v-if="subitemOpen"
        class="kanban-subitem kanban-subitem--input"
      >
        <el-input
          ref="subitemInput"
          v-model="subitemTitle"
          size="small"
          placeholder="子项名称，回车添加"
          aria-label="新子项名称"
          maxlength="300"
          :readonly="subitemSaving"
          @keydown="onSubitemKeydown"
          @blur="!subitemTitle.trim() && closeSubitemInput()"
        />
        <inline-problem
          v-if="subitemError"
          :problem="subitemError"
        />
      </div>
      <button
        v-else-if="canCreate"
        type="button"
        class="kanban-subitem kanban-subitem--add"
        @click.stop="openSubitemInput"
      >
        + 添加子项
      </button>
    </div>
  </div>
</template>

<style scoped>
.kanban-card-stack { display: grid; gap: 0; }

.kanban-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-surface);
  box-shadow: var(--yp-shadow-card);
  cursor: pointer;
  transition: box-shadow var(--yp-motion-fast) var(--yp-ease-standard), border-color var(--yp-motion-fast) var(--yp-ease-standard);
}
.kanban-card:hover, .kanban-card--active { border-color: var(--yp-border-default); box-shadow: var(--yp-shadow-popover); }

.kanban-card__head { display: flex; align-items: flex-start; gap: 4px; min-width: 0; }
.kanban-card__name { flex: 1 1 auto; min-width: 0; height: auto; min-height: 24px; padding: 0; }
.kanban-card__name :deep(.work-item-title-text) {
  display: -webkit-box;
  width: auto;
  max-width: 100%;
  line-height: 20px;
  text-align: left;
  white-space: normal;
  word-break: break-word;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  cursor: text;
}
.kanban-card__name :deep(.work-item-detail-button) { display: none; }
.kanban-card__name :deep(.work-item-name-input) { height: 24px; }
.kanban-card__menu { flex: 0 0 auto; opacity: 0; transition: opacity var(--yp-motion-fast) var(--yp-ease-standard); }
.kanban-card:hover .kanban-card__menu, .kanban-card--active .kanban-card__menu, .kanban-card__menu:focus-within,
.kanban-card__menu.work-item-row-actions--open { opacity: 1; }

.kanban-card__tags { display: flex; flex-wrap: wrap; gap: 6px; min-width: 0; }
.kanban-card__tags > * { display: inline-flex; max-width: 100%; min-width: 0; }

.kanban-card__foot { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 28px; }
.kanban-card__assignees {
  display: inline-flex;
  min-width: 28px;
  height: 28px;
  align-items: center;
  padding: 0 2px;
  border: 0;
  border-radius: var(--yp-radius-sm);
  background: transparent;
  cursor: pointer;
}
.kanban-card__assignees:hover:not(:disabled) { background: var(--yp-bg-hover); }
.kanban-card__assignees:disabled { cursor: default; }
.kanban-card__foot-actions { display: inline-flex; align-items: center; gap: 2px; margin-left: auto; }
.kanban-card__icon-button {
  display: inline-flex;
  height: 28px;
  min-width: 28px;
  align-items: center;
  justify-content: center;
  gap: 2px;
  padding: 0 4px;
  border: 0;
  border-radius: var(--yp-radius-sm);
  background: transparent;
  color: var(--yp-text-secondary);
  font-size: 12px;
  cursor: pointer;
}
.kanban-card__icon-button:hover { background: var(--yp-bg-hover); color: var(--yp-text-primary); }
.kanban-card__icon-button--active { background: var(--yp-bg-selected); color: var(--yp-action-primary); }
.kanban-card__icon-button:focus-visible, .kanban-card__assignees:focus-visible { outline: 2px solid var(--yp-focus-ring); outline-offset: -2px; }

/* 子项：与表格子项相同的层级连接线（颜色、线宽、圆角取同一组变量）。 */
.kanban-subitems {
  --kanban-rail-x: 16px;
  --kanban-rail-indent: 28px;
  --kanban-rail-line: var(--work-item-hierarchy-line-width, 1px);
  --kanban-rail-radius: var(--work-item-hierarchy-corner-radius, 6px);
  --kanban-rail-color: var(--work-item-group-accent, rgb(87, 155, 252));
  display: grid;
  gap: 8px;
  padding: 8px 0 4px var(--kanban-rail-indent);
}
.kanban-subitem {
  position: relative;
  display: flex;
  min-height: 36px;
  align-items: center;
  box-sizing: border-box;
  padding: 6px 10px;
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-surface);
  color: var(--yp-text-primary);
  font: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.kanban-subitem > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.kanban-subitem:hover { border-color: var(--yp-border-default); }
/* 竖线从卡片底部延伸到当前子项中线，转角圆弧连入子项左边。 */
.kanban-subitem::before {
  position: absolute;
  top: -9px;
  bottom: 50%;
  left: calc(var(--kanban-rail-x) - var(--kanban-rail-indent));
  width: calc(var(--kanban-rail-indent) - var(--kanban-rail-x));
  box-sizing: border-box;
  border-bottom: var(--kanban-rail-line) solid var(--kanban-rail-color);
  border-left: var(--kanban-rail-line) solid var(--kanban-rail-color);
  border-bottom-left-radius: var(--kanban-rail-radius);
  content: '';
  pointer-events: none;
}
.kanban-subitem:not(:last-child)::after {
  position: absolute;
  top: 50%;
  bottom: -9px;
  left: calc(var(--kanban-rail-x) - var(--kanban-rail-indent));
  border-left: var(--kanban-rail-line) solid var(--kanban-rail-color);
  content: '';
  pointer-events: none;
}
.kanban-subitem--add, .kanban-subitem--loading {
  border-style: dashed;
  background: transparent;
  color: var(--yp-text-secondary);
}
.kanban-subitem--add:hover { background: var(--yp-fill-neutral-active); color: var(--yp-text-primary); }
.kanban-subitem--add::before { border-color: color-mix(in srgb, var(--kanban-rail-color) 50%, transparent); }
.kanban-subitem--input { display: grid; gap: 6px; padding: 4px; cursor: default; }
</style>
