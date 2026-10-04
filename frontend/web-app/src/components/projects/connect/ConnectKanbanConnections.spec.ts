import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { ElPopover } from 'element-plus'
import type { WorkItemConnection, WorkItemConnectionCell } from '@yumpoo/api-client'
import ConnectKanbanConnections from './ConnectKanbanConnections.vue'
import ConnectionChip from './ConnectionChip.vue'
import ConnectionList from './ConnectionList.vue'
import { connection } from './connectTestFixtures'
import { connectionTestContext } from './connectTestSupport'

enableAutoUnmount(afterEach)
function cell(outgoing: WorkItemConnection[], reverse: WorkItemConnection[]): WorkItemConnectionCell {
  return { workItemId: 'item', outgoing: outgoing.length ? [{ columnId: connection.columnId, connections: outgoing }] : [], incoming: [],
    incomingTotal: 0, incomingByColumn: reverse.length ? [{ columnId: 'reverse-column', connections: reverse, total: reverse.length }] : [] }
}
describe('看板连接', () => {
  it('出站与双向连接合计最多三个 chip，完整列表保持只读，选择打开对端卡片', async () => {
    const { context, global } = connectionTestContext()
    const connections = Array.from({ length: 3 }, (_, index) => ({ ...connection, id: `connection-${index}` }))
    const reverse = Array.from({ length: 2 }, (_, index) => ({ ...connection, id: `reverse-${index}`, columnId: 'reverse-column' }))
    const wrapper = mount(ConnectKanbanConnections, { props: { cell: cell(connections, reverse) }, global })
    expect(wrapper.findAllComponents(ConnectionChip)).toHaveLength(3)
    expect(wrapper.get('.kanban-connections__more').text()).toBe('+2')
    await wrapper.findAllComponents(ConnectionChip)[1]!.get('button').trigger('click')
    expect(wrapper.emitted('openCard')).toEqual([[connections[1], 'source']])
    wrapper.getComponent(ElPopover).vm.$emit('update:visible', true); await flushPromises()
    const lists = wrapper.findAllComponents(ConnectionList)
    expect(lists.map(list => list.props('perspective'))).toEqual(['source', 'target'])
    expect(lists.every(list => list.props('readOnly'))).toBe(true)
    expect(wrapper.find('.connection-list__unlink').exists()).toBe(false)
    lists[1]!.vm.$emit('openCard', reverse[1]); await flushPromises()
    expect(wrapper.emitted('openCard')?.[1]).toEqual([reverse[1], 'target'])
    expect(wrapper.getComponent(ElPopover).props('visible')).toBe(false)
    expect(context.unlink).not.toHaveBeenCalled()
  })
  it('无连接时不渲染入口，归档卡片仍复用状态表现', async () => {
    const wrapper = mount(ConnectKanbanConnections, { props: { cell: cell([], []) } })
    expect(wrapper.find('.kanban-connections').exists()).toBe(false)
    await wrapper.setProps({ cell: cell([{ ...connection, target: { ...connection.target, archived: true } }], []) })
    expect(wrapper.getComponent(ConnectionChip).props('card').archived).toBe(true)
    expect(wrapper.find('.kanban-connections__more').exists()).toBe(false)
  })
})
