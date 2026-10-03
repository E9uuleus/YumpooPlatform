import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ConnectFilterSection from './ConnectFilterSection.vue'
import { connectionCatalog } from './connectTestFixtures'

describe('连接筛选', () => {
  it('展示列状态与服务端入站计数，并发出独立筛选变更', async () => {
    const column = connectionCatalog.items[0]!
    const wrapper = mount(ConnectFilterSection, { props: {
      columns: [column], incoming: [{ value: 'source', label: '来源项目', count: 23 }],
      connectedColumnIds: new Set([column.id]), unconnectedColumnIds: new Set<string>(), incomingProjectIds: new Set<string>(),
    } })
    expect((wrapper.get(`[aria-label="${column.name} 已连接"] input`).element as HTMLInputElement).checked).toBe(true)
    await wrapper.get(`[aria-label="${column.name} 未连接"] input`).setValue(true)
    await wrapper.get('[aria-label="来自 来源项目"] input').setValue(true)
    expect(wrapper.emitted('change')).toEqual([['unconnectedColumnIds', column.id, true], ['incomingProjectIds', 'source', true]])
    expect(wrapper.text()).toContain('23')
    wrapper.unmount()
  })
  it('无连接时显示空态且不提供排序或分组入口', () => {
    const wrapper = mount(ConnectFilterSection, { props: { columns: [], incoming: [],
      connectedColumnIds: new Set<string>(), unconnectedColumnIds: new Set<string>(), incomingProjectIds: new Set<string>() } })
    expect(wrapper.text()).toContain('还没有可筛选的连接')
    expect(wrapper.find('input').exists()).toBe(false)
    wrapper.unmount()
  })
})
