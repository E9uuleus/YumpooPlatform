import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
import DashboardTabs from './DashboardTabs.vue'

const dashboards = [
  { id: 'a', name: '交付概览', projectCount: 2, widgetCount: 8, updatedAt: new Date() },
  { id: 'b', name: '研发周报', projectCount: 1, widgetCount: 3, updatedAt: new Date() },
]
function tabs(props = {}) {
  return mount(DashboardTabs, {
    props: { dashboards, activeId: 'a', activeName: '交付概览（未保存）', renamable: true, ...props },
    global: { stubs: { ElTooltip: { template: '<span><slot/></span>' }, ElPopover: { template: '<span><slot name="reference"/></span>' } } },
    attachTo: document.body,
  })
}

describe('dashboard tabs', () => {
  it('shows personal dashboards and selects another dashboard', async () => {
    const wrapper = tabs()
    const labels = wrapper.findAll('[role="tab"]').map(tab => tab.text())
    expect(labels).toEqual(['交付概览（未保存）', '研发周报'])
    await wrapper.findAll('[role="tab"]')[1]!.trigger('click')
    await wrapper.findAll('[role="tab"]')[0]!.trigger('click')
    expect(wrapper.emitted('select')).toEqual([['b']])
    wrapper.unmount()
  })
  it('renames only the active dashboard on double click', async () => {
    const wrapper = tabs()
    await wrapper.findAll('[role="tab"]')[1]!.trigger('dblclick')
    expect(wrapper.find('input').exists()).toBe(false)
    await wrapper.findAll('[role="tab"]')[0]!.trigger('dblclick'); await nextTick()
    const input = wrapper.get('input')
    await input.setValue('  新名称  ')
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('rename')).toEqual([['新名称']])
    expect(wrapper.find('input').exists()).toBe(false)
    wrapper.unmount()
  })
})
