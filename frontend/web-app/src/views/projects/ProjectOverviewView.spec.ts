import {
  ProjectActorAccess,
  ProjectLifecycle,
  ProjectTemplateKey,
  ProjectType,
  type ProjectDetail,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectOverviewView from './ProjectOverviewView.vue'

const state = vi.hoisted(() => ({
  route: undefined as unknown as { params: { projectId: string } },
  getProject: vi.fn(),
}))

vi.mock('vue-router', () => ({ useRoute: () => state.route }))
vi.mock('../../api/client', () => ({
  projectsApi: {
    getProject: state.getProject,
    activateProject: vi.fn(),
  },
}))

function project(id: string): ProjectDetail {
  return {
    id,
    workspaceId: 'workspace-1',
    workspaceCode: 'MAIN',
    workspaceName: '主工作空间',
    code: id.toUpperCase(),
    name: `项目 ${id}`,
    description: null,
    projectType: ProjectType.ProductDevelopment,
    lifecycle: ProjectLifecycle.Active,
    ownerUserId: 'owner-1',
    ownerDisplayName: '负责人',
    templateKey: ProjectTemplateKey.Rnd,
    templateVersion: 1,
    customerName: null,
    customerReference: null,
    deliverySite: null,
    contactNote: null,
    actorAccess: ProjectActorAccess.Owner,
    capabilities: {
      canUpdateSettings: true,
      canActivate: false,
      canManageMembers: true,
      canReassignOwner: true,
      canManageProductLinks: true,
      canArchive: true,
      canRestore: false,
      canMoveWorkspace: false,
      canOverrideArchive: false,
    },
    rowVersion: 1,
    etag: '"1"',
    createdAt: new Date('2026-08-25T00:00:00Z'),
    updatedAt: new Date('2026-08-25T00:00:00Z'),
    activatedAt: null,
    archivedAt: null,
  }
}

function mountView() {
  return mount(ProjectOverviewView, {
    global: {
      stubs: {
        InlineProblem: true,
        ProjectLifecycleActions: true,
        ProjectWorkspaceHeader: {
          props: ['project'],
          template: '<div data-testid="project-name">{{ project.name }}<slot name="primary-action" /></div>',
        },
      },
    },
  })
}

describe('项目概览页', () => {
  beforeEach(() => {
    state.route = reactive({ params: { projectId: 'project-1' } })
    state.getProject.mockReset()
    state.getProject.mockImplementation(({ projectId }: { projectId: string }) => Promise.resolve(project(projectId)))
  })

  afterEach(() => vi.restoreAllMocks())

  it('切换路由中的项目后重新加载并展示新项目', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    const wrapper = mountView()
    await flushPromises()

    expect(state.getProject).toHaveBeenCalledWith({ projectId: 'project-1' })
    expect(wrapper.get('[data-testid="project-name"]').text()).toContain('项目 project-1')

    state.route.params.projectId = 'project-2'
    await nextTick()
    await flushPromises()

    expect(state.getProject).toHaveBeenLastCalledWith({ projectId: 'project-2' })
    expect(wrapper.get('[data-testid="project-name"]').text()).toContain('项目 project-2')
    expect(warn.mock.calls.flat().join(' ')).not.toContain('Failed to resolve directive: loading')

    wrapper.unmount()
  })

  it('忽略晚于新项目返回的旧项目请求', async () => {
    let resolveFirst: ((value: ProjectDetail) => void) | undefined
    state.getProject
      .mockImplementationOnce(() => new Promise<ProjectDetail>((resolve) => { resolveFirst = resolve }))
      .mockResolvedValueOnce(project('project-2'))
    const wrapper = mountView()

    state.route.params.projectId = 'project-2'
    await nextTick()
    await flushPromises()
    expect(wrapper.get('[data-testid="project-name"]').text()).toContain('项目 project-2')

    resolveFirst?.(project('project-1'))
    await flushPromises()
    expect(wrapper.get('[data-testid="project-name"]').text()).toContain('项目 project-2')

    wrapper.unmount()
  })
})
