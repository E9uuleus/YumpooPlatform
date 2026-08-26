<script setup lang="ts">
import {
  WorkItemLabelColorToken,
  readCsrfToken,
  type WorkItemLabelCatalog,
  type WorkItemPriorityLabel,
  type WorkItemStatusLabel,
} from '@yumpoo/api-client'
import { ElDropdown, ElDropdownItem, ElDropdownMenu, ElInput, ElMessage, ElPopover } from 'element-plus'
import { computed, nextTick, ref } from 'vue'
import { workItemsApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'

type Label = WorkItemStatusLabel | WorkItemPriorityLabel

interface DraftLabel {
  code: string
  persistedCode: string | null
  displayName: string
  colorToken: WorkItemLabelColorToken
  active: boolean
  sortOrder: number
  inUse: boolean
  protectedLabel: boolean
}

interface WorkflowStatusOption {
  statusCode: string
  displayName: string
  colorToken?: WorkItemLabelColorToken
  statusCategory?: string
  active?: boolean
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
  kind: 'status' | 'priority'
  projectId: string
  catalog?: WorkItemLabelCatalog | undefined
  workflowStatuses?: WorkflowStatusOption[] | undefined
  priorityOptions?: PriorityOption[] | undefined
  currentValue?: string | null | undefined
  canManage?: boolean | undefined
  availableTransitions?: Array<{ toStatus: string }> | undefined
}>()

const emit = defineEmits<{
  selectStatus: [statusCode: string]
  selectPriority: [priorityCode: string | null]
  updated: [catalog: WorkItemLabelCatalog]
}>()

const mode = ref<'select' | 'edit'>('select')
const isJellyWobble = ref(false)
const saving = ref('')
const draggedIndex = ref<number | null>(null)
const inputRefs = ref<InstanceType<typeof ElInput>[]>([])
const draftLabels = ref<DraftLabel[]>([])
const deletedCodes = ref(new Set<string>())
let draftSequence = 0

const colorPalette = [
  { token: WorkItemLabelColorToken.Lime, label: '草绿', color: 'var(--yp-label-lime)' },
  { token: WorkItemLabelColorToken.Amber, label: '琥珀黄', color: 'var(--yp-label-amber)' },
  { token: WorkItemLabelColorToken.Orange, label: '橙色', color: 'var(--yp-label-orange)' },
  { token: WorkItemLabelColorToken.Red, label: '红色', color: 'var(--yp-label-red)' },
  { token: WorkItemLabelColorToken.Magenta, label: '品红', color: 'var(--yp-label-magenta)' },
  { token: WorkItemLabelColorToken.Purple, label: '紫色', color: 'var(--yp-label-purple)' },
  { token: WorkItemLabelColorToken.Indigo, label: '靛蓝', color: 'var(--yp-label-indigo)' },
  { token: WorkItemLabelColorToken.Blue, label: '蓝色', color: 'var(--yp-label-blue)' },
  { token: WorkItemLabelColorToken.Cyan, label: '青色', color: 'var(--yp-label-cyan)' },
  { token: WorkItemLabelColorToken.Teal, label: '墨绿', color: 'var(--yp-label-teal)' },
  { token: WorkItemLabelColorToken.Green, label: '绿色', color: 'var(--yp-label-green)' },
  { token: WorkItemLabelColorToken.Gray, label: '灰色', color: 'var(--yp-label-gray)' },
]

const colorValues: Record<string, string> = {
  GREEN: 'var(--yp-label-green)',
  TEAL: 'var(--yp-label-teal)',
  BLUE: 'var(--yp-label-blue)',
  INDIGO: 'var(--yp-label-indigo)',
  PURPLE: 'var(--yp-label-purple)',
  MAGENTA: 'var(--yp-label-magenta)',
  RED: 'var(--yp-label-red)',
  ORANGE: 'var(--yp-label-orange)',
  AMBER: 'var(--yp-label-amber)',
  LIME: 'var(--yp-label-lime)',
  CYAN: 'var(--yp-label-cyan)',
  GRAY: 'var(--yp-label-gray)',
}

function colorStyle(token?: WorkItemLabelColorToken | string): Record<string, string> {
  if (!token) return { backgroundColor: 'var(--yp-label-gray)' }
  return { backgroundColor: colorValues[token] ?? 'var(--yp-label-gray)' }
}

const labels = computed(() => draftLabels.value)

const ITEMS_PER_COLUMN = 6

const labelColumns = computed(() => {
  const all = labels.value
  const cols: DraftLabel[][] = []
  if (all.length === 0) {
    cols.push([])
    return cols
  }
  for (let i = 0; i < all.length; i += ITEMS_PER_COLUMN) {
    cols.push(all.slice(i, i + ITEMS_PER_COLUMN))
  }
  return cols
})

const isNewButtonOnSeparateColumn = computed(() => {
  const lastCol = labelColumns.value[labelColumns.value.length - 1]
  return lastCol && lastCol.length >= ITEMS_PER_COLUMN
})

function switchToEdit(): void {
  const source = props.kind === 'status' ? props.catalog?.statuses : props.catalog?.priorities
  draftLabels.value = [...(source ?? [])]
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map(label => ({
      code: label.code,
      persistedCode: label.code,
      displayName: label.displayName,
      colorToken: label.colorToken,
      active: label.active,
      sortOrder: label.sortOrder,
      inUse: label.inUse,
      protectedLabel: protectedLabel(label),
    }))
  deletedCodes.value = new Set()
  mode.value = 'edit'
  isJellyWobble.value = true
  setTimeout(() => {
    isJellyWobble.value = false
  }, 340)
}

function resetEditor(): void {
  mode.value = 'select'
  isJellyWobble.value = false
  draggedIndex.value = null
  draftLabels.value = []
  deletedCodes.value = new Set()
}

defineExpose({ resetEditor })

function protectedLabel(label: Label | DraftLabel): boolean {
  return 'protectedLabel' in label && Boolean(label.protectedLabel)
}

function isTransitionAllowed(statusCode: string): boolean {
  if (props.currentValue === statusCode) return true
  return Boolean(props.availableTransitions?.some(item => item.toStatus === statusCode))
}

function onSelectStatus(statusCode: string): void {
  if (!isTransitionAllowed(statusCode)) return
  emit('selectStatus', statusCode)
}

function onSelectPriority(code: string | null): void {
  emit('selectPriority', code)
}

async function createLabel(): Promise<void> {
  if (saving.value) return
  const existingNames = new Set(draftLabels.value.map(label => label.displayName))
  let defaultName = '新标签'
  let suffix = 2
  while (existingNames.has(defaultName)) defaultName = `新标签 ${suffix++}`

  draftSequence += 1
  draftLabels.value.push({
    code: `draft-${draftSequence}`,
    persistedCode: null,
    displayName: defaultName,
    colorToken: WorkItemLabelColorToken.Blue,
    active: true,
    sortOrder: draftLabels.value.length + 1,
    inUse: false,
    protectedLabel: false,
  })
  await nextTick()
  const lastInput = inputRefs.value[inputRefs.value.length - 1]
  lastInput?.focus()
  lastInput?.select()
}

function updateLabel(label: DraftLabel, patch: {
  displayName?: string
  colorToken?: WorkItemLabelColorToken
  active?: boolean
  sortOrder?: number
}): void {
  if (saving.value) return
  draftLabels.value = draftLabels.value.map(item => item.code === label.code ? { ...item, ...patch } : item)
}

function deleteLabel(label: DraftLabel): void {
  if (saving.value || label.inUse || label.protectedLabel) return
  if (label.persistedCode) {
    const nextDeleted = new Set(deletedCodes.value)
    nextDeleted.add(label.persistedCode)
    deletedCodes.value = nextDeleted
  }
  draftLabels.value = draftLabels.value
    .filter(item => item.code !== label.code)
    .map((item, index) => ({ ...item, sortOrder: index + 1 }))
}

function catalogLabels(catalog: WorkItemLabelCatalog): Label[] {
  return props.kind === 'status' ? catalog.statuses : catalog.priorities
}

async function persistDelete(catalog: WorkItemLabelCatalog, code: string, csrf: string): Promise<WorkItemLabelCatalog> {
  const common = { projectId: props.projectId, code, xXSRFTOKEN: csrf, ifMatch: catalog.etag }
  return props.kind === 'status'
    ? workItemsApi.deleteProjectWorkItemStatusLabel(common)
    : workItemsApi.deleteProjectWorkItemPriorityLabel(common)
}

async function persistCreate(catalog: WorkItemLabelCatalog, label: DraftLabel, csrf: string): Promise<WorkItemLabelCatalog> {
  const common = {
    projectId: props.projectId,
    xXSRFTOKEN: csrf,
    ifMatch: catalog.etag,
    workItemLabelCreateRequest: { displayName: label.displayName.trim(), colorToken: label.colorToken },
  }
  return props.kind === 'status'
    ? workItemsApi.createProjectWorkItemStatusLabel(common)
    : workItemsApi.createProjectWorkItemPriorityLabel(common)
}

async function persistUpdate(
  catalog: WorkItemLabelCatalog,
  label: DraftLabel,
  persisted: Label,
  sortOrder: number,
  csrf: string,
): Promise<WorkItemLabelCatalog> {
  const displayName = label.displayName.trim()
  const common = {
    projectId: props.projectId,
    code: persisted.code,
    xXSRFTOKEN: csrf,
    ifMatch: catalog.etag,
    workItemLabelUpdateRequest: {
      displayName: displayName !== persisted.displayName ? displayName : null,
      colorToken: label.colorToken !== persisted.colorToken ? label.colorToken : null,
      active: label.active !== persisted.active ? label.active : null,
      sortOrder: sortOrder !== persisted.sortOrder ? sortOrder : null,
    },
  }
  return props.kind === 'status'
    ? workItemsApi.updateProjectWorkItemStatusLabel(common)
    : workItemsApi.updateProjectWorkItemPriorityLabel(common)
}

const canApply = computed(() => !saving.value && draftLabels.value.every(label => Boolean(label.displayName.trim())))

async function applyChanges(): Promise<void> {
  if (!props.catalog || !canApply.value) return
  const csrf = readCsrfToken()
  if (!csrf) {
    ElMessage.error('缺少 CSRF 凭据，请刷新后重试。')
    return
  }

  saving.value = 'apply'
  let currentCatalog = props.catalog
  try {
    for (const code of deletedCodes.value) currentCatalog = await persistDelete(currentCatalog, code, csrf)

    for (const label of draftLabels.value.filter(item => !item.persistedCode)) {
      const previousCodes = new Set(catalogLabels(currentCatalog).map(item => item.code))
      currentCatalog = await persistCreate(currentCatalog, label, csrf)
      const created = catalogLabels(currentCatalog).find(item => !previousCodes.has(item.code))
      if (!created) throw new Error('新增标签后未找到返回的标签。')
      label.persistedCode = created.code
    }

    for (const [index, label] of draftLabels.value.entries()) {
      const persisted = catalogLabels(currentCatalog).find(item => item.code === label.persistedCode)
      if (!persisted) continue
      const displayName = label.displayName.trim()
      const changed = displayName !== persisted.displayName
        || label.colorToken !== persisted.colorToken
        || label.active !== persisted.active
        || index + 1 !== persisted.sortOrder
      if (changed) currentCatalog = await persistUpdate(currentCatalog, label, persisted, index + 1, csrf)
    }

    emit('updated', currentCatalog)
    resetEditor()
  } catch (reason) {
    if (currentCatalog !== props.catalog) emit('updated', currentCatalog)
    resetEditor()
    ElMessage.error(problemMessage(await toApiProblem(reason)))
  } finally {
    saving.value = ''
  }
}

function onDragStart(event: DragEvent, index: number): void {
  draggedIndex.value = index
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', String(index))
  }
}

