import { createHash, randomBytes, timingSafeEqual } from 'node:crypto'
import type {
  DesktopAuthErrorCode,
  DesktopAuthStatus,
  DesktopAuthStartOptions,
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

export type DesktopAuthStage = 'authorize' | 'open-browser' | 'exchange'

export interface DesktopAuthControllerOptions {
  readonly enabled: boolean
  readonly authorize: (attempt: DesktopPkceAttempt, remember: boolean) => Promise<DesktopAuthorizationResponse>
  readonly openExternal: (url: string) => Promise<unknown>
  /** Without remember the credentials stay in memory only: nothing is written to the safeStorage store. */
  readonly exchange: (input: DesktopAuthExchangeInput, remember: boolean) => Promise<void>
  readonly publishStatus: (status: DesktopAuthStatus) => void
  /** Receives only the stage and a non-secret reason; state, codes and verifiers never reach it. */
  readonly logFailure?: (stage: DesktopAuthStage, reason: string) => void
  readonly now?: () => number
  readonly createAttempt?: () => DesktopPkceAttempt
}

export type DesktopAuthFetch = (url: string, init: RequestInit) => Promise<Response>

export interface DesktopAuthExchangeOptions {
  readonly webOrigin: string
  readonly clientVersion: string
  readonly fetchImplementation?: DesktopAuthFetch
}

export type DesktopAuthAuthorizationOptions = DesktopAuthExchangeOptions

interface PendingDesktopAuthAttempt extends DesktopPkceAttempt {
  readonly remember: boolean
  readonly expiresAt: number
}

export class DesktopAuthHttpError extends Error {
  constructor(readonly status: number) {
    super(`HTTP_${status}`)
    this.name = 'DesktopAuthHttpError'
  }
}

/** HTTP status, a Chromium net error code or the error class: enough to tell a server refusal from a network failure. */
export function desktopAuthFailureReason(error: unknown): string {
  if (error instanceof DesktopAuthHttpError) return error.message
  if (error instanceof Error) {
    const netError = /\bnet::(ERR_[A-Z0-9_]+)/u.exec(error.message)
    return netError?.[1] ?? error.name
  }
  return 'UNKNOWN'
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
  private expiryTimer: ReturnType<typeof setTimeout> | undefined

  constructor(private readonly options: DesktopAuthControllerOptions) {
    this.now = options.now ?? Date.now
    this.createAttempt = options.createAttempt ?? createDesktopPkceAttempt
  }

  isEnabled(): boolean {
    return this.options.enabled
  }

  /** A new start replaces any pending attempt: the browser page of the old one may be closed or lost after a reload. */
  async start(options: DesktopAuthStartOptions = { remember: true }): Promise<void> {
    if (!this.options.enabled) {
      this.fail('AUTH_DISABLED')
      return
    }

    const attempt = this.createAttempt()
    const localPending = Object.freeze({
      ...attempt,
      remember: options.remember,
      expiresAt: this.now() + ATTEMPT_TTL_MS,
    })
    this.setPending(localPending)
    this.options.publishStatus(status('OPENING_BROWSER'))

    let authorization: DesktopAuthorizationResponse
    try {
      authorization = await this.options.authorize(attempt, options.remember)
    } catch (error) {
      this.abandon(localPending, attempt, 'authorize', error, 'AUTH_SERVICE_FAILED')
      return
    }
    const serverExpiry = Date.parse(authorization.expiresAt)
    const pendingAttempt = Object.freeze({
      ...localPending,
      expiresAt: Number.isFinite(serverExpiry)
        ? Math.min(serverExpiry, this.now() + ATTEMPT_TTL_MS)
        : this.now() + ATTEMPT_TTL_MS,
    })
    if (this.pending !== localPending) {
      return
    }
    this.setPending(pendingAttempt)
    try {
      await this.options.openExternal(authorization.authorizationUrl)
    } catch (error) {
      this.abandon(localPending, attempt, 'open-browser', error, 'BROWSER_OPEN_FAILED')
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
      this.setPending(undefined)
      this.fail('ATTEMPT_EXPIRED')
      return
    }
    if (!sameState(callback.state, attempt.state)) {
      this.fail('STATE_MISMATCH')
      return
    }

    // Consume locally before the network call. A failed or duplicated exchange must
    // require a completely new browser flow and can never reuse PKCE material.
    this.setPending(undefined)
    this.options.publishStatus(status('EXCHANGING'))
    try {
      await this.options.exchange({
        code: callback.code,
        state: callback.state,
        codeVerifier: attempt.verifier,
      }, attempt.remember)
      this.options.publishStatus(status('SUCCEEDED'))
    } catch (error) {
      this.options.logFailure?.('exchange', desktopAuthFailureReason(error))
      this.fail('EXCHANGE_FAILED')
    }
  }

  /** The person gave up in the browser; a late callback for the dropped attempt then finds nothing pending. */
  cancel(): void {
    this.setPending(undefined)
    this.options.publishStatus(status('IDLE'))
  }

  private abandon(
    localPending: PendingDesktopAuthAttempt,
    attempt: DesktopPkceAttempt,
    stage: DesktopAuthStage,
    error: unknown,
    errorCode: DesktopAuthErrorCode,
  ): void {
    this.options.logFailure?.(stage, desktopAuthFailureReason(error))
    // A newer start or a cancel already replaced this attempt; its failure must not overwrite that status.
    if (this.pending !== localPending && this.pending?.state !== attempt.state) return
    this.setPending(undefined)
    this.fail(errorCode)
  }

  /** The browser never reports a cancelled or closed login page, so the attempt reports its own expiry. */
  private setPending(attempt: PendingDesktopAuthAttempt | undefined): void {
    clearTimeout(this.expiryTimer)
    this.pending = attempt
    if (!attempt) return
    this.expiryTimer = setTimeout(() => {
      if (this.pending !== attempt) return
      this.pending = undefined
      this.fail('ATTEMPT_EXPIRED')
    }, attempt.expiresAt - this.now())
  }

  private fail(errorCode: DesktopAuthErrorCode): void {
    this.options.publishStatus(status('FAILED', errorCode))
  }
}

export async function requestDesktopAuthorization(
  attempt: DesktopPkceAttempt,
  remember: boolean,
  options: DesktopAuthAuthorizationOptions,
): Promise<DesktopAuthorizationResponse> {
  const response = await desktopAuthFetch(AUTHORIZATION_PATH, options, {
    state: attempt.state,
    codeChallenge: attempt.challenge,
    codeChallengeMethod: 'S256',
    remember,
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
  const response = await fetchImplementation(url.href, {
    method: 'POST',
    redirect: 'error',
    credentials: 'omit',
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
    throw new DesktopAuthHttpError(response.status)
  }
  return response
}

function validOpaqueCredential(value: unknown): value is string {
  return typeof value === 'string'
    && value.length >= 43
    && value.length <= 256
    && /^[A-Za-z0-9._~-]+$/.test(value)
}
