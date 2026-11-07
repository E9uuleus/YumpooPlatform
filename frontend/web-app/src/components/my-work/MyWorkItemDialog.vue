<script setup lang="ts">
import { Right } from '@element-plus/icons-vue'
import {
  ProjectMembershipStatusFilter,
  type ProjectContentCatalog, type ProjectDetail, type ProjectMember, type ProjectWorkItemListItem,
  type TimeTrackingSummary, type WorkItemDetail, type WorkItemLabelCatalog,
} from '@yumpoo/api-client'
import { ElDialog, ElIcon, ElMessage, ElMessageBox } from 'element-plus'
import { computed, ref, watch } from 'vue'
import { contentsApi, projectsApi, timeTrackingApi, workItemsApi } from '../../api/client'
import { problemMessage, toApiProblem, type ApiProblem } from '../../api/problems'
import { vBrandLoading as vLoading } from '../../brand/loading'
import InlineProblem from '../InlineProblem.vue'
import WorkItemDetailPanel from '../collaboration/WorkItemDetailPanel.vue'
import { useWorkItemEdits, type WorkItemPatchField, type WorkItemPatchValue } from '../projects/useWorkItemEdits'
import { workItemAssignees } from '../projects/workItemAssignees'
import { workItemDiscussionAccess } from '../projects/workItemDiscussionAccess'
import { apiDate, type DueDateValue } from '../projects/workItemDueDate'
import WorkItemNameCell from '../projects/WorkItemNameCell.vue'
import WorkItemPropertyList from '../projects/WorkItemPropertyList.vue'
import YpNotice from '../yp/YpNotice.vue'

type DetailTab = 'details' | 'discussion' | 'activity'
const props = defineProps<{ workItemId: string | undefined }>()
const tab = defineModel<DetailTab>('tab', { required: true })
const emit = defineEmits<{ close: []; changed: [projectId: string] }>()

const loading = ref(false)
const problem = ref<ApiProblem>()
const detail = ref<WorkItemDetail>()
const project = ref<ProjectDetail>()
const labels = ref<WorkItemLabelCatalog>()
const contents = ref<ProjectContentCatalog>()
const members = ref<ProjectMember[]>([])
const summary = ref<TimeTrackingSummary | null>(null)
const panel = ref<InstanceType<typeof WorkItemDetailPanel>>()
let generation = 0

/** 字段单元格沿用表格行的形状；详情不带计时汇总，单独读取。 */
const row = computed<ProjectWorkItemListItem | undefined>(() => detail.value
  ? { ...detail.value, timeTracking: summary.value, subitemCount: 0 }
  : undefined)
const values = computed(() => row.value ? { ...row.value, assignees: workItemAssignees(row.value) } : undefined)
const access = computed(() => workItemDiscussionAccess(project.value, detail.value))

async function loadMembers(projectId: string, current: number): Promise<ProjectMember[]> {
  const active: ProjectMember[] = []
  let page = 0
  let totalPages = 1
  while (page < totalPages && current === generation) {
    const result = await projectsApi.listProjectMembers({ projectId, status: ProjectMembershipStatusFilter.Active, page, size: 100 })
    active.push(...result.items)
    totalPages = result.totalPages
    page++
  }
  return active
}

async function load(workItemId: string): Promise<void> {
  const current = ++generation
  loading.value = true
  problem.value = undefined
  try {
    const item = await workItemsApi.getWorkItem({ workItemId })
    if (current !== generation) return
    const projectId = item.projectId
    const [nextProject, nextLabels, nextContents, activeMembers, summaries] = await Promise.all([
      projectsApi.getProject({ projectId }),
      workItemsApi.getProjectWorkItemLabels({ projectId }),
      contentsApi.listProjectContents({ projectId }),
      loadMembers(projectId, current),
      timeTrackingApi.getTimeTrackingSummaries({ projectId, workItemId: [workItemId] }),
    ])
    if (current !== generation) return
    detail.value = item
    project.value = nextProject
    labels.value = nextLabels
    contents.value = nextContents
    members.value = activeMembers
    summary.value = summaries.items[0] ?? null
  } catch (reason) {
    const failure = await toApiProblem(reason)
    if (current === generation) problem.value = failure
  } finally {
    if (current === generation) loading.value = false
  }
}
watch(() => props.workItemId, workItemId => {
  detail.value = undefined
  problem.value = undefined
  if (workItemId) void load(workItemId)
  else { generation++; loading.value = false }
}, { immediate: true })

