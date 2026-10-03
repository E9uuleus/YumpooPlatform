import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { ElPopover } from 'element-plus'
import ConnectColumnAddButton from './ConnectColumnAddButton.vue'
import ConnectColumnAddPopover from './ConnectColumnAddPopover.vue'

enableAutoUnmount(afterEach)
describe('添加列按钮', () => {
  it('打开 440 列中心，选择类型后关闭并向表格发出事件', async () => {
    const wrapper = mount(ConnectColumnAddButton, { props: { canManage: true, hiddenColumns: [] }, attachTo: document.body })
    expect(wrapper.findComponent(ElPopover).props()).toMatchObject({ width: 440, placement: 'bottom-end' })
    wrapper.findComponent(ElPopover).vm.$emit('update:visible', true); await flushPromises()
    wrapper.findComponent(ConnectColumnAddPopover).vm.$emit('addConnectColumn'); await flushPromises()
    expect(wrapper.emitted('addConnectColumn')).toHaveLength(1)
    expect(wrapper.findComponent(ElPopover).props('visible')).toBe(false)
  })
})
