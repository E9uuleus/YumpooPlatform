import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import IdentityOverviewView from './IdentityOverviewView.vue'

const api = vi.hoisted(() => ({
  getCompany: vi.fn(),
  getWeComIntegrationStatus: vi.fn(),
}))

vi.mock('../../api/client', () => ({
  identityAdministrationApi: api,
}))

describe('M1-11 身份管理概览', () => {
  beforeEach(() => {
    api.getCompany.mockResolvedValue({
      id: '00000000-0000-4000-8000-000000000001',
      displayName: 'Yumpoo 测试公司',
      timezone: 'Asia/Shanghai',
      weekStartDay: 'MONDAY',
      defaultWorkdayMinutes: 480,
      rowVersion: 0,
    })
    api.getWeComIntegrationStatus.mockResolvedValue({
      oauth: {
        enabled: true,
        configured: true,
        corpIdMasked: '****5678',
        agentIdConfigured: true,
        appSecretConfigured: true,
        callbackConfigured: true,
      },
      directory: {
        enabled: true,
        configured: true,
        corpIdMasked: '****5678',
        directorySecretConfigured: true,
        profileSecretConfigured: true,
      },
      corpIdConsistent: true,
      activeRunId: null,
      lastSuccessfulRunAt: null,
      lastProblemAt: null,
      lastProblemCode: null,
    })
  })

  it('只展示配置状态和脱敏标识，不渲染凭据编辑控件', async () => {
    const wrapper = mount(IdentityOverviewView)
    await flushPromises()

    expect(wrapper.text()).toContain('Yumpoo 测试公司')
    expect(wrapper.text()).toContain('****5678')
    expect(wrapper.text()).toContain('凭据由外部安全配置注入')
    expect(wrapper.find('input[type="password"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('oauth-super-secret')
  })
})
