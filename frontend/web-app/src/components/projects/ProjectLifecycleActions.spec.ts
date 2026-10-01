import {
  ProjectActorAccess,
  ProjectLifecycle,
  type ProjectDetail,
} from '@yumpoo/api-client'
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ProjectLifecycleActions from './ProjectLifecycleActions.vue'

vi.mock('../../api/client', () => ({
  administrationApi: { createGovernanceOverride: vi.fn() },
  projectsApi: {
    archiveProject: vi.fn(), restoreProject: vi.fn(),
  },
}))
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
  it('按服务端能力显示普通归档与覆盖归档入口', () => {
    const wrapper = mount(ProjectLifecycleActions, { props: { project: activeProject } })

    expect(wrapper.text()).toContain('归档项目')
    expect(wrapper.get('#lifecycle-actions-title').text()).toBe('危险区域')
    expect(wrapper.text()).toContain('治理覆盖归档')
    expect(wrapper.text()).not.toContain('迁移 Workspace')
    expect(wrapper.text()).not.toContain('恢复项目')
  })

  it('归档项目仅显示管理员恢复入口', () => {
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
  })
})
