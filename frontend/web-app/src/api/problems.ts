import {
  ErrorCode,
  ErrorResponseFromJSON,
  FetchError,
  ResponseError,
  instanceOfErrorResponse,
  type ErrorResponse,
  type FieldError,
  type Middleware,
} from '@yumpoo/api-client'

export type ApiProblem =
  | {
      kind: 'response'
      status: number
      error: ErrorResponse
      location?: string
      retryAfter?: string
    }
  | {
      kind: 'fallback'
      message: string
      requestId?: string
    }

type GlobalProblemListener = (problem: ApiProblem) => void

const globalProblemListeners = new Set<GlobalProblemListener>()

export const globalProblemMiddleware: Middleware = {
  async post({ response }) {
    const problem = await responseProblem(response)
    if (problem && isGlobalProblem(problem)) {
      for (const listener of globalProblemListeners) {
        listener(problem)
      }
    }
    return response
  },
}

export function subscribeGlobalProblems(listener: GlobalProblemListener): () => void {
  globalProblemListeners.add(listener)
  return () => globalProblemListeners.delete(listener)
}

export async function toApiProblem(reason: unknown): Promise<ApiProblem> {
  if (reason instanceof ResponseError) {
    return await responseProblem(reason.response) ?? fallbackProblem(
      '请求未能完成，请稍后重试。',
      reason.response.headers.get('X-Request-Id') ?? undefined,
    )
  }
  if (reason instanceof FetchError || reason instanceof TypeError) {
    return fallbackProblem('网络连接异常，请检查连接后重试。')
  }
  return fallbackProblem('系统暂时无法处理请求，请稍后重试。')
}

export function localProblem(message: string): ApiProblem {
  return fallbackProblem(message)
}

export function problemMessage(problem: ApiProblem): string {
  return problem.kind === 'response' ? problem.error.message : problem.message
}

export function problemRequestId(problem: ApiProblem): string | undefined {
  return problem.kind === 'response' ? problem.error.requestId : problem.requestId
}

export function isProblemCode(problem: ApiProblem, code: ErrorCode): boolean {
  return problem.kind === 'response' && problem.error.code === code
}

export function isProblemStatus(problem: ApiProblem, status: number): boolean {
  return problem.kind === 'response' && problem.status === status
}

function fallbackProblem(message: string, requestId?: string): ApiProblem {
  return {
    kind: 'fallback',
    message,
    ...(requestId ? { requestId } : {}),
  }
}

async function responseProblem(response: Response): Promise<ApiProblem | undefined> {
  if (response.status < 400) {
    return undefined
  }
  try {
    const raw: unknown = await response.clone().json()
    if (!raw || typeof raw !== 'object' || !instanceOfErrorResponse(raw)) {
      return undefined
    }
    const error = ErrorResponseFromJSON(raw)
    if (!validErrorResponse(error)) {
      return undefined
    }
    const location = response.headers.get('Location')
    const retryAfter = response.headers.get('Retry-After')
    return {
      kind: 'response',
      status: response.status,
      error,
      ...(location ? { location } : {}),
      ...(retryAfter ? { retryAfter } : {}),
    }
  } catch {
    return undefined
  }
}

function validErrorResponse(error: ErrorResponse): boolean {
  return typeof error.message === 'string'
    && error.message.length > 0
    && typeof error.requestId === 'string'
    && error.requestId.length > 0
    && typeof error.retryable === 'boolean'
    && Array.isArray(error.fieldErrors)
}

/** 失败分类：输入问题由用户修正，冲突要刷新后重试，内部错误要带诊断信息联系管理员。 */
export type ProblemKind = 'input' | 'denied' | 'conflict' | 'internal' | 'network'

export function problemKind(problem: ApiProblem): ProblemKind {
  if (problem.kind === 'fallback') return problem.requestId ? 'internal' : 'network'
  if (problem.status >= 500) return 'internal'
  if (problem.status === 401 || problem.status === 403 || problem.status === 404) return 'denied'
  if ([409, 412, 428, 429].includes(problem.status)) return 'conflict'
  return 'input'
}

export function problemFieldErrors(problem: ApiProblem): FieldError[] {
  return problem.kind === 'response' ? problem.error.fieldErrors : []
}

/** 复制给管理员的诊断信息，凭请求 ID 与时间在运行日志中定位；不含请求正文。 */
export function problemDiagnostics(problem: ApiProblem, at: Date, fieldLabel?: (field: string) => string): string {
  const lines = [
    `时间：${at.toISOString()}`,
    `客户端：${window.yumpooDesktop ? '桌面端' : 'Web'}`,
    `页面：${window.location.pathname}`,
    `提示：${problemMessage(problem)}`,
  ]
  if (problem.kind === 'response') {
    lines.push(`状态：HTTP ${problem.status}`, `错误码：${problem.error.code}`)
    if (problem.error.details.reason) lines.push(`原因：${problem.error.details.reason}`)
    for (const error of problem.error.fieldErrors) {
      const label = fieldLabel?.(error.field)
      lines.push(`字段：${label ? `${label}（${error.field}）` : error.field} · ${error.code} · ${error.message}`)
    }
  }
  const requestId = problemRequestId(problem)
  if (requestId) lines.push(`请求 ID：${requestId}`)
  return lines.join('\n')
}

function isGlobalProblem(problem: ApiProblem): boolean {
  return problem.kind === 'response'
    && (
      (problem.status === 401
        && problem.error.code === ErrorCode.AuthenticationRequired)
      || (problem.status === 403
        && problem.error.code === ErrorCode.AccountDisabled)
      || (problem.status === 426
        && problem.error.code === ErrorCode.ClientUpgradeRequired)
    )
}
