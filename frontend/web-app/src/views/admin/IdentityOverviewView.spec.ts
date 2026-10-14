import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import IdentityOverviewView from './IdentityOverviewView.vue'

const api = vi.hoisted(() => ({
  getCompany: vi.fn(),
  getWeComIntegrationStatus: vi.fn(),
  listMembers: vi.fn(),
  listProjects: vi.fn(),
  listScheduledJobs: vi.fn(),
  updateCompany: vi.fn(),
}))
const onboardingReady = vi.hoisted(() => vi.fn())
const session = vi.hoisted(() => ({ isCompanyAdmin: false, ensureAuthentication: vi.fn() }))
vi.mock('../../composables/useSession', () => ({
  useSession: () => ({ authentication: ref(), isCompanyAdmin: ref(session.isCompanyAdmin) }),
  ensureAuthentication: session.ensureAuthentication,
}))
vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))
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
    session.isCompanyAdmin = false
    session.ensureAuthentication.mockReset()
    api.updateCompany.mockReset()
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
    expect(wrapper.findAll('.yp-jump-card--row').map(card => card.find('strong').text()))
      .toEqual(['项目', '成员与角色', '活动日志', '同步运行', '定时任务'])
  })

  it('标题区只显示公司名称与 Corp ID，不展示默认工作制，状态以圆点文字呈现', async () => {
    const wrapper = mount(IdentityOverviewView)
    await flushPromises()

    const identity = wrapper.get('[data-tour="company-profile"]')
    expect(identity.get('h2').text()).toBe('Yumpoo 测试公司')
    expect(identity.text()).toContain('企微 Corp ID')
    expect(identity.text()).toContain('****5678')
    expect(wrapper.text()).not.toMatch(/标准工时|周起始日|时区/)
    expect(wrapper.find('[aria-label="编辑公司名称"]').exists()).toBe(false)
    expect(wrapper.findAll('.yp-status-tag--dot').map(tag => tag.text()))
      .toEqual(['已启用', '配置完整', '已启用', '配置完整'])
    expect(wrapper.find('.yp-status-tag--soft').exists()).toBe(false)
  })

  it('公司管理员可编辑公司名称并刷新会话', async () => {
    session.isCompanyAdmin = true
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValueOnce({ value: '  新公司名  ', action: 'confirm' } as never)
    api.updateCompany.mockResolvedValueOnce({
      id: '00000000-0000-4000-8000-000000000001',
      displayName: '新公司名',
      timezone: 'Asia/Shanghai',
      weekStartDay: 'MONDAY',
      defaultWorkdayMinutes: 480,
      rowVersion: 1,
    })
    const wrapper = mount(IdentityOverviewView)
    await flushPromises()

    await wrapper.get('[aria-label="编辑公司名称"]').trigger('click')
    await flushPromises()

    expect(api.updateCompany).toHaveBeenCalledWith({
      xXSRFTOKEN: 'csrf-token',
      ifMatch: '"0"',
      companyUpdateRequest: { displayName: '新公司名' },
    })
    expect(wrapper.get('h2').text()).toBe('新公司名')
    expect(session.ensureAuthentication).toHaveBeenCalledWith(true)
  })

  it.each(['getCompany', 'getWeComIntegrationStatus'] as const)('does not start onboarding when %s fails', async method => {
    api[method].mockRejectedValueOnce(new Error('offline'))
    const wrapper = mount(IdentityOverviewView)
    await flushPromises()
    expect(wrapper.text()).toContain('概览加载失败')
    expect(onboardingReady).not.toHaveBeenCalled()
  })
})
