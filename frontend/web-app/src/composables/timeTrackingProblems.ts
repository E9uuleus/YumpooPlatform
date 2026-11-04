import { problemMessage, toApiProblem } from '../api/problems'

const reasons: Record<string, string> = {
  TIMER_DISABLED: '计时器已关闭，可在“我的工时”的设置中重新开启。',
  SESSION_NOT_EDITABLE: '这条记录已不能修改：它可能正在计时、已被删除，或所属项目已归档。',
  TIMER_CHANGED: '记录已在其他窗口变化，已刷新，请重新操作。',
  PROJECT_ARCHIVED: '该项目已归档，不能再记录工时。',
  PROJECT_ENDED: '该项目已结束，不能再记录工时。',
}

/** Chinese explanation for time-tracking failures, preferring the stable `details.reason` over the generic problem text. */
export async function timeProblemMessage(error: unknown): Promise<string> {
  if (error instanceof Error && !('response' in error)) return error.message
  const problem = await toApiProblem(error)
  if (problem.kind === 'response') {
    const reason = problem.error.details?.reason
    if (reason && reasons[reason]) return reasons[reason]
    if (problem.status === 412) return '记录已在其他窗口更新，已刷新，请重新操作。'
  }
  return problemMessage(problem)
}
