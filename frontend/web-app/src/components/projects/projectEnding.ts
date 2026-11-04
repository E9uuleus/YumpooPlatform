import { readCsrfToken } from '@yumpoo/api-client'
import { ElMessage, ElMessageBox } from 'element-plus'
import { projectsApi } from '../../api/client'
import { isProblemStatus, problemMessage, toApiProblem } from '../../api/problems'
import { notifyProjectLifecycleChanged } from '../../composables/projectLifecycleEvents'

/** Confirms, then ends a running project or reopens an ended one; true when the caller should reload. */
export async function changeProjectEnded(project: { id: string; name: string; etag: string; endedAt?: Date | null }): Promise<boolean> {
  const ending = !project.endedAt
  try {
    await ElMessageBox.confirm(ending
      ? `结束「${project.name}」后项目内容变为只读：不能新建或编辑工作项、讨论和工时，项目内正在运行的计时会停止。项目仍对成员可见并计入统计，可以随时重新打开。`
      : `重新打开「${project.name}」后，成员可以继续编辑工作项、讨论和记录工时。`,
    ending ? '结束项目' : '重新打开项目',
    { type: 'warning', confirmButtonText: ending ? '确认结束' : '确认重新打开', cancelButtonText: '取消' })
  } catch { return false }
  const token = readCsrfToken()
  if (!token) { ElMessage.error('缺少 CSRF 凭据，请刷新后重试。'); return false }
  const request = { projectId: project.id, xXSRFTOKEN: token, ifMatch: project.etag, idempotencyKey: crypto.randomUUID() }
  try {
    await (ending ? projectsApi.endProject(request) : projectsApi.resumeProject(request))
    ElMessage.success(ending ? '项目已结束' : '项目已重新打开')
  } catch (reason) {
    const problem = await toApiProblem(reason)
    if (!isProblemStatus(problem, 412)) { ElMessage.error(problemMessage(problem)); return false }
    ElMessage.warning('项目已被其他操作更新，已刷新为最新状态。')
  }
  notifyProjectLifecycleChanged(project.id)
  return true
}
