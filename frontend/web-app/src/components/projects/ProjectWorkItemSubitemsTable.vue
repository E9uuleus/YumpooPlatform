<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import {
  readCsrfToken,
  type ProjectMember,
  type ProjectWorkItemListItem,
  type WorkItemLabelColorToken,
  type WorkItemDetail,
  type WorkItemLabelCatalog,
} from '@yumpoo/api-client'
import {
  ElButton,
  ElDatePicker,
  ElIcon,
  ElInput,
  ElLoading,
  ElMessage,
  ElOption as ElOptionRaw,
  ElPopover,
  ElSelect as ElSelectRaw,
  ElTable,
  ElTableColumn,
} from 'element-plus'
import { computed, ref, watch, type CSSProperties, type DefineComponent } from 'vue'
import { workItemsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../InlineProblem.vue'
import YpAssignee from '../yp/YpAssignee.vue'
import MondayColumnQuickSort from './MondayColumnQuickSort.vue'
import WorkItemLabelPopoverContent from './WorkItemLabelPopoverContent.vue'
import { workItemLabelColorValue } from './workItemLabelColors'

export interface ProjectWorkItemSubitemSortRule {
  field: string
  direction: 'ASC' | 'DESC'
}

export interface ProjectWorkItemSubitemColumn {
  key: 'title' | 'assignee' | 'status' | 'priority' | 'content' | 'dueDate' | 'updatedAt'
  label: string
}

interface ContentOption { id: string; name: string }
interface StatusOption {
  statusCode: string
  displayName: string
  statusCategory: string
  colorToken?: WorkItemLabelColorToken
  active: boolean
  sortOrder: number
}
interface PriorityOption {
  code: string
  displayName: string
  colorToken: WorkItemLabelColorToken
  active: boolean
  sortOrder: number
}

const props = defineProps<{
  projectId: string
  parent: ProjectWorkItemListItem
  items: ProjectWorkItemListItem[]
  loading: boolean
  error?: ApiProblem | undefined
  sortRules: ProjectWorkItemSubitemSortRule[]
  columns: ProjectWorkItemSubitemColumn[]
  columnWidths: Record<ProjectWorkItemSubitemColumn['key'], number>
  activeContents: ContentOption[]
  members: ProjectMember[]
  workflowStatuses: StatusOption[]
  priorityOptions: PriorityOption[]
  labelCatalog?: WorkItemLabelCatalog | undefined
  canCreate: boolean
  editingCell: boolean
}>()

const vLoading = ElLoading.directive
const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent

const emit = defineEmits<{
  retry: []
  sortChange: [rules: ProjectWorkItemSubitemSortRule[]]
  created: [parent: ProjectWorkItemListItem]
  updated: [id: string, detail: WorkItemDetail]
  openDetail: [item: ProjectWorkItemListItem, tab: 'details' | 'discussion']
  patch: [item: ProjectWorkItemListItem, field: 'assignee' | 'priority' | 'dueDate', value: string | Date | null]
  transition: [item: ProjectWorkItemListItem, statusCode: string]
  selectionChange: [parentId: string, rows: ProjectWorkItemListItem[]]
  headerResize: [newWidth: number, oldWidth: number, column: { label: string }]
  moveColumn: [source: string, target: string]
}>()

const quickOpen = ref(false)
const quickTitle = ref('')
const quickContentId = ref(props.parent.contentId)
const quickCreating = ref(false)
const quickError = ref<ApiProblem>()
const assigneeSearch = ref('')
const draggingItem = ref<ProjectWorkItemListItem>()
const columnDraggingKey = ref<string>()
const savingSortOrder = ref(false)

watch(() => props.parent.contentId, value => {
  if (!quickOpen.value) quickContentId.value = value
})

const filteredMembers = computed(() => {
  const query = assigneeSearch.value.trim().toLocaleLowerCase()
  const active = props.members.filter(member => member.membershipStatus === 'ACTIVE')
  return query ? active.filter(member => member.displayName.toLocaleLowerCase().includes(query)) : active
})

const sortFieldByColumn: Record<ProjectWorkItemSubitemColumn['key'], string> = {
  title: 'TITLE', assignee: 'ASSIGNEE', status: 'STATUS', priority: 'PRIORITY',
  content: 'CONTENT', dueDate: 'DUE_DATE', updatedAt: 'UPDATED_AT',
}

function statusLabel(code: string): string {
  return props.workflowStatuses.find(status => status.statusCode === code)?.displayName ?? code
}

function statusStyle(code: string): CSSProperties {
  const token = props.workflowStatuses.find(status => status.statusCode === code)?.colorToken
  return token ? { backgroundColor: workItemLabelColorValue(token), color: 'var(--yp-text-inverse)' } : {}
}

function priorityPresentation(priority: string | null): { label: string; tone: string } {
  if (!priority) return { label: '—', tone: 'empty' }
  const option = props.priorityOptions.find(item => item.code === priority)
  const tones: Record<string, string> = {
    RED: 'urgent', MAGENTA: 'urgent', ORANGE: 'high', AMBER: 'high', GREEN: 'low',
    LIME: 'low', TEAL: 'medium', CYAN: 'medium', BLUE: 'low', INDIGO: 'medium',
    PURPLE: 'medium', GRAY: 'empty',
  }
  return { label: option?.displayName ?? priority, tone: tones[option?.colorToken ?? ''] ?? 'empty' }
}

function priorityStyle(priority: string | null): CSSProperties {
  const token = props.priorityOptions.find(item => item.code === priority)?.colorToken
  return token ? { backgroundColor: workItemLabelColorValue(token), color: 'var(--yp-text-inverse)' } : {}
}

function formatDate(value: Date | string | null): string {
  return value ? new Date(value).toISOString().slice(0, 10) : '—'
}

function formatTime(value: Date | string): string {
  return new Date(value).toLocaleString('zh-CN')
}

function isOverdue(value: Date | string | null, statusCode: string): boolean {
  if (!value) return false
  const category = props.workflowStatuses.find(item => item.statusCode === statusCode)?.statusCategory
  if (category === 'DONE' || category === 'CANCELED') return false
  const due = new Date(value)
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return !Number.isNaN(due.getTime()) && due < today
}

function apiDate(value: string | null): Date | null {
  return value ? new Date(`${value}T00:00:00.000Z`) : null
}

function todayValue(): string {
  const today = new Date()
  return `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
}

function sortDirection(key: ProjectWorkItemSubitemColumn['key']): 'ASC' | 'DESC' | undefined {
  return props.sortRules.find(rule => rule.field === sortFieldByColumn[key])?.direction
}

function applySort(key: ProjectWorkItemSubitemColumn['key']): void {
  if (savingSortOrder.value) return
  const field = sortFieldByColumn[key]
  const next = props.sortRules.map(rule => ({ ...rule }))
  const index = next.findIndex(rule => rule.field === field)
  if (index >= 0) next[index] = { field, direction: next[index]!.direction === 'ASC' ? 'DESC' : 'ASC' }
  else if (next.length < 3) next.push({ field, direction: 'ASC' })
  else next[next.length - 1] = { field, direction: 'ASC' }
  emit('sortChange', next)
}

function clearSort(key: ProjectWorkItemSubitemColumn['key']): void {
  emit('sortChange', props.sortRules.filter(rule => rule.field !== sortFieldByColumn[key]))
}

async function saveSortedOrder(): Promise<void> {
  if (!props.sortRules.length || savingSortOrder.value || props.items.length < 2) return
  const csrf = readCsrfToken()
  if (!csrf) { quickError.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  savingSortOrder.value = true
  try {
    for (let index = 1; index < props.items.length; index += 1) {
      const item = props.items[index]!
      const updated = await workItemsApi.moveWorkItemSubitemOrder({
        parentWorkItemId: props.parent.id,
        subitemId: item.id,
        xXSRFTOKEN: csrf,
        ifMatch: item.etag,
        idempotencyKey: globalThis.crypto.randomUUID(),
        projectWorkItemOrderMoveRequest: {
          previousVisibleWorkItemId: props.items[index - 1]!.id,
          nextVisibleWorkItemId: null,
        },
      })
      emit('updated', item.id, updated)
    }
    emit('sortChange', [])
    ElMessage.success(`已保存 ${props.items.length} 个子项的当前顺序`)
  } catch (reason) {
    quickError.value = await toApiProblem(reason)
    emit('retry')
  } finally {
    savingSortOrder.value = false
  }
}

async function createQuick(continueAdding: boolean): Promise<void> {
  if (quickCreating.value || !quickTitle.value.trim() || !quickContentId.value) return
  const csrf = readCsrfToken()
  if (!csrf) { quickError.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  quickCreating.value = true
  quickError.value = undefined
  try {
    await workItemsApi.createWorkItemSubitem({
      parentWorkItemId: props.parent.id,
      xXSRFTOKEN: csrf,
      idempotencyKey: globalThis.crypto.randomUUID(),
      workItemSubitemCreateRequest: {
        contentId: quickContentId.value,
        title: quickTitle.value.trim(),
        priority: null,
        assigneeUserId: null,
        description: null,
        notes: null,
        timelineStartDate: null,
        timelineEndDate: null,
        dueDate: null,
      },
    })
    emit('created', props.parent)
    if (continueAdding) quickTitle.value = ''
    else { quickOpen.value = false; quickTitle.value = '' }
  } catch (reason) {
    quickError.value = await toApiProblem(reason)
  } finally {
    quickCreating.value = false
  }
}

function onQuickKeydown(rawEvent: Event | KeyboardEvent): void {
  const event = rawEvent as KeyboardEvent
  if (event.key !== 'Enter') return
  event.preventDefault()
  void createQuick(event.shiftKey)
}

function row(raw: unknown): ProjectWorkItemListItem {
  return raw as ProjectWorkItemListItem
}

function openItem(raw: unknown, tab: 'details' | 'discussion'): void {
  emit('openDetail', row(raw), tab)
}

function patchItem(raw: unknown, field: 'assignee' | 'priority' | 'dueDate',
  value: string | Date | null): void {
  emit('patch', row(raw), field, value)
}

function transitionItem(raw: unknown, statusCode: string): void {
  emit('transition', row(raw), statusCode)
}

function onRowDragStart(raw: unknown): void {
  const item = row(raw)
  if (!props.sortRules.length && item.capabilities.canMoveInProjectOrder) draggingItem.value = item
}

async function dropBefore(raw: unknown): Promise<void> {
  const target = row(raw)
  const moving = draggingItem.value
  draggingItem.value = undefined
  if (!moving || moving.id === target.id) return
  const targetIndex = props.items.findIndex(item => item.id === target.id)
  const remaining = props.items.filter(item => item.id !== moving.id)
  const insertIndex = Math.max(0, remaining.findIndex(item => item.id === target.id))
  remaining.splice(insertIndex, 0, moving)
  const nextIndex = remaining.findIndex(item => item.id === moving.id)
  const csrf = readCsrfToken()
  if (!csrf) { quickError.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  try {
    const updated = await workItemsApi.moveWorkItemSubitemOrder({
      parentWorkItemId: props.parent.id,
      subitemId: moving.id,
      xXSRFTOKEN: csrf,
      ifMatch: moving.etag,
      idempotencyKey: globalThis.crypto.randomUUID(),
      projectWorkItemOrderMoveRequest: {
        previousVisibleWorkItemId: remaining[nextIndex - 1]?.id ?? null,
        nextVisibleWorkItemId: remaining[nextIndex + 1]?.id ?? null,
      },
    })
    emit('updated', moving.id, updated)
    emit('retry')
    if (targetIndex >= 0) ElMessage.success('子项顺序已更新')
  } catch (reason) {
    quickError.value = await toApiProblem(reason)
    emit('retry')
  }
}

function onColumnDrop(target: string): void {
  if (columnDraggingKey.value && columnDraggingKey.value !== target) {
    emit('moveColumn', columnDraggingKey.value, target)
  }
  columnDraggingKey.value = undefined
}
</script>

<template>
  <section class="subitem-table-shell" :aria-label="`${parent.title} 的子项`">
    <inline-problem v-if="error" :problem="error" @retry="$emit('retry')" />
    <inline-problem v-if="quickError" :problem="quickError" />
    <el-table
      v-loading="loading"
      :data="items"
      row-key="id"
      class="monday-subitem-table"
      empty-text="暂无子项，可在下方快速添加"
      border
      @selection-change="$emit('selectionChange', parent.id, $event)"
      @header-dragend="(newWidth: number, oldWidth: number, column: { label: string }) => $emit('headerResize', newWidth, oldWidth, column)"
    >
      <el-table-column type="selection" width="48" reserve-selection fixed />
      <el-table-column
        v-for="column in columns"
        :key="column.key"
        :label="column.label"
        :column-key="column.key"
        :width="columnWidths[column.key]"
        :fixed="column.key === 'title'"
        align="center"
        resizable
      >
        <template #header>
          <div
            :draggable="column.key !== 'title'"
            @dragstart="columnDraggingKey = column.key"
            @dragover.prevent
            @drop.prevent="onColumnDrop(column.key)"
          >
            <monday-column-quick-sort
              :label="column.label"
              :direction="sortDirection(column.key)"
              :saving="savingSortOrder"
              @sort="applySort(column.key)"
              @clear="clearSort(column.key)"
              @save="saveSortedOrder"
            />
          </div>
        </template>
        <template #default="scope">
          <div
            v-if="column.key === 'title'"
            class="subitem-title-cell"
            :draggable="scope.row.capabilities.canMoveInProjectOrder && !sortRules.length"
            @dragstart="onRowDragStart(scope.row)"
            @dragend="draggingItem = undefined"
            @dragover.prevent
            @drop.prevent="dropBefore(scope.row)"
          >
            <button class="subitem-link" @click.stop="openItem(scope.row, 'details')">
              {{ scope.row.title }}
            </button>
            <button class="subitem-discussion" aria-label="打开协作讨论" @click.stop="openItem(scope.row, 'discussion')">
              <svg width="17" height="17" viewBox="0 0 24 24" fill="none">
                <path d="M12 21C16.9706 21 21 16.9706 21 12C21 7.02944 16.9706 3 12 3C7.02944 3 3 7.02944 3 12C3 13.8214 3.54139 15.5165 4.4741 16.9366L3.25 21L7.54583 19.8665C8.89531 20.5902 10.4079 21 12 21Z" stroke="currentColor" stroke-width="1.6" />
                <path d="M12 8.5V15.5M8.5 12H15.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
              </svg>
            </button>
          </div>

          <el-popover v-else-if="column.key === 'assignee'" placement="bottom" :width="360" trigger="click" @show="assigneeSearch = ''">
            <template #reference>
              <button class="subitem-cell-button" :disabled="editingCell">
                <yp-assignee :user-id="scope.row.assigneeUserId" :display-name="scope.row.assigneeDisplayName" :show-name="false" size="table" />
              </button>
            </template>
            <div class="subitem-popover-stack">
              <el-input v-model="assigneeSearch" clearable placeholder="搜索项目成员" />
              <button class="subitem-option" @click="patchItem(scope.row, 'assignee', null)">清空处理人</button>
              <button v-for="member in filteredMembers" :key="member.userId" class="subitem-option" @click="patchItem(scope.row, 'assignee', member.userId)">
                <yp-assignee :user-id="member.userId" :display-name="member.displayName" />
              </button>
            </div>
          </el-popover>

          <el-popover v-else-if="column.key === 'status'" placement="bottom" width="auto" trigger="click">
            <template #reference>
              <button class="subitem-block-cell" :style="statusStyle(scope.row.statusCode)" :disabled="editingCell">{{ statusLabel(scope.row.statusCode) }}</button>
            </template>
            <work-item-label-popover-content
              kind="status"
              :project-id="projectId"
              :catalog="labelCatalog"
              :workflow-statuses="workflowStatuses.filter(item => item.active || item.statusCode === scope.row.statusCode)"
              :current-value="scope.row.statusCode"
              :can-manage="Boolean(labelCatalog?.canManage)"
              :available-transitions="scope.row.capabilities.availableTransitions"
              @select-status="transitionItem(scope.row, $event)"
            />
          </el-popover>

          <el-popover v-else-if="column.key === 'priority'" placement="bottom" width="auto" trigger="click">
            <template #reference>
              <button class="subitem-block-cell" :class="`subitem-priority--${priorityPresentation(scope.row.priority).tone}`" :style="priorityStyle(scope.row.priority)" :disabled="editingCell">
                {{ priorityPresentation(scope.row.priority).label }}
              </button>
            </template>
            <work-item-label-popover-content
              kind="priority"
              :project-id="projectId"
              :catalog="labelCatalog"
              :priority-options="priorityOptions.filter(item => item.active)"
              :current-value="scope.row.priority"
              :can-manage="Boolean(labelCatalog?.canManage)"
              @select-priority="patchItem(scope.row, 'priority', $event)"
            />
          </el-popover>

          <span v-else-if="column.key === 'content'">{{ scope.row.contentName }}</span>

          <el-popover v-else-if="column.key === 'dueDate'" placement="bottom" :width="300" trigger="click">
            <template #reference>
              <button class="subitem-cell-button" :class="{ 'subitem-due--overdue': isOverdue(scope.row.dueDate, scope.row.statusCode) }" :disabled="editingCell">
                {{ formatDate(scope.row.dueDate) }}
              </button>
            </template>
            <div class="subitem-date-editor">
              <el-button @click="patchItem(scope.row, 'dueDate', apiDate(todayValue()))">Today</el-button>
              <el-button text @click="patchItem(scope.row, 'dueDate', null)">清空</el-button>
              <el-date-picker :model-value="scope.row.dueDate ? formatDate(scope.row.dueDate) : null" type="date" value-format="YYYY-MM-DD" @update:model-value="patchItem(scope.row, 'dueDate', apiDate($event as string | null))" />
            </div>
          </el-popover>

          <span v-else-if="column.key === 'updatedAt'" class="subitem-timestamp">{{ formatTime(scope.row.updatedAt) }}</span>
        </template>
      </el-table-column>

      <template #append>
        <div v-if="quickOpen" class="subitem-quick-row">
          <el-input v-model="quickTitle" maxlength="300" :disabled="quickCreating" placeholder="输入子项名称；Enter 创建，Shift+Enter 创建后继续" @keydown="onQuickKeydown" />
          <el-select v-model="quickContentId" filterable :disabled="quickCreating" placeholder="选择工作项类别">
            <el-option v-for="content in activeContents" :key="content.id" :label="content.name" :value="content.id" />
          </el-select>
          <el-button type="primary" :loading="quickCreating" :disabled="!quickTitle.trim() || !quickContentId" @click="createQuick(false)">添加</el-button>
        </div>
        <button v-else class="subitem-add" :disabled="!canCreate" @click="quickOpen = true; quickContentId = parent.contentId">
          <el-icon><plus /></el-icon><span>添加子项</span>
        </button>
      </template>
    </el-table>
  </section>
</template>

<style scoped>
.subitem-table-shell {
  position: relative;
  margin-left: 32px;
  border-left: 3px solid var(--yp-link);
  background: var(--yp-bg-surface);
}
.monday-subitem-table { width: calc(100% - 3px); --el-table-row-hover-bg-color: var(--yp-bg-sunken); }
:deep(.monday-subitem-table .el-table__header tr),
:deep(.monday-subitem-table .el-table__body tr) { height: 36px; }
:deep(.monday-subitem-table .el-table__cell) { box-sizing: border-box; height: 36px; padding: 0; border-color: var(--yp-border-subtle); }
:deep(.monday-subitem-table .cell) { height: 34px; padding: 0 8px; display: flex; align-items: center; justify-content: center; }
:deep(.monday-subitem-table th .cell) { font-size: 13px; color: var(--yp-text-secondary); }
.subitem-title-cell { width: 100%; height: 34px; display: flex; align-items: center; min-width: 0; }
.subitem-link { flex: 1; min-width: 0; text-align: left; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; border: 0; background: transparent; color: var(--yp-text-primary); cursor: pointer; }
.subitem-link:hover { color: var(--yp-link); text-decoration: underline; }
.subitem-discussion { width: 32px; height: 32px; border: 0; background: transparent; color: var(--yp-text-secondary); cursor: pointer; }
.subitem-cell-button, .subitem-block-cell { width: 100%; height: 100%; border: 0; background: transparent; color: inherit; cursor: pointer; }
.subitem-block-cell { color: var(--yp-text-inverse); }
.subitem-popover-stack { display: grid; gap: 6px; }
.subitem-option { min-height: 34px; border: 0; background: transparent; text-align: left; cursor: pointer; }
.subitem-option:hover { background: var(--yp-bg-sunken); }
.subitem-date-editor { display: flex; flex-wrap: wrap; gap: 8px; }
.subitem-due--overdue { color: var(--yp-status-red); font-weight: 600; }
.subitem-timestamp { color: var(--yp-text-secondary); font-size: 12px; }
.subitem-quick-row { min-height: 44px; display: grid; grid-template-columns: minmax(240px, 1fr) 220px auto; gap: 8px; align-items: center; padding: 4px 12px 4px 48px; border-top: 1px solid var(--yp-border-subtle); }
.subitem-add { width: 100%; height: 36px; display: flex; align-items: center; gap: 8px; padding-left: 56px; border: 0; border-top: 1px solid var(--yp-border-subtle); background: transparent; color: var(--yp-text-secondary); cursor: pointer; }
.subitem-add:hover:not(:disabled) { color: var(--yp-link); background: var(--yp-bg-sunken); }
.subitem-add:disabled { cursor: not-allowed; opacity: .55; }
@media (max-width: 900px) {
  .subitem-quick-row { grid-template-columns: 1fr; padding-left: 12px; }
}
</style>
