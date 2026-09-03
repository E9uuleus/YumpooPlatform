<script setup lang="ts">
import {
  WorkItemLabelColorToken,
  readCsrfToken,
  type ProjectContentCatalog,
} from '@yumpoo/api-client'
import { ElDropdown, ElDropdownItem, ElDropdownMenu, ElInput, ElMessage, ElPopover } from 'element-plus'
import { computed, nextTick, ref } from 'vue'
import { contentsApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'
import { mondayWorkItemLabelColors, workItemLabelColorStyle as colorStyle } from './workItemLabelColors'

interface DraftContent {
  key: string
  id?: string
  name: string
  colorToken: WorkItemLabelColorToken
  active: boolean
  protectedContent: boolean
  inUse: boolean
}

const props = defineProps<{
  projectId: string
  catalog?: ProjectContentCatalog | undefined
  currentValue?: string | undefined
  canManage?: boolean | undefined
}>()

const emit = defineEmits<{
  select: [contentId: string]
  updated: [catalog: ProjectContentCatalog]
}>()

const ITEMS_PER_COLUMN = 6
const mode = ref<'select' | 'edit'>('select')
const isJellyWobble = ref(false)
const drafts = ref<DraftContent[]>([])
const deletedIds = ref<string[]>([])
const saving = ref(false)
const draggedIndex = ref<number | null>(null)
const inputRefs = ref<InstanceType<typeof ElInput>[]>([])
let draftSequence = 0

const selectable = computed(() => [...(props.catalog?.items ?? [])]
  .filter(item => item.active || item.id === props.currentValue)
  .sort((a, b) => a.sortOrder - b.sortOrder))

const contentColumns = computed(() => {
  const columns: DraftContent[][] = []
  if (drafts.value.length === 0) return [[]]
  for (let index = 0; index < drafts.value.length; index += ITEMS_PER_COLUMN) {
    columns.push(drafts.value.slice(index, index + ITEMS_PER_COLUMN))
  }
  return columns
})

const isNewButtonOnSeparateColumn = computed(() => {
  const lastColumn = contentColumns.value[contentColumns.value.length - 1]
  return Boolean(lastColumn && lastColumn.length >= ITEMS_PER_COLUMN)
})

const canApply = computed(() => !saving.value && drafts.value.every(item => Boolean(item.name.trim())))

function resetEditor(): void {
  mode.value = 'select'
  isJellyWobble.value = false
  drafts.value = []
  deletedIds.value = []
  draggedIndex.value = null
}

defineExpose({ resetEditor })

function switchToEdit(): void {
  drafts.value = [...(props.catalog?.items ?? [])]
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map(item => ({
      key: item.id,
      id: item.id,
      name: item.name,
      colorToken: item.colorToken,
      active: item.active,
      protectedContent: item.protectedContent,
      inUse: item.inUse,
    }))
  deletedIds.value = []
  mode.value = 'edit'
  isJellyWobble.value = true
  setTimeout(() => {
    isJellyWobble.value = false
  }, 340)
}

async function add(): Promise<void> {
  const names = new Set(drafts.value.map(item => item.name))
  let name = '新类别'
  let suffix = 2
  while (names.has(name)) name = `新类别 ${suffix++}`
  drafts.value.push({
    key: `new-${++draftSequence}`,
    name,
    colorToken: WorkItemLabelColorToken.BrightBlue,
    active: true,
    protectedContent: false,
    inUse: false,
  })
  await nextTick()
  const lastInput = inputRefs.value[inputRefs.value.length - 1]
  lastInput?.focus()
  lastInput?.select()
}

function isLastActive(item: DraftContent): boolean {
  return item.active && drafts.value.filter(value => value.active).length <= 1
}

function cannotDelete(item: DraftContent): boolean {
  return item.protectedContent || item.inUse || isLastActive(item)
}

function remove(item: DraftContent): void {
  if (saving.value || cannotDelete(item)) return
  if (item.id) deletedIds.value.push(item.id)
  drafts.value = drafts.value.filter(value => value.key !== item.key)
}

function toggle(item: DraftContent): void {
  if (saving.value || isLastActive(item)) return
  item.active = !item.active
}

function updateContent(item: DraftContent, patch: {
  name?: string
  colorToken?: WorkItemLabelColorToken
}): void {
  if (saving.value) return
  drafts.value = drafts.value.map(value => value.key === item.key ? { ...value, ...patch } : value)
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
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
}

function onDrop(targetIndex: number): void {
  const sourceIndex = draggedIndex.value
  if (sourceIndex === null || sourceIndex === targetIndex) {
    draggedIndex.value = null
    return
  }
  draggedIndex.value = null
  const next = [...drafts.value]
  const [moved] = next.splice(sourceIndex, 1)
  if (!moved) return
  next.splice(targetIndex, 0, moved)
  drafts.value = next
}

async function reload(): Promise<ProjectContentCatalog> {
  return contentsApi.listProjectContents({ projectId: props.projectId })
}

async function applyChanges(): Promise<void> {
  if (saving.value || !props.catalog || !canApply.value) return
  saving.value = true
  try {
    const csrf = readCsrfToken()
    if (!csrf) {
      ElMessage.error('缺少 CSRF 凭据，请刷新后重试。')
      return
    }
    let catalog = await reload()
    for (const contentId of deletedIds.value) {
      await contentsApi.deleteContent({
        projectId: props.projectId, contentId, xXSRFTOKEN: csrf, ifMatch: catalog.etag,
      })
      catalog = await reload()
    }
    for (const draft of drafts.value.filter(item => !item.id)) {
      await contentsApi.createContent({
        projectId: props.projectId,
        xXSRFTOKEN: csrf,
        idempotencyKey: globalThis.crypto.randomUUID(),
        contentCreateRequest: { name: draft.name.trim(), colorToken: draft.colorToken },
      })
      catalog = await reload()
      const created = catalog.items.find(item => item.name === draft.name.trim()
        && !drafts.value.some(existing => existing.id === item.id))
      if (created) draft.id = created.id
    }
    for (let index = 0; index < drafts.value.length; index += 1) {
      const draft = drafts.value[index]!
      if (!draft.id) continue
      const persisted = catalog.items.find(item => item.id === draft.id)
      if (!persisted) continue
      const sortOrder = (index + 1) * 10
      if (persisted.name === draft.name.trim() && persisted.colorToken === draft.colorToken
        && persisted.active === draft.active && persisted.sortOrder === sortOrder) continue
      await contentsApi.updateContent({
        projectId: props.projectId,
        contentId: draft.id,
        xXSRFTOKEN: csrf,
        ifMatch: catalog.etag,
        contentUpdateRequest: {
          name: draft.name.trim(), colorToken: draft.colorToken,
          active: draft.active, sortOrder,
        },
      })
      catalog = await reload()
    }
    emit('updated', catalog)
    resetEditor()
    ElMessage.success('工作项类别已更新')
  } catch (reason) {
    ElMessage.error(problemMessage(await toApiProblem(reason)))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="yp-label-popover-root content-catalog-popover" :class="mode === 'edit' ? 'yp-label-popover-root--edit' : 'yp-label-popover-root--select'">
    <div v-if="mode === 'select'" class="label-select-view content-select-view">
      <div class="label-select-list content-select-list">
        <button
          v-for="item in selectable"
          :key="item.id"
          class="priority-option-pill content-option content-pill"
          :class="{ 'content-option--inactive': !item.active }"
          :style="colorStyle(item.colorToken)"
          type="button"
          @click="emit('select', item.id)"
        >
          <span>{{ item.name }}</span>
        </button>
      </div>
      <button v-if="canManage" class="content-manage edit-action-btn" type="button" @click="switchToEdit">
        <svg class="edit-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
          <path d="M11.5 2.5l2 2L5 13H3v-2L11.5 2.5z" />
        </svg>
        <span>编辑</span>
      </button>
    </div>

    <div v-else class="label-edit-view content-edit-view" :class="{ 'jelly-wobble': isJellyWobble }">
      <div class="label-columns-container content-columns-container">
        <div v-for="(column, columnIndex) in contentColumns" :key="columnIndex" class="label-column content-column">
          <div
            v-for="(item, rowIndex) in column"
            :key="item.key"
            class="label-edit-row content-editor-row"
            draggable="true"
            @dragstart="onDragStart($event, columnIndex * ITEMS_PER_COLUMN + rowIndex)"
            @dragover="onDragOver"
            @drop="onDrop(columnIndex * ITEMS_PER_COLUMN + rowIndex)"
          >
            <button class="content-drag drag-handle-btn" type="button" title="按住拖动调整顺序" tabindex="-1">
              <svg viewBox="0 0 16 16" width="10" height="14" fill="currentColor">
                <circle cx="4" cy="3" r="1.3" />
                <circle cx="12" cy="3" r="1.3" />
                <circle cx="4" cy="8" r="1.3" />
                <circle cx="12" cy="8" r="1.3" />
                <circle cx="4" cy="13" r="1.3" />
                <circle cx="12" cy="13" r="1.3" />
              </svg>
            </button>

            <div class="label-card-box content-card-box" :class="{ 'label-card-box--inactive': !item.active }">
              <el-popover placement="bottom-start" :width="156" trigger="click" popper-class="color-picker-popover">
                <template #reference>
                  <button class="color-square-btn" :style="colorStyle(item.colorToken)" type="button" title="选择颜色">
                    <svg viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="var(--yp-text-inverse)" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                      <path d="M12.5 5.5l-4-4-5.5 5.5a1.41 1.41 0 000 2l3.5 3.5a1.41 1.41 0 002 0l4-4z" />
                      <path d="M2.5 10l5.5-5.5" />
                      <path d="M13.5 11c0 1.1-.9 2-2 2s-2-.9-2-2c0-.8 2-3 2-3s2 2.2 2 3z" fill="var(--yp-text-inverse)" />
                    </svg>
                  </button>
                </template>
                <div class="color-palette-grid">
                  <button
                    v-for="colorItem in mondayWorkItemLabelColors"
                    :key="colorItem.token"
                    class="color-swatch-item"
                    :class="{ 'color-swatch-item--selected': colorItem.token === item.colorToken }"
                    :style="{ backgroundColor: colorItem.color }"
                    :title="colorItem.label"
                    type="button"
                    @click="updateContent(item, { colorToken: colorItem.token })"
                  />
                </div>
              </el-popover>
              <el-input
                ref="inputRefs"
                class="label-name-input content-name-input"
                :model-value="item.name"
                maxlength="80"
                :disabled="saving"
                placeholder="类别名称"
                @update:model-value="updateContent(item, { name: String($event) })"
              />
            </div>

            <el-dropdown trigger="click" placement="bottom-end">
              <button class="more-options-btn" type="button" title="更多选项">
                <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor">
                  <circle cx="3" cy="8" r="1.5" />
                  <circle cx="8" cy="8" r="1.5" />
                  <circle cx="13" cy="8" r="1.5" />
                </svg>
              </button>
              <template #dropdown>
                <el-dropdown-menu class="label-more-dropdown-menu content-more-dropdown-menu">
                  <el-dropdown-item class="content-toggle" :disabled="isLastActive(item) || saving" @click="toggle(item)">
                    <span class="dropdown-item-content">
                      <svg viewBox="0 0 16 16" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.6">
                        <circle cx="8" cy="8" r="6" />
                        <line x1="3.8" y1="3.8" x2="12.2" y2="12.2" />
                      </svg>
                      <span>{{ item.active ? '停用类别' : '启用类别' }}</span>
                    </span>
                  </el-dropdown-item>
                  <el-dropdown-item class="content-delete" :disabled="cannotDelete(item) || saving" @click="remove(item)">
                    <span class="dropdown-item-content dropdown-item-content--danger">
                      <svg viewBox="0 0 16 16" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M2.5 4.5h11M5.5 4.5V3a1 1 0 011-1h3a1 1 0 011 1v1.5M4 4.5l.8 9a1.5 1.5 0 001.5 1.5h3.4a1.5 1.5 0 001.5-1.5l.8-9" />
                      </svg>
                      <span>删除类别</span>
                    </span>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>

          <div v-if="columnIndex === contentColumns.length - 1 && !isNewButtonOnSeparateColumn" class="new-label-row new-content-row">
            <button class="new-label-action-btn new-content-action-btn" type="button" :disabled="saving" @click="add">
              <span>+ 新增类别</span>
            </button>
          </div>
        </div>

        <div v-if="isNewButtonOnSeparateColumn" class="label-column content-column">
          <div class="new-label-row new-content-row">
            <button class="new-label-action-btn new-content-action-btn" type="button" :disabled="saving" @click="add">
              <span>+ 新增类别</span>
            </button>
          </div>
        </div>
      </div>

      <div class="content-editor-actions apply-action-bar">
        <button class="apply-action-btn" type="button" :disabled="!canApply" @click="applyChanges">
          {{ saving ? '应用中…' : '应用' }}
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
  width: 148px;
  padding: 16px;
  box-sizing: border-box;
}

.yp-label-popover-root--edit {
  width: auto;
  padding: 16px;
  box-sizing: border-box;
}

.label-select-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.priority-option-pill {
  display: flex;
  width: 100%;
  height: 34px;
  align-items: center;
  justify-content: center;
  padding: 0 8px;
  border: 0;
  border-radius: var(--yp-radius-xs);
  box-sizing: border-box;
  color: var(--yp-text-inverse);
  font-size: 13px;
  font-weight: 500;
  text-align: center;
  cursor: pointer;
  overflow: hidden;
  transition: transform 120ms ease, filter 120ms ease, opacity 120ms ease;
}

.priority-option-pill span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.priority-option-pill:hover:not(:disabled) {
  filter: brightness(0.93);
  transform: translateY(-1px);
}

.content-option--inactive {
  opacity: 0.55;
}

.edit-action-btn {
  display: flex;
  width: 100%;
  height: 32px;
  margin-top: 12px;
  align-items: center;
  justify-content: center;
  gap: 6px;
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

@keyframes puddingWobble {
  0% { opacity: 0; transform: scale(0.92) translateY(2px); }
  35% { opacity: 1; transform: scale(1.03) translateX(-3px); }
  55% { transform: scale(0.98) translateX(2px); }
  75% { transform: scale(1.01) translateX(-1px); }
  100% { opacity: 1; transform: scale(1) translateX(0) translateY(0); }
}

.label-edit-view.jelly-wobble {
  animation: puddingWobble 340ms cubic-bezier(0.25, 1, 0.5, 1) forwards;
  transform-origin: top center;
}

.label-columns-container {
  display: flex;
  flex-direction: row;
  gap: 10px;
  align-items: flex-start;
}

.label-column {
  display: flex;
  width: 156px;
  flex-shrink: 0;
  flex-direction: column;
  gap: 6px;
}

.label-edit-row {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 3px;
  cursor: pointer;
}

.drag-handle-btn {
  display: flex;
  width: 14px;
  height: 30px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--yp-text-muted);
  cursor: grab;
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

.label-card-box {
  display: flex;
  height: 30px;
  min-width: 0;
  flex: 1;
  align-items: center;
  gap: 3px;
  padding: 0 3px;
  border: 1px solid var(--yp-border-default);
  border-radius: var(--yp-radius-xs);
  box-sizing: border-box;
  background: var(--yp-bg-surface);
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

.color-square-btn {
  display: flex;
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 0;
  border-radius: 3px;
  cursor: pointer;
  transition: transform 120ms ease;
}

.color-square-btn:hover {
  transform: scale(1.08);
}

.label-name-input {
  min-width: 0;
  flex: 1;
  font-size: 13px;
}

.label-name-input :deep(.el-input__wrapper) {
  height: 26px;
  padding: 0 3px;
  outline: none !important;
  outline-offset: 0;
  box-shadow: none !important;
  background: transparent;
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

.more-options-btn {
  display: flex;
  width: 18px;
  height: 30px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 0;
  border-radius: var(--yp-radius-xs);
  background: transparent;
  color: var(--yp-text-secondary);
  cursor: pointer;
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

.new-label-row {
  display: flex;
  width: 100%;
  align-items: center;
  padding-right: 21px;
  padding-left: 17px;
  box-sizing: border-box;
}

.new-label-action-btn {
  display: flex;
  width: 100%;
  height: 30px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--yp-border-default);
  border-radius: var(--yp-radius-xs);
  box-sizing: border-box;
  outline: none !important;
  box-shadow: none !important;
  background: var(--yp-bg-surface);
  color: var(--yp-text-primary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
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

.apply-action-bar {
  display: flex;
  width: 100%;
  justify-content: center;
  margin-top: 12px;
  padding-top: 4px;
}

.apply-action-btn {
  display: inline-flex;
  height: 30px;
  align-items: center;
  justify-content: center;
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

.color-palette-grid {
  display: grid;
  grid-template-columns: repeat(4, 26px);
  justify-content: center;
  gap: var(--yp-space-2);
  padding: var(--yp-space-1);
}

.color-swatch-item {
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: 5px;
  box-sizing: border-box;
  cursor: pointer;
  transition: transform 120ms ease, outline 120ms ease;
}

.color-swatch-item:hover {
  transform: scale(1.12);
}

.color-swatch-item--selected {
  outline: 2px solid var(--yp-action-primary);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--yp-action-primary) 30%, transparent);
}

.dropdown-item-content {
  display: flex;
  align-items: center;
  gap: var(--yp-space-2);
  font-size: 13px;
}

.dropdown-item-content--danger {
  color: var(--yp-status-red);
}

@media (prefers-reduced-motion: reduce) {
  .label-edit-view.jelly-wobble {
    animation: none;
  }
}
</style>
