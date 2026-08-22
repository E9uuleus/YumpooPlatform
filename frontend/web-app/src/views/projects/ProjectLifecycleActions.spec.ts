import {
  ProjectActorAccess,
  ProjectLifecycle,
  ProjectTemplateKey,
  ProjectType,
  type ProjectDetail,
} from '@yumpoo/api-client'
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ProjectLifecycleActions from './ProjectLifecycleActions.vue'

vi.mock('../../api/client', () => ({
  administrationApi: { createGovernanceOverride: vi.fn() },
  projectsApi: {
    archiveProject: vi.fn(), restoreProject: vi.fn(), moveProjectWorkspace: vi.fn(),
  },
  workspacesApi: { listWorkspaces: vi.fn() },
}))
vi.mock('@yumpoo/api-client', async (importOriginal) => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))

const activeProject: ProjectDetail = {
  id: 'project-1', workspaceId: 'workspace-1', workspaceCode: 'PRODUCT',
  workspaceName: '产品空间', code: 'M2_08', name: '生命周期治理', description: null,
  projectType: ProjectType.ProductDevelopment, lifecycle: ProjectLifecycle.Active,
  ownerUserId: 'owner-1', ownerDisplayName: '负责人', templateKey: ProjectTemplateKey.Rnd,
  templateVersion: 1, customerName: null, customerReference: null, deliverySite: null,
  contactNote: null, actorAccess: ProjectActorAccess.Owner,
  capabilities: {
    canUpdateSettings: true, canActivate: false, canManageMembers: true,
    canReassignOwner: false, canManageProductLinks: true, canArchive: true,
    canRestore: false, canMoveWorkspace: true, canOverrideArchive: true,
  },
  rowVersion: 3, etag: '"3"', createdAt: new Date(), updatedAt: new Date(),
  activatedAt: new Date(), archivedAt: null,
}

describe('ProjectLifecycleActions', () => {
  it('按服务端能力显示普通归档、覆盖归档与迁移入口', () => {
    const wrapper = mount(ProjectLifecycleActions, { props: { project: activeProject } })

    expect(wrapper.text()).toContain('归档 Project')
    expect(wrapper.text()).toContain('治理覆盖归档')
    expect(wrapper.text()).toContain('迁移 Workspace')
    expect(wrapper.text()).not.toContain('恢复 Project')
  })

  it('归档项目仅显示管理员恢复入口', () => {
    const archived: ProjectDetail = {
      ...activeProject,
      lifecycle: ProjectLifecycle.Archived,
      capabilities: {
        canUpdateSettings: false, canActivate: false, canManageMembers: false,
        canReassignOwner: false, canManageProductLinks: false, canArchive: false,
        canRestore: true, canMoveWorkspace: false, canOverrideArchive: false,
      },
      archivedAt: new Date(),
    }
    const wrapper = mount(ProjectLifecycleActions, { props: { project: archived } })

    expect(wrapper.text()).toContain('恢复 Project')
    expect(wrapper.text()).not.toContain('治理覆盖归档')
    expect(wrapper.text()).not.toContain('迁移 Workspace')
  })
})
