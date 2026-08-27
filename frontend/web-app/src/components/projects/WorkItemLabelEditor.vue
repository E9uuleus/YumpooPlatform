<script setup lang="ts">
import {
  WorkItemLabelColorToken,
  readCsrfToken,
  type WorkItemLabelCatalog,
  type WorkItemPriorityLabel,
  type WorkItemStatusLabel,
} from '@yumpoo/api-client'
import { ElButton, ElDialog, ElInput, ElMessage, ElTooltip } from 'element-plus'
import { computed, ref } from 'vue'
import { workItemsApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'
import { mondayWorkItemLabelColors, workItemLabelColorStyle } from './workItemLabelColors'

const props = defineProps<{
  modelValue: boolean
  projectId: string
  kind: 'status' | 'priority'
  catalog: WorkItemLabelCatalog
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  updated: [catalog: WorkItemLabelCatalog]
}>()

type Label = WorkItemStatusLabel | WorkItemPriorityLabel
const colors = mondayWorkItemLabelColors.map(item => item.token)
const newName = ref('')
const newColor = ref<WorkItemLabelColorToken>(WorkItemLabelColorToken.BrightBlue)
const saving = ref('')
const labels = computed<Label[]>(() => props.kind === 'status'
  ? props.catalog.statuses
  : props.catalog.priorities)

function colorStyle(token: WorkItemLabelColorToken): Record<string, string> {
  return workItemLabelColorStyle(token)
}

async function createLabel(): Promise<void> {
  const name = newName.value.trim()
  if (!name || saving.value) return
  const csrf = readCsrfToken()
  if (!csrf) { ElMessage.error('缺少 CSRF 凭据，请刷新后重试。'); return }
  saving.value = 'new'
  try {
    const common = {
      projectId: props.projectId,
      xXSRFTOKEN: csrf,
      ifMatch: props.catalog.etag,
      workItemLabelCreateRequest: { displayName: name, colorToken: newColor.value },
    }
    const result = props.kind === 'status'
      ? await workItemsApi.createProjectWorkItemStatusLabel(common)
      : await workItemsApi.createProjectWorkItemPriorityLabel(common)
    newName.value = ''
    emit('updated', result)
  } catch (reason) {
    ElMessage.error(problemMessage(await toApiProblem(reason)))
  } finally { saving.value = '' }
}

async function updateLabel(label: Label, patch: {
  displayName?: string
  colorToken?: WorkItemLabelColorToken
  active?: boolean
  sortOrder?: number
}): Promise<void> {
  if (saving.value) return
  const csrf = readCsrfToken()
  if (!csrf) { ElMessage.error('缺少 CSRF 凭据，请刷新后重试。'); return }
  saving.value = label.code
  try {
    const common = {
      projectId: props.projectId,
      code: label.code,
      xXSRFTOKEN: csrf,
      ifMatch: props.catalog.etag,
      workItemLabelUpdateRequest: {
        displayName: patch.displayName ?? null,
        colorToken: patch.colorToken ?? null,
        active: patch.active ?? null,
        sortOrder: patch.sortOrder ?? null,
      },
    }
    const result = props.kind === 'status'
      ? await workItemsApi.updateProjectWorkItemStatusLabel(common)
      : await workItemsApi.updateProjectWorkItemPriorityLabel(common)
    emit('updated', result)
  } catch (reason) {
    ElMessage.error(problemMessage(await toApiProblem(reason)))
  } finally { saving.value = '' }
}

async function deleteLabel(label: Label): Promise<void> {
  if (saving.value || label.inUse) return
  const csrf = readCsrfToken()
  if (!csrf) { ElMessage.error('缺少 CSRF 凭据，请刷新后重试。'); return }
  saving.value = label.code
  try {
    const common = { projectId: props.projectId, code: label.code,
      xXSRFTOKEN: csrf, ifMatch: props.catalog.etag }
    const result = props.kind === 'status'
      ? await workItemsApi.deleteProjectWorkItemStatusLabel(common)
      : await workItemsApi.deleteProjectWorkItemPriorityLabel(common)
    emit('updated', result)
  } catch (reason) {
    ElMessage.error(problemMessage(await toApiProblem(reason)))
  } finally { saving.value = '' }
}

function move(label: Label, offset: number): void {
  const index = labels.value.findIndex(item => item.code === label.code)
  const target = labels.value[index + offset]
  if (target) void updateLabel(label, { sortOrder: target.sortOrder + (offset > 0 ? 1 : 0) })
}

function protectedLabel(label: Label): boolean {
  return 'protectedLabel' in label && label.protectedLabel
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="kind === 'status' ? '编辑状态标签' : '编辑优先级标签'"
    width="min(620px, 96vw)"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="label-editor-list">
      <div v-for="(label, index) in labels" :key="label.code" class="label-editor-row">
        <div class="label-order-actions">
          <button :disabled="index === 0 || Boolean(saving)" :aria-label="`上移${label.displayName}`" @click="move(label, -1)">↑</button>
          <button :disabled="index === labels.length - 1 || Boolean(saving)" :aria-label="`下移${label.displayName}`" @click="move(label, 1)">↓</button>
        </div>
        <span class="label-color-preview" :style="colorStyle(label.colorToken)" />
        <el-input
          :model-value="label.displayName"
          maxlength="80"
          :disabled="saving === label.code"
          aria-label="标签名称"
          @change="updateLabel(label, { displayName: String($event) })"
        />
        <div class="label-color-grid" aria-label="标签颜色">
          <button
            v-for="color in colors"
            :key="color"
            class="label-color-swatch"
            :class="{ selected: color === label.colorToken }"
            :style="colorStyle(color)"
            :aria-label="color"
            @click="updateLabel(label, { colorToken: color })"
          />
        </div>
        <el-button
          size="small"
          :disabled="protectedLabel(label) || saving === label.code"
          @click="updateLabel(label, { active: !label.active })"
        >{{ label.active ? '停用' : '启用' }}</el-button>
        <el-tooltip :disabled="!label.inUse" content="你不能删除正在使用的标签" placement="top">
          <span>
            <el-button
              size="small"
              type="danger"
              plain
              :disabled="label.inUse || protectedLabel(label) || saving === label.code"
              @click="deleteLabel(label)"
            >删除</el-button>
          </span>
        </el-tooltip>
      </div>
    </div>
    <div class="label-create-row">
      <el-input v-model="newName" maxlength="80" placeholder="新标签名称" @keyup.enter="createLabel" />
      <div class="label-color-grid">
        <button
          v-for="color in colors"
          :key="color"
          class="label-color-swatch"
          :class="{ selected: color === newColor }"
          :style="colorStyle(color)"
          :aria-label="color"
          @click="newColor = color"
        />
      </div>
      <el-button type="primary" :loading="saving === 'new'" :disabled="!newName.trim()" @click="createLabel">新增标签</el-button>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">完成</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.label-editor-list { display: grid; gap: 12px; max-height: 55vh; overflow: auto; }
.label-editor-row { display: grid; grid-template-columns: 42px 20px minmax(120px, 1fr) minmax(150px, 1.5fr) auto auto; gap: 8px; align-items: center; }
.label-order-actions { display: flex; gap: 2px; }
.label-order-actions button { border: 0; background: transparent; cursor: pointer; }
.label-color-preview { width: 18px; height: 30px; border-radius: 5px; }
.label-color-grid { display: grid; grid-template-columns: repeat(6, 20px); gap: 4px; }
.label-color-swatch { width: 20px; height: 20px; border: 2px solid transparent; border-radius: 5px; cursor: pointer; }
.label-color-swatch.selected { border-color: var(--yp-text-primary); box-shadow: 0 0 0 2px var(--yp-bg-surface) inset; }
.label-create-row { display: grid; grid-template-columns: minmax(150px, 1fr) auto auto; gap: 12px; align-items: center; margin-top: 20px; padding-top: 16px; border-top: 1px solid var(--yp-border-subtle); }
@media (max-width: 720px) { .label-editor-row { grid-template-columns: 42px 20px 1fr; } .label-color-grid { grid-column: 3; } .label-create-row { grid-template-columns: 1fr; } }
</style>
