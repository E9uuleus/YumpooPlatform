import { ErrorCode, type ErrorResponse } from '@yumpoo/api-client'
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import InlineProblem from './InlineProblem.vue'

describe('统一内联错误', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('显示安全消息、requestId、Retry-After 并支持复制', async () => {
    const writeText = vi.fn(async () => undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const error: ErrorResponse = {
      code: ErrorCode.RateLimited,
      message: '请求较多，请稍后重试。',
      requestId: 'req-copy',
      retryable: true,
      fieldErrors: [],
      details: {},
    }
    const wrapper = mount(InlineProblem, {
      props: {
        problem: { kind: 'response', status: 429, error, retryAfter: '30' },
      },
    })
    expect(wrapper.text()).toContain('请求较多，请稍后重试。')
    expect(wrapper.text()).toContain('req-copy')
    expect(wrapper.text()).toContain('30 秒')
    await wrapper.get('button').trigger('click')
    expect(writeText).toHaveBeenCalledWith('req-copy')
    expect(wrapper.text()).toContain('已复制')
  })
})
