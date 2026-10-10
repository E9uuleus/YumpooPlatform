import {
  ProjectActorAccess,
  ProjectLifecycle,
  WorkItemStatusCategory,
  type ProjectDetail,
} from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectLifecycleActions from './ProjectLifecycleActions.vue'

const api = vi.hoisted(() => ({ archiveProject: vi.fn(), restoreProject: vi.fn(), getProjectWorkItemLabels: vi.fn(), listProjectWorkItemFilterOptions: vi.fn() }))
vi.mock('../../api/client', () => ({ projectsApi: api, workItemsApi: api }))
vi.mock('@yumpoo/api-client', async (importOriginal) => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))

const activeProject: ProjectDetail = {
  id: 'project-1', workspaceId: 'workspace-1', workspaceCode: 'PRODUCT',
  workspaceName: '产品空间', code: 'M2_08', name: '生命周期治理', description: null,
  lifecycle: ProjectLifecycle.Active,
  ownerUserId: 'owner-1', ownerDisplayName: '负责人',
  actorAccess: ProjectActorAccess.Owner,
  capabilities: {
    canUpdateSettings: true, canManageMembers: true,
    canReassignOwner: false, canArchive: true,
    canRestore: false, canMoveWorkspace: false, canOverrideArchive: true,
  },
  rowVersion: 3, etag: '"3"', createdAt: new Date(), updatedAt: new Date(),
  archivedAt: null,
}

describe('ProjectLifecycleActions', () => {
  enableAutoUnmount(afterEach)
  beforeEach(() => {
    Object.values(api).forEach(mock => mock.mockReset())
    vi.restoreAllMocks()
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    api.getProjectWorkItemLabels.mockResolvedValue({ statuses: [
      { code: 'OPEN', statusCategory: WorkItemStatusCategory.Todo },
      { code: 'IN_PROGRESS', statusCategory: WorkItemStatusCategory.InProgress },
      { code: 'DONE', statusCategory: WorkItemStatusCategory.Done },
    ] })
    api.listProjectWorkItemFilterOptions.mockResolvedValue({ items: [{ value: 'OPEN', count: 3 }, { value: 'DONE', count: 5 }], nextCursor: null })
  })

  it('按服务端能力显示普通归档并移除治理覆盖入口', () => {
    const wrapper = mount(ProjectLifecycleActions, { props: { project: activeProject } })

    expect(wrapper.text()).toContain('归档项目')
    expect(wrapper.get('#lifecycle-actions-title').text()).toBe('危险区域')
    expect(wrapper.text()).not.toContain('治理覆盖归档')
    expect(wrapper.text()).not.toContain('迁移 Workspace')
    expect(wrapper.text()).not.toContain('恢复项目')
  })

  it('归档项目支持负责人恢复入口', async () => {
    const archived: ProjectDetail = {
      ...activeProject,
      lifecycle: ProjectLifecycle.Archived,
      capabilities: {
        canUpdateSettings: false,  canManageMembers: false,
        canReassignOwner: false, canArchive: false,
        canRestore: true, canMoveWorkspace: false, canOverrideArchive: false,
      },
      archivedAt: new Date(),
    }
    const wrapper = mount(ProjectLifecycleActions, { props: { project: archived } })

    expect(wrapper.text()).toContain('恢复项目')
    expect(wrapper.text()).not.toContain('治理覆盖归档')
    expect(wrapper.text()).not.toContain('迁移 Workspace')
    await wrapper.findAll('button').find(button => button.text() === '恢复项目')!.trigger('click')
    await flushPromises()
    expect(api.restoreProject).toHaveBeenCalledWith(expect.objectContaining({ projectId: archived.id, ifMatch: archived.etag, xXSRFTOKEN: 'csrf-token' }))
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('汇总各页未关闭数量作确认提示，开放事项不会阻止归档', async () => {
    api.listProjectWorkItemFilterOptions.mockResolvedValueOnce({ items: [{ value: 'OPEN', count: 3 }, { value: 'DONE', count: 5 }], nextCursor: 'next' })
      .mockResolvedValueOnce({ items: [{ value: 'IN_PROGRESS', count: 2 }], nextCursor: null })
    const wrapper = mount(ProjectLifecycleActions, { props: { project: { ...activeProject, actorAccess: ProjectActorAccess.CompanyAdmin } } })
    await wrapper.findAll('button').find(button => button.text() === '归档项目')!.trigger('click')
    await flushPromises()
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('5 个未关闭工作项，仍可归档'), '归档项目', expect.any(Object))
    expect(api.listProjectWorkItemFilterOptions).toHaveBeenLastCalledWith(expect.objectContaining({ cursor: 'next' }))
    expect(api.archiveProject).toHaveBeenCalledWith(expect.objectContaining({ projectId: activeProject.id, ifMatch: activeProject.etag, xXSRFTOKEN: 'csrf-token' }))
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('提示数量查询失败时仍允许确认归档', async () => {
    api.getProjectWorkItemLabels.mockRejectedValue(new TypeError('offline'))
    const wrapper = mount(ProjectLifecycleActions, { props: { project: activeProject } })
    await wrapper.findAll('button').find(button => button.text() === '归档项目')!.trigger('click')
    await flushPromises()
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('数量暂无法读取，仍可归档'), '归档项目', expect.any(Object))
    expect(api.archiveProject).toHaveBeenCalledOnce()
  })
})
