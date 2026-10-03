import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ElButton } from 'element-plus'
import IncomingConnectCell from './IncomingConnectCell.vue'
import ConnectCell from './ConnectCell.vue'
import ConnectionList from './ConnectionList.vue'
import { connectionTestContext } from './connectTestSupport'
import { connection, sourceItem } from './connectTestFixtures'

enableAutoUnmount(afterEach)
function mountIncoming(incoming = [connection], total = 3) {
  const { context, global } = connectionTestContext()
  return { context, wrapper: mount(IncomingConnectCell, { props: { item: sourceItem, incoming, incomingTotal: total, readOnly: true },
    global: { ...global, stubs: { ConnectCell: { props: ['connections', 'incomingTotal'], template: '<div><slot name="popover" :open-card="connection => $emit(\'openCard\', connection)" /></div>' } } } }) }
}

describe('被连接列', () => {
  it('按来源项目和列分组，分页去重，并只显示服务端允许的只读卡片', async () => {
    const { wrapper, context } = mountIncoming()
    const other = { ...connection, id: 'other', columnId: 'other-column', columnName: '实施问题', source: { ...connection.source, projectId: 'other-project', projectName: '华南现场' } }
    vi.mocked(context.incoming).mockResolvedValue({ items: [connection, other], page: 0, size: 50, totalElements: 2, totalPages: 1 })
    expect(wrapper.get('h3').text()).toBe('华东现场实施 · 产品缺陷')
    await wrapper.getComponent(ElButton).trigger('click'); await flushPromises()
    expect(context.incoming).toHaveBeenCalledWith(sourceItem.id, 0, expect.any(AbortSignal))
    expect(wrapper.findAll('h3').map(node => node.text())).toEqual(['华东现场实施 · 产品缺陷', '华南现场 · 实施问题'])
    expect(wrapper.getComponent(ConnectCell).props('connections')).toHaveLength(2)
    expect(wrapper.findAllComponents(ConnectionList).every(list => list.props('readOnly'))).toBe(true)
    wrapper.findComponent(ConnectionList).vm.$emit('openCard', connection)
    expect(wrapper.emitted('openCard')?.[0]).toEqual([connection])
    expect(wrapper.text()).not.toContain('加载更多')
  })
  it('A6 已给出第一页时从第二页加载，行切换中止并丢弃过期响应', async () => {
    const items = Array.from({ length: 50 }, (_, index) => ({ ...connection, id: `connection-${index}` }))
    const { wrapper, context } = mountIncoming(items, 51)
    let resolve!: (page: Awaited<ReturnType<typeof context.incoming>>) => void
    vi.mocked(context.incoming).mockImplementation(() => new Promise(done => { resolve = done }))
    await wrapper.getComponent(ElButton).trigger('click')
    expect(context.incoming).toHaveBeenCalledWith(sourceItem.id, 1, expect.any(AbortSignal))
    const signal = vi.mocked(context.incoming).mock.calls[0]![2]!
    await wrapper.setProps({ item: { ...sourceItem, id: 'new-item' }, incoming: [], incomingTotal: 0 })
    expect(signal.aborted).toBe(true)
    resolve({ items: [connection], page: 1, size: 50, totalElements: 51, totalPages: 2 }); await flushPromises()
    expect(wrapper.getComponent(ConnectCell).props('connections')).toEqual([])
    expect(wrapper.text()).toContain('还没有连接')
  })
})
