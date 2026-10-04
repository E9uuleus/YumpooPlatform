import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElPopover } from 'element-plus'
import { ProjectLifecycle } from '@yumpoo/api-client'
import ConnectCell from './ConnectCell.vue'
import ConnectItemPicker from './ConnectItemPicker.vue'
import { connectionTestContext } from './connectTestSupport'
import { connectColumn, connection, sourceItem } from './connectTestFixtures'

const reverse = { columnId: connectColumn.id, columnName: connectColumn.name, projectId: connection.source.projectId, projectCode: 'P012',
  projectName: connection.source.projectName, projectLifecycle: ProjectLifecycle.Active, actorCanLinkExisting: true }
enableAutoUnmount(afterEach)
let resized: () => void
beforeEach(() => {
  vi.stubGlobal('ResizeObserver', class { constructor(callback: () => void) { resized = callback } observe() {} disconnect() {} })
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) { return { width: this.classList.contains('connection-chip') ? 120 : 0 } as DOMRect })
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })
describe('连接单元格', () => {
  it('只读空单元格显示占位横线，没有可聚焦入口也不能打开弹窗', async () => {
    const { global } = connectionTestContext()
    const wrapper = mount(ConnectCell, { props: { item: sourceItem, column: connectColumn, connections: [], readOnly: true }, global })
    expect(wrapper.get('.connect-cell__box').classes()).toContain('work-item-accent-bar')
    expect(wrapper.get('.connect-cell__empty').text()).toBe('–')
    expect(wrapper.find('.connect-cell__open').exists()).toBe(false)
    expect(wrapper.find('.connect-cell__plus').exists()).toBe(false)
    expect(wrapper.getComponent(ElPopover).props('disabled')).toBe(true)
    await wrapper.setProps({ connections: [connection] })
    expect(wrapper.find('.connect-cell__empty').exists()).toBe(false)
    await wrapper.get('.connect-cell__open').trigger('click')
    expect(wrapper.getComponent(ElPopover).props('visible')).toBe(true)
    await wrapper.setProps({ connections: [] })
    expect(wrapper.getComponent(ElPopover).props('visible')).toBe(false)
  })
  it('随宽度显示可容纳的 chip 和 +N，chip 打开卡片，空白或计数打开选择弹层', async () => {
    const { global } = connectionTestContext()
    const connections = [connection, { ...connection, id: 'connection-2' }, { ...connection, id: 'connection-3' }]
    const wrapper = mount(ConnectCell, { props: { item: sourceItem, column: connectColumn, connections, readOnly: false }, global })
    Object.defineProperty(wrapper.get('.connect-cell__chips').element, 'clientWidth', { configurable: true, value: 200 })
    await flushPromises(); resized(); await flushPromises()
    expect(wrapper.findAll('.connect-cell__chips .connection-chip')).toHaveLength(1)
    expect(wrapper.get('.connect-cell__count').text()).toBe('+2')
    await wrapper.get('.connect-cell__chips .connection-chip').trigger('click')
    expect(wrapper.emitted('openCard')?.[0]).toEqual([connection]); expect(wrapper.getComponent(ElPopover).props('visible')).toBe(false)
    await wrapper.get('.connect-cell__count').trigger('click'); await flushPromises()
    expect(wrapper.getComponent(ElPopover).props()).toMatchObject({ visible: true, width: 400 })
    expect(wrapper.get('.connect-cell').classes()).toContain('connect-cell--open')
    expect(wrapper.getComponent(ConnectItemPicker).props()).toMatchObject({ column: connectColumn, total: 3, readOnly: false })
    await wrapper.get('.connect-cell').trigger('keydown', { key: 'Escape' }); await flushPromises()
    expect(wrapper.getComponent(ElPopover).props('visible')).toBe(false)
  })
  it('反向列显示来源卡片，只有两端成员才有新增入口', async () => {
    const { global } = connectionTestContext()
    const wrapper = mount(ConnectCell, { props: { item: sourceItem, reverse: { ...reverse, actorCanLinkExisting: false }, connections: [], total: 0, readOnly: false }, global })
    expect(wrapper.find('.connect-cell__plus').exists()).toBe(false)
    expect(wrapper.getComponent(ElPopover).props('disabled')).toBe(true)
    await wrapper.setProps({ reverse, connections: [connection], total: 1 })
    expect(wrapper.find('.connect-cell__plus').exists()).toBe(true)
    expect(wrapper.text()).toContain(connection.source.title)
    await wrapper.get('.connect-cell__open').trigger('click'); await flushPromises()
    expect(wrapper.getComponent(ConnectItemPicker).props()).toMatchObject({ reverse, column: undefined, connections: [connection] })
  })
  it('多目标列的 chip 始终带项目名', async () => {
    const { global } = connectionTestContext()
    const wrapper = mount(ConnectCell, { props: { item: sourceItem, column: { ...connectColumn, targets: [...connectColumn.targets, { ...connectColumn.targets[0]!, projectId: 'other' }] },
      connections: [connection], readOnly: true }, global })
    expect(wrapper.text()).toContain('Yumpoo 门户 · 打印服务调用超时')
  })
})
