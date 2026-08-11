import { createHash } from 'node:crypto'
import { describe, expect, it, vi } from 'vitest'
import {
  buildDesktopAuthorizationUrl,
  createDesktopPkceAttempt,
  DesktopAuthController,
  exchangeDesktopAuthCode,
} from '../src/main/desktop-auth'

const STATE = 's'.repeat(43)
const VERIFIER = 'v'.repeat(43)
const CHALLENGE = 'h'.repeat(43)
const CODE = 'c'.repeat(43)

function fixedAttempt() {
  return { state: STATE, verifier: VERIFIER, challenge: CHALLENGE }
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

  it('授权地址始终从配置 origin 和固定路径构造', () => {
    const url = buildDesktopAuthorizationUrl(
      'https://yumpoo.example.com/nested/app',
      fixedAttempt(),
    )
    expect(url.origin).toBe('https://yumpoo.example.com')
    expect(url.pathname).toBe('/_m0/m0-15/electron/auth/authorize')
    expect(url.searchParams.get('state')).toBe(STATE)
    expect(url.searchParams.get('codeChallenge')).toBe(CHALLENGE)
    expect(url.searchParams.get('codeChallengeMethod')).toBe('S256')
  })

  it('只允许一个 pending attempt，并在匹配后先本地消费再兑换', async () => {
    const openedUrls: string[] = []
    const exchanges: unknown[] = []
    const statuses: unknown[] = []
    const controller = new DesktopAuthController({
      enabled: true,
      webOrigin: 'https://yumpoo.example.com',
      createAttempt: fixedAttempt,
      openExternal: async (url) => {
        openedUrls.push(url)
      },
      exchange: async (input) => {
        exchanges.push(input)
      },
      publishStatus: (value) => statuses.push(value),
    })

    await controller.start()
    await controller.start()
    expect(openedUrls).toHaveLength(1)
    expect(statuses).toContainEqual({
      phase: 'FAILED',
      errorCode: 'AUTH_IN_PROGRESS',
    })

    await controller.handleCallback({ code: CODE, state: STATE })
    expect(exchanges).toEqual([
      { code: CODE, state: STATE, codeVerifier: VERIFIER },
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
      webOrigin: 'https://yumpoo.example.com',
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
      webOrigin: 'https://yumpoo.example.com',
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
    const fetchImplementation = vi.fn(async () => new Response(null, { status: 204 }))
    await exchangeDesktopAuthCode(
      { code: CODE, state: STATE, codeVerifier: VERIFIER },
      {
        webOrigin: 'https://yumpoo.example.com/application',
        clientVersion: '0.0.0',
        fetchImplementation,
      },
    )

    const [url, request] = fetchImplementation.mock.calls[0] ?? []
    expect(url).toBeInstanceOf(URL)
    expect((url as URL).href).toBe(
      'https://yumpoo.example.com/_m0/m0-15/electron/auth/exchange',
    )
    expect(request).toMatchObject({
      method: 'POST',
      redirect: 'error',
      headers: {
        'Content-Type': 'application/json',
        'X-Client-Type': 'ELECTRON',
        'X-Client-Version': '0.0.0',
        'X-Client-Protocol-Version': '1',
      },
    })
    expect(JSON.parse(String(request?.body))).toEqual({
      code: CODE,
      state: STATE,
      codeVerifier: VERIFIER,
    })
  })
})
