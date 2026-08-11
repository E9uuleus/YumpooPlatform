import path from 'node:path'

const PROTOCOL = 'yumpoo'
const MAX_CALLBACK_URL_LENGTH = 512
const OPAQUE_TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/

export interface ProtocolApplication {
  setAsDefaultProtocolClient(
    protocol: string,
    executablePath?: string,
    argumentsList?: string[],
  ): boolean
}

export interface ProtocolRegistrationOptions {
  readonly isPackaged: boolean
  readonly executablePath: string
  readonly processArguments: readonly string[]
}

export interface ParsedDesktopAuthCallback {
  readonly code: string
  readonly state: string
}

type ProtocolCallbackHandler = (callback: ParsedDesktopAuthCallback) => void

export function registerYumpooProtocolClient(
  application: ProtocolApplication,
  options: ProtocolRegistrationOptions,
): boolean {
  if (options.isPackaged) {
    return application.setAsDefaultProtocolClient(PROTOCOL)
  }

  const applicationEntry = options.processArguments[1]
  if (!applicationEntry) {
    return false
  }
  return application.setAsDefaultProtocolClient(PROTOCOL, options.executablePath, [
    path.resolve(applicationEntry),
  ])
}

export function parseDesktopAuthCallback(
  candidate: string,
): ParsedDesktopAuthCallback | undefined {
  if (
    candidate.length === 0 ||
    candidate.length > MAX_CALLBACK_URL_LENGTH ||
    candidate.includes('#') ||
    candidate.includes('\0')
  ) {
    return undefined
  }

  let url: URL
  try {
    url = new URL(candidate)
  } catch {
    return undefined
  }

  if (
    url.protocol !== `${PROTOCOL}:` ||
    url.hostname !== 'auth' ||
    url.pathname !== '/callback' ||
    url.username ||
    url.password ||
    url.port ||
    url.hash
  ) {
    return undefined
  }

  const entries = [...url.searchParams.entries()]
  if (
    entries.length !== 2 ||
    entries.some(([name]) => name !== 'code' && name !== 'state') ||
    url.searchParams.getAll('code').length !== 1 ||
    url.searchParams.getAll('state').length !== 1
  ) {
    return undefined
  }

  const code = url.searchParams.get('code') ?? ''
  const state = url.searchParams.get('state') ?? ''
  if (!OPAQUE_TOKEN_PATTERN.test(code) || !OPAQUE_TOKEN_PATTERN.test(state)) {
    return undefined
  }
  return Object.freeze({ code, state })
}

function protocolArguments(processArguments: readonly string[]): string[] {
  return processArguments.filter((argument) =>
    argument.toLowerCase().startsWith(`${PROTOCOL}:`),
  )
}

export class ProtocolLaunchDispatcher {
  private handler: ProtocolCallbackHandler | undefined
  private readonly queuedCallbacks: ParsedDesktopAuthCallback[] = []

  dispatch(processArguments: readonly string[]): boolean {
    const candidates = protocolArguments(processArguments)
    if (candidates.length !== 1) {
      return false
    }
    const candidate = candidates[0]
    if (!candidate) {
      return false
    }
    const callback = parseDesktopAuthCallback(candidate)
    if (!callback) {
      return false
    }
    if (this.handler) {
      this.handler(callback)
    } else {
      this.queuedCallbacks.push(callback)
    }
    return true
  }

  setHandler(handler: ProtocolCallbackHandler): void {
    this.handler = handler
    for (const callback of this.queuedCallbacks.splice(0)) {
      handler(callback)
    }
  }
}
