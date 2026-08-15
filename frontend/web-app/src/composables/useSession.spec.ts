import {
  AuthenticationClientType,
  AuthenticationRole,
  ClientCompatibility,
  ErrorCode,
  ResponseError,
  type CurrentAuthentication,
} from '@yumpoo/api-client'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  getCurrentAuthentication: vi.fn(),
  logoutCurrentSession: vi.fn(),
  csrf: 'csrf-token' as string | undefined,
}))

vi.mock('../api/client', () => ({ authenticationApi: api }))
vi.mock('@yumpoo/api-client', async (importOriginal) => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => api.csrf,
}))

import { ensureAuthentication, useSession } from './useSession'

function currentAuthentication(compatibility = ClientCompatibility.Supported): CurrentAuthentication {
  return {
    user: { id: crypto.randomUUID(), displayName: '测试用户' },
    company: {
      id: crypto.randomUUID(), displayName: '测试公司', timezone: 'Asia/Shanghai', weekStartDay: 'MONDAY',
    },
    roles: new Set([AuthenticationRole.CompanyAdmin]),
    client: { type: AuthenticationClientType.Web, compatibility },
  } as CurrentAuthentication
}

function responseError(status: number, code: ErrorCode): ResponseError {
  return new ResponseError(new Response(JSON.stringify({
    code, message: '安全错误', requestId: 'req-session', retryable: false, fieldErrors: [], details: {},
  }), { status, headers: { 'Content-Type': 'application/json' } }))
}

describe('全局会话状态', () => {
  beforeEach(() => {
    api.getCurrentAuthentication.mockReset()
    api.logoutCurrentSession.mockReset()
    api.csrf = 'csrf-token'
    const session = useSession()
    session.phase.value = 'checking'
    session.authentication.value = undefined
    session.blockingProblem.value = undefined
    session.actionProblem.value = undefined
  })

  it('GET /auth/me 并发单飞并建立认证主体', async () => {
    let resolve!: (value: CurrentAuthentication) => void
    api.getCurrentAuthentication.mockReturnValue(new Promise<CurrentAuthentication>((done) => { resolve = done }))
    const first = ensureAuthentication()
    const second = ensureAuthentication()
    expect(api.getCurrentAuthentication).toHaveBeenCalledOnce()
    resolve(currentAuthentication())
    await Promise.all([first, second])
    expect(useSession().phase.value).toBe('authenticated')
  })

  it('BLOCKED 客户端进入升级阻断状态', async () => {
    api.getCurrentAuthentication.mockResolvedValue(currentAuthentication(ClientCompatibility.Blocked))
    await ensureAuthentication()
    expect(useSession().phase.value).toBe('upgradeRequired')
  })

  it('退出 204 或已失效 401 后转为匿名，以便重新认证', async () => {
    const session = useSession()
    session.phase.value = 'authenticated'
    session.authentication.value = currentAuthentication()
    api.logoutCurrentSession.mockResolvedValueOnce(undefined)
    await expect(session.logout()).resolves.toBe(true)
    expect(session.phase.value).toBe('anonymous')

    session.phase.value = 'authenticated'
    session.authentication.value = currentAuthentication()
    api.logoutCurrentSession.mockRejectedValueOnce(responseError(401, ErrorCode.AuthenticationRequired))
    await expect(session.logout()).resolves.toBe(true)
    expect(session.phase.value).toBe('anonymous')
  })

  it('缺少 CSRF、账号禁用和网络失败都不伪装退出成功', async () => {
    const session = useSession()
    session.phase.value = 'authenticated'
    session.authentication.value = currentAuthentication()
    api.csrf = undefined
    await expect(session.logout()).resolves.toBe(false)
    expect(api.logoutCurrentSession).not.toHaveBeenCalled()

    api.csrf = 'csrf-token'
    api.logoutCurrentSession.mockRejectedValueOnce(responseError(403, ErrorCode.AccountDisabled))
    await expect(session.logout()).resolves.toBe(false)
    expect(session.phase.value).toBe('accountDisabled')

    session.phase.value = 'authenticated'
    session.authentication.value = currentAuthentication()
    api.logoutCurrentSession.mockRejectedValueOnce(new TypeError('offline'))
    await expect(session.logout()).resolves.toBe(false)
    expect(session.phase.value).toBe('authenticated')
    expect(session.actionProblem.value?.kind).toBe('fallback')
  })
})
