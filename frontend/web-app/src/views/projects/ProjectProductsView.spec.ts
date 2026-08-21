import {
  ProductStatus,
  ProjectActorAccess,
  ProjectLifecycle,
  ProjectProductLinkStatus,
  ProjectProductRelationType,
  type ProjectDetail,
  type ProjectProductCandidate,
  type ProjectProductLink,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectProductsView from './ProjectProductsView.vue'

const api = vi.hoisted(() => ({
  getProject: vi.fn(),
  listProjectProducts: vi.fn(),
  listProjectProductCandidates: vi.fn(),
  createProjectProductLink: vi.fn(),
  updateProjectProductLink: vi.fn(),
  removeProjectProductLink: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { projectId: 'project-1' } }),
  useRouter: () => ({ push: vi.fn() }),
}))
vi.mock('../../api/client', () => ({ projectsApi: api }))
vi.mock('@yumpoo/api-client', async (importOriginal) => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))

const project = {
  id: 'project-1', name: '项目一', description: null, code: 'PROJECT_1',
  workspaceId: 'workspace-1', workspaceCode: 'WORKSPACE', workspaceName: '工作区',
  projectType: 'PRODUCT_DEVELOPMENT', lifecycle: ProjectLifecycle.Active,
  ownerUserId: 'owner-1', ownerDisplayName: '负责人', actorAccess: ProjectActorAccess.Owner,
  capabilities: {
    canUpdateSettings: true, canActivate: false, canManageMembers: true,
    canReassignOwner: false, canManageProductLinks: true,
  },
  etag: '"0"', rowVersion: 0,
} as ProjectDetail

const primary = relation('link-primary', 'product-primary', true, '"0"')
const secondary = relation('link-secondary', 'product-secondary', false, '"2"')
const candidate: ProjectProductCandidate = {
  id: 'product-candidate', code: 'CANDIDATE', name: '候选 Product',
  activeRelationTypes: new Set([ProjectProductRelationType.Support]), primary: false,
}

function relation(id: string, productId: string, isPrimary: boolean, etag: string): ProjectProductLink {
  return {
    id, projectId: 'project-1', productId, productCode: productId.toUpperCase(),
    productName: productId, productStatus: ProductStatus.Active,
    relationType: ProjectProductRelationType.Development, isPrimary,
    status: ProjectProductLinkStatus.Active, linkedAt: new Date(), linkedByUserId: 'owner-1',
    updatedAt: new Date(), updatedByUserId: 'owner-1', removedAt: null,
    removedByUserId: null, removeReason: null, rowVersion: Number(etag.replace(/\D/g, '')), etag,
  }
}

function mountView() {
  return mount(ProjectProductsView, { attachTo: document.body })
}

describe('M2-07 Project Product 关联页', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    Object.values(api).forEach(mock => mock.mockReset())
    api.getProject.mockResolvedValue(project)
    api.listProjectProducts.mockResolvedValue({ items: [primary, secondary] })
    api.listProjectProductCandidates.mockResolvedValue({
      items: [candidate], page: 0, size: 20, totalElements: 1, totalPages: 1,
    })
    api.updateProjectProductLink.mockResolvedValue(secondary)
    api.createProjectProductLink.mockResolvedValue(secondary)
  })

  it('搜索候选并禁用已有的相同关系类型', async () => {
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      query: string
      selectedProductId: string
      relationType: ProjectProductRelationType
      searchCandidates: () => Promise<void>
      duplicateSelectedType: () => boolean
    }
    vm.query = '候选'
    await vm.searchCandidates()
    expect(api.listProjectProductCandidates).toHaveBeenCalledWith({
      projectId: 'project-1', query: '候选', page: 0, size: 20,
    })
    vm.selectedProductId = candidate.id
    vm.relationType = ProjectProductRelationType.Support
    expect(vm.duplicateSelectedType()).toBe(true)
    wrapper.unmount()
  })

  it('切换主 Product 严格执行取消旧主再设置新主', async () => {
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as unknown as { makePrimary: (link: ProjectProductLink) => Promise<void> }
    await vm.makePrimary(secondary)
    expect(api.updateProjectProductLink).toHaveBeenNthCalledWith(1, {
      projectId: 'project-1', linkId: primary.id, xXSRFTOKEN: 'csrf-token', ifMatch: primary.etag,
      projectProductLinkUpdateRequest: { isPrimary: false },
    })
    expect(api.updateProjectProductLink).toHaveBeenNthCalledWith(2, {
      projectId: 'project-1', linkId: secondary.id, xXSRFTOKEN: 'csrf-token', ifMatch: secondary.etag,
      projectProductLinkUpdateRequest: { isPrimary: true },
    })
    wrapper.unmount()
  })

  it('能力为只读时隐藏建立关系与操作列', async () => {
    api.getProject.mockResolvedValue({
      ...project,
      actorAccess: ProjectActorAccess.Member,
      capabilities: { ...project.capabilities, canManageProductLinks: false },
    })
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('当前角色拥有只读权限')
    expect(wrapper.text()).not.toContain('建立关系')
    expect(wrapper.text()).not.toContain('设为主')
    wrapper.unmount()
  })
})
