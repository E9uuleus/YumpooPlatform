import { createHash, randomBytes, timingSafeEqual } from 'node:crypto'
import type {
  DesktopAuthErrorCode,
  DesktopAuthStatus,
} from '@yumpoo/preload-contract'

const AUTHORIZATION_PATH = '/api/v1/electron/auth/attempts'
const EXCHANGE_PATH = '/api/v1/electron/auth/exchange'
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

export interface DesktopAuthorizationResponse {
  readonly authorizationUrl: string
  readonly expiresAt: string
}

export interface DesktopSessionBundle {
  readonly sessionCredential: string
  readonly csrfCredential: string
  readonly absoluteExpiresAt: string
}

export interface DesktopAuthControllerOptions {
  readonly enabled: boolean
  readonly authorize: (attempt: DesktopPkceAttempt) => Promise<DesktopAuthorizationResponse>
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

export type DesktopAuthAuthorizationOptions = DesktopAuthExchangeOptions

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
    const localPending = Object.freeze({
      ...attempt,
      expiresAt: this.now() + ATTEMPT_TTL_MS,
    })
    this.pending = localPending
    this.options.publishStatus(status('OPENING_BROWSER'))

    try {
      const authorization = await this.options.authorize(attempt)
      const serverExpiry = Date.parse(authorization.expiresAt)
      const pendingAttempt = Object.freeze({
        ...attempt,
        expiresAt: Number.isFinite(serverExpiry)
          ? Math.min(serverExpiry, this.now() + ATTEMPT_TTL_MS)
          : this.now() + ATTEMPT_TTL_MS,
      })
      if (this.pending !== localPending) {
        return
      }
      this.pending = pendingAttempt
      await this.options.openExternal(authorization.authorizationUrl)
      if (this.pending === pendingAttempt) {
        this.options.publishStatus(status('WAITING_FOR_CALLBACK'))
      }
    } catch {
      if (this.pending === localPending || this.pending?.state === attempt.state) {
        this.pending = undefined
      }
      this.fail('BROWSER_OPEN_FAILED')
      return
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

export async function requestDesktopAuthorization(
  attempt: DesktopPkceAttempt,
  options: DesktopAuthAuthorizationOptions,
): Promise<DesktopAuthorizationResponse> {
  const response = await desktopAuthFetch(AUTHORIZATION_PATH, options, {
    state: attempt.state,
    codeChallenge: attempt.challenge,
    codeChallengeMethod: 'S256',
  })
  const body = await response.json() as Partial<DesktopAuthorizationResponse>
  if (typeof body.authorizationUrl !== 'string' || typeof body.expiresAt !== 'string') {
    throw new Error('Desktop authorization response is invalid')
  }
  const url = new URL(body.authorizationUrl)
  if (url.protocol !== 'https:' || url.hostname !== 'open.work.weixin.qq.com') {
    throw new Error('Desktop authorization URL is not allowlisted')
  }
  return Object.freeze({ authorizationUrl: url.href, expiresAt: body.expiresAt })
}

export async function exchangeDesktopAuthCode(
  input: DesktopAuthExchangeInput,
  options: DesktopAuthExchangeOptions,
): Promise<DesktopSessionBundle> {
  const response = await desktopAuthFetch(EXCHANGE_PATH, options, {
    handoffCode: input.code,
    state: input.state,
    codeVerifier: input.codeVerifier,
  })
  const body = await response.json() as Partial<DesktopSessionBundle>
  if (!validOpaqueCredential(body.sessionCredential)
    || !validOpaqueCredential(body.csrfCredential)
    || typeof body.absoluteExpiresAt !== 'string'
    || !Number.isFinite(Date.parse(body.absoluteExpiresAt))) {
    throw new Error('Desktop auth exchange response is invalid')
  }
  return Object.freeze({
    sessionCredential: body.sessionCredential,
    csrfCredential: body.csrfCredential,
    absoluteExpiresAt: body.absoluteExpiresAt,
  })
}

async function desktopAuthFetch(
  path: string,
  options: DesktopAuthExchangeOptions,
  body: object,
): Promise<Response> {
  const fetchImplementation = options.fetchImplementation ?? fetch
  const url = new URL(path, `${new URL(options.webOrigin).origin}/`)
  const response = await fetchImplementation(url, {
    method: 'POST',
    redirect: 'error',
    headers: {
      'Content-Type': 'application/json',
      'X-Client-Type': 'ELECTRON',
      'X-Client-Version': options.clientVersion,
      'X-Client-Protocol-Version': '1',
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(EXCHANGE_TIMEOUT_MS),
  })

  if (!response.ok) {
    await response.body?.cancel().catch(() => undefined)
    throw new Error('Desktop auth exchange failed')
  }
  return response
}

function validOpaqueCredential(value: unknown): value is string {
  return typeof value === 'string'
    && value.length >= 43
    && value.length <= 256
    && /^[A-Za-z0-9._~-]+$/.test(value)
}
