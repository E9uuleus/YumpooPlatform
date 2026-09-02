<script setup lang="ts">
import {
  WorkItemLabelColorToken,
  readCsrfToken,
  type Content,
  type ProjectContentCatalog,
} from '@yumpoo/api-client'
import { ElInput, ElMessage } from 'element-plus'
import { computed, ref } from 'vue'
import { contentsApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'
import { mondayWorkItemLabelColors, workItemLabelColorStyle } from './workItemLabelColors'

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

const mode = ref<'select' | 'edit'>('select')
const drafts = ref<DraftContent[]>([])
const deletedIds = ref<string[]>([])
const saving = ref(false)
const draggedIndex = ref<number>()
let draftSequence = 0

const selectable = computed(() => [...(props.catalog?.items ?? [])]
  .filter(item => item.active || item.id === props.currentValue)
  .sort((a, b) => a.sortOrder - b.sortOrder))

function resetEditor(): void {
  mode.value = 'select'
  drafts.value = []
  deletedIds.value = []
  draggedIndex.value = undefined
}

defineExpose({ resetEditor })

function edit(): void {
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
}

function add(): void {
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
}

function remove(item: DraftContent): void {
  if (item.protectedContent || item.inUse || drafts.value.filter(value => value.active).length <= 1) return
  if (item.id) deletedIds.value.push(item.id)
  drafts.value = drafts.value.filter(value => value.key !== item.key)
}

function toggle(item: DraftContent): void {
  if (item.active && drafts.value.filter(value => value.active).length <= 1) return
  item.active = !item.active
}

function drop(index: number): void {
  if (draggedIndex.value === undefined || draggedIndex.value === index) return
  const next = [...drafts.value]
  const [moved] = next.splice(draggedIndex.value, 1)
  if (moved) next.splice(index, 0, moved)
  drafts.value = next
  draggedIndex.value = undefined
}

async function reload(): Promise<ProjectContentCatalog> {
  return contentsApi.listProjectContents({ projectId: props.projectId })
}

async function save(): Promise<void> {
  if (saving.value || !props.catalog) return
  const invalid = drafts.value.find(item => !item.name.trim())
  if (invalid) {
    ElMessage.warning('工作项类别名称不能为空')
    return
  }
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
        idempotencyKey: crypto.randomUUID(),
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

function pillStyle(item: Content | DraftContent): Record<string, string> {
  return workItemLabelColorStyle(item.colorToken)
}
</script>

<template>
  <div class="content-popover">
    <template v-if="mode === 'select'">
      <button
        v-for="item in selectable"
        :key="item.id"
        class="content-option"
        :class="{ 'content-option--inactive': !item.active }"
        type="button"
        @click="emit('select', item.id)"
      >
        <span class="content-pill" :style="pillStyle(item)">{{ item.name }}</span>
      </button>
      <button v-if="canManage" class="content-manage" type="button" @click="edit">编辑工作项类别</button>
    </template>

    <template v-else>
      <div class="content-editor-list">
        <div
          v-for="(item, index) in drafts"
          :key="item.key"
          class="content-editor-row"
          draggable="true"
          @dragstart="draggedIndex = index"
          @dragover.prevent
          @drop="drop(index)"
        >
          <span class="content-drag" aria-label="拖拽排序">⋮⋮</span>
          <el-input v-model="item.name" maxlength="80" />
          <el-select v-model="item.colorToken" class="content-color" aria-label="类别颜色">
            <el-option v-for="color in mondayWorkItemLabelColors" :key="color.token" :value="color.token" :label="color.label" />
          </el-select>
          <button type="button" class="content-toggle" @click="toggle(item)">{{ item.active ? '停用' : '启用' }}</button>
          <button type="button" class="content-delete" :disabled="item.protectedContent || item.inUse" @click="remove(item)">删除</button>
        </div>
      </div>
      <div class="content-editor-actions">
        <button type="button" @click="add">新增类别</button>
        <span />
        <button type="button" @click="resetEditor">取消</button>
        <button type="button" :disabled="saving" @click="save">保存</button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.content-popover { min-width: 280px; padding: 8px; }
.content-option { display: block; width: 100%; padding: 4px 0; border: 0; background: transparent; cursor: pointer; }
.content-option--inactive { opacity: .55; }
.content-pill { display: flex; align-items: center; justify-content: center; width: 100%; height: 34px; padding: 0 16px; border-radius: 999px; color: white; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; box-sizing: border-box; }
.content-manage { width: 100%; margin-top: 8px; border: 0; background: transparent; color: var(--yp-accent); cursor: pointer; }
.content-editor-list { display: grid; gap: 8px; max-height: 360px; overflow: auto; }
.content-editor-row { display: grid; grid-template-columns: 24px minmax(120px, 1fr) 100px auto auto; gap: 6px; align-items: center; }
.content-drag { cursor: grab; color: var(--yp-text-muted); }
.content-color { height: 32px; min-width: 0; border: 1px solid var(--yp-border); border-radius: 6px; }
.content-toggle,.content-delete,.content-editor-actions button { border: 0; background: transparent; color: var(--yp-accent); cursor: pointer; }
.content-delete:disabled { color: var(--yp-text-muted); cursor: not-allowed; }
.content-editor-actions { display: grid; grid-template-columns: auto 1fr auto auto; gap: 8px; padding-top: 12px; }
</style>
