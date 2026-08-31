import {
  AuthenticationRole, ProductStatus, ProductStatusFilter, type CurrentAuthentication,
  type Product, type ProductPage,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useSession } from '../../composables/useSession'
import ProductsView from './ProductsView.vue'

const push = vi.hoisted(() => vi.fn())
const api = vi.hoisted(() => ({ listProducts: vi.fn(), createProduct: vi.fn(), listMembers: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('../../api/client', () => ({
  productsApi: { listProducts: api.listProducts, createProduct: api.createProduct },
  identityAdministrationApi: { listMembers: api.listMembers },
}))
vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(), readCsrfToken: () => 'csrf-token',
}))

const product: Product = {
  id: 'product-1', code: 'YP', name: 'Yumpoo', description: null,
  status: ProductStatus.Active, ownerUserId: 'owner-1', ownerDisplayName: '负责人甲',
  rowVersion: 0, etag: '"0"', capabilities: {
    canUpdate: true, canArchive: true, canRestore: false,
    canOverrideArchive: true, canReassignOwner: true,
  },
}
const page: ProductPage = { items: [product], page: 0, size: 20, totalElements: 1, totalPages: 1 }

describe('产品列表页', () => {
  beforeEach(() => {
    push.mockReset()
    Object.values(api).forEach(mock => mock.mockReset())
    api.listProducts.mockResolvedValue(page)
    api.listMembers.mockResolvedValue({ items: [{ userId: 'owner-1', displayName: '负责人甲' }] })
    api.createProduct.mockResolvedValue(product)
    useSession().authentication.value = {
      roles: new Set([AuthenticationRole.CompanyAdmin]),
    } as CurrentAuthentication
  })

  it('支持状态、前缀搜索、负责人展示和详情导航', async () => {
    const wrapper = mount(ProductsView)
    await flushPromises()
    expect(api.listProducts).toHaveBeenCalledWith({ status: ProductStatusFilter.Active, page: 0, size: 20 })
    expect(wrapper.text()).toContain('Yumpoo')
    expect(wrapper.text()).toContain('负责人甲')
    await wrapper.get('.product-link').trigger('click')
    expect(push).toHaveBeenCalledWith({ name: 'product-detail', params: { productId: 'product-1' } })
  })

  it('CompanyAdmin 使用独立幂等键创建产品并重新加载', async () => {
    const wrapper = mount(ProductsView)
    await flushPromises()
    const vm = wrapper.vm as unknown as { form: Record<string, string>; createProduct: () => Promise<void> }
    Object.assign(vm.form, { code: ' yp ', name: ' 新产品 ', description: ' 描述 ', ownerUserId: 'owner-1' })
    await vm.createProduct()
    expect(api.createProduct).toHaveBeenCalledWith(expect.objectContaining({
      xXSRFTOKEN: 'csrf-token', idempotencyKey: expect.any(String),
      productCreateRequest: { code: 'YP', name: '新产品', description: '描述', ownerUserId: 'owner-1' },
    }))
    expect(api.listProducts).toHaveBeenCalledTimes(2)
  })
})
