import { createHash, randomBytes, timingSafeEqual } from 'node:crypto'
import type {
  DesktopAuthErrorCode,
  DesktopAuthStatus,
} from '@yumpoo/preload-contract'

const AUTHORIZATION_PATH = '/_m0/m0-15/electron/auth/authorize'
const EXCHANGE_PATH = '/_m0/m0-15/electron/auth/exchange'
const ATTEMPT_TTL_MS = 5 * 60 * 1_000
const EXCHANGE_TIMEOUT_MS = 15_000

export interface DesktopPkceAttempt {
  readonly state: string
  readonly verifier: string
  readonly challenge: string
}

export interface DesktopAuthCallback {
  readonly code: string
  readonly state: string
}

export interface DesktopAuthExchangeInput extends DesktopAuthCallback {
  readonly codeVerifier: string
}

export interface DesktopAuthControllerOptions {
  readonly enabled: boolean
  readonly webOrigin: string
  readonly openExternal: (url: string) => Promise<unknown>
  readonly exchange: (input: DesktopAuthExchangeInput) => Promise<void>
  readonly publishStatus: (status: DesktopAuthStatus) => void
  readonly now?: () => number
  readonly createAttempt?: () => DesktopPkceAttempt
}

export interface DesktopAuthExchangeOptions {
  readonly webOrigin: string
  readonly clientVersion: string
  readonly fetchImplementation?: typeof fetch
}

interface PendingDesktopAuthAttempt extends DesktopPkceAttempt {
  readonly expiresAt: number
}

function base64Url(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString('base64url')
}

export function createDesktopPkceAttempt(): DesktopPkceAttempt {
  const state = base64Url(randomBytes(32))
  const verifier = base64Url(randomBytes(32))
  const challenge = createHash('sha256').update(verifier, 'ascii').digest('base64url')
  return Object.freeze({ state, verifier, challenge })
}

export function buildDesktopAuthorizationUrl(
  webOrigin: string,
  attempt: Pick<DesktopPkceAttempt, 'state' | 'challenge'>,
): URL {
  const url = new URL(AUTHORIZATION_PATH, `${new URL(webOrigin).origin}/`)
  url.searchParams.set('state', attempt.state)
  url.searchParams.set('codeChallenge', attempt.challenge)
  url.searchParams.set('codeChallengeMethod', 'S256')
  return url
}

function sameState(actual: string, expected: string): boolean {
  const actualBytes = Buffer.from(actual, 'ascii')
  const expectedBytes = Buffer.from(expected, 'ascii')
  return (
    actualBytes.length === expectedBytes.length &&
    timingSafeEqual(actualBytes, expectedBytes)
  )
}

function status(
  phase: DesktopAuthStatus['phase'],
  errorCode?: DesktopAuthErrorCode,
): DesktopAuthStatus {
  return Object.freeze(errorCode ? { phase, errorCode } : { phase })
}

export class DesktopAuthController {
  private readonly now: () => number
  private readonly createAttempt: () => DesktopPkceAttempt
  private pending: PendingDesktopAuthAttempt | undefined

  constructor(private readonly options: DesktopAuthControllerOptions) {
    this.now = options.now ?? Date.now
    this.createAttempt = options.createAttempt ?? createDesktopPkceAttempt
  }

  isEnabled(): boolean {
    return this.options.enabled
  }

  async start(): Promise<void> {
    if (!this.options.enabled) {
      this.fail('AUTH_DISABLED')
      return
    }

    this.discardExpiredAttempt()
    if (this.pending) {
      this.fail('AUTH_IN_PROGRESS')
      return
    }

    const attempt = this.createAttempt()
    const pendingAttempt = Object.freeze({
      ...attempt,
      expiresAt: this.now() + ATTEMPT_TTL_MS,
    })
    this.pending = pendingAttempt
    this.options.publishStatus(status('OPENING_BROWSER'))

    try {
      await this.options.openExternal(
        buildDesktopAuthorizationUrl(this.options.webOrigin, attempt).href,
      )
    } catch {
      if (this.pending === pendingAttempt) {
        this.pending = undefined
        this.fail('BROWSER_OPEN_FAILED')
      }
      return
    }

    if (this.pending === pendingAttempt) {
      this.options.publishStatus(status('WAITING_FOR_CALLBACK'))
    }
  }

  async handleCallback(callback: DesktopAuthCallback): Promise<void> {
    if (!this.options.enabled) {
      this.fail('AUTH_DISABLED')
      return
    }

    const attempt = this.pending
    if (!attempt) {
      this.fail('NO_PENDING_ATTEMPT')
      return
    }
    if (this.now() >= attempt.expiresAt) {
      this.pending = undefined
      this.fail('ATTEMPT_EXPIRED')
      return
    }
    if (!sameState(callback.state, attempt.state)) {
      this.fail('STATE_MISMATCH')
      return
    }

    // Consume locally before the network call. A failed or duplicated exchange must
    // require a completely new browser flow and can never reuse PKCE material.
    this.pending = undefined
    this.options.publishStatus(status('EXCHANGING'))
    try {
      await this.options.exchange({
        code: callback.code,
        state: callback.state,
        codeVerifier: attempt.verifier,
      })
      this.options.publishStatus(status('SUCCEEDED'))
    } catch {
      this.fail('EXCHANGE_FAILED')
    }
  }

  private discardExpiredAttempt(): void {
    if (this.pending && this.now() >= this.pending.expiresAt) {
      this.pending = undefined
    }
  }

  private fail(errorCode: DesktopAuthErrorCode): void {
    this.options.publishStatus(status('FAILED', errorCode))
  }
}

export async function exchangeDesktopAuthCode(
  input: DesktopAuthExchangeInput,
  options: DesktopAuthExchangeOptions,
): Promise<void> {
  const fetchImplementation = options.fetchImplementation ?? fetch
  const exchangeUrl = new URL(EXCHANGE_PATH, `${new URL(options.webOrigin).origin}/`)
  const response = await fetchImplementation(exchangeUrl, {
    method: 'POST',
    redirect: 'error',
    headers: {
      'Content-Type': 'application/json',
      'X-Client-Type': 'ELECTRON',
      'X-Client-Version': options.clientVersion,
      'X-Client-Protocol-Version': '1',
    },
    body: JSON.stringify({
      code: input.code,
      state: input.state,
      codeVerifier: input.codeVerifier,
    }),
    signal: AbortSignal.timeout(EXCHANGE_TIMEOUT_MS),
  })

  if (!response.ok) {
    await response.body?.cancel().catch(() => undefined)
    throw new Error('Desktop auth exchange failed')
  }
  await response.body?.cancel().catch(() => undefined)
}