function onDragOver(event: DragEvent): void {
  event.preventDefault()
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'move'
  }
}

function onDrop(targetIndex: number): void {
  const sourceIndex = draggedIndex.value
  if (sourceIndex === null || sourceIndex === targetIndex) {
    draggedIndex.value = null
    return
  }
  draggedIndex.value = null
  const next = [...draftLabels.value]
  const [sourceLabel] = next.splice(sourceIndex, 1)
  if (!sourceLabel) return
  next.splice(targetIndex, 0, sourceLabel)
  draftLabels.value = next.map((item, index) => ({ ...item, sortOrder: index + 1 }))
}
</script>

<template>
  <div class="yp-label-popover-root" :class="[mode === 'edit' ? 'yp-label-popover-root--edit' : 'yp-label-popover-root--select']">
    <!-- 1. 选择模式 (Select Mode) -->
    <div v-if="mode === 'select'" class="label-select-view">
      <div class="label-select-list">
        <!-- 状态列表 -->
        <template v-if="kind === 'status'">
          <button
            v-for="status in (workflowStatuses ?? [])"
            :key="status.statusCode"
            class="status-option-pill"
            :style="colorStyle(status.colorToken)"
            :disabled="!isTransitionAllowed(status.statusCode)"
            @click="onSelectStatus(status.statusCode)"
          >
            <span>{{ status.displayName }}</span>
          </button>
        </template>

        <!-- 优先级列表 -->
        <template v-else>
          <button
            v-for="priority in (priorityOptions ?? [])"
            :key="priority.code"
            class="priority-option-pill"
            :style="colorStyle(priority.colorToken)"
            @click="onSelectPriority(priority.code)"
          >
            <span>{{ priority.displayName }}</span>
          </button>
          <button
            class="priority-option-pill priority-option-pill--empty"
            @click="onSelectPriority(null)"
          >
            <span>清空</span>
          </button>
        </template>
      </div>

      <!-- 编辑按钮 -->
      <button
        v-if="canManage"
        class="edit-action-btn"
        type="button"
        @click="switchToEdit"
      >
        <svg class="edit-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
          <path d="M11.5 2.5l2 2L5 13H3v-2L11.5 2.5z" />
        </svg>
        <span>编辑</span>
      </button>
    </div>

    <!-- 2. 编辑模式 (Edit Mode) 带布丁抖动与自适应多列 -->
    <div
      v-else
      class="label-edit-view"
      :class="{ 'jelly-wobble': isJellyWobble }"
    >
      <div class="label-columns-container">
        <!-- 现有列 -->
        <div
          v-for="(col, colIdx) in labelColumns"
          :key="colIdx"
          class="label-column"
        >
          <div
            v-for="(label, rowIdx) in col"
            :key="label.code"
            class="label-edit-row"
            draggable="true"
            @dragstart="onDragStart($event, colIdx * ITEMS_PER_COLUMN + rowIdx)"
            @dragover="onDragOver($event)"
            @drop="onDrop(colIdx * ITEMS_PER_COLUMN + rowIdx)"
          >
            <!-- 左侧 6 点拖拽手柄（默认隐藏，鼠标悬停行时显示） -->
            <button
              class="drag-handle-btn"
              type="button"
              title="按住拖动调整顺序"
              tabindex="-1"
            >
              <svg viewBox="0 0 16 16" width="10" height="14" fill="currentColor">
                <circle cx="4" cy="3" r="1.3" />
                <circle cx="12" cy="3" r="1.3" />
                <circle cx="4" cy="8" r="1.3" />
                <circle cx="12" cy="8" r="1.3" />
                <circle cx="4" cy="13" r="1.3" />
                <circle cx="12" cy="13" r="1.3" />
              </svg>
            </button>

            <!-- 中间卡片容器 -->
            <div
              class="label-card-box"
              :class="{ 'label-card-box--inactive': !label.active }"
            >
              <!-- 颜色方块与 Popover -->
              <el-popover
                placement="bottom-start"
                :width="156"
                trigger="click"
                popper-class="color-picker-popover"
              >
                <template #reference>
                  <button
                    class="color-square-btn"
                    :style="colorStyle(label.colorToken)"
                    type="button"
                    title="选择颜色"
                  >
                    <!-- 油漆桶 SVG 图标 -->
                    <svg viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="var(--yp-text-inverse)" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                      <path d="M12.5 5.5l-4-4-5.5 5.5a1.41 1.41 0 000 2l3.5 3.5a1.41 1.41 0 002 0l4-4z" />
                      <path d="M2.5 10l5.5-5.5" />
                      <path d="M13.5 11c0 1.1-.9 2-2 2s-2-.9-2-2c0-.8 2-3 2-3s2 2.2 2 3z" fill="var(--yp-text-inverse)" />
                    </svg>
                  </button>
                </template>

                <!-- 4 列颜色选择器面板 -->
                <div class="color-palette-grid">
                  <button
                    v-for="colorItem in colorPalette"
                    :key="colorItem.token"
                    class="color-swatch-item"
                    :class="{ 'color-swatch-item--selected': colorItem.token === label.colorToken }"
                    :style="{ backgroundColor: colorItem.color }"
                    :title="colorItem.label"
                    type="button"
                    @click="updateLabel(label, { colorToken: colorItem.token })"
                  />
                </div>
              </el-popover>

              <!-- 名称输入框 -->
              <el-input
                ref="inputRefs"
                class="label-name-input"
                :model-value="label.displayName"
                maxlength="80"
                :disabled="Boolean(saving)"
                placeholder="标签名称"
                @update:model-value="updateLabel(label, { displayName: String($event) })"
              />
            </div>

            <!-- 右侧更多扩展按钮（默认隐藏，鼠标悬停行时显示） -->
            <el-dropdown trigger="click" placement="bottom-end">
              <button class="more-options-btn" type="button" title="更多选项">
                <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor">
                  <circle cx="3" cy="8" r="1.5" />
                  <circle cx="8" cy="8" r="1.5" />
                  <circle cx="13" cy="8" r="1.5" />
                </svg>
              </button>
              <template #dropdown>
                <el-dropdown-menu class="label-more-dropdown-menu">
                  <el-dropdown-item
                    :disabled="protectedLabel(label) || Boolean(saving)"
                    @click="updateLabel(label, { active: !label.active })"
                  >
                    <span class="dropdown-item-content">
                      <svg viewBox="0 0 16 16" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.6">
                        <circle cx="8" cy="8" r="6" />
                        <line x1="3.8" y1="3.8" x2="12.2" y2="12.2" />
                      </svg>
                      <span>{{ label.active ? '停用标签' : '启用标签' }}</span>
                    </span>
                  </el-dropdown-item>
                  <el-dropdown-item
                    :disabled="Boolean(label.inUse) || protectedLabel(label) || Boolean(saving)"
                    @click="deleteLabel(label)"
                  >
                    <span class="dropdown-item-content dropdown-item-content--danger">
                      <svg viewBox="0 0 16 16" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M2.5 4.5h11M5.5 4.5V3a1 1 0 011-1h3a1 1 0 011 1v1.5M4 4.5l.8 9a1.5 1.5 0 001.5 1.5h3.4a1.5 1.5 0 001.5-1.5l.8-9" />
                      </svg>
                      <span>删除标签</span>
                    </span>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>

          <!-- 若当前列未满且为最后一列，则在列尾放置“新增标签” -->
          <div
            v-if="colIdx === labelColumns.length - 1 && !isNewButtonOnSeparateColumn"
            class="new-label-row"
          >
            <button
              class="new-label-action-btn"
              type="button"
              :disabled="Boolean(saving)"
              @click="createLabel"
            >
              <span>+ 新增标签</span>
            </button>
          </div>
        </div>

        <!-- 若标签数量恰好满列，则新建独立列放置“新增标签” -->
        <div v-if="isNewButtonOnSeparateColumn" class="label-column">
          <div class="new-label-row">
            <button
              class="new-label-action-btn"
              type="button"
              :disabled="Boolean(saving)"
              @click="createLabel"
            >
              <span>+ 新增标签</span>
            </button>
          </div>
        </div>
      </div>

      <!-- 底部居中“应用”按钮 -->
      <div class="apply-action-bar">
        <button
          class="apply-action-btn"
          type="button"
          :disabled="!canApply"
          @click="applyChanges"
        >
          {{ saving === 'apply' ? '应用中…' : '应用' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.yp-label-popover-root {
  display: block;
  user-select: none;
  font-family: var(--yp-font-family);
}

.yp-label-popover-root--select {
  padding: 16px;
  width: 148px;
  box-sizing: border-box;
}

.yp-label-popover-root--edit {
  padding: 16px;
  width: auto;
  box-sizing: border-box;
}

/* 1. 选择列表样式 */
.label-select-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.status-option-pill,
.priority-option-pill {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 34px;
  padding: 0 8px;
  border: 0;
  border-radius: var(--yp-radius-xs);
  color: var(--yp-text-inverse);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  box-sizing: border-box;
  text-align: center;
  transition: transform 120ms ease, filter 120ms ease, opacity 120ms ease;
}

.status-option-pill:hover:not(:disabled),
.priority-option-pill:hover:not(:disabled) {
  filter: brightness(0.93);
  transform: translateY(-1px);
}

.status-option-pill:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.priority-option-pill--empty {
  background-color: var(--yp-priority-empty) !important;
  color: var(--yp-priority-empty-foreground) !important;
}

/* 编辑按钮 */
.edit-action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
  height: 32px;
  margin-top: 12px;
  border: 0;
  border-radius: var(--yp-radius-xs);
  background: transparent;
  color: var(--yp-text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: background 150ms ease, color 150ms ease;
}

.edit-action-btn:hover,
.edit-action-btn:focus-visible {
  background: var(--yp-bg-hover);
  color: var(--yp-text-primary);
}

.edit-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}

/* 2. 编辑模式与布丁果冻抖动动画 */
@keyframes puddingWobble {
  0% {
    opacity: 0;
    transform: scale(0.92) translateY(2px);
  }
  35% {
    opacity: 1;
    transform: scale(1.03) translateX(-3px);
  }
  55% {
    transform: scale(0.98) translateX(2px);
  }
  75% {
    transform: scale(1.01) translateX(-1px);
  }
  100% {
    opacity: 1;
    transform: scale(1) translateX(0) translateY(0);
  }
}

.label-edit-view.jelly-wobble {
  animation: puddingWobble 340ms cubic-bezier(0.25, 1, 0.5, 1) forwards;
  transform-origin: top center;
}

/* 多列容器 */
.label-columns-container {
  display: flex;
  flex-direction: row;
  gap: 10px;
  align-items: flex-start;
}

.label-column {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 156px;
  flex-shrink: 0;
}

.label-edit-row {
  display: flex;
  align-items: center;
  gap: 3px;
  width: 100%;
  cursor: pointer;
}

/* 拖拽手柄：默认隐藏，鼠标悬停在行上或聚焦时显示 */
.drag-handle-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 30px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--yp-text-muted);
  cursor: grab;
  flex-shrink: 0;
  opacity: 0;
  visibility: hidden;
  transition: opacity 150ms ease, visibility 150ms ease;
}

