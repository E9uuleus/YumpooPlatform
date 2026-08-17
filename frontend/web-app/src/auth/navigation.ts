const RETURN_PATH_KEY = 'yumpoo.auth.return-path.v1'
const RETURN_PATH_TTL_MS = 5 * 60 * 1000
const MAX_RETURN_PATH_LENGTH = 2048
const AUTHORIZE_PATH = '/api/v1/auth/wecom/authorize'

let authorizationStarted = false

interface StoredReturnPath {
  path: string
  expiresAt: number
}

export function beginAuthentication(path?: string): void {
  if (authorizationStarted) {
    return
  }
  if (path) saveReturnPath(path)
  authorizationStarted = true
  window.location.assign(AUTHORIZE_PATH)
}

export function rememberReturnPath(path: string): void {
  saveReturnPath(path)
}

export function consumeReturnPath(now = Date.now()): string {
  let raw: string | null
  try {
    raw = window.sessionStorage.getItem(RETURN_PATH_KEY)
    window.sessionStorage.removeItem(RETURN_PATH_KEY)
  } catch {
    return '/'
  }
  if (!raw) {
    return '/'
  }
  try {
    const stored = JSON.parse(raw) as Partial<StoredReturnPath>
    if (typeof stored.path !== 'string'
      || typeof stored.expiresAt !== 'number'
      || stored.expiresAt < now
      || !validReturnPath(stored.path)) {
      return '/'
    }
    return stored.path
  } catch {
    return '/'
  }
}

export function resetAuthenticationNavigation(): void {
  authorizationStarted = false
}

function saveReturnPath(path: string): void {
  if (!validReturnPath(path)) {
    return
  }
  const stored: StoredReturnPath = {
    path,
    expiresAt: Date.now() + RETURN_PATH_TTL_MS,
  }
  try {
    window.sessionStorage.setItem(RETURN_PATH_KEY, JSON.stringify(stored))
  } catch {
    // Storage can be unavailable in hardened browser contexts; '/' remains safe.
  }
}

function validReturnPath(path: string): boolean {
  if (path.length === 0
    || path.length > MAX_RETURN_PATH_LENGTH
    || !path.startsWith('/')
    || path.startsWith('//')) {
    return false
  }
  let target: URL
  let decodedPathname: string
  try {
    target = new URL(path, window.location.origin)
    decodedPathname = decodeURIComponent(target.pathname)
  } catch {
    return false
  }
  if (target.origin !== window.location.origin) {
    return false
  }
  return !decodedPathname.startsWith('/api')
    && !decodedPathname.startsWith('/status')
    && decodedPathname !== '/forbidden'
}
