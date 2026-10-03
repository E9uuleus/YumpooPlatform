import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElCheckbox, ElDialog, ElInput } from 'element-plus'
import { ResponseError } from '@yumpoo/api-client'
import ConnectColumnDialog from './ConnectColumnDialog.vue'
import { connectionTestContext } from './connectTestSupport'
import { connectColumn } from './connectTestFixtures'

const api = vi.hoisted(() => ({ searchConnectTargetProjects: vi.fn() }))
vi.mock('../../../api/client', () => ({ workItemsApi: api }))
enableAutoUnmount(afterEach)
beforeEach(() => { api.searchConnectTargetProjects.mockReset(); api.searchConnectTargetProjects.mockResolvedValue({ items: [{ id: 'source-project', code: 'P012', name: '本项目' }, ...Array.from({ length: 21 }, (_, i) => ({ id: `target-${i}`, code: `P${i}`, name: `项目 ${i}` }))], page: 0, totalPages: 1 }); document.body.innerHTML = '' })
function render(mode: 'create' | 'edit' = 'create') {
  const provided = connectionTestContext()
  const wrapper = mount(ConnectColumnDialog, { props: { open: true, projectId: 'source-project', mode, ...(mode === 'edit' ? { column: connectColumn } : {}), existingNames: ['连接项目', '连接项目 2', '产品缺陷'] }, global: provided.global, attachTo: document.body })
  return { wrapper, ...provided }
}
describe('添加与设置连接列', () => {
  it('点击项目行与复选框只切换一次，选中项目后可以提交', async () => {
    const { wrapper, context } = render(); await flushPromises()
    const row = document.querySelector<HTMLElement>('.connect-column-form__project')!
    row.click(); await flushPromises()
    expect(wrapper.findAllComponents(ElCheckbox)[0]!.props('modelValue')).toBe(true)
    row.querySelector<HTMLInputElement>('input')!.click(); await flushPromises()
    expect(wrapper.findAllComponents(ElCheckbox)[0]!.props('modelValue')).toBe(false)
    row.querySelector<HTMLInputElement>('input')!.click(); await flushPromises()
    await (wrapper.vm as unknown as { submit: () => Promise<void> }).submit()
    expect(context.createColumn).toHaveBeenCalledWith({ name: '连接项目 3', targetProjectIds: new Set(['target-0']) })
  })
  it('默认名递增，640 档位且排除当前项目，最多选择 20 个', async () => {
    const { wrapper } = render(); await flushPromises()
    expect(wrapper.findComponent(ElInput).props('modelValue')).toBe('连接项目 3')
    expect(wrapper.findComponent(ElDialog).props('width')).toBe('min(640px, calc(100vw - 32px))')
    const choices = wrapper.findAllComponents(ElCheckbox)
    expect(choices).toHaveLength(21)
    for (const choice of choices.slice(0, 20)) { choice.vm.$emit('change', true); await flushPromises() }
    expect(choices[20]!.props('disabled')).toBe(true)
    expect(document.body.textContent).toContain('最多选择 20 个项目')
  })
  it('名称不能与内置列或其他列重名，编辑交给携带 ETag 的命令', async () => {
    const { wrapper, context } = render('edit'); await flushPromises()
    const vm = wrapper.vm as unknown as { submit: () => Promise<void> }
    wrapper.findComponent(ElInput).vm.$emit('update:modelValue', '状态'); await flushPromises(); await vm.submit()
    expect(context.updateColumn).not.toHaveBeenCalled()
    wrapper.findComponent(ElInput).vm.$emit('update:modelValue', '产品缺陷'); await flushPromises(); await vm.submit()
    expect(context.updateColumn).toHaveBeenCalledWith(expect.objectContaining({ etag: '"1"' }), { name: '产品缺陷', targetProjectIds: new Set(['target-project']) })
  })
  it('保留在用目标的名字并显示服务端返回的连接数量', async () => {
    const { wrapper, context } = render('edit'); await flushPromises()
    vi.mocked(context.updateColumn).mockRejectedValueOnce(new ResponseError(new Response(JSON.stringify({ code: 'INVALID_STATE_TRANSITION', message: '目标项目仍有连接', requestId: 'test', retryable: false, fieldErrors: [], details: { reason: 'CONNECT_TARGET_IN_USE', targetProjectId: 'target-project', activeConnectionCount: 7 } }), { status: 409 })))
    await (wrapper.vm as unknown as { submit: () => Promise<void> }).submit(); await flushPromises()
    expect(document.body.textContent).toContain('「Yumpoo 门户」中还有 7 个连接，请先解除后再移除该项目。')
    expect(wrapper.emitted('saved')).toBeUndefined()
  })
})
