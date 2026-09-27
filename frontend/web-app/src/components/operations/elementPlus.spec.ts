import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { ElSelect, ElTabPane, ElOption as RawOption } from 'element-plus'
import { describe, expect, it } from 'vitest'
import { ElOption, ElTabs } from './elementPlus'

describe('运维控件的类型适配', () => {
  it('选项显示标签并维持多选绑定', async () => {
    const view = defineComponent({ components: { ElSelect, ElOption }, setup: () => ({ modules: ref(['foundation']) }), template: '<el-select v-model="modules" multiple><el-option label="基础服务" value="foundation" /><el-option label="运维" value="operations" /></el-select>' })
    const wrapper = mount(view)
    await flushPromises()
    expect(wrapper.text()).toContain('基础服务')
    await wrapper.findAllComponents(RawOption).find(option => option.text() === '运维')!.trigger('click')
    await flushPromises()
    expect(wrapper.vm.modules).toEqual(['foundation', 'operations'])
    wrapper.unmount()
  })
  it('页签切换保留 v-model 事件', async () => {
    const view = defineComponent({ components: { ElTabs, ElTabPane }, setup: () => ({ tab: ref('active') }), template: '<el-tabs v-model="tab"><el-tab-pane label="活跃" name="active" /><el-tab-pane label="规则" name="rules" /></el-tabs>' })
    const wrapper = mount(view)
    await wrapper.findAll('[role="tab"]')[1]!.trigger('click')
    expect(wrapper.vm.tab).toBe('rules')
    wrapper.unmount()
  })
})