.drag-handle-btn:active {
  cursor: grabbing;
}

.label-edit-row:hover .drag-handle-btn,
.label-edit-row:focus-within .drag-handle-btn {
  opacity: 1;
  visibility: visible;
}

/* 卡片容器 */
.label-card-box {
  display: flex;
  align-items: center;
  gap: 3px;
  flex: 1;
  min-width: 0;
  height: 30px;
  padding: 0 3px;
  border: 1px solid var(--yp-border-default);
  border-radius: var(--yp-radius-xs);
  background: var(--yp-bg-surface);
  box-sizing: border-box;
  cursor: pointer;
  transition: border-color 150ms ease, box-shadow 150ms ease, background-color 150ms ease;
}

.label-card-box:focus-within {
  border-color: var(--yp-action-primary);
  box-shadow: 0 0 0 1px var(--yp-action-primary);
}

.label-card-box--inactive {
  background: var(--yp-bg-sunken);
  opacity: 0.85;
}

/* 颜色方块 */
.color-square-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border: 0;
  border-radius: 3px;
  cursor: pointer;
  flex-shrink: 0;
  padding: 0;
  transition: transform 120ms ease;
}

.color-square-btn:hover {
  transform: scale(1.08);
}

/* 标签名称输入框 */
.label-name-input {
  flex: 1;
  min-width: 0;
  font-size: 13px;
}

