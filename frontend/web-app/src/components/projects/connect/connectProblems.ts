import { ResponseError } from '@yumpoo/api-client'
import { localProblem, toApiProblem, type ApiProblem } from '../../../api/problems'

export class ConnectFailure extends Error {
  constructor(readonly problem: ApiProblem) { super('Connection request failed') }
}

export async function toConnectProblem(reason: unknown, options: {
  projectName?: (id: string) => string
  operation?: 'link' | 'createAndLink'
} = {}): Promise<ApiProblem> {
  if (reason instanceof ConnectFailure) return reason.problem
  const problem = await toApiProblem(reason)
  if (problem.kind !== 'response') return problem
  const detailReason = problem.error.details.reason
  let message: string | undefined
  if (detailReason === 'PROJECT_ARCHIVED') message = options.operation ? '目标项目已归档' : '项目已归档'
  if (detailReason === 'CONNECTION_LIMIT') message = '每个工作项在一列中最多连接 50 个工作项'
  if (problem.error.fieldErrors.some(field => field.field === 'targetProjectId' && field.code === 'CONNECT_TARGET_NOT_IN_COLUMN')
    || detailReason === 'CONNECT_TARGET_NOT_IN_COLUMN') message = '该项目已不在此连接列中，请刷新'
  if (detailReason === 'CONNECT_TARGET_IN_USE' && reason instanceof ResponseError) {
    const raw: unknown = await reason.response.clone().json().catch(() => undefined)
    const details = (raw as { details?: { targetProjectId?: unknown; activeConnectionCount?: unknown } } | undefined)?.details
    if (typeof details?.targetProjectId === 'string' && typeof details.activeConnectionCount === 'number') {
      message = `「${options.projectName?.(details.targetProjectId) ?? '目标项目'}」中还有 ${details.activeConnectionCount} 个连接，请先解除后再移除该项目。`
    }
  }
  if (problem.status === 412) message = '连接信息已被其他成员更新，已刷新，请核对后重试。'
  return message ? { ...problem, error: { ...problem.error, message } } : problem
}

export function missingConnectCsrf(): ConnectFailure { return new ConnectFailure(localProblem('缺少 CSRF 凭据，请刷新后重试。')) }