function updated(next: WorkItemDetail): void {
  if (props.workItemId === next.id) detail.value = next
  emit('changed', next.projectId)
}
const { busy: editing, patch: patchItem, transition: transitionItem } = useWorkItemEdits({
  updated: (_id, next) => updated(next),
  failed: async reason => {
    ElMessage.error(problemMessage(reason))
    if (detail.value) await load(detail.value.id)
  },
})
function patch(field: WorkItemPatchField, value: WorkItemPatchValue): void {
  if (row.value) void patchItem(row.value, field, value)
}
function deadline(value: DueDateValue): void {
  if (row.value) void patchItem(row.value, 'dueDate', apiDate(value.dueDate), value.dueTime)
}
function transition(statusCode: string): void {
  if (row.value) void transitionItem(row.value, statusCode)
}

/** 关闭或离开前确认描述/讨论草稿；由页面的路由守卫调用。 */
async function beforeLeave(): Promise<boolean> {
  if (editing.value || panel.value?.busy) { ElMessage.info('内容正在保存，请稍候再离开。'); return false }
  if (!panel.value?.hasDraft) return true
  try {
    await ElMessageBox.confirm('离开将丢弃尚未保存的描述或讨论草稿。', '放弃草稿', {
      confirmButtonText: '放弃草稿', cancelButtonText: '继续编写', type: 'warning',
    })
  } catch { return false }
  panel.value.discardDraft()
  return true
}
defineExpose({ beforeLeave })
</script>

<template>
  <el-dialog
    :model-value="Boolean(workItemId)"
    class="my-work-detail"
    aria-label="工作项详情"
    width="min(1280px, calc(100vw - 64px))"
    top="4vh"
    append-to-body
    :before-close="() => emit('close')"
  >
    <div
      v-loading="loading"
      class="my-work-detail__layout"
      data-detail-scroll-root
    >
      <inline-problem
        v-if="problem"
        class="my-work-detail__problem"
        :problem="problem"
        title="工作项加载失败"
      />
      <template v-else-if="detail && row && values && project">
        <section
          class="my-work-detail__fields"
          aria-label="工作项字段"
        >
          <yp-notice
            v-if="detail.archived"
            class="my-work-detail__notice"
            tone="warning"
          >
            此工作项已归档，可在所属项目中恢复
          </yp-notice>
          <work-item-name-cell
            class="my-work-detail__title"
            :item="row"
            strict-version
            @updated="(_id, next) => updated(next)"
          />
          <p class="my-work-detail__crumb">
            <span>所属</span>
            <el-icon aria-hidden="true">
              <right />
            </el-icon>
            <router-link :to="{ name: 'project-overview', params: { projectId: detail.projectId }, query: { workItemId: detail.id } }">
              {{ project.name }}
            </router-link>
            <span class="my-work-detail__meta">{{ detail.contentName }} · {{ detail.itemNo }}</span>
          </p>
          <work-item-property-list
            :values="values"
            :project-id="detail.projectId"
            :label-catalog="labels"
            :content-catalog="contents"
            :available-transitions="row.capabilities.availableTransitions"
            :can-edit="row.capabilities.canEditFields"
            :busy="Boolean(editing)"
            :row="row"
            @patch="patch"
            @transition="transition"
            @deadline="deadline"
            @labels-updated="labels = $event"
            @contents-updated="contents = $event"
            @timer-changed="emit('changed', detail.projectId)"
            @open-activity="panel?.openActivity()"
          />
        </section>
        <section
          class="my-work-detail__activity"
          aria-label="详情、协作讨论与动态"
        >
          <work-item-detail-panel
            ref="panel"
            v-model="tab"
            :detail="detail"
            :members="members"
            :can-publish="access.canPublish"
            :read-only-reason="access.readOnlyReason"
            @discussion-changed="emit('changed', detail.projectId)"
            @description-updated="updated"
          />
        </section>
      </template>
    </div>
  </el-dialog>
