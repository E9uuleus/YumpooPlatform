import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ElButton } from 'element-plus'
import { ProjectLifecycle } from '@yumpoo/api-client'
import ReverseConnectCell from './ReverseConnectCell.vue'
import ConnectCell from './ConnectCell.vue'
import ConnectionList from './ConnectionList.vue'
import { connectionTestContext } from './connectTestSupport'
import { connection, sourceItem } from './connectTestFixtures'

const reverse = { columnId: connection.columnId, columnName: connection.columnName, projectId: connection.source.projectId,
  projectCode: connection.source.projectCode, projectName: connection.source.projectName, projectLifecycle: ProjectLifecycle.Active, actorCanLinkExisting: true }
enableAutoUnmount(afterEach)
function mountReverse(connections = [connection], total = 3) {
  const { context, global } = connectionTestContext()
  return { context, wrapper: mount(ReverseConnectCell, { props: { item: sourceItem, reverse, connections, total, readOnly: true },
    global: { ...global, stubs: { ConnectCell: { props: ['connections', 'incomingTotal'], template: '<div><slot name="popover" :open-card="connection => $emit(\'openCard\', connection)" /></div>' } } } }) }
}

describe('双向连接反向列单元格', () => {
  it('按来源列分页加载并去重，只显示服务端允许的卡片', async () => {
    const { wrapper, context } = mountReverse()
    const other = { ...connection, id: 'other' }
    vi.mocked(context.incoming).mockResolvedValue({ items: [connection, other], page: 0, size: 50, totalElements: 2, totalPages: 1 })
    expect(wrapper.text()).toContain('华东现场实施 · 产品缺陷')
    await wrapper.getComponent(ElButton).trigger('click'); await flushPromises()
    expect(context.incoming).toHaveBeenCalledWith(sourceItem.id, 0, expect.any(AbortSignal), reverse.columnId)
    expect(wrapper.getComponent(ConnectCell).props('connections')).toHaveLength(2)
    expect(wrapper.getComponent(ConnectionList).props()).toMatchObject({ perspective: 'target', readOnly: true })
    wrapper.findComponent(ConnectionList).vm.$emit('openCard', connection)
    expect(wrapper.emitted('openCard')?.[0]).toEqual([connection])
    expect(wrapper.text()).not.toContain('加载更多')
  })
  it('单元格已给出首批 50 条时从第二页加载，行切换中止并丢弃过期响应', async () => {
    const items = Array.from({ length: 50 }, (_, index) => ({ ...connection, id: `connection-${index}` }))
    const { wrapper, context } = mountReverse(items, 51)
    let resolve!: (page: Awaited<ReturnType<typeof context.incoming>>) => void
    vi.mocked(context.incoming).mockImplementation(() => new Promise(done => { resolve = done }))
    await wrapper.getComponent(ElButton).trigger('click')
    expect(context.incoming).toHaveBeenCalledWith(sourceItem.id, 1, expect.any(AbortSignal), reverse.columnId)
    const signal = vi.mocked(context.incoming).mock.calls[0]![2]!
    await wrapper.setProps({ item: { ...sourceItem, id: 'new-item' }, connections: [], total: 0 })
    expect(signal.aborted).toBe(true)
    resolve({ items: [connection], page: 1, size: 50, totalElements: 51, totalPages: 2 }); await flushPromises()
    expect(wrapper.getComponent(ConnectCell).props('connections')).toEqual([])
    expect(wrapper.text()).toContain('还没有连接')
  })
})
