import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { ElDialog } from 'element-plus'
import ConnectColumnDeleteDialog from './ConnectColumnDeleteDialog.vue'
import { connectionTestContext } from './connectTestSupport'
import { connectColumn } from './connectTestFixtures'

enableAutoUnmount(afterEach)
describe('删除连接列', () => {
  it('480 确认说明仅解除连接，显式确认后执行删除命令', async () => {
    const { context, global } = connectionTestContext()
    const wrapper = mount(ConnectColumnDeleteDialog, { props: { column: connectColumn }, global, attachTo: document.body })
    await flushPromises()
    expect(wrapper.findComponent(ElDialog).props('width')).toBe('min(480px, calc(100vw - 32px))')
    expect(document.body.textContent).toContain('不会删除任何工作项')
    expect(context.deleteColumn).not.toHaveBeenCalled()
    await (wrapper.vm as unknown as { remove: () => Promise<void> }).remove()
    expect(context.deleteColumn).toHaveBeenCalledWith(connectColumn)
    expect(wrapper.emitted('deleted')).toHaveLength(1)
  })
})
