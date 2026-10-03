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

  it('保留缺失的已选列和来源，零计数项也能单独取消', async () => {
    const wrapper = mount(ConnectFilterSection, { props: { columns: [], incoming: [],
      connectedColumnIds: new Set(['gone-connected']), unconnectedColumnIds: new Set(['gone-unconnected']),
      incomingProjectIds: new Set(['gone-project']) } })
    expect(wrapper.text()).not.toContain('还没有可筛选的连接')
    expect(wrapper.emitted('change')).toBeUndefined()
    expect(wrapper.get('.connect-filters__incoming .el-checkbox small').text()).toBe('0')
    expect((wrapper.get('[aria-label="不可用连接列（gone-connected） 未连接"] input').element as HTMLInputElement).disabled).toBe(true)
    await wrapper.get('[aria-label="不可用连接列（gone-connected） 已连接"] input').setValue(false)
    await wrapper.get('[aria-label="不可用连接列（gone-unconnected） 未连接"] input').setValue(false)
    await wrapper.get('[aria-label="来自 未匹配来源项目（gone-project）"] input').setValue(false)
    expect(wrapper.emitted('change')).toEqual([
      ['connectedColumnIds', 'gone-connected', false], ['unconnectedColumnIds', 'gone-unconnected', false],
      ['incomingProjectIds', 'gone-project', false],
    ])
    wrapper.unmount()
  })

  it.each(['idle', 'loading', 'error'] as const)('%s 时不把缺失的已选项误判失效，加载恢复后仍保持选择', async phase => {
    const column = connectionCatalog.items[0]!
    const wrapper = mount(ConnectFilterSection, { props: { columns: [], incoming: [], columnsState: phase, incomingState: phase,
      connectedColumnIds: new Set([column.id]), unconnectedColumnIds: new Set<string>(), incomingProjectIds: new Set(['source']) } })
    expect(wrapper.text()).toContain('待确认连接列')
    expect(wrapper.text()).toContain('待确认来源项目')
    expect(wrapper.text()).not.toContain('不可用连接列')
    expect(wrapper.get('.connect-filters__incoming .el-checkbox small').text()).toBe('—')
    expect(wrapper.emitted('change')).toBeUndefined()
    await wrapper.setProps({ columnsState: 'ready', incomingState: 'ready', columns: [column], incoming: [{ value: 'source', label: '来源项目', count: 4 }] })
    expect((wrapper.get(`[aria-label="${column.name} 已连接"] input`).element as HTMLInputElement).checked).toBe(true)
    expect((wrapper.get('[aria-label="来自 来源项目"] input').element as HTMLInputElement).checked).toBe(true)
    wrapper.unmount()
  })

  it('来源按名称自然排序，同名按 ID 稳定排序且不改写接口数组', async () => {
    const incoming = [{ value: 'last', label: '项目10', count: 10 }, { value: 'z', label: '项目2', count: 2 }, { value: 'a', label: '项目2', count: 1 }]
    const wrapper = mount(ConnectFilterSection, { props: { columns: [], incoming,
      connectedColumnIds: new Set<string>(), unconnectedColumnIds: new Set<string>(), incomingProjectIds: new Set<string>() } })
    expect(wrapper.findAll('.connect-filters__incoming .el-checkbox small').map(row => row.text())).toEqual(['1', '2', '10'])
    await wrapper.findAll('.connect-filters__incoming input')[0]!.setValue(true)
    expect(wrapper.emitted('change')?.[0]).toEqual(['incomingProjectIds', 'a', true])
    await wrapper.setProps({ incoming: [...incoming].reverse() })
    expect(wrapper.findAll('.connect-filters__incoming .el-checkbox small').map(row => row.text())).toEqual(['1', '2', '10'])
    expect(incoming.map(row => row.value)).toEqual(['last', 'z', 'a'])
    wrapper.unmount()
  })

  it('选择达到二十项时仅禁用新增，仍允许取消已选项', async () => {
    const incoming = Array.from({ length: 21 }, (_, index) => ({ value: `source-${index + 1}`, label: `来源${index + 1}`, count: 1 }))
    const selected = new Set(incoming.slice(0, 20).map(row => row.value))
    const wrapper = mount(ConnectFilterSection, { props: { columns: [], incoming,
      connectedColumnIds: new Set<string>(), unconnectedColumnIds: new Set<string>(), incomingProjectIds: selected } })
    expect((wrapper.get('[aria-label="来自 来源21"] input').element as HTMLInputElement).disabled).toBe(true)
    expect((wrapper.get('[aria-label="来自 来源1"] input').element as HTMLInputElement).disabled).toBe(false)
    await wrapper.get('[aria-label="来自 来源1"] input').setValue(false)
    await wrapper.setProps({ incomingProjectIds: new Set([...selected].slice(1)) })
    expect((wrapper.get('[aria-label="来自 来源21"] input').element as HTMLInputElement).disabled).toBe(false)
    wrapper.unmount()
  })
})
