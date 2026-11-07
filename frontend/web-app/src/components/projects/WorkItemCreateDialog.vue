<script setup lang="ts">
import { Right } from '@element-plus/icons-vue'
import {
  readCsrfToken,
  type ProjectContentCatalog, type ProjectDetail, type ProjectMember, type ProjectWorkItemListItem,
  type WorkItemAssignee, type WorkItemLabelCatalog,
} from '@yumpoo/api-client'
import { ElButton, ElDialog, ElIcon, ElInput, ElMessage } from 'element-plus'
import { computed, reactive, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { workItemsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import { useSession } from '../../composables/useSession'
import InlineProblem from '../InlineProblem.vue'
import type { WorkItemPatchField, WorkItemPatchValue } from './useWorkItemEdits'
import { MAX_WORK_ITEM_ASSIGNEES } from './workItemAssignees'
import { applyDefaultStatus } from './workItemDefaultStatus'
import { apiDate, type DueDateValue } from './workItemDueDate'
import WorkItemPropertyList, { type WorkItemPropertyValues } from './WorkItemPropertyList.vue'
import type { WorkItemCreateDefaults } from './workItemTableSettings'

const props = defineProps<{
  project: ProjectDetail
  /** 有值时创建为它的子项。 */
  parent?: ProjectWorkItemListItem | undefined
  labelCatalog?: WorkItemLabelCatalog | undefined
  contentCatalog?: ProjectContentCatalog | undefined
  /** 项目在职成员，用于显示默认处理人的名字。 */
  members: ProjectMember[]
  defaults: WorkItemCreateDefaults
  defaultContentId: string | undefined
}>()
const emit = defineEmits<{
  close: []
  created: [parentId: string | undefined]
  labelsUpdated: [catalog: WorkItemLabelCatalog]
  contentsUpdated: [catalog: ProjectContentCatalog]
}>()

const user = useSession().authentication.value?.user
const titleInput = ref<InstanceType<typeof ElInput>>()
const title = ref('')
const saving = ref(false)
onBeforeRouteLeave(() => !saving.value)
const problem = ref<ApiProblem>()
/** 同一次打开内重试沿用同一个幂等键，网络失败后再次提交不会重复创建。 */
const idempotencyKey = globalThis.crypto.randomUUID()
const activeContents = (props.contentCatalog?.items ?? []).filter(item => item.active)
const draft = reactive({
  contentId: activeContents.find(item => item.id === props.parent?.contentId)?.id ?? props.defaultContentId ?? '',
  statusCode: props.defaults.statusCode ?? 'NOT_STARTED',
  priority: props.defaults.fields.priority ?? null,
  // 我的工作只显示分配给自己的工作项，新建默认处理人包含自己，否则创建后会立刻从列表消失。
  assigneeUserIds: [...new Set([...(user ? [user.id] : []), ...(props.defaults.fields.assigneeUserIds ?? [])])]
    .slice(0, MAX_WORK_ITEM_ASSIGNEES),
  dueDate: props.defaults.fields.dueDate ?? null,
  dueTime: null as string | null,
})

function assignee(userId: string): WorkItemAssignee {
  const member = props.members.find(item => item.userId === userId)
  return { userId, displayName: member?.displayName ?? (userId === user?.id ? user.displayName : userId) }
}
const values = computed<WorkItemPropertyValues>(() => {
  const content = props.contentCatalog?.items.find(item => item.id === draft.contentId)
  return {
    statusCode: draft.statusCode,
    statusCategory: props.labelCatalog?.statuses.find(status => status.code === draft.statusCode)?.statusCategory ?? 'TODO',
    priority: draft.priority,
    contentId: draft.contentId,
    contentName: content?.name ?? '',
    contentColorToken: content?.colorToken ?? props.parent?.contentColorToken ?? activeContents[0]!.colorToken,
    assignees: draft.assigneeUserIds.map(assignee),
    dueDate: draft.dueDate,
    dueTime: draft.dueTime,
  }
})
const transitions = computed(() => (props.labelCatalog?.statuses ?? [])
  .filter(status => status.active).map(status => ({ toStatus: status.code })))

function patch(field: WorkItemPatchField, value: WorkItemPatchValue): void {
  if (field === 'assignees') draft.assigneeUserIds = [...(value as readonly string[])]
  else if (field === 'priority') draft.priority = value as string | null
  else if (field === 'content') draft.contentId = value as string
}
function deadline(value: DueDateValue): void {
  draft.dueDate = apiDate(value.dueDate)
  draft.dueTime = value.dueTime
}
function onTitleKeydown(rawEvent: Event | KeyboardEvent): void {
  const event = rawEvent as KeyboardEvent
  if (event.isComposing || event.keyCode === 229 || event.key !== 'Enter') return
  event.preventDefault()
  void submit()
}

async function submit(): Promise<void> {
  const name = title.value.trim()
  if (!name || !draft.contentId || saving.value) return
  const csrf = readCsrfToken()
  if (!csrf) { problem.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  saving.value = true
  problem.value = undefined
  const body = {
    contentId: draft.contentId, title: name, priority: draft.priority,
    assigneeUserIds: draft.assigneeUserIds, assigneeUserId: draft.assigneeUserIds[0] ?? null,
    description: null, notes: null, timelineStartDate: null, timelineEndDate: null,
    dueDate: draft.dueDate, dueTime: draft.dueTime,
  }
  try {
    const created = props.parent
      ? await workItemsApi.createWorkItemSubitem({ parentWorkItemId: props.parent.id, xXSRFTOKEN: csrf, idempotencyKey, workItemSubitemCreateRequest: body })
      : await workItemsApi.createWorkItem({ projectId: props.project.id, xXSRFTOKEN: csrf, idempotencyKey, workItemCreateRequest: body })
    await applyDefaultStatus(created, draft.statusCode, csrf)
    ElMessage.success(`已创建 ${created.itemNo}`)
    emit('created', props.parent?.id)
  } catch (reason) {
    problem.value = await toApiProblem(reason)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="true"
    class="work-item-create-dialog"
    width="min(640px, calc(100vw - 32px))"
    align-center
    append-to-body
    :close-on-click-modal="false"
    :close-on-press-escape="!saving"
    :show-close="!saving"
    @opened="titleInput?.focus()"
    @close="emit('close')"
  >
    <template #header>
      <el-input
        ref="titleInput"
        v-model="title"
        class="work-item-create-dialog__title"
        maxlength="300"
        placeholder="工作项名称"
        aria-label="工作项名称"
        :disabled="saving"
        @keydown="onTitleKeydown"
      />
      <p class="work-item-create-dialog__crumb">
        <span>新建到</span>
        <el-icon aria-hidden="true">
          <right />
        </el-icon>
        <strong>{{ project.name }}</strong>
        <span v-if="parent">· 父项 {{ parent.title }}</span>
      </p>
    </template>
    <work-item-property-list
      :values="values"
      :project-id="project.id"
      :label-catalog="labelCatalog"
      :content-catalog="contentCatalog"
      :available-transitions="transitions"
      can-edit
      :busy="saving"
      @patch="patch"
      @transition="statusCode => { draft.statusCode = statusCode }"
      @deadline="deadline"
      @labels-updated="emit('labelsUpdated', $event)"
      @contents-updated="emit('contentsUpdated', $event)"
    />
    <inline-problem
      v-if="problem"
      class="work-item-create-dialog__problem"
      :problem="problem"
      title="创建失败"
    />
    <template #footer>
      <el-button
        :disabled="saving"
        @click="emit('close')"
      >
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="saving"
        :disabled="!title.trim() || !draft.contentId"
        @click="submit"
      >
        创建工作项
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.work-item-create-dialog__title :deep(.el-input__wrapper) { padding: 0; box-shadow: none; background: transparent; }
.work-item-create-dialog__title :deep(.el-input__inner) { height: 40px; color: var(--yp-text-primary); font-size: 24px; font-weight: 650; }
.work-item-create-dialog__crumb { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin: var(--yp-space-2) 0 0; color: var(--yp-text-secondary); font-size: 13px; }
.work-item-create-dialog__crumb strong { color: var(--yp-text-primary); font-weight: 600; }
.work-item-create-dialog__problem { margin-top: var(--yp-space-4); }
</style>

<style>
.work-item-create-dialog.el-dialog { --el-dialog-padding-primary: 0; overflow: hidden; border-radius: var(--yp-radius-lg); }
.work-item-create-dialog .el-dialog__header { margin: 0; padding: var(--yp-space-8) var(--yp-space-10) var(--yp-space-4); }
.work-item-create-dialog .el-dialog__body { max-height: min(60dvh, 560px); overflow-y: auto; overscroll-behavior: contain; padding: var(--yp-space-2) var(--yp-space-10) var(--yp-space-6); }
.work-item-create-dialog .el-dialog__footer { padding: var(--yp-space-4) var(--yp-space-6); border-top: 1px solid var(--yp-border-subtle); }
@media (max-width: 599.98px) {
  .work-item-create-dialog .el-dialog__header { padding: var(--yp-space-6) var(--yp-space-4) var(--yp-space-3); }
  .work-item-create-dialog .el-dialog__body { padding: var(--yp-space-2) var(--yp-space-4) var(--yp-space-4); }
}
</style>
