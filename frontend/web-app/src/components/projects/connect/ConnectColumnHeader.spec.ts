import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { ElDropdown, ElDropdownItem } from 'element-plus'
import ConnectColumnHeader from './ConnectColumnHeader.vue'

enableAutoUnmount(afterEach)
describe('连接列表头', () => {
  it('成员只有设置和隐藏，负责人可删除，被连接列始终只有隐藏', async () => {
    const wrapper = mount(ConnectColumnHeader, { props: { label: '产品缺陷', kind: 'connect', canManage: true, canDelete: false } })
    expect(wrapper.findAllComponents(ElDropdownItem).map(item => item.props('command'))).toEqual(['edit', 'hide'])
    await wrapper.setProps({ canDelete: true })
    expect(wrapper.findAllComponents(ElDropdownItem).map(item => item.props('command'))).toEqual(['edit', 'hide', 'delete'])
    await wrapper.setProps({ kind: 'incoming' })
    expect(wrapper.findAllComponents(ElDropdownItem).map(item => item.props('command'))).toEqual(['hide'])
    wrapper.findComponent(ElDropdown).vm.$emit('command', 'hide')
    expect(wrapper.emitted('hide')).toHaveLength(1)
  })
  it('列宽按键盘调整并限制最小值，表头不提供拖拽排序', async () => {
    const wrapper = mount(ConnectColumnHeader, { props: { label: '产品缺陷', kind: 'connect', canManage: false, canDelete: false, width: 140, minWidth: 140, columnKey: 'connect:1' } })
    await wrapper.get('.connect-column-header__resize').trigger('keydown', { key: 'ArrowLeft' })
    expect(wrapper.emitted('resize')).toEqual([[140]])
    expect(wrapper.attributes('data-connect-key')).toBe('connect:1')
    expect(wrapper.find('[draggable="true"]').exists()).toBe(false)
  })
})