.label-name-input :deep(.el-input__wrapper) {
  padding: 0 3px;
  outline: none !important;
  outline-offset: 0;
  box-shadow: none !important;
  background: transparent;
  height: 26px;
}

.label-name-input :deep(.el-input__inner) {
  height: 26px;
  line-height: 26px;
  color: var(--yp-text-primary);
  font-size: 13px;
  cursor: text;
}

.label-card-box--inactive .label-name-input :deep(.el-input__inner) {
  color: var(--yp-text-muted);
}

/* 更多选项按钮：默认隐藏，鼠标悬停在行上或聚焦时显示 */
.more-options-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 30px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--yp-text-secondary);
  cursor: pointer;
  border-radius: var(--yp-radius-xs);
  flex-shrink: 0;
  opacity: 0;
  visibility: hidden;
  transition: opacity 150ms ease, visibility 150ms ease, background 150ms ease, color 150ms ease;
}

.more-options-btn:hover {
  background: var(--yp-bg-hover);
  color: var(--yp-text-primary);
}

.label-edit-row:hover .more-options-btn,
.label-edit-row:focus-within .more-options-btn {
  opacity: 1;
  visibility: visible;
}

/* 新增标签行与按钮：大小与已有标签卡片完全保持一致，外围无蓝色高亮 */
.new-label-row {
  display: flex;
  align-items: center;
  width: 100%;
  padding-left: 17px;
  padding-right: 21px;
  box-sizing: border-box;
}

