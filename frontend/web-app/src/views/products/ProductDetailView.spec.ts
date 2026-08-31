import {
  ErrorCode, ProductStatus, ResponseError, type Product,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ProductDetailView from './ProductDetailView.vue'

const push = vi.hoisted(() => vi.fn())
const api = vi.hoisted(() => ({
  getProduct: vi.fn(), updateProduct: vi.fn(), archiveProduct: vi.fn(),
  restoreProduct: vi.fn(), createGovernanceOverride: vi.fn(),
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { productId: 'product-1' } }),
  useRouter: () => ({ push }),
}))
vi.mock('../../api/client', () => ({
  productsApi: {
    getProduct: api.getProduct, updateProduct: api.updateProduct,
    archiveProduct: api.archiveProduct, restoreProduct: api.restoreProduct,
  },
  administrationApi: { createGovernanceOverride: api.createGovernanceOverride },
}))
vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(), readCsrfToken: () => 'csrf-token',
}))

const active = (): Product => ({
  id: 'product-1', code: 'YP', name: 'Yumpoo', description: '原描述',
  status: ProductStatus.Active, ownerUserId: 'owner-1', ownerDisplayName: '负责人甲',
  rowVersion: 3, etag: '"3"', capabilities: {
    canUpdate: true, canArchive: true, canRestore: false,
    canOverrideArchive: true, canReassignOwner: true,
  },
})

function failure(status: number, reason: string, blockers: Array<{ code: string; count: number }> = []) {
  return new ResponseError(new Response(JSON.stringify({
    code: status === 412 ? ErrorCode.VersionConflict : ErrorCode.InvalidStateTransition,
    message: '请求失败', requestId: 'request-1', retryable: false, fieldErrors: [],
    details: { reason, blockers },
  }), { status, headers: { 'Content-Type': 'application/json' } }))
}

describe('产品详情治理', () => {
  beforeEach(() => {
    Object.values(api).forEach(mock => mock.mockReset())
    api.getProduct.mockResolvedValue(active())
    api.updateProduct.mockResolvedValue(active())
    api.archiveProduct.mockResolvedValue(active())
    api.restoreProduct.mockResolvedValue(active())
    api.createGovernanceOverride.mockResolvedValue({})
  })

  it('409 显示分类计数并使用产品专用治理覆盖动作', async () => {
    api.archiveProduct.mockRejectedValueOnce(failure(409, 'PRODUCT_ARCHIVE_BLOCKED', [
      { code: 'ACTIVE_DEVELOPMENT_SUPPORT_PROJECTS', count: 2 },
    ]))
    const wrapper = mount(ProductDetailView)
    await flushPromises()
    const vm = wrapper.vm as unknown as { archive: () => Promise<void>; overrideReason: string; overrideArchive: () => Promise<void> }
    await vm.archive()
    await flushPromises()
    expect(wrapper.text()).toContain('ACTIVE_DEVELOPMENT_SUPPORT_PROJECTS：2')
    vm.overrideReason = '确认项目事实保留并执行阶段归档覆盖'
    await vm.overrideArchive()
    expect(api.createGovernanceOverride).toHaveBeenCalledWith(expect.objectContaining({
      ifMatch: '"3"', idempotencyKey: expect.any(String),
      governanceOverrideRequest: expect.objectContaining({
        action: 'PRODUCT_ARCHIVE_WITH_BLOCKERS', targetType: 'PRODUCT', targetId: 'product-1',
      }),
    }))
    expect(api.getProduct).toHaveBeenCalledTimes(2)
  })

  it('412 保留编辑输入、加载最新详情且不自动重提', async () => {
    api.updateProduct.mockRejectedValueOnce(failure(412, 'VERSION_CONFLICT'))
    const wrapper = mount(ProductDetailView)
    await flushPromises()
    const vm = wrapper.vm as unknown as { draft: { name: string; description: string }; save: () => Promise<void> }
    vm.draft.name = '用户未提交的新名称'
    await vm.save()
    await flushPromises()
    expect(vm.draft.name).toBe('用户未提交的新名称')
    expect(wrapper.text()).toContain('你的输入已保留')
    expect(api.updateProduct).toHaveBeenCalledTimes(1)
    expect(api.getProduct).toHaveBeenCalledTimes(2)
  })
})
