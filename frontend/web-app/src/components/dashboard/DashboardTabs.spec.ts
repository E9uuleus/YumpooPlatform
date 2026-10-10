import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
import DashboardTabs from './DashboardTabs.vue'
import { MY_TIME_VIEW_ID, TEAM_VIEW_ID } from './dashboardModel'

const dashboards = [
  { id: 'a', name: '交付概览', projectCount: 2, widgetCount: 8, updatedAt: new Date() },
  { id: 'b', name: '研发周报', projectCount: 1, widgetCount: 3, updatedAt: new Date() },
]
function tabs(props = {}) {
  return mount(DashboardTabs, {
    props: { dashboards, activeId: 'a', activeName: '交付概览（未保存）', team: true, renamable: true, ...props },
    global: { stubs: { ElTooltip: { template: '<span><slot/></span>' }, ElPopover: { template: '<span><slot name="reference"/></span>' } } },
    attachTo: document.body,
  })
}

describe('dashboard tabs', () => {
  it('pins my hours first, shows the team view only for administrators and selects another dashboard', async () => {
    const wrapper = tabs()
    const labels = wrapper.findAll('[role="tab"]').map(tab => tab.text())
    expect(labels).toEqual(['我的工时', '团队视图', '交付概览（未保存）', '研发周报'])
    await wrapper.findAll('[role="tab"]')[3]!.trigger('click')
    await wrapper.findAll('[role="tab"]')[1]!.trigger('click')
    await wrapper.findAll('[role="tab"]')[0]!.trigger('click')
    expect(wrapper.emitted('select')).toEqual([['b'], [TEAM_VIEW_ID], [MY_TIME_VIEW_ID]])
    await wrapper.setProps({ team: false })
    expect(wrapper.findAll('[role="tab"]').map(tab => tab.text())).toEqual(['我的工时', '交付概览（未保存）', '研发周报'])
    wrapper.unmount()
  })
  it('renames only the active dashboard on double click', async () => {
    const wrapper = tabs()
    await wrapper.findAll('[role="tab"]')[0]!.trigger('dblclick')
    await wrapper.findAll('[role="tab"]')[3]!.trigger('dblclick')
    expect(wrapper.find('input').exists()).toBe(false)
    await wrapper.findAll('[role="tab"]')[2]!.trigger('dblclick'); await nextTick()
    const input = wrapper.get('input')
    await input.setValue('  新名称  ')
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('rename')).toEqual([['新名称']])
    expect(wrapper.find('input').exists()).toBe(false)
    wrapper.unmount()
  })
})
