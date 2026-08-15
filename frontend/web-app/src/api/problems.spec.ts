import { ErrorCode, ResponseError, type ResponseContext } from '@yumpoo/api-client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { globalProblemMiddleware, subscribeGlobalProblems, toApiProblem } from './problems'

function errorResponse(status: number, code: string, body: Partial<Record<string, unknown>> = {}): Response {
  return new Response(JSON.stringify({
    code, message: '安全错误信息', requestId: 'req-123', retryable: false, fieldErrors: [], details: {}, ...body,
  }), { status, headers: { 'Content-Type': 'application/json' } })
}

async function runMiddleware(response: Response): Promise<void> {
  await globalProblemMiddleware.post?.({ response } as ResponseContext)
}

describe('生成客户端统一错误适配', () => {
  const cleanups: Array<() => void> = []
  afterEach(() => {
    cleanups.splice(0).forEach(cleanup => cleanup())
    vi.restoreAllMocks()
  })

  it.each([
    [401, ErrorCode.AuthenticationRequired],
    [403, ErrorCode.AccountDisabled],
    [426, ErrorCode.ClientUpgradeRequired],
  ])('只接管全局状态 %s / %s', async (status, code) => {
    const listener = vi.fn()
    cleanups.push(subscribeGlobalProblems(listener))
    await runMiddleware(errorResponse(status, code))
    expect(listener).toHaveBeenCalledOnce()
  })

  it.each([
    [403, ErrorCode.AccessDenied],
    [409, ErrorCode.IdempotencyKeyReused],
    [403, 'FUTURE_UNKNOWN_CODE'],
  ])('将业务或未知错误留在页面上下文 %s / %s', async (status, code) => {
    const listener = vi.fn()
    cleanups.push(subscribeGlobalProblems(listener))
    await runMiddleware(errorResponse(status, code))
    expect(listener).not.toHaveBeenCalled()
  })

  it('畸形 JSON 不触发全局跳转，并保留响应级安全兜底', async () => {
    const listener = vi.fn()
    cleanups.push(subscribeGlobalProblems(listener))
    const response = new Response('{broken', { status: 401, headers: { 'X-Request-Id': 'req-header' } })
    await runMiddleware(response)
    expect(listener).not.toHaveBeenCalled()
    const problem = await toApiProblem(new ResponseError(response))
    expect(problem).toMatchObject({ kind: 'fallback', requestId: 'req-header' })
  })
})
