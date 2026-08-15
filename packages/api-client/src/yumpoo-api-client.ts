import {
  Configuration,
  type ConfigurationParameters,
  type FetchAPI,
} from './generated/runtime.js'

const csrfCookieName = '__Host-yumpoo-csrf'
const csrfHeaderName = 'X-XSRF-TOKEN'
const safeMethods = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE'])

export function readCsrfToken(): string | undefined {
  if (typeof document === 'undefined') {
    return undefined
  }
  return readCookie(csrfCookieName)
}

export function createYumpooApiClient(
  parameters: ConfigurationParameters = {},
): Configuration {
  return new Configuration({
    ...parameters,
    basePath: parameters.basePath ?? '/api/v1',
    credentials: parameters.credentials ?? 'include',
    fetchApi: csrfFetch(parameters.fetchApi),
  })
}

function csrfFetch(delegate?: FetchAPI): FetchAPI {
  return async (input, init = {}) => {
    const target = delegate ?? globalThis.fetch
    const requestInput = isRequest(input)
    const method = (init.method ?? (requestInput ? input.method : 'GET'))
      .toUpperCase()
    if (!safeMethods.has(method) && isBrowserSameOrigin(input)) {
      const token = readCsrfToken()
      if (token) {
        const headers = new Headers(
          init.headers ?? (requestInput ? input.headers : undefined),
        )
        if (!headers.has(csrfHeaderName)) {
          headers.set(csrfHeaderName, token)
          init = { ...init, headers }
        }
      }
    }
    return target(input, init)
  }
}

function isBrowserSameOrigin(input: RequestInfo | URL): boolean {
  if (typeof document === 'undefined' || typeof location === 'undefined') {
    return false
  }
  const raw = isRequest(input) ? input.url : input.toString()
  return new URL(raw, location.href).origin === location.origin
}

function isRequest(input: RequestInfo | URL): input is Request {
  return typeof Request !== 'undefined' && input instanceof Request
}

function readCookie(name: string): string | undefined {
  const prefix = `${name}=`
  for (const part of document.cookie.split(';')) {
    const candidate = part.trim()
    if (candidate.startsWith(prefix)) {
      const value = candidate.slice(prefix.length)
      try {
        return decodeURIComponent(value)
      } catch {
        return undefined
      }
    }
  }
  return undefined
}
