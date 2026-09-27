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

vi.mock('vue-router', () => ({ useRoute: () => ({ fullPath: '/admin/company/members' }) }))

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
    const disableButton = wrapper.findAll('button').find(button => button.text().includes('停用账号'))
    await disableButton?.trigger('click')
    await flushPromises()

    expect(api.disableMemberAccount).toHaveBeenCalledOnce()
    expect(api.listMembers).toHaveBeenCalledTimes(2)
    expect(api.getMember).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).toContain('数据已被其他人修改，请复核最新状态。')
    expect(document.body.textContent).toContain('req-version')
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
