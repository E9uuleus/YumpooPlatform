import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { ElPopover } from 'element-plus'
import ConnectKanbanConnections from './ConnectKanbanConnections.vue'
import ConnectionChip from './ConnectionChip.vue'
import ConnectionList from './ConnectionList.vue'
import { connection } from './connectTestFixtures'
import { connectionTestContext } from './connectTestSupport'

enableAutoUnmount(afterEach)
describe('看板连接', () => {
  it('最多三个 chip 和溢出计数，完整列表保持只读，选择打开对端卡片', async () => {
    const { context, global } = connectionTestContext()
    const connections = Array.from({ length: 5 }, (_, index) => ({ ...connection, id: `connection-${index}` }))
    const wrapper = mount(ConnectKanbanConnections, { props: { connections }, global })
    expect(wrapper.findAllComponents(ConnectionChip)).toHaveLength(3)
    expect(wrapper.get('.kanban-connections__more').text()).toBe('+2')
    await wrapper.findAllComponents(ConnectionChip)[1]!.get('button').trigger('click')
    expect(wrapper.emitted('openCard')).toEqual([[connections[1]]])
    wrapper.getComponent(ElPopover).vm.$emit('update:visible', true); await flushPromises()
    const list = wrapper.getComponent(ConnectionList)
    expect(list.props('readOnly')).toBe(true)
    expect(list.find('.connection-list__unlink').exists()).toBe(false)
    list.vm.$emit('openCard', connections[4]); await flushPromises()
    expect(wrapper.emitted('openCard')?.[1]).toEqual([connections[4]])
    expect(wrapper.getComponent(ElPopover).props('visible')).toBe(false)
    expect(context.unlink).not.toHaveBeenCalled()
  })
  it('无连接时不渲染入口，归档卡片仍复用状态表现', async () => {
    const wrapper = mount(ConnectKanbanConnections, { props: { connections: [] } })
    expect(wrapper.find('.kanban-connections').exists()).toBe(false)
    await wrapper.setProps({ connections: [{ ...connection, target: { ...connection.target, archived: true } }] })
    expect(wrapper.getComponent(ConnectionChip).props('card').archived).toBe(true)
    expect(wrapper.find('.kanban-connections__more').exists()).toBe(false)
  })
})
