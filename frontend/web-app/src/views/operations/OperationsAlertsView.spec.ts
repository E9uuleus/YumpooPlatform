import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive, ref } from 'vue'
import { ElDialog } from 'element-plus'
import OperationsAlertsView from './OperationsAlertsView.vue'

const alertId = '00000000-0000-4000-8000-000000000001'
const namedActor = '00000000-0000-4000-8000-0000000000aa'
const missingActor = '00000000-0000-4000-8000-0000000000bb'

const api = vi.hoisted(() => ({
  listOperationsAlerts: vi.fn(),
  listOperationsAlertRules: vi.fn(),
  getOperationsAlert: vi.fn(),
  getOperationsMetrics: vi.fn(),
}))
const members = vi.hoisted(() => ({ getMember: vi.fn() }))
const navigation = vi.hoisted(() => ({ query: {} as Record<string, string> }))
vi.mock('../../api/client', () => ({ operationsApi: api, identityAdministrationApi: members }))
vi.mock('../../composables/useSession', () => ({ ensureAuthentication: vi.fn() }))
vi.mock('../../components/operations/OperationsChart.vue', () => ({
  default: { template: '<div />' },
}))
vi.mock('../../components/operations/operationsContext', () => ({
  useOperations: () => ({ data: ref(undefined) }),
}))
vi.mock('vue-router', () => ({
  RouterLink: { template: '<a><slot /></a>' },
  useRoute: () => reactive(navigation),
}))

const alert = {
  id: alertId,
  ruleCode: 'OUTBOX_DEAD',
  subjectKey: 'instance',
  severity: 'CRITICAL',
  status: 'FIRING',
  startedAt: new Date('2026-09-30T00:00:00Z'),
  evaluatedAt: new Date('2026-09-30T00:05:00Z'),
  peakValue: 16,
  lastValue: 16,
  params: { unit: 'COUNT' },
  acknowledgedAt: new Date('2026-09-30T00:03:00Z'),
  acknowledgedByUserId: namedActor,
  acknowledgeNote: null,
  resolvedAt: null,
  resolution: null,
}
function event(id: string, eventType: string, actorUserId: string | null) {
  return {
    id,
    eventType,
    severity: 'CRITICAL',
    value: 16,
    actorUserId,
    occurredAt: new Date('2026-09-30T00:03:00Z'),
  }
}

beforeEach(() => {
  reactive(navigation).query = {}
  api.listOperationsAlerts.mockResolvedValue({ items: [alert], totalElements: 1, page: 0, size: 20 })
  api.listOperationsAlertRules.mockResolvedValue([])
  api.getOperationsMetrics.mockResolvedValue({ timestamps: [], series: [] })
  api.getOperationsAlert.mockResolvedValue({ alert, events: [] })
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
})

afterEach(() => {
  vi.restoreAllMocks()
  Object.values(api).forEach((mock) => mock.mockReset())
  members.getMember.mockReset()
})

