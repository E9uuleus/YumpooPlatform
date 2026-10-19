import { afterEach, describe, expect, it, vi } from 'vitest'
import router from './index'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('首次导航', () => {
  it('会话检查失败时落到不可用页，不与会话监听互相取消而反复请求', async () => {
    // Yielding to the event loop lets a regression fail on the test timeout instead of starving it.
    const fetchMock = vi.fn(async (_input: RequestInfo | URL) => {
      await new Promise(resolve => setTimeout(resolve, 0))
      return new Response('', { status: 502 })
    })
    vi.stubGlobal('fetch', fetchMock)

    await router.push('/')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('unavailable')
    const sessionChecks = fetchMock.mock.calls.filter(([input]) => String(input instanceof Request ? input.url : input).includes('/auth/me'))
    expect(sessionChecks.length).toBeLessThanOrEqual(2)
  }, 10_000)
})
