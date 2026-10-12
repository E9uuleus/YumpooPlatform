import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import IdentityOverviewView from './IdentityOverviewView.vue'

const api = vi.hoisted(() => ({
  getCompany: vi.fn(),
  getWeComIntegrationStatus: vi.fn(),
  listMembers: vi.fn(),
  listProjects: vi.fn(),
  listScheduledJobs: vi.fn(),
}))
const onboardingReady = vi.hoisted(() => vi.fn())
vi.mock('../../onboarding/useOnboarding', () => ({ usePageOnboarding: () => ({ ready: onboardingReady }) }))
enableAutoUnmount(afterEach)

vi.mock('../../api/client', () => ({
  identityAdministrationApi: api,
  projectsApi: { listProjects: api.listProjects },
  administrationApi: { listScheduledJobs: api.listScheduledJobs },
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

describe('M1-11 身份管理概览', () => {
  beforeEach(() => {
    onboardingReady.mockClear()
    api.listMembers.mockResolvedValue({ totalElements: 8, items: [] })
    api.listProjects.mockResolvedValue({ totalElements: 3, items: [] })
    api.listScheduledJobs.mockResolvedValue({ items: [] })
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
    expect(onboardingReady).toHaveBeenCalledOnce()
    expect(wrapper.findAll('.yp-jump-card').map(card => card.find('strong').text()))
      .toEqual(['项目', '成员与角色', '活动日志', '同步运行', '定时任务'])
  })

  it.each(['getCompany', 'getWeComIntegrationStatus'] as const)('does not start onboarding when %s fails', async method => {
    api[method].mockRejectedValueOnce(new Error('offline'))
    const wrapper = mount(IdentityOverviewView)
    await flushPromises()
    expect(wrapper.text()).toContain('概览加载失败')
    expect(onboardingReady).not.toHaveBeenCalled()
  })
})