.new-label-action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 30px;
  border: 1px solid var(--yp-border-default);
  border-radius: var(--yp-radius-xs);
  background: var(--yp-bg-surface);
  color: var(--yp-text-primary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  box-sizing: border-box;
  outline: none !important;
  box-shadow: none !important;
  transition: border-color 150ms ease, background 150ms ease;
}

.new-label-action-btn:hover {
  border-color: var(--yp-border-default);
  background: var(--yp-bg-hover);
}

.new-label-action-btn:focus,
.new-label-action-btn:focus-visible,
.new-label-action-btn:active {
  outline: none !important;
  box-shadow: none !important;
  border-color: var(--yp-border-default) !important;
}

/* 底部应用栏 */
.apply-action-bar {
  display: flex;
  justify-content: center;
  width: 100%;
  margin-top: 12px;
  padding-top: 4px;
}

.apply-action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 30px;
  padding: 0 var(--yp-space-4);
  border: 0;
  border-radius: var(--yp-radius-xs);
  background: transparent;
  color: var(--yp-text-primary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background 150ms ease;
}

.apply-action-btn:hover,
.apply-action-btn:focus-visible {
  background: var(--yp-bg-hover);
}

.apply-action-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  background: transparent;
}

@media (prefers-reduced-motion: reduce) {
  .label-edit-view.jelly-wobble {
    animation: none;
  }
}

/* 颜色选择器 Popover 网格 */
.color-palette-grid {
  display: grid;
  grid-template-columns: repeat(4, 26px);
  gap: var(--yp-space-2);
  padding: var(--yp-space-1);
  justify-content: center;
}

.color-swatch-item {
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: 5px;
  cursor: pointer;
  transition: transform 120ms ease, outline 120ms ease;
  box-sizing: border-box;
}

.color-swatch-item:hover {
  transform: scale(1.12);
}

.color-swatch-item--selected {
  outline: 2px solid var(--yp-action-primary);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--yp-action-primary) 30%, transparent);
}

/* 下拉菜单项 */
.dropdown-item-content {
  display: flex;
  align-items: center;
  gap: var(--yp-space-2);
  font-size: 13px;
}

.dropdown-item-content--danger {
  color: var(--yp-status-red);
}
</style>
