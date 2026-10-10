<script setup lang="ts">
import {
  ListProjectWorkItemFilterOptionsFieldEnum, ProjectLifecycle, WorkItemStatusCategory,
  readCsrfToken, type ProjectDetail,
} from '@yumpoo/api-client'
import { ElButton, ElMessage, ElMessageBox } from 'element-plus'
import { computed, ref } from 'vue'
import { projectsApi, workItemsApi } from '../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import { notifyProjectLifecycleChanged } from '../../composables/projectLifecycleEvents'

const props = defineProps<{ project: ProjectDetail }>()
const emit = defineEmits<{ changed: []; problem: [problem: ApiProblem] }>()
const busy = ref(false)
const canOperate = computed(() => props.project.capabilities.canArchive || props.project.capabilities.canRestore)

async function run(operation: (token: string) => Promise<unknown>, success: string): Promise<void> {
  const token = readCsrfToken()
  if (!token) { emit('problem', localProblem('缺少 CSRF 凭据，请刷新后重试。')); return }
  busy.value = true
  try {
    await operation(token)
    ElMessage.success(success)
    notifyProjectLifecycleChanged(props.project.id)
    emit('changed')
  } catch (reason) {
    const problem = await toApiProblem(reason)
    if (isProblemStatus(problem, 412)) {
      ElMessage.warning('项目已被其他操作更新，已刷新为最新状态。')
      emit('changed')
      notifyProjectLifecycleChanged(props.project.id)
    }
    emit('problem', problem)
  } finally { busy.value = false }
}

async function openWorkItemCount(): Promise<number> {
  const projectId = props.project.id
  const labels = await workItemsApi.getProjectWorkItemLabels({ projectId })
  const openStatuses = new Set(labels.statuses
    .filter(label => label.statusCategory === WorkItemStatusCategory.Todo || label.statusCategory === WorkItemStatusCategory.InProgress)
    .map(label => label.code))
  let count = 0, cursor: string | undefined
  do {
    const page = await workItemsApi.listProjectWorkItemFilterOptions({
      projectId, field: ListProjectWorkItemFilterOptionsFieldEnum.Status, limit: 100,
      ...(cursor ? { cursor } : {}),
    })
    count += page.items.filter(option => openStatuses.has(option.value)).reduce((total, option) => total + option.count, 0)
    cursor = page.nextCursor ?? undefined
  } while (cursor)
  return count
}

async function archive(): Promise<void> {
  if (busy.value) return
  busy.value = true
  let warning: string
  try { warning = `当前有 ${await openWorkItemCount()} 个未关闭工作项，仍可归档。` }
  catch { warning = '未关闭工作项数量暂无法读取，仍可归档。' }
  try {
    await ElMessageBox.confirm(`${warning}归档后普通成员将无法访问，项目不再计入统计。负责人或企业管理员可只读浏览和恢复。`, '归档项目', {
      type: 'warning', confirmButtonText: '确认归档', cancelButtonText: '取消',
    })
    await run(token => projectsApi.archiveProject({
      projectId: props.project.id, xXSRFTOKEN: token, ifMatch: props.project.etag,
      idempotencyKey: crypto.randomUUID(),
    }), '项目已归档')
  } catch { return }
  finally { busy.value = false }
}

async function restore(): Promise<void> {
  if (busy.value) return
  try {
    await ElMessageBox.confirm('恢复后项目重新对成员可见，并计入统计。', '恢复项目', {
      type: 'warning', confirmButtonText: '确认恢复', cancelButtonText: '取消',
    })
  } catch { return }
  await run(token => projectsApi.restoreProject({
    projectId: props.project.id, xXSRFTOKEN: token, ifMatch: props.project.etag,
    idempotencyKey: crypto.randomUUID(),
  }), '项目已恢复')
}
</script>

<template>
  <section
    v-if="canOperate"
    class="lifecycle-actions"
    aria-labelledby="lifecycle-actions-title"
  >
    <div>
      <h2 id="lifecycle-actions-title">
        危险区域
      </h2>
      <p>归档项目仅负责人和企业管理员可只读浏览或恢复，不再计入统计。</p>
    </div>
    <div class="lifecycle-actions__buttons">
      <el-button
        v-if="project.capabilities.canArchive && project.lifecycle === ProjectLifecycle.Active"
        :loading="busy"
        @click="archive"
      >
        归档项目
      </el-button>
      <el-button
        v-if="project.capabilities.canRestore"
        type="primary"
        :loading="busy"
        @click="restore"
      >
        恢复项目
      </el-button>
    </div>
  </section>
</template>

<style scoped>
.lifecycle-actions {
  display: grid;
  gap: var(--yp-space-4);
  margin-bottom: var(--yp-space-5);
  padding: var(--yp-space-6);
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-surface);
}
.lifecycle-actions h2,
.lifecycle-actions p { margin: 0; }
.lifecycle-actions h2 { font-size: 16px; font-weight: 600; }
.lifecycle-actions p { margin-top: var(--yp-space-1); color: var(--yp-text-secondary); }
.lifecycle-actions__buttons { display: flex; flex-wrap: wrap; gap: var(--yp-space-2); }
</style>
