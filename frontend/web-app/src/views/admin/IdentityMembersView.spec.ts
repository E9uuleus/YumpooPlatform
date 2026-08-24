import {
  AccountStatus,
  AuthenticationClientType,
  AuthenticationRole,
  ClientCompatibility,
  EmploymentStatus,
  ErrorCode,
  ResponseError,
  type Member,
  type MemberPage,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useSession } from '../../composables/useSession'
import IdentityMembersView from './IdentityMembersView.vue'

const api = vi.hoisted(() => ({
  listMembers: vi.fn(), getMember: vi.fn(),
  disableMemberAccount: vi.fn(), enableMemberAccount: vi.fn(),
}))

vi.mock('../../api/client', () => ({
  identityAdministrationApi: { listMembers: api.listMembers, getMember: api.getMember },
  identityGovernanceApi: {
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
})
