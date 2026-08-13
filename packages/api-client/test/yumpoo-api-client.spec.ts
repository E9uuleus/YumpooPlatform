import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ErrorCode,
  ErrorCodeFromJSON,
} from '../src/generated/models/ErrorCode.js'
import { createYumpooApiClient } from '../src/yumpoo-api-client.js'

describe('createYumpooApiClient', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('默认使用同源 v1 路径并携带 Cookie', () => {
    const client = createYumpooApiClient()

    expect(client.basePath).toBe('/api/v1')
    expect(client.credentials).toBe('include')
  })

  it('允许调用方覆盖 basePath 和 fetch 实现', () => {
    const fetchApi = async () => new Response(null, { status: 204 })
    const client = createYumpooApiClient({
      basePath: 'https://example.test/api/v1',
      fetchApi,
    })

    expect(client.basePath).toBe('https://example.test/api/v1')
    expect(client.fetchApi).not.toBe(fetchApi)
  })

  it('为浏览器同源写请求注入 CSRF 头', async () => {
    vi.stubGlobal('location', new URL('https://yumpoo.example.test/app'))
    vi.stubGlobal('document', { cookie: '__Host-yumpoo-csrf=csrf-token' })
    const delegate = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) =>
      new Response(null, { status: 204 }))
    const client = createYumpooApiClient({ fetchApi: delegate })

    await client.fetchApi?.('/api/v1/items', { method: 'POST' })

    const init = delegate.mock.calls[0]?.[1]
    expect(new Headers(init?.headers).get('X-XSRF-TOKEN')).toBe('csrf-token')
  })

  it('不向安全方法或跨源请求泄露 CSRF', async () => {
    vi.stubGlobal('location', new URL('https://yumpoo.example.test/app'))
    vi.stubGlobal('document', { cookie: '__Host-yumpoo-csrf=csrf-token' })
    const delegate = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) =>
      new Response(null, { status: 204 }))
    const client = createYumpooApiClient({ fetchApi: delegate })

    await client.fetchApi?.('/api/v1/items', { method: 'GET' })
    await client.fetchApi?.('https://other.example.test/items', { method: 'POST' })

    expect(new Headers(delegate.mock.calls[0]?.[1]?.headers).has('X-XSRF-TOKEN')).toBe(false)
    expect(new Headers(delegate.mock.calls[1]?.[1]?.headers).has('X-XSRF-TOKEN')).toBe(false)
  })

  it('无 DOM 时不注入且保留调用方显式 CSRF 头', async () => {
    const delegate = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) =>
      new Response(null, { status: 204 }))
    const serverClient = createYumpooApiClient({ fetchApi: delegate })
    await serverClient.fetchApi?.('https://yumpoo.example.test/api/v1/items', {
      method: 'POST',
    })
    expect(new Headers(delegate.mock.calls[0]?.[1]?.headers).has('X-XSRF-TOKEN')).toBe(false)

    vi.stubGlobal('location', new URL('https://yumpoo.example.test/app'))
    vi.stubGlobal('document', { cookie: '__Host-yumpoo-csrf=cookie-token' })
    const browserClient = createYumpooApiClient({ fetchApi: delegate })
    await browserClient.fetchApi?.('/api/v1/items', {
      method: 'POST',
      headers: { 'X-XSRF-TOKEN': 'explicit-token' },
    })
    expect(new Headers(delegate.mock.calls[1]?.[1]?.headers).get('X-XSRF-TOKEN'))
      .toBe('explicit-token')
  })

  it('将服务端新增的未知错误码安全降级为兜底枚举', () => {
    expect(ErrorCodeFromJSON('FUTURE_SERVER_ERROR')).toBe(
      ErrorCode.UnknownDefaultOpenApi,
    )
  })
})
