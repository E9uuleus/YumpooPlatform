import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { ElSwitch } from 'element-plus'
import type { OperationsLogPage } from '@yumpoo/api-client'
import OperationsLogsView from './OperationsLogsView.vue'
import OperationsChart from '../../components/operations/OperationsChart.vue'

const api = vi.hoisted(() => ({
  queryOperationsLogs: vi.fn(),
  getOperationsLogHistogram: vi.fn(),
  tailOperationsLogs: vi.fn(),
}))
const navigation = vi.hoisted(() => ({
  route: { query: {} as Record<string, string> },
  replace: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('../../api/client', () => ({ operationsApi: api }))
vi.mock('../../composables/useSession', () => ({ ensureAuthentication: vi.fn() }))
vi.mock('../../components/operations/OperationsChart.vue', () => ({
  default: { name: 'OperationsChart', emits: ['range'], template: '<div />' },
}))
vi.mock('vue-router', () => ({
  useRoute: () => reactive(navigation.route),
  useRouter: () => ({ replace: navigation.replace }),
}))
function page(message: string, cursor: string | null): OperationsLogPage {
  return {
    items: [
      {
        id: message + ':1',
        seq: null,
        record: {
          time: new Date('2026-09-26T11:59:00Z'),
          level: 'INFO',
          module: 'test',
          event: null,
          msg: message,
          logger: 'test',
          thread: 'test',
          requestId: null,
          correlationId: null,
          userId: null,
          fields: {},
          error: null,
        },
      },
    ],
    source: 'FILE',
    nextCursor: cursor,
    partial: false,
    partialReason: null,
    scannedBytes: 0,
    skippedLines: 0,
    droppedCount: 0,
  }
}
beforeEach(() => {
  reactive(navigation.route).query = {
    from: '2026-09-26T11:00:00Z', to: '2026-09-26T12:00:00Z',
  }
  navigation.replace.mockClear()
  api.queryOperationsLogs.mockResolvedValue(page('history', null))
  api.getOperationsLogHistogram.mockResolvedValue({
    buckets: [], bucketSeconds: 60, partial: false, partialReason: null,
  })
  api.tailOperationsLogs.mockResolvedValue({
    items: [], bootId: 'boot-1', nextAfterSeq: '1', hasMore: false,
    gap: false, gapReason: null, droppedCount: 0,
  })
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
})
afterEach(() => {
  vi.restoreAllMocks()
  Object.values(api).forEach((mock) => mock.mockReset())
})
describe('历史日志筛选', () => {
  it('级别计数覆盖当前区间的所有级别，精确筛选收在折叠区', async () => {
    api.queryOperationsLogs.mockResolvedValue(page('history', null))
    api.getOperationsLogHistogram.mockResolvedValue({
      buckets: [{ time: new Date(), counts: { INFO: 4, ERROR: 2, DEBUG: 9 } }],
      bucketSeconds: 60,
      partial: false,
      partialReason: null,
    })
    const wrapper = mount(OperationsLogsView)
    await flushPromises()
    expect(wrapper.get('[aria-label="日志级别"]').text()).toContain('DEBUG 9')
    expect(api.getOperationsLogHistogram.mock.lastCall?.[0].levels).toBe(
      'TRACE,DEBUG,INFO,WARN,ERROR',
    )
    expect(wrapper.get('.log-more-filters').attributes('open')).toBeUndefined()
    expect(wrapper.find('.log-more-filters input[aria-label="事件代码"]').exists()).toBe(true)
    await wrapper
      .findAll('[aria-label="日志级别"] button')
      .find((button) => button.text().startsWith('DEBUG'))!
      .trigger('click')
    await flushPromises()
    expect(api.queryOperationsLogs.mock.lastCall?.[0].levels).toContain('DEBUG')
    wrapper.unmount()
  })
  it('编辑中的筛选不会污染分页，重新查询后丢弃被中止的旧页', async () => {
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    api.getOperationsLogHistogram.mockResolvedValue({
      buckets: [],
      bucketSeconds: 60,
      partial: false,
      partialReason: null,
    })
    let oldPage!: (value: OperationsLogPage) => void
    api.queryOperationsLogs
      .mockResolvedValueOnce(page('first', 'cursor-1'))
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            oldPage = resolve
          }),
      )
      .mockResolvedValueOnce(page('new-filter', null))
    const wrapper = mount(OperationsLogsView)
    await flushPromises()
    await wrapper.get('input[aria-label="搜索日志"]').setValue('new')
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '加载更早')!
      .trigger('click')
    await flushPromises()
    expect(api.queryOperationsLogs.mock.calls[1]?.[0]).toMatchObject({ q: '', cursor: 'cursor-1' })
    const oldSignal = api.queryOperationsLogs.mock.calls[1]?.[1].signal as AbortSignal
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '查询')!
      .trigger('click')
    await flushPromises()
    expect(oldSignal.aborted).toBe(true)
    oldPage(page('stale-page', null))
    await flushPromises()
    expect(wrapper.text()).toContain('new-filter')
    expect(wrapper.text()).not.toContain('stale-page')
    expect(api.queryOperationsLogs.mock.calls[2]?.[0]).toMatchObject({ q: 'new' })
    wrapper.unmount()
  })
  it('连续框选后清除时间恢复首次框选前的一小时区间', async () => {
    const wrapper = mount(OperationsLogsView)
    await flushPromises()
    const hour = wrapper.findAll('[aria-label="日志时间范围"] button')
      .find((button) => button.text() === '1 小时')!
    await hour.trigger('click')
    await flushPromises()
    const original = api.queryOperationsLogs.mock.lastCall![0]
    const requests = api.queryOperationsLogs.mock.calls.length
    const chart = wrapper.getComponent(OperationsChart)
    chart.vm.$emit('range', new Date(original.from.getTime() + 600000),
      new Date(original.from.getTime() + 1200000))
    await flushPromises()
    chart.vm.$emit('range', new Date(original.from.getTime() + 720000),
      new Date(original.from.getTime() + 900000))
    await flushPromises()
    expect(hour.attributes('aria-pressed')).toBe('false')
    expect(wrapper.find('.log-time-chip').exists()).toBe(true)
    await wrapper.get('[aria-label="清除时间筛选"]').trigger('click')
    await flushPromises()
    expect(api.queryOperationsLogs.mock.lastCall![0]).toMatchObject({
      from: original.from, to: original.to,
    })
    expect(api.queryOperationsLogs.mock.calls.length).toBe(requests + 3)
    expect(wrapper.find('.log-time-chip').exists()).toBe(false)
    expect(hour.attributes('aria-pressed')).toBe('true')
    expect(navigation.replace.mock.lastCall![0].query).toMatchObject({
      from: original.from.toISOString(), to: original.to.toISOString(),
    })
    wrapper.unmount()
  })
  it('从暂停的实时模式框选后，清除时间返回原来的实时状态', async () => {
    reactive(navigation.route).query = {}
    const wrapper = mount(OperationsLogsView)
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '暂停显示')!.trigger('click')
    wrapper.getComponent(OperationsChart).vm.$emit('range',
      new Date('2026-09-30T08:50:00Z'), new Date('2026-09-30T08:53:00Z'))
    await flushPromises()
    expect(api.queryOperationsLogs).toHaveBeenCalledTimes(1)
    await wrapper.get('[aria-label="清除时间筛选"]').trigger('click')
    await flushPromises()
    expect(api.tailOperationsLogs).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.log-live__pill').text()).toBe('已暂停')
    expect(navigation.replace.mock.lastCall![0].query).not.toHaveProperty('from')
    expect(wrapper.find('.log-time-chip').exists()).toBe(false)
    wrapper.unmount()
  })
  it('清除全部恢复预设区间并清除其他条件，保留日志级别', async () => {
    reactive(navigation.route).query = {
      from: '2026-09-30T08:00:00Z', to: '2026-09-30T09:00:00Z',
      q: 'failure', modules: 'foundation', event: 'http.request.failed',
      requestId: 'request-1', userId: 'user-1',
    }
    const wrapper = mount(OperationsLogsView)
    await flushPromises()
    await wrapper.findAll('[aria-label="日志时间范围"] button')
      .find((button) => button.text() === '15 分钟')!.trigger('click')
    await wrapper.findAll('[aria-label="日志级别"] button')
      .find((button) => button.text().startsWith('DEBUG'))!.trigger('click')
    await flushPromises()
    const original = api.queryOperationsLogs.mock.lastCall![0]
    wrapper.getComponent(OperationsChart).vm.$emit('range',
      new Date(original.from.getTime() + 60000), new Date(original.from.getTime() + 120000))
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '清除全部')!.trigger('click')
    await flushPromises()
    expect(api.queryOperationsLogs.mock.lastCall![0]).toMatchObject({
      from: original.from, to: original.to, levels: original.levels,
      q: '', modules: '', event: '', requestId: '', userId: '',
    })
    expect(wrapper.find('.log-chips').exists()).toBe(false)
    wrapper.unmount()
  })
  it.each([
    ['2026-09-30T08:50:00Z', '2026-09-30T08:53:00Z', '08:50–08:53'],
    ['2026-09-30T08:50:10Z', '2026-09-30T08:50:40Z', '08:50:10–08:50:40'],
    ['2026-09-29T23:59:00Z', '2026-09-30T00:01:00Z', '2026/09/29 23:59–2026/09/30 00:01'],
  ])('URL 区间 %s 以 UTC 显示，清除后默认返回实时', async (from, to, label) => {
    reactive(navigation.route).query = { from, to }
    const wrapper = mount(OperationsLogsView)
    await flushPromises()
    wrapper.getComponent(ElSwitch).vm.$emit('update:modelValue', true)
    await flushPromises()
    expect(wrapper.get('.log-time-chip').text()).toBe('时间：' + label)
    expect(wrapper.find('.el-date-editor').exists()).toBe(false)
    await wrapper.get('[aria-label="清除时间筛选"]').trigger('click')
    await flushPromises()
    expect(api.tailOperationsLogs).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.log-time-chip').exists()).toBe(false)
    wrapper.unmount()
  })
  it('选择预设结束自定义区间；切换 URL 后按新的时间查询', async () => {
    const wrapper = mount(OperationsLogsView)
    await flushPromises()
    await wrapper.findAll('[aria-label="日志时间范围"] button')
      .find((button) => button.text() === '6 小时')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.log-time-chip').exists()).toBe(false)
    reactive(navigation.route).query = {
      from: '2026-09-30T08:50:00Z', to: '2026-09-30T08:53:00Z', q: 'new query',
    }
    await flushPromises()
    expect(api.queryOperationsLogs.mock.lastCall![0]).toMatchObject({
      from: new Date('2026-09-30T08:50:00Z'), to: new Date('2026-09-30T08:53:00Z'), q: 'new query',
    })
    expect(wrapper.find('.log-time-chip').exists()).toBe(true)
    wrapper.unmount()
  })
})
