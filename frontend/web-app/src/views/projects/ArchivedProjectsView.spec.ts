import { ProjectActorAccess, ProjectLifecycle, ProjectLifecycleFilter, ResponseError, type ProjectPage, type ProjectSummary } from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ArchivedProjectsView from './ArchivedProjectsView.vue'

const api = vi.hoisted(() => ({ listProjects: vi.fn(), restoreProject: vi.fn() }))
const push = vi.hoisted(() => vi.fn())
vi.mock('../../api/client', () => ({ projectsApi: api }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('../../composables/useSession', () => ({ useSession: () => ({ authentication: { value: { company: { timezone: 'UTC' } } } }) }))
vi.mock('@yumpoo/api-client', async original => ({ ...await original<typeof import('@yumpoo/api-client')>(), readCsrfToken: () => 'csrf' }))

const archived: ProjectSummary = {
  id: 'project-archived', workspaceId: 'main', workspaceCode: 'MAIN', workspaceName: '主工作空间',
  code: 'P001', name: '历史项目', lifecycle: ProjectLifecycle.Archived, ownerUserId: 'owner', ownerDisplayName: '负责人',
  actorAccess: ProjectActorAccess.Owner, rowVersion: 4, etag: '"4"',
  capabilities: { canUpdateSettings: false, canManageMembers: false, canReassignOwner: false, canArchive: false,
    canRestore: true, canMoveWorkspace: false, canOverrideArchive: false },
  createdAt: new Date('2026-10-01T00:00:00Z'), updatedAt: new Date('2026-10-09T15:00:00Z'), archivedAt: new Date('2026-10-09T15:00:00Z'),
}
const page = (items: ProjectSummary[] = [archived]): ProjectPage => ({ items, page: 0, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0 })
enableAutoUnmount(afterEach)
beforeEach(() => {
  Object.values(api).forEach(mock => mock.mockReset())
  push.mockReset()
  vi.restoreAllMocks()
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  api.listProjects.mockResolvedValue(page())
})
afterEach(() => vi.useRealTimers())

describe('归档项目页', () => {
  it('请求归档范围并展示真实归档时间，打开项目进入只读详情', async () => {
    const wrapper = mount(ArchivedProjectsView)
    await flushPromises()
    expect(api.listProjects).toHaveBeenCalledWith({ lifecycle: ProjectLifecycleFilter.Archived, page: 0, size: 20 })
    expect(wrapper.findAll('th').map(cell => cell.text())).toEqual(['项目名称', '项目编号', '负责人', '归档时间', '操作'])
    expect(wrapper.text()).toContain('历史项目')
    expect(wrapper.text()).toContain('2026-10-09')
    await wrapper.findAll('button').find(button => button.text() === '打开')!.trigger('click')
    expect(push).toHaveBeenCalledWith({ name: 'project-overview', params: { projectId: archived.id } })
  })

  it('负责人恢复使用当前版本和幂等键并刷新归档列表', async () => {
    const wrapper = mount(ArchivedProjectsView)
    await flushPromises()
    api.listProjects.mockResolvedValue(page([]))
    await wrapper.findAll('button').find(button => button.text() === '恢复')!.trigger('click')
    await flushPromises()
    expect(api.restoreProject).toHaveBeenCalledWith({ projectId: archived.id, ifMatch: archived.etag, xXSRFTOKEN: 'csrf', idempotencyKey: expect.any(String) })
    expect(wrapper.text()).toContain('暂无可访问的归档项目')
  })

  it('版本冲突刷新列表并保留错误说明', async () => {
    api.restoreProject.mockRejectedValue(new ResponseError(new Response(JSON.stringify({ code: 'VERSION_CONFLICT', message: '项目版本冲突', requestId: 'archive-412', retryable: false, fieldErrors: [], details: {} }), { status: 412 })))
    const wrapper = mount(ArchivedProjectsView)
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '恢复')!.trigger('click')
    await flushPromises()
    expect(api.listProjects).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('项目版本冲突')
  })

  it('无可见归档项目显示空状态', async () => {
    api.listProjects.mockResolvedValue(page([]))
    const wrapper = mount(ArchivedProjectsView)
    await flushPromises()
    expect(wrapper.find('table').exists()).toBe(false)
    expect(wrapper.text()).toContain('暂无可访问的归档项目')
  })
})