describe('告警详情时间线', () => {
  it('补齐操作人姓名，查询失败时保留操作人 ID', async () => {
    reactive(navigation).query = { alert: alertId }
    api.listOperationsAlerts.mockResolvedValue({ items: [], totalElements: 0, page: 0, size: 20 })
    api.listOperationsAlertRules.mockResolvedValue([])
    api.getOperationsAlert.mockResolvedValue({
      alert,
      events: [
        event('e1', 'FIRED', null),
        event('e2', 'ACKNOWLEDGED', namedActor),
        event('e3', 'ACKNOWLEDGED', missingActor),
      ],
    })
    members.getMember.mockImplementation(({ userId }: { userId: string }) =>
      userId === namedActor
        ? Promise.resolve({ userId, displayName: '陈静' })
        : Promise.reject(new Error('not found')),
    )
    const wrapper = mount(OperationsAlertsView, { global: { stubs: { teleport: true } } })
    await flushPromises()
    await flushPromises()

    const actors = wrapper.findAll('.alert-actor')
    expect(actors).toHaveLength(2)
    expect(actors[0]!.text()).toContain('陈静')
    expect(actors[0]!.text()).toContain(namedActor)
    expect(actors[1]!.text()).not.toContain('未分配')
    expect(actors[1]!.text()).toContain(missingActor)
    expect(members.getMember).toHaveBeenCalledTimes(2)
    expect(api.getOperationsAlert).toHaveBeenCalledWith({ alertId }, expect.anything())
    expect(wrapper.find('.alert-detail-dialog').exists()).toBe(true)
    wrapper.unmount()
  })
  it('活跃告警打开居中宽弹窗，关闭后保留告警列表', async () => {
    const wrapper = mount(OperationsAlertsView, { global: { stubs: { teleport: true } } })
    await flushPromises()
    expect(api.getOperationsAlert).not.toHaveBeenCalled()
    await wrapper.findAll('button').find((button) => button.text() === '详情与时间线')!.trigger('click')
    await flushPromises()
    const dialog = wrapper.findAllComponents(ElDialog).find((item) => item.props('title') === '告警详情')!
    expect(dialog.props()).toMatchObject({
      modelValue: true, alignCenter: true, width: 'min(960px, calc(100vw - 48px))',
    })
    expect(wrapper.find('.el-drawer').exists()).toBe(false)
    expect(wrapper.get('.alert-detail__summary').text()).toContain('当前值')
    expect(wrapper.get('.alert-detail__timeline').text()).toContain('暂无事件')
    expect(wrapper.get('.alert-detail__footer').text()).toContain('查看日志')
    expect(api.getOperationsAlert).toHaveBeenCalledTimes(1)
    await wrapper.get('.alert-detail__footer button').trigger('click')
    await flushPromises()
    expect(dialog.props('modelValue')).toBe(false)
    expect(wrapper.find('.alert-card').exists()).toBe(true)
    wrapper.unmount()
  })
  it('历史表格行打开同一个详情弹窗，恢复事件使用结束时间计算持续时长', async () => {
    const resolved = {
      ...alert, status: 'RESOLVED', resolvedAt: new Date('2026-09-30T00:05:00Z'), resolution: 'RECOVERED',
    }
    api.listOperationsAlerts.mockResolvedValue({ items: [resolved], totalElements: 1, page: 0, size: 20 })
    api.getOperationsAlert.mockResolvedValue({ alert: resolved, events: [event('e3', 'RESOLVED', null)] })
    const wrapper = mount(OperationsAlertsView, { global: { stubs: { teleport: true } } })
    await flushPromises()
    await wrapper.findAll('[role="tab"]').find((item) => item.text() === '历史')!.trigger('click')
    await flushPromises()
    await wrapper.get('tbody tr').trigger('click')
    await flushPromises()
    expect(api.getOperationsAlert.mock.lastCall![0]).toEqual({ alertId })
    expect(wrapper.get('.alert-detail__stats').text()).toContain('5 分钟')
    expect(wrapper.get('.alert-detail__info').text()).toContain('结束方式已恢复')
    expect(wrapper.get('.alert-detail__timeline').text()).toContain('恢复')
    wrapper.unmount()
  })
  it('详情加载失败可以重试', async () => {
    api.getOperationsAlert.mockRejectedValueOnce(new Error('unavailable'))
    const wrapper = mount(OperationsAlertsView, { global: { stubs: { teleport: true } } })
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '详情与时间线')!.trigger('click')
    await flushPromises()
    expect(wrapper.get('.alert-detail__error').text()).toContain('重新加载')
    await wrapper.get('.alert-detail__error button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.alert-detail__error').exists()).toBe(false)
    expect(wrapper.get('.alert-detail__heading').text()).toContain('事件投递失败')
    wrapper.unmount()
  })
  it('切换 URL 告警时清除旧详情，丢弃被中止的旧请求', async () => {
    reactive(navigation).query = { alert: alertId }
    api.getOperationsAlert.mockResolvedValueOnce({ alert, events: [event('old', 'FIRED', null)] })
    let oldResult!: (value: unknown) => void, newResult!: (value: unknown) => void
    api.getOperationsAlert
      .mockImplementationOnce(() => new Promise((resolve) => { oldResult = resolve }))
      .mockImplementationOnce(() => new Promise((resolve) => { newResult = resolve }))
    const wrapper = mount(OperationsAlertsView, { global: { stubs: { teleport: true } } })
    await flushPromises()
    expect(wrapper.get('.alert-detail__heading').text()).toContain('事件投递失败')
    reactive(navigation).query = { alert: 'alert-2' }
    await flushPromises()
    const signal = api.getOperationsAlert.mock.lastCall![1].signal as AbortSignal
    expect(wrapper.find('.alert-detail__summary').exists()).toBe(false)
    expect(wrapper.get('[role="status"]').text()).toContain('正在加载')
    reactive(navigation).query = { alert: 'alert-3' }
    await flushPromises()
    expect(signal.aborted).toBe(true)
    oldResult({ alert, events: [] })
    await flushPromises()
    expect(wrapper.find('.alert-detail__summary').exists()).toBe(false)
    newResult({ alert: { ...alert, id: 'alert-3', ruleCode: 'DB_UNAVAILABLE' }, events: [] })
    await flushPromises()
    expect(wrapper.get('.alert-detail__heading').text()).toContain('数据库不可达')
    expect(wrapper.get('.alert-detail__heading').text()).not.toContain('事件投递失败')
    wrapper.unmount()
  })
  it('关闭加载中的详情会中止请求，返回的旧响应不会重新打开弹窗', async () => {
    let resolve!: (value: unknown) => void
    api.getOperationsAlert.mockImplementationOnce(() => new Promise((done) => { resolve = done }))
    const wrapper = mount(OperationsAlertsView, { global: { stubs: { teleport: true } } })
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '详情与时间线')!.trigger('click')
    await flushPromises()
    const signal = api.getOperationsAlert.mock.lastCall![1].signal as AbortSignal
    await wrapper.get('.alert-detail__footer button').trigger('click')
    await flushPromises()
    expect(signal.aborted).toBe(true)
    resolve({ alert, events: [] })
    await flushPromises()
    const dialog = wrapper.findAllComponents(ElDialog).find((item) => item.props('title') === '告警详情')!
    expect(dialog.props('modelValue')).toBe(false)
    expect(wrapper.find('.alert-detail__summary').exists()).toBe(false)
    wrapper.unmount()
  })
})
