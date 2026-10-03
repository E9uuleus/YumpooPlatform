import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElPopover } from 'element-plus'
import ConnectCell from './ConnectCell.vue'
import { connectionTestContext } from './connectTestSupport'
import { connectColumn, connection, sourceItem } from './connectTestFixtures'

enableAutoUnmount(afterEach)
let resized: () => void
beforeEach(() => {
  vi.stubGlobal('ResizeObserver', class { constructor(callback: () => void) { resized = callback } observe() {} disconnect() {} })
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) { return { width: this.classList.contains('connection-chip') ? 120 : 0 } as DOMRect })
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })
describe('连接单元格', () => {
  it('随宽度显示可容纳的 chip 和 +N，并且只在点击空白或计数时打开列表', async () => {
    const { global } = connectionTestContext()
    const connections = [connection, { ...connection, id: 'connection-2' }, { ...connection, id: 'connection-3' }]
    const wrapper = mount(ConnectCell, { props: { item: sourceItem, column: connectColumn, connections, readOnly: false }, global })
    Object.defineProperty(wrapper.get('.connect-cell').element, 'clientWidth', { configurable: true, value: 200 })
    await flushPromises(); resized(); await flushPromises()
    expect(wrapper.findAll('.connect-cell__chips .connection-chip')).toHaveLength(1)
    expect(wrapper.get('.connect-cell__count').text()).toBe('+2')
    await wrapper.get('.connect-cell__chips .connection-chip').trigger('click')
    expect(wrapper.emitted('openCard')?.[0]).toEqual([connection]); expect(wrapper.findComponent(ElPopover).props('visible')).toBe(false)
    await wrapper.get('.connect-cell__count').trigger('click'); await flushPromises()
    expect(wrapper.findComponent(ElPopover).props()).toMatchObject({ visible: true, width: 380 })
    await wrapper.get('.connect-cell').trigger('keydown', { key: 'Escape' }); await flushPromises()
    expect(wrapper.findComponent(ElPopover).props('visible')).toBe(false)
  })
  it('空白只读单元格没有加号，多目标 chip 始终带项目名', async () => {
    const { global } = connectionTestContext()
    const wrapper = mount(ConnectCell, { props: { item: sourceItem, column: connectColumn, connections: [], readOnly: true }, global })
    expect(wrapper.find('.connect-cell__plus').exists()).toBe(false)
    await wrapper.setProps({ connections: [connection], column: { ...connectColumn, targets: [...connectColumn.targets, { ...connectColumn.targets[0]!, projectId: 'other' }] } })
    expect(wrapper.text()).toContain('Yumpoo 门户 · 打印服务调用超时')
  })
})
