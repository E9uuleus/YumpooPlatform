import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { ElPopconfirm } from 'element-plus'
import ConnectionList from './ConnectionList.vue'
import { connectionTestContext } from './connectTestSupport'
import { connection } from './connectTestFixtures'

enableAutoUnmount(afterEach)
describe('已连接列表', () => {
  it('按观察端展示对端；只有确认后才解除', async () => {
    const { context, global } = connectionTestContext()
    const wrapper = mount(ConnectionList, { props: { connections: [connection], perspective: 'source', readOnly: false }, global })
    expect(wrapper.text()).toContain(connection.target.title)
    await wrapper.get('.connection-list__unlink').trigger('click')
    expect(context.unlink).not.toHaveBeenCalled()
    wrapper.findComponent(ElPopconfirm).vm.$emit('confirm'); await flushPromises()
    expect(context.unlink).toHaveBeenCalledWith(connection); expect(wrapper.emitted('changed')).toHaveLength(1)
    await wrapper.setProps({ perspective: 'target', readOnly: true })
    expect(wrapper.text()).toContain(connection.source.title)
    expect(wrapper.findComponent(ElPopconfirm).exists()).toBe(false)
  })
  it('服务端 canUnlink=false 时不渲染解除入口', () => {
    const { global } = connectionTestContext()
    const wrapper = mount(ConnectionList, { props: { connections: [{ ...connection, capabilities: { canUnlink: false } }], perspective: 'source', readOnly: false }, global })
    expect(wrapper.find('.connection-list__unlink').exists()).toBe(false)
  })
})
