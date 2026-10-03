import { describe, expect, it } from 'vitest'
import { ResponseError } from '@yumpoo/api-client'
import { toConnectProblem } from './connectProblems'

describe('连接错误映射', () => {
  it.each([
    [undefined, '项目已归档'],
    ['link', '目标项目已归档'],
    ['createAndLink', '目标项目已归档'],
  ] as const)('归档文案仅在 %s 关联操作中指向目标项目', async (operation, message) => {
    const reason = new ResponseError(new Response(JSON.stringify({ code: 'INVALID_STATE_TRANSITION', message: '归档', requestId: 'test', retryable: false,
      fieldErrors: [], details: { reason: 'PROJECT_ARCHIVED' } }), { status: 409 }))
    expect(await toConnectProblem(reason, operation ? { operation } : {})).toMatchObject({ kind: 'response', error: { message } })
  })
  it('按稳定字段错误码识别被移除目标，不依赖服务端文案', async () => {
    const problem = await toConnectProblem(new ResponseError(new Response(JSON.stringify({ code: 'VALIDATION_FAILED', message: '目标无效', requestId: 'test', retryable: false,
      fieldErrors: [{ field: 'targetProjectId', code: 'CONNECT_TARGET_NOT_IN_COLUMN', message: '已移除' }], details: {} }), { status: 422 })))
    expect(problem).toMatchObject({ kind: 'response', error: { message: '该项目已不在此连接列中，请刷新' } })
  })
})
