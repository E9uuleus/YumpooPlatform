import {
  AccountStatus,
  AuthenticationClientType,
  AuthenticationRole,
  ClientCompatibility,
  EmploymentStatus,
  PlatformRoleTier,
  ManagedPlatformRole,
  ErrorCode,
  ResponseError,
  type Member,
  type MemberPage,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { ElAlert, ElButton, ElDialog, ElInput, ElMessageBox, ElRadioGroup } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useSession } from '../../composables/useSession'
import IdentityMembersView from './IdentityMembersView.vue'

const api = vi.hoisted(() => ({
  listMembers: vi.fn(), getMember: vi.fn(),
  disableMemberAccount: vi.fn(), enableMemberAccount: vi.fn(), changeMemberPlatformRole: vi.fn(),
}))
const route = vi.hoisted(() => ({ fullPath: '/admin/company/members', query: {} as Record<string, string> }))
const beginAuthentication = vi.hoisted(() => vi.fn())

vi.mock('vue-router', () => ({ useRoute: () => route }))
vi.mock('../../auth/navigation', () => ({ beginAuthentication }))

vi.mock('../../api/client', () => ({
  identityAdministrationApi: { listMembers: api.listMembers, getMember: api.getMember },
  identityGovernanceApi: {
    changeMemberPlatformRole: api.changeMemberPlatformRole,
    disableMemberAccount: api.disableMemberAccount,
    enableMemberAccount: api.enableMemberAccount,
  },
}))
vi.mock('@yumpoo/api-client', async (importOriginal) => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))

function member(accountStatus = AccountStatus.Enabled, etag = '"v1"'): Member {
  return {
    userId: '00000000-0000-4000-8000-000000000456', displayName: '成员甲', externalUserId: 'wecom-1',
    email: null, mobile: null, departmentSummary: '研发部', employmentStatus: EmploymentStatus.Active,
    accountStatus, directorySyncedAt: new Date(), leftAt: null, accountDisabledAt: null,
    accountDisabledByUserId: null, platformRoles: new Set(), authorizationVersion: 1, rowVersion: 1, etag,
  }
}

function page(item: Member): MemberPage {
  return { items: [item], page: 0, size: 20, totalElements: 1, totalPages: 1 }
}

function versionConflict(): ResponseError {
  return new ResponseError(new Response(JSON.stringify({
    code: ErrorCode.VersionConflict,
    message: '数据已被其他人修改，请复核最新状态。', requestId: 'req-version',
    retryable: false, fieldErrors: [], details: {},
  }), { status: 412, headers: { 'Content-Type': 'application/json' } }))
}

