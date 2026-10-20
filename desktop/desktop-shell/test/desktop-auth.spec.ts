import { createHash } from 'node:crypto'
import { describe, expect, it, vi } from 'vitest'
import {
  createDesktopPkceAttempt,
  DesktopAuthController,
  DesktopAuthHttpError,
  desktopAuthFailureReason,
  exchangeDesktopAuthCode,
  requestDesktopAuthorization,
} from '../src/main/desktop-auth'

const STATE = 's'.repeat(43)
const VERIFIER = 'v'.repeat(43)
const CHALLENGE = 'h'.repeat(43)
const CODE = 'c'.repeat(43)

function fixedAttempt() {
  return { state: STATE, verifier: VERIFIER, challenge: CHALLENGE }
}

function authorization() {
  return {
    authorizationUrl: 'https://open.work.weixin.qq.com/wwopen/sso/qrConnect?state=test',
    expiresAt: new Date(Date.now() + 5 * 60 * 1_000).toISOString(),
  }
}

describe('Electron PKCE 与浏览器登录交接', () => {
  it('生成 32-byte base64url state/verifier 和 S256 challenge', () => {
    const attempt = createDesktopPkceAttempt()
    expect(attempt.state).toMatch(/^[A-Za-z0-9_-]{43}$/)
    expect(attempt.verifier).toMatch(/^[A-Za-z0-9_-]{43}$/)
    expect(attempt.challenge).toBe(
      createHash('sha256').update(attempt.verifier, 'ascii').digest('base64url'),
    )
  })

  it('通过同源 POST 创建 attempt 且只接受企微官方扫码地址', async () => {
    const fetchImplementation = vi.fn(async () => new Response(JSON.stringify(authorization()), {
      status: 201, headers: { 'Content-Type': 'application/json' },
    }))
    const result = await requestDesktopAuthorization(fixedAttempt(), false, {
      webOrigin: 'https://yumpoo.example.com/nested/app', clientVersion: '0.1.0', fetchImplementation,
    })
    expect(result.authorizationUrl).toContain('https://open.work.weixin.qq.com/wwopen/sso/qrConnect')
    const [url, request] = fetchImplementation.mock.calls[0] ?? []
    expect(new URL(url as string).pathname).toBe('/api/v1/electron/auth/attempts')
    expect(JSON.parse(String(request?.body))).toEqual({
      state: STATE, codeChallenge: CHALLENGE, codeChallengeMethod: 'S256', remember: false,
    })
  })

  it('再次登录替换旧 attempt 并重开浏览器，匹配后先本地消费再兑换', async () => {
    const openedUrls: string[] = []
    const exchanges: unknown[] = []
    const statuses: unknown[] = []
    const controller = new DesktopAuthController({
      enabled: true,
      authorize: async () => authorization(),
      createAttempt: fixedAttempt,
      openExternal: async (url) => {
        openedUrls.push(url)
      },
      exchange: async (input, remember) => {
        exchanges.push({ input, remember })
      },
      publishStatus: (value) => statuses.push(value),
    })

    await controller.start()
    await controller.start({ remember: false })
    expect(openedUrls).toHaveLength(2)
    expect(statuses.at(-1)).toEqual({ phase: 'WAITING_FOR_CALLBACK' })

    await controller.handleCallback({ code: CODE, state: STATE })
    expect(exchanges).toEqual([
      { input: { code: CODE, state: STATE, codeVerifier: VERIFIER }, remember: false },
    ])
    expect(statuses.at(-1)).toEqual({ phase: 'SUCCEEDED' })

    await controller.handleCallback({ code: CODE, state: STATE })
    expect(exchanges).toHaveLength(1)
    expect(statuses.at(-1)).toEqual({
      phase: 'FAILED',
      errorCode: 'NO_PENDING_ATTEMPT',
    })
  })

  it('拒绝错误 state 但保留正确回调，并拒绝过期 attempt', async () => {
    let now = 1_000
    const exchange = vi.fn(async () => undefined)
    const statuses: unknown[] = []
    const controller = new DesktopAuthController({
      enabled: true,
      authorize: async () => authorization(),
      now: () => now,
      createAttempt: fixedAttempt,
      openExternal: async () => undefined,
      exchange,
      publishStatus: (value) => statuses.push(value),
    })

    await controller.start()
    await controller.handleCallback({ code: CODE, state: 'x'.repeat(43) })
    expect(exchange).not.toHaveBeenCalled()
    expect(statuses.at(-1)).toEqual({
      phase: 'FAILED',
      errorCode: 'STATE_MISMATCH',
    })
    await controller.handleCallback({ code: CODE, state: STATE })
    expect(exchange).toHaveBeenCalledOnce()

    await controller.start()
    now += 5 * 60 * 1_000
    await controller.handleCallback({ code: CODE, state: STATE })
    expect(exchange).toHaveBeenCalledOnce()
    expect(statuses.at(-1)).toEqual({
      phase: 'FAILED',
      errorCode: 'ATTEMPT_EXPIRED',
    })
  })

  it('深链先于 openExternal 返回时不以等待状态覆盖兑换结果', async () => {
    let releaseBrowser: (() => void) | undefined
    const statuses: unknown[] = []
    const controller = new DesktopAuthController({
      enabled: true,
      authorize: async () => authorization(),
      createAttempt: fixedAttempt,
      openExternal: () =>
        new Promise<void>((resolve) => {
          releaseBrowser = resolve
        }),
      exchange: async () => undefined,
      publishStatus: (value) => statuses.push(value),
    })

    const start = controller.start()
    await controller.handleCallback({ code: CODE, state: STATE })
    releaseBrowser?.()
    await start

    expect(statuses.at(-1)).toEqual({ phase: 'SUCCEEDED' })
    expect(statuses.filter((value) =>
      (value as { phase?: string }).phase === 'WAITING_FOR_CALLBACK',
    )).toHaveLength(0)
  })

  it('同源 POST 兑换并发送固定 Electron 客户端头', async () => {
    const bundle = {
      sessionCredential: 'a'.repeat(43), csrfCredential: 'b'.repeat(43),
      absoluteExpiresAt: '2026-08-18T00:00:00Z',
    }
    const fetchImplementation = vi.fn(async () => new Response(JSON.stringify(bundle), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }))
    expect(await exchangeDesktopAuthCode(
      { code: CODE, state: STATE, codeVerifier: VERIFIER },
      {
        webOrigin: 'https://yumpoo.example.com/application',
        clientVersion: '0.1.0',
        fetchImplementation,
      },
    )).toEqual(bundle)

    const [url, request] = fetchImplementation.mock.calls[0] ?? []
    expect(url).toBe('https://yumpoo.example.com/api/v1/electron/auth/exchange')
    expect(request).toMatchObject({
      method: 'POST',
      redirect: 'error',
      credentials: 'omit',
      headers: {
        'Content-Type': 'application/json',
        'X-Client-Type': 'ELECTRON',
        'X-Client-Version': '0.1.0',
        'X-Client-Protocol-Version': '1',
      },
    })
    expect(JSON.parse(String(request?.body))).toEqual({
      handoffCode: CODE,
      state: STATE,
      codeVerifier: VERIFIER,
    })
  })

  it('授权服务拒绝时报 AUTH_SERVICE_FAILED 并记录 HTTP 状态，不尝试打开浏览器', async () => {
    const openExternal = vi.fn(async () => undefined)
    const statuses: unknown[] = []
    const failures: unknown[] = []
    const fetchImplementation = vi.fn(async () => new Response('{}', { status: 401 }))
    const controller = new DesktopAuthController({
      enabled: true,
      authorize: (attempt, remember) => requestDesktopAuthorization(attempt, remember, {
        webOrigin: 'https://yumpoo.example.com', clientVersion: '1.0.0', fetchImplementation,
      }),
      createAttempt: fixedAttempt,
      openExternal,
      exchange: async () => undefined,
      publishStatus: (value) => statuses.push(value),
      logFailure: (stage, reason) => failures.push({ stage, reason }),
    })

    await controller.start()
    expect(openExternal).not.toHaveBeenCalled()
    expect(statuses.at(-1)).toEqual({ phase: 'FAILED', errorCode: 'AUTH_SERVICE_FAILED' })
    expect(failures).toEqual([{ stage: 'authorize', reason: 'HTTP_401' }])

    // The failed attempt is released, so the next click starts a fresh one instead of AUTH_IN_PROGRESS.
    fetchImplementation.mockResolvedValueOnce(new Response(JSON.stringify(authorization()), { status: 201 }))
    await controller.start()
    expect(openExternal).toHaveBeenCalledOnce()
    expect(statuses.at(-1)).toEqual({ phase: 'WAITING_FOR_CALLBACK' })
  })

  it('非企微地址同样按授权服务失败处理', async () => {
    const statuses: unknown[] = []
    const fetchImplementation = vi.fn(async () => new Response(JSON.stringify({
      ...authorization(), authorizationUrl: 'https://evil.example.com/login',
    }), { status: 201 }))
    const controller = new DesktopAuthController({
      enabled: true,
      authorize: (attempt, remember) => requestDesktopAuthorization(attempt, remember, {
        webOrigin: 'https://yumpoo.example.com', clientVersion: '1.0.0', fetchImplementation,
      }),
      createAttempt: fixedAttempt,
      openExternal: async () => undefined,
      exchange: async () => undefined,
      publishStatus: (value) => statuses.push(value),
    })

    await controller.start()
    expect(statuses.at(-1)).toEqual({ phase: 'FAILED', errorCode: 'AUTH_SERVICE_FAILED' })
  })

  it('只有 openExternal 失败才报 BROWSER_OPEN_FAILED，兑换失败记录原因', async () => {
    const statuses: unknown[] = []
    const failures: unknown[] = []
    const options = {
      enabled: true,
      authorize: async () => authorization(),
      createAttempt: fixedAttempt,
      publishStatus: (value: unknown) => statuses.push(value),
      logFailure: (stage: string, reason: string) => failures.push({ stage, reason }),
    }
    const browserless = new DesktopAuthController({
      ...options,
      openExternal: async () => { throw new Error('no handler') },
      exchange: async () => undefined,
    })
    await browserless.start()
    expect(statuses.at(-1)).toEqual({ phase: 'FAILED', errorCode: 'BROWSER_OPEN_FAILED' })

    const exchangeFails = new DesktopAuthController({
      ...options,
      openExternal: async () => undefined,
      exchange: async () => { throw new Error('net::ERR_CONNECTION_RESET') },
    })
    await exchangeFails.start()
    await exchangeFails.handleCallback({ code: CODE, state: STATE })
    expect(statuses.at(-1)).toEqual({ phase: 'FAILED', errorCode: 'EXCHANGE_FAILED' })
    expect(failures).toEqual([
      { stage: 'open-browser', reason: 'Error' },
      { stage: 'exchange', reason: 'ERR_CONNECTION_RESET' },
    ])
  })

  it('到期主动推送 ATTEMPT_EXPIRED，取消后推送 IDLE 且忽略迟到的回调', async () => {
    vi.useFakeTimers()
    try {
      const exchange = vi.fn(async () => undefined)
      const statuses: unknown[] = []
      const controller = new DesktopAuthController({
        enabled: true,
        authorize: async () => ({ ...authorization(), expiresAt: new Date(Date.now() + 10 * 60 * 1_000).toISOString() }),
        createAttempt: fixedAttempt,
        openExternal: async () => undefined,
        exchange,
        publishStatus: (value) => statuses.push(value),
      })

      await controller.start()
      vi.advanceTimersByTime(5 * 60 * 1_000)
      expect(statuses.at(-1)).toEqual({ phase: 'FAILED', errorCode: 'ATTEMPT_EXPIRED' })

      await controller.start()
      controller.cancel()
      expect(statuses.at(-1)).toEqual({ phase: 'IDLE' })
      await controller.handleCallback({ code: CODE, state: STATE })
      expect(exchange).not.toHaveBeenCalled()
      expect(statuses.at(-1)).toEqual({ phase: 'FAILED', errorCode: 'NO_PENDING_ATTEMPT' })
    } finally {
      vi.useRealTimers()
    }
  })

  it('失败原因只保留状态码、网络错误码或错误类名', () => {
    expect(desktopAuthFailureReason(new DesktopAuthHttpError(503))).toBe('HTTP_503')
    expect(desktopAuthFailureReason(new TypeError('fetch failed https://x/?state=secret'))).toBe('TypeError')
    expect(desktopAuthFailureReason('boom')).toBe('UNKNOWN')
  })
})
