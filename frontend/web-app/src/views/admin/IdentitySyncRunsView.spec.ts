import {
  AuthenticationClientType,
  AuthenticationRole,
  ClientCompatibility,
  DirectorySyncRunStatus,
  DirectorySyncTriggerType,
  ErrorCode,
  ResponseError,
  type DirectorySyncFailurePage,
  type DirectorySyncRun,
  type DirectorySyncRunPage,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useSession } from '../../composables/useSession'
import IdentitySyncRunsView from './IdentitySyncRunsView.vue'

const api = vi.hoisted(() => ({
  listDirectorySyncRuns: vi.fn(), triggerDirectorySync: vi.fn(),
  getDirectorySyncRun: vi.fn(), listDirectorySyncFailures: vi.fn(),
}))

vi.mock('../../api/client', () => ({ identityAdministrationApi: api }))
vi.mock('@yumpoo/api-client', async (importOriginal) => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))

const runId = '00000000-0000-4000-8000-000000000123'
const emptyPage = { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } as DirectorySyncRunPage
const run = {
  runId,
  status: DirectorySyncRunStatus.Succeeded,
  triggerType: DirectorySyncTriggerType.Manual,
  phase: 'COMPLETED',
  startedAt: new Date(),
  finishedAt: new Date(),
  counts: { discovered: 1, succeeded: 1, failed: 0 },
  requestId: 'req-run',
} as unknown as DirectorySyncRun
const emptyFailures = { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } as DirectorySyncFailurePage

function conflict(location: string): ResponseError {
  return new ResponseError(new Response(JSON.stringify({
    code: ErrorCode.RequestInProgress,
    message: '已有同步运行', requestId: 'req-conflict', retryable: false, fieldErrors: [], details: {},
  }), { status: 409, headers: { 'Content-Type': 'application/json', Location: location } }))
}

describe('同步运行冲突恢复', () => {
  beforeEach(() => {
    useSession().authentication.value = {
      user: { id: 'user', displayName: '管理员' },
      company: { id: 'company', displayName: '测试公司', timezone: 'Asia/Shanghai', weekStartDay: 'MONDAY' },
      roles: new Set([AuthenticationRole.CompanyAdmin]),
      client: { type: AuthenticationClientType.Web, compatibility: ClientCompatibility.Supported },
    } as import('@yumpoo/api-client').CurrentAuthentication
    Object.values(api).forEach(mock => mock.mockReset())
    api.listDirectorySyncRuns.mockResolvedValue(emptyPage)
    api.getDirectorySyncRun.mockResolvedValue(run)
    api.listDirectorySyncFailures.mockResolvedValue(emptyFailures)
  })

  it('409 的同源标准 Location 恢复既有运行', async () => {
    api.triggerDirectorySync.mockRejectedValue(conflict(`/api/v1/admin/directory-sync-runs/${runId}`))
    const wrapper = mount(IdentitySyncRunsView)
    await flushPromises()
    await wrapper.get('.toolbar .el-button').trigger('click')
    await flushPromises()
    expect(api.getDirectorySyncRun).toHaveBeenCalledWith({ runId })
    expect(api.triggerDirectorySync).toHaveBeenCalledOnce()
    expect(wrapper.text()).not.toContain('已有同步运行')
  })

  it('拒绝跨源 Location 并使用统一内联错误', async () => {
    api.triggerDirectorySync.mockRejectedValue(conflict(`https://evil.example/api/v1/admin/directory-sync-runs/${runId}`))
    const wrapper = mount(IdentitySyncRunsView)
    await flushPromises()
    await wrapper.get('.toolbar .el-button').trigger('click')
    await flushPromises()
    expect(api.getDirectorySyncRun).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('已有同步运行')
    expect(wrapper.text()).toContain('req-conflict')
  })
})
