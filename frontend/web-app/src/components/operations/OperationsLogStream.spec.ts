import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { OperationsLogEntry } from '@yumpoo/api-client'
import OperationsLogStream from './OperationsLogStream.vue'

function entry(time: string, event: string | null = 'http.request.failed'): OperationsLogEntry {
  return { id: time, seq: '1', record: { time: new Date(time), level: 'WARN', module: 'foundation', event, msg: 'http request failed', logger: 'test', thread: 'test', requestId: null, correlationId: null, userId: null, error: null, fields: { method: 'GET', route: '/api/v1/projects/{id}', status: 500 } } }
}

describe('运行日志摘要', () => {
  it('显示中文事件与模块以及请求方法和安全路由', () => {
    const wrapper = mount(OperationsLogStream, { props: { entries: [entry('2026-09-26T23:59:00Z')] } })
    expect(wrapper.get('summary').text()).toContain('基础服务')
    expect(wrapper.get('summary').text()).toContain('请求失败 · GET /api/v1/projects/{id}')
    expect(wrapper.get('.ops-log-detail').text()).toContain('http request failed')
    wrapper.unmount()
  })
  it('跨日显示日期并保留未知事件的原始消息', () => {
    const wrapper = mount(OperationsLogStream, { props: { utc: true, entries: [entry('2026-09-26T23:59:00Z', 'unknown'), entry('2026-09-27T00:01:00Z')] } })
    expect(wrapper.findAll('summary')[0]?.text()).toContain('09/26')
    expect(wrapper.findAll('summary')[1]?.text()).toContain('09/27')
    expect(wrapper.findAll('summary')[0]?.text()).toContain('http request failed')
    wrapper.unmount()
  })
})
