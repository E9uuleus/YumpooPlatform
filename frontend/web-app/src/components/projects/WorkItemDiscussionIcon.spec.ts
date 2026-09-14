import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WorkItemDiscussionIcon from './WorkItemDiscussionIcon.vue'

describe('表格讨论图标', () => {
  it('兼容旧响应，并随讨论从无到有再清空切换数量标记', async () => {
    const wrapper = mount(WorkItemDiscussionIcon)
    expect(wrapper.find('.work-item-discussion-icon__count').exists()).toBe(false)
    await wrapper.setProps({ count: 4 })
    expect(wrapper.classes()).toContain('work-item-discussion-icon--active')
    expect(wrapper.get('.work-item-discussion-icon__count').text()).toBe('4')
    await wrapper.setProps({ count: 100 })
    expect(wrapper.get('.work-item-discussion-icon__count').text()).toBe('99+')
    await wrapper.setProps({ count: 0 })
    expect(wrapper.classes()).not.toContain('work-item-discussion-icon--active')
    expect(wrapper.find('.work-item-discussion-icon__count').exists()).toBe(false)
    wrapper.unmount()
  })
})
