import { describe, expect, it } from 'vitest'
import {
  ErrorCode,
  ErrorCodeFromJSON,
} from '../src/generated/models/ErrorCode.js'
import { createYumpooApiClient } from '../src/yumpoo-api-client.js'

describe('createYumpooApiClient', () => {
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
    expect(client.fetchApi).toBe(fetchApi)
  })

  it('将服务端新增的未知错误码安全降级为兜底枚举', () => {
    expect(ErrorCodeFromJSON('FUTURE_SERVER_ERROR')).toBe(
      ErrorCode.UnknownDefaultOpenApi,
    )
  })
})
