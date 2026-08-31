import {
  WorkItemRelationCandidateEligibilityEnum,
  WorkItemRelationRole,
  WorkItemRelationType,
  type WorkItemRelation,
  type WorkItemRelationCandidate,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WorkItemRelations from './WorkItemRelations.vue'

const api = vi.hoisted(() => ({
  listWorkItemRelations: vi.fn(),
  listWorkItemRelationCandidates: vi.fn(),
  createWorkItemRelationRaw: vi.fn(),
  changeWorkItemParent: vi.fn(),
  deleteWorkItemRelation: vi.fn(),
  listProjects: vi.fn(),
}))

vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))

vi.mock('../../api/client', () => ({ workItemsApi: api, projectsApi: api }))

function relation(overrides: Partial<WorkItemRelation> = {}): WorkItemRelation {
  return {
    id: 'relation-1', relationType: WorkItemRelationType.Blocks,
    currentRole: WorkItemRelationRole.Blocks, counterpartRole: WorkItemRelationRole.BlockedBy,
    counterpartVisible: true,
    counterpart: {
      id: 'item-2', projectId: 'project-1', contentId: 'content-1', itemNo: 'YMP-2',
      type: 'TASK', title: '对端事项', statusCode: 'TODO', deleted: false,
    },
    status: 'ACTIVE', createdByUserId: 'user-1', createdAt: new Date('2026-08-30T01:00:00Z'),
    deletedByUserId: null, deletedAt: null, deleteReason: null, rowVersion: 0, etag: '"0"',
    capabilities: { canDelete: true, canChangeParent: false },
    ...overrides,
  } as WorkItemRelation
}

function candidate(overrides: Partial<WorkItemRelationCandidate> = {}): WorkItemRelationCandidate {
  return {
    item: {
      id: 'item-2', projectId: 'project-1', contentId: 'content-1', itemNo: 'YMP-2',
      type: 'TASK', title: '候选事项', statusCode: 'TODO', deleted: false,
    },
    eligibility: WorkItemRelationCandidateEligibilityEnum.Eligible,
    reasonCode: null,
    activeParent: null,
    ...overrides,
  }
}

function relationPage(items: WorkItemRelation[] = [relation()]) {
  return {
    items, page: 0, size: 20, totalElements: items.length, totalPages: 1,
    canCreate: true, hasHiddenRelations: false,
  }
}

