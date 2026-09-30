import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
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
  useRoute: () => ({ query: { alert: alertId } }),
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

afterEach(() => {
  Object.values(api).forEach((mock) => mock.mockReset())
  members.getMember.mockReset()
})

describe('告警详情时间线', () => {
  it('补齐操作人姓名，查询失败时保留操作人 ID', async () => {
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
    wrapper.unmount()
  })
})
