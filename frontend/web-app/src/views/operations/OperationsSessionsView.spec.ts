import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import OperationsSessionsView from './OperationsSessionsView.vue'

const api = vi.hoisted(() => ({
  listOperationsSessions: vi.fn(),
  getOperationsSessionSummary: vi.fn(),
}))
vi.mock('../../api/client', () => ({ operationsApi: api }))
vi.mock('../../composables/useSession', () => ({ ensureAuthentication: vi.fn() }))
vi.mock('../../components/operations/operationsContext', () => ({
  useOperations: () => ({ interval: ref(0), refreshVersion: ref(0) }),
}))

function session(id: string, clientType: string, clientVersion: string | null) {
  const at = new Date('2026-09-30T12:00:00Z')
  return { id, clientType, clientVersion, issuedAt: at, lastSeenAt: at, expiresAt: at }
}

afterEach(() => Object.values(api).forEach((mock) => mock.mockReset()))

describe('在线会话客户端', () => {
  it('显示 Electron 版本，并把同类 Web 会话合并计数', async () => {
    api.listOperationsSessions.mockResolvedValue({
      items: [
        {
          userId: '00000000-0000-4000-8000-0000000000aa',
          displayName: '陈静',
          presence: 'ONLINE',
          lastSeenAt: new Date('2026-09-30T12:00:00Z'),
          sessions: [
            session('s1', 'WEB', null),
            session('s2', 'WEB', null),
            session('s3', 'WEB', null),
            session('s4', 'ELECTRON', '0.1.0'),
          ],
        },
      ],
      totalElements: 1,
      page: 0,
      size: 20,
    })
    api.getOperationsSessionSummary.mockResolvedValue({
      online: 1,
      idle: 0,
      away: 0,
      activeSessions: 4,
      byClientVersion: [
        { clientType: 'ELECTRON', clientVersion: '0.1.0', count: 1 },
        { clientType: 'WEB', clientVersion: null, count: 3 },
      ],
    })
    const wrapper = mount(OperationsSessionsView)
    await flushPromises()

    const chips = wrapper.findAll('.session-clients .ops-chip').map((chip) => chip.text())
    expect(chips).toEqual(['Electron 0.1.0', 'Web× 3'])
    expect(wrapper.get('.session-versions').text()).toContain('Electron 0.1.0 × 1')
    wrapper.unmount()
  })
})
