import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import ConnectColumnAddPopover from './ConnectColumnAddPopover.vue'

enableAutoUnmount(afterEach)
describe('列中心', () => {
  it('本地按名称与说明过滤，隐藏列按 key 恢复', async () => {
    const wrapper = mount(ConnectColumnAddPopover, { props: { canManage: true, hiddenColumns: [{ key: 'connect:a', label: '产品缺陷' }, { key: 'dueDate', label: '截止日期' }] } })
    await wrapper.get('input').setValue('缺陷')
    expect(wrapper.text()).not.toContain('连接项目'); expect(wrapper.text()).toContain('产品缺陷')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('showColumn')).toEqual([['connect:a']])
    await wrapper.get('input').setValue('其他项目')
    expect(wrapper.text()).toContain('连接项目')
    await wrapper.get('input').setValue('没有这个类型')
    expect(wrapper.text()).toContain('没有匹配的列类型')
  })
  it('只读用户仍能恢复列，但不能添加列', () => {
    const wrapper = mount(ConnectColumnAddPopover, { props: { canManage: false, hiddenColumns: [{ key: 'dueDate', label: '截止日期' }] } })
    expect(wrapper.get('.connect-column-type').attributes('disabled')).toBeDefined()
    expect(wrapper.get('.connect-column-center__hidden button').attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).not.toContain('即将推出')
  })
})
