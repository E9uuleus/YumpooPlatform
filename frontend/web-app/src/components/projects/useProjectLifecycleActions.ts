import {
  ListProjectWorkItemFilterOptionsFieldEnum, WorkItemStatusCategory, readCsrfToken, type ProjectDetail,
} from '@yumpoo/api-client'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ref } from 'vue'
import { projectsApi, workItemsApi } from '../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import { notifyProjectLifecycleChanged } from '../../composables/projectLifecycleEvents'
import { changeProjectEnded } from './projectEnding'

/** 结束 / 重新打开、归档、恢复：项目设置的生命周期区与页头项目菜单共用。 */
export function useProjectLifecycleActions(
  project: () => ProjectDetail,
  on: { changed: () => void; problem: (problem: ApiProblem) => void },
) {
  const busy = ref(false)

  async function toggleEnded(): Promise<void> {
    if (busy.value) return
    busy.value = true
    try { if (await changeProjectEnded(project())) on.changed() } finally { busy.value = false }
  }

  async function run(operation: (token: string) => Promise<unknown>, success: string): Promise<void> {
    const token = readCsrfToken()
    if (!token) { on.problem(localProblem('缺少 CSRF 凭据，请刷新后重试。')); return }
    busy.value = true
    try {
      await operation(token)
      ElMessage.success(success)
      notifyProjectLifecycleChanged(project().id)
      on.changed()
    } catch (reason) {
      const problem = await toApiProblem(reason)
      if (isProblemStatus(problem, 412)) {
        ElMessage.warning('项目已被其他操作更新，已刷新为最新状态。')
        on.changed()
        notifyProjectLifecycleChanged(project().id)
      }
      on.problem(problem)
    } finally { busy.value = false }
  }

  async function openWorkItemCount(): Promise<number> {
    const projectId = project().id
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
        type: 'warning', confirmButtonText: '确认归档', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger',
      })
      await run(token => projectsApi.archiveProject({
        projectId: project().id, xXSRFTOKEN: token, ifMatch: project().etag,
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
      projectId: project().id, xXSRFTOKEN: token, ifMatch: project().etag,
      idempotencyKey: crypto.randomUUID(),
    }), '项目已恢复')
  }
  return { busy, toggleEnded, archive, restore }
}
