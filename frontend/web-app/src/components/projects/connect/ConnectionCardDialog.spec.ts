import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElDialog, ElPopconfirm } from 'element-plus'
import { ResponseError } from '@yumpoo/api-client'
import ConnectionCardDialog from './ConnectionCardDialog.vue'
import { connectionTestContext } from './connectTestSupport'
import { connection } from './connectTestFixtures'

const push = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('../../../composables/useSession', () => ({ useSession: () => ({ authentication: { value: { company: { timezone: 'UTC' } } } }) }))
enableAutoUnmount(afterEach)
beforeEach(() => { document.body.innerHTML = ''; push.mockReset() })
describe('连接卡片', () => {
  it('立即展示对端并刷新，只读字段通过 dl 展示，打开完整工作项带目标 id', async () => {
    const { context, global } = connectionTestContext()
    const wrapper = mount(ConnectionCardDialog, { props: { open: true, connection, perspective: 'source' }, global: { ...global, stubs: { RouterLink: true } }, attachTo: document.body })
    await flushPromises()
    expect(wrapper.findComponent(ElDialog).props('width')).toBe('min(640px, calc(100vw - 32px))')
    expect(document.body.textContent).toContain('打印服务调用超时')
    expect(document.body.querySelector('.connection-card-fields')?.tagName).toBe('DL')
    expect(context.getConnection).toHaveBeenCalledWith(connection.id, expect.any(AbortSignal))
    await (wrapper.vm as unknown as { openWorkItem: () => Promise<void> }).openWorkItem()
    expect(push).toHaveBeenCalledWith({ path: '/projects/target-project/overview', query: { view: 'table', workItemId: 'target-item' } })
  })
  it('不可打开、不可解除、无处理人、无优先级和归档均有明确信息', async () => {
    const { context, global } = connectionTestContext()
    const archived = { ...connection, target: { ...connection.target, archived: true, assignee: null, priority: null, canOpen: false }, capabilities: { canUnlink: false } }
    vi.mocked(context.getConnection).mockResolvedValue(archived)
    const wrapper = mount(ConnectionCardDialog, { props: { open: true, connection: archived, perspective: 'source' }, global: { ...global, stubs: { RouterLink: true } }, attachTo: document.body })
    await flushPromises()
    const text = document.body.textContent ?? ''
    expect(text).toContain('已归档'); expect(text).toContain('未分配'); expect(text).toContain('—')
    expect(text).not.toContain('打开工作项'); expect(wrapper.findComponent(ElPopconfirm).exists()).toBe(false)
  })
  it('连接 404 时关闭卡片并通知表格刷新', async () => {
    const { context, global } = connectionTestContext()
    vi.mocked(context.getConnection).mockRejectedValueOnce(new ResponseError(new Response(JSON.stringify({ code: 'RESOURCE_NOT_FOUND', message: '不存在', requestId: 'test', retryable: false, fieldErrors: [], details: {} }), { status: 404 })))
    const wrapper = mount(ConnectionCardDialog, { props: { open: true, connection, perspective: 'target' }, global: { ...global, stubs: { RouterLink: true } }, attachTo: document.body })
    await flushPromises()
    expect(wrapper.emitted('update:open')).toEqual([[false]])
    expect(wrapper.emitted('invalidated')).toEqual([[connection]])
  })
})