</template>

<style scoped>
.my-work-detail__layout { display: grid; grid-template-columns: minmax(360px, 42%) minmax(0, 1fr); align-items: start; overflow-y: auto; overscroll-behavior: contain; height: min(86dvh, 920px); min-height: 0; }
.my-work-detail__problem { grid-column: 1 / -1; align-self: start; margin: var(--yp-space-10); }
.my-work-detail__fields,
.my-work-detail__activity { min-width: 0; min-height: 0; animation: my-work-detail-in 260ms var(--yp-ease-standard) backwards; }
.my-work-detail__fields { position: sticky; top: 0; height: min(86dvh, 920px); box-sizing: border-box; overflow-y: auto; overscroll-behavior: contain; padding: var(--yp-space-10) var(--yp-space-8) var(--yp-space-8) var(--yp-space-10); border-right: 1px solid var(--yp-border-subtle); }
.my-work-detail__activity { padding: var(--yp-space-6) var(--yp-space-8) var(--yp-space-8); animation-delay: 60ms; }
.my-work-detail__notice { margin-bottom: var(--yp-space-4); }
.my-work-detail__title { height: auto; min-height: 36px; padding: 0; }
.my-work-detail__title :deep(.work-item-title-text) { width: auto; max-width: 100%; color: var(--yp-text-primary); font-size: 24px; font-weight: 650; line-height: 32px; white-space: normal; word-break: break-word; text-align: left; cursor: text; }
.my-work-detail__title :deep(.work-item-detail-button) { display: none; }
.my-work-detail__title :deep(.work-item-name-input) { height: 36px; font-size: 20px; }
.my-work-detail__crumb { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin: var(--yp-space-2) 0 var(--yp-space-6); color: var(--yp-text-secondary); font-size: 14px; }
.my-work-detail__crumb a { color: var(--yp-link); text-decoration: none; }
.my-work-detail__crumb a:hover { text-decoration: underline; }
.my-work-detail__meta { color: var(--yp-text-muted); font-size: 12px; }
@keyframes my-work-detail-in { from { opacity: 0; transform: translateY(6px); } }
@media (max-width: 1279.98px) {
  .my-work-detail__layout { grid-template-columns: minmax(320px, 44%) minmax(0, 1fr); }
  .my-work-detail__fields { padding: var(--yp-space-8) var(--yp-space-6) var(--yp-space-6) var(--yp-space-8); }
  .my-work-detail__activity { padding: var(--yp-space-6); }
}
@media (max-width: 959.98px) {
  .my-work-detail__layout { display: block; height: calc(100dvh - 24px); overflow-y: auto; }
  .my-work-detail__fields,
  .my-work-detail__activity { overflow: visible; padding: var(--yp-space-6) var(--yp-space-4); }
  .my-work-detail__fields { position: static; height: auto; border-right: 0; border-bottom: 1px solid var(--yp-border-subtle); }
}
</style>

<style>
.my-work-detail.el-dialog { --el-dialog-padding-primary: 0; overflow: hidden; border-radius: var(--yp-radius-lg); }
.my-work-detail .el-dialog__header { height: 0; margin: 0; padding: 0; }
.my-work-detail .el-dialog__headerbtn { z-index: 2; top: 12px; right: 12px; }
.my-work-detail .el-dialog__body { padding: 0; }
@media (max-width: 959.98px) {
  .my-work-detail.el-dialog { width: calc(100vw - 24px) !important; margin: 12px auto !important; }
}
</style>