describe('WorkItemRelations', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listWorkItemRelations.mockResolvedValue(relationPage())
    api.listWorkItemRelationCandidates.mockResolvedValue({
      items: [], page: 0, size: 12, totalElements: 0, totalPages: 0,
    })
    api.listProjects.mockResolvedValue({ items: [
      { id: 'project-1', code: 'P1', name: '当前项目' },
      { id: 'project-2', code: 'P2', name: '目标项目' },
    ], page: 0, size: 20, totalElements: 2, totalPages: 1 })
  })

  it('按当前侧语义分组，并将已删除对端禁用但保留解除能力', async () => {
    api.listWorkItemRelations.mockResolvedValue(relationPage([
      relation({
        counterpart: { ...relation().counterpart, deleted: true },
      }),
    ]))
    const wrapper = mount(WorkItemRelations, {
      props: { workItemId: 'item-1', currentProjectId: 'project-1' },
      global: { stubs: { InlineProblem: true } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('被当前事项阻塞')
    expect(wrapper.text()).toContain('已删除')
    expect(wrapper.get('button.relation-link').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('解除')
  })

  it('展示禁用原因并在重复创建响应时不追加本地关系', async () => {
    const eligible = candidate()
    const invalid = candidate({
      item: { ...candidate().item, id: 'item-3', itemNo: 'YMP-3' },
      eligibility: WorkItemRelationCandidateEligibilityEnum.Ineligible,
      reasonCode: 'CHILD_HAS_CHILDREN',
    })
    api.listWorkItemRelationCandidates.mockResolvedValue({
      items: [eligible, invalid], page: 0, size: 12, totalElements: 2, totalPages: 1,
    })
    api.createWorkItemRelationRaw.mockResolvedValue({
      raw: { status: 200 }, value: vi.fn().mockResolvedValue(relation()),
    })
    const wrapper = mount(WorkItemRelations, {
      props: { workItemId: 'item-1', currentProjectId: 'project-1' }, global: { stubs: { InlineProblem: true } },
    })
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      openCreate: () => void
      query: string
      search: (page?: number) => Promise<void>
      choose: (value: WorkItemRelationCandidate) => Promise<void>
    }
    vm.openCreate()
    vm.query = 'YMP'
    await vm.search()
    await flushPromises()

    expect(wrapper.text()).toContain('有子项的根项不能成为子项')
    expect(wrapper.findAll('.candidate-list button')[1]?.attributes('disabled')).toBeDefined()
    await vm.choose(eligible)
    expect(api.createWorkItemRelationRaw).toHaveBeenCalledWith(expect.objectContaining({
      workItemId: 'item-1', xXSRFTOKEN: 'csrf-token', idempotencyKey: expect.any(String),
      workItemRelationCreateRequest: expect.objectContaining({ targetWorkItemId: 'item-2' }),
    }))
    expect(api.listWorkItemRelations).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('changed')?.[0]).toEqual([['item-1', 'item-2']])
  })

  it('需换父时明确确认并调用原子接口，解除时提交原因与 ETag', async () => {
    const reparent = candidate({
      eligibility: WorkItemRelationCandidateEligibilityEnum.ReparentRequired,
      reasonCode: 'CHILD_ALREADY_HAS_PARENT',
      activeParent: {
        relationId: 'old-relation', etag: '"2"',
        parent: { ...candidate().item, id: 'old-parent', itemNo: 'YMP-1', title: '旧父项' },
      },
    })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValueOnce('confirm' as never)
    vi.spyOn(ElMessageBox, 'prompt')
      .mockResolvedValueOnce({ value: '调整层级', action: 'confirm' } as never)
      .mockResolvedValueOnce({ value: '关系不再需要', action: 'confirm' } as never)
    api.changeWorkItemParent.mockResolvedValue(relation())
    api.deleteWorkItemRelation.mockResolvedValue(relation({ status: 'DELETED' } as never))
    const wrapper = mount(WorkItemRelations, {
      props: { workItemId: 'item-1', currentProjectId: 'project-1' }, global: { stubs: { InlineProblem: true } },
    })
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      relationType: WorkItemRelationType
      currentRole: WorkItemRelationRole
      targetProjectId: string
      choose: (value: WorkItemRelationCandidate) => Promise<void>
      remove: (value: WorkItemRelation) => Promise<void>
    }
    vm.targetProjectId = 'project-2'
    vm.relationType = WorkItemRelationType.ParentChild
    await flushPromises()
    expect(vm.targetProjectId).toBe('project-1')
    vm.currentRole = WorkItemRelationRole.Parent
    await vm.choose(reparent)
    expect(api.changeWorkItemParent).toHaveBeenCalledWith(expect.objectContaining({
      relationId: 'old-relation', ifMatch: '"2"', idempotencyKey: expect.any(String),
      workItemParentChangeRequest: { newParentWorkItemId: 'item-1', reason: '调整层级' },
    }))

    await vm.remove(relation())
    expect(api.deleteWorkItemRelation).toHaveBeenCalledWith(expect.objectContaining({
      relationId: 'relation-1', ifMatch: '"0"', idempotencyKey: expect.any(String),
      workItemRelationDeleteRequest: { reason: '关系不再需要' },
    }))
  })

  it('切换目标项目会清空候选，查询和创建都携带目标项目', async () => {
    const eligible = candidate({ item: { ...candidate().item, projectId: 'project-2' } })
    api.listWorkItemRelationCandidates.mockResolvedValue({
      items: [eligible], page: 0, size: 12, totalElements: 1, totalPages: 1,
    })
    api.createWorkItemRelationRaw.mockResolvedValue({
      raw: { status: 201 }, value: vi.fn().mockResolvedValue(relation()),
    })
    const wrapper = mount(WorkItemRelations, {
      props: { workItemId: 'item-1', currentProjectId: 'project-1' },
      global: { stubs: { InlineProblem: true } },
    })
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      openCreate: () => void
      targetProjectId: string
      query: string
      candidates: WorkItemRelationCandidate[]
      search: () => Promise<void>
      choose: (value: WorkItemRelationCandidate) => Promise<void>
    }
    vm.openCreate()
    await flushPromises()
    vm.targetProjectId = 'project-2'
    vm.query = '目标'
    await vm.search()
    expect(api.listWorkItemRelationCandidates).toHaveBeenCalledWith(expect.objectContaining({
      targetProjectId: 'project-2',
    }))
    await vm.choose(eligible)
    expect(api.createWorkItemRelationRaw).toHaveBeenCalledWith(expect.objectContaining({
      workItemRelationCreateRequest: expect.objectContaining({ targetProjectId: 'project-2' }),
    }))
  })

  it('仅显示单一匿名占位，并为跨项目对端发出带项目上下文的打开事件', async () => {
    api.listWorkItemRelations.mockResolvedValue({
      ...relationPage([relation({ counterpart: { ...relation().counterpart, projectId: 'project-2' } })]),
      hasHiddenRelations: true,
    })
    const wrapper = mount(WorkItemRelations, {
      props: { workItemId: 'item-1', currentProjectId: 'project-1' },
      global: { stubs: { InlineProblem: true } },
    })
    await flushPromises()
    expect(wrapper.text().match(/存在关联项不可见/g)).toHaveLength(1)
    await wrapper.get('button.relation-link').trigger('click')
    expect(wrapper.emitted('openWorkItem')?.[0]).toEqual([
      { workItemId: 'item-2', projectId: 'project-2' },
    ])
  })
})
