import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ElDialog, ElInput } from 'element-plus'
import { ResponseError } from '@yumpoo/api-client'
import ConnectCreateDialog from './ConnectCreateDialog.vue'
import { connectionTestContext } from './connectTestSupport'
import { connectColumn, sourceItem } from './connectTestFixtures'

enableAutoUnmount(afterEach)
function render() {
  const provided = connectionTestContext()
  const wrapper = mount(ConnectCreateDialog, { props: { open: true, column: connectColumn, targetProjectId: 'target-project', sourceItem, initialTitle: '搜索里的标题' }, global: provided.global, attachTo: document.body })
  return { wrapper, ...provided }
}
describe('新建并关联', () => {
  it('480 档位、预填标题和默认类别，创建只提交设计允许的字段', async () => {
    const { wrapper, context } = render(); await flushPromises()
    expect(wrapper.findComponent(ElDialog).props('width')).toBe('min(480px, calc(100vw - 32px))')
    expect(wrapper.findComponent(ElInput).props('modelValue')).toBe('搜索里的标题')
    await (wrapper.vm as unknown as { submit: () => Promise<void> }).submit()
    expect(context.createAndLink).toHaveBeenCalledWith(sourceItem.id, { columnId: connectColumn.id, targetProjectId: 'target-project', title: '搜索里的标题', contentId: 'bug' })
    expect(wrapper.emitted('created')).toHaveLength(1)
  })
  it.each([['PROJECT_ARCHIVED', '目标项目已归档'], ['CONNECTION_LIMIT', '每个工作项在一列中最多连接 50 个工作项']])('映射 %s 并保留输入', async (reason, text) => {
    const { wrapper, context } = render(); await flushPromises()
    vi.mocked(context.createAndLink).mockRejectedValueOnce(new ResponseError(new Response(JSON.stringify({ code: 'INVALID_STATE_TRANSITION', message: '冲突', requestId: 'test', retryable: false, fieldErrors: [], details: { reason } }), { status: 409 })))
    await (wrapper.vm as unknown as { submit: () => Promise<void> }).submit(); await flushPromises()
    expect(document.body.textContent).toContain(text)
    expect(wrapper.findComponent(ElInput).props('modelValue')).toBe('搜索里的标题')
  })
  it('标题为空或超过 300 字时禁止提交', async () => {
    const { wrapper, context } = render(); await flushPromises()
    for (const title of ['   ', '字'.repeat(301)]) {
      wrapper.findComponent(ElInput).vm.$emit('update:modelValue', title); await flushPromises()
      await (wrapper.vm as unknown as { submit: () => Promise<void> }).submit()
    }
    expect(context.createAndLink).not.toHaveBeenCalled()
  })
})