describe('成员版本冲突', () => {
  beforeEach(() => {
    useSession().authentication.value = {
      user: { id: 'user', displayName: '管理员', workspaceSlug: 'admin-user' },
      company: { id: 'company', displayName: '测试公司', timezone: 'Asia/Shanghai', weekStartDay: 'MONDAY' },
      roles: new Set([AuthenticationRole.CompanyAdmin]),
      client: { type: AuthenticationClientType.Web, compatibility: ClientCompatibility.Supported },
    } as import('@yumpoo/api-client').CurrentAuthentication
    Object.values(api).forEach(mock => mock.mockReset())
    beginAuthentication.mockReset()
    route.query = {}
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({
      value: '例行账号治理',
      action: 'confirm',
    } as never)
  })

  it('412 后刷新列表与已打开详情，保留提示且不自动重试', async () => {
    const original = member()
    const refreshed = member(AccountStatus.Disabled, '"v2"')
    api.listMembers.mockResolvedValueOnce(page(original)).mockResolvedValueOnce(page(refreshed))
    api.getMember.mockResolvedValueOnce(original).mockResolvedValueOnce(refreshed)
    api.disableMemberAccount.mockRejectedValueOnce(versionConflict())

    const wrapper = mount(IdentityMembersView, { attachTo: document.body })
    await flushPromises()
    const detailsButton = wrapper.findAll('button').find(button => button.text().includes('详情'))
    await detailsButton?.trigger('click')
    await flushPromises()
    Array.from(document.body.querySelectorAll('button')).find(button => button.textContent?.includes('停用账号'))?.click()
    await flushPromises()

    expect(api.disableMemberAccount).toHaveBeenCalledOnce()
    expect(api.listMembers).toHaveBeenCalledTimes(2)
    expect(api.getMember).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).toContain('数据已被其他人修改，请复核最新状态。')
    expect(document.body.textContent).toContain('req-version')
    wrapper.unmount()
  })

  it.each([true, false])('在成员详情展示账号拒绝原因，仅认证过期 %s 提供重新认证入口', async expired => {
    const original = member()
    api.listMembers.mockResolvedValue(page(original))
    api.getMember.mockResolvedValue(original)
    api.disableMemberAccount.mockRejectedValue(new ResponseError(new Response(JSON.stringify({
      code: ErrorCode.AccessDenied, message: expired ? '最近认证已过期，请重新认证后再试' : '无权执行此操作',
      requestId: 'req-account-denied', retryable: false, fieldErrors: [],
      details: expired ? { reason: 'RECENT_AUTHENTICATION_REQUIRED' } : {},
    }), { status: 403 })))
    const wrapper = mount(IdentityMembersView, { attachTo: document.body })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text().includes('详情'))?.trigger('click')
    await flushPromises()
    document.querySelector<HTMLButtonElement>('.member-profile__access button')!.click()
    await flushPromises()

    const detail = document.querySelector('.member-profile')!
    expect(detail.textContent).toContain('req-account-denied')
    expect(detail.textContent).toContain(expired ? '最近认证已过期' : '无权执行此操作')
    const reauthenticate = Array.from(detail.querySelectorAll('button')).find(button => button.textContent?.includes('重新认证并返回'))
    expect(!!reauthenticate).toBe(expired)
    if (expired) {
      reauthenticate!.click()
      expect(beginAuthentication).toHaveBeenCalledWith(`/admin/company/members?memberId=${original.userId}`)
    }
    expect(api.disableMemberAccount).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('重新认证返回时打开目标成员详情，复核后可以成功停用', async () => {
    const original = member()
    route.query = { memberId: original.userId }
    api.listMembers.mockResolvedValue(page(original))
    api.getMember.mockResolvedValueOnce(original).mockResolvedValueOnce(member(AccountStatus.Disabled, '"v2"'))
    api.disableMemberAccount.mockResolvedValue({})
    const wrapper = mount(IdentityMembersView, { attachTo: document.body })
    await flushPromises()
    expect(api.getMember).toHaveBeenCalledWith({ userId: original.userId })
    expect(api.disableMemberAccount).not.toHaveBeenCalled()
    document.querySelector<HTMLButtonElement>('.member-profile__access button')!.click()
    await flushPromises()
    expect(api.disableMemberAccount).toHaveBeenCalledOnce()
    expect(document.querySelector('.member-profile__access')!.textContent).toContain('启用账号')
    wrapper.unmount()
  })
  it('仅平台管理员可更改角色，本人行禁止操作，成员显示普通员工', async () => {
    api.listMembers.mockResolvedValue(page(member()))
    const administrator = mount(IdentityMembersView)
    await flushPromises()
    expect(administrator.text()).toContain('普通员工')
    expect(administrator.text()).not.toContain('更改角色')
    administrator.unmount()
    useSession().authentication.value!.roles = new Set([AuthenticationRole.AppManager])
    useSession().authentication.value!.user.id = member().userId
    const manager = mount(IdentityMembersView)
    await flushPromises()
    const button = manager.findAll('button').find(item => item.text() === '更改角色')
    expect(button?.attributes('disabled')).toBeDefined()
    expect(button?.attributes('title')).toContain('不能更改自己')
    manager.unmount()
  })

  it.each([
    [undefined, PlatformRoleTier.CompanyAdmin],
    [undefined, PlatformRoleTier.AppManager],
    [ManagedPlatformRole.CompanyAdmin, PlatformRoleTier.AppManager],
  ])('从 %s 升级到 %s 时提示登录会话失效', async (currentRole, nextTier) => {
    useSession().authentication.value!.roles = new Set([AuthenticationRole.AppManager])
    const target = member()
    if (currentRole) target.platformRoles.add(currentRole)
    api.listMembers.mockResolvedValue(page(target))
    const wrapper = mount(IdentityMembersView, { attachTo: document.body })
    await flushPromises()
    await wrapper.findAll('button').find(item => item.text() === '更改角色')?.trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(ElAlert).exists()).toBe(false)
    wrapper.findComponent(ElRadioGroup).vm.$emit('update:modelValue', nextTier)
    await flushPromises()
    expect(wrapper.findComponent(ElAlert).props('title')).toBe('该成员的登录会话将失效')
    wrapper.unmount()
  })

  it.each([
    undefined,
    ManagedPlatformRole.CompanyAdmin,
    ManagedPlatformRole.AppManager,
  ])('当前层级 %s 即使填写理由也不能提交', async (currentRole) => {
    useSession().authentication.value!.roles = new Set([AuthenticationRole.AppManager])
    const target = member()
    if (currentRole) target.platformRoles.add(currentRole)
    api.listMembers.mockResolvedValue(page(target))
    const wrapper = mount(IdentityMembersView, { attachTo: document.body })
    await flushPromises()
    await wrapper.findAll('button').find(item => item.text() === '更改角色')?.trigger('click')
    await flushPromises()
    wrapper.findAllComponents(ElInput).find(item => item.props('type') === 'textarea')?.vm.$emit('update:modelValue', '职责复核')
    await flushPromises()
    const submit = wrapper.findComponent(ElDialog).findAllComponents(ElButton)
      .find(button => button.text() === '当前角色')
    expect(submit).toBeDefined()
    expect(submit?.props('disabled')).toBe(true)
    expect(wrapper.findComponent(ElAlert).exists()).toBe(false)
    submit?.vm.$emit('click')
    await flushPromises()
    expect(api.changeMemberPlatformRole).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('角色页签按层级筛选并回到第一页', async () => {
    api.listMembers.mockResolvedValue(page(member()))
    const wrapper = mount(IdentityMembersView)
    await flushPromises()
    await wrapper.findAll('.tier-tab').find(tab => tab.text() === '平台管理员')?.trigger('click')
    await flushPromises()
    expect(api.listMembers).toHaveBeenLastCalledWith(expect.objectContaining({ platformRole: PlatformRoleTier.AppManager, page: 0 }))
    expect(wrapper.get('.tier-tab.active').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('.tier-tab.active').text()).toBe('平台管理员')
    wrapper.unmount()
  })

  it('角色变更在网络失败后使用相同幂等键重试', async () => {
    useSession().authentication.value!.roles = new Set([AuthenticationRole.AppManager])
    api.listMembers.mockResolvedValue(page(member()))
    api.changeMemberPlatformRole.mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce({})
    const wrapper = mount(IdentityMembersView, { attachTo: document.body })
    await flushPromises()
    await wrapper.findAll('button').find(item => item.text() === '更改角色')?.trigger('click')
    await flushPromises()
    wrapper.findComponent(ElRadioGroup).vm.$emit('update:modelValue', PlatformRoleTier.AppManager)
    wrapper.findAllComponents(ElInput).find(item => item.props('type') === 'textarea')?.vm.$emit('update:modelValue', '职责调整')
    await flushPromises()
    const submit = () => Array.from(document.body.querySelectorAll('button')).find(item => item.textContent?.replace(/\s/g, '') === '设为平台管理员')?.click()
    submit()
    await flushPromises()
    expect(api.changeMemberPlatformRole).toHaveBeenCalledTimes(1)
    submit()
    await flushPromises()
    expect(api.changeMemberPlatformRole).toHaveBeenCalledTimes(2)
    const first = api.changeMemberPlatformRole.mock.calls[0]?.[0]
    expect(first).toMatchObject({ userId: member().userId, ifMatch: member().etag,
      platformRoleChangeRequest: { role: PlatformRoleTier.AppManager, reason: '职责调整' } })
    expect(api.changeMemberPlatformRole.mock.calls[1]?.[0].idempotencyKey).toBe(first.idempotencyKey)
    wrapper.unmount()
  })

})
