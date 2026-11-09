import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { defineComponent } from 'vue'
import MyWorkViewMenu from './MyWorkViewMenu.vue'

const DropdownStub = defineComponent({ name: 'ElDropdown', emits: ['command'], template: '<div><slot /><slot name="dropdown" /></div>' })
const mountMenu = () => mount(MyWorkViewMenu, {
  props: { modelValue: 'DUE_DATE' },
  global: { stubs: {
    ElDropdown: DropdownStub,
    ElDropdownMenu: { template: '<ul><slot /></ul>' },
    ElDropdownItem: { template: '<li><slot /></li>' },
  } },
})

describe('我的工作视图选项', () => {
  it('四个视图都以 SVG 图标加文字显示，并标记当前视图', () => {
    const items = mountMenu().findAll('li')
    expect(items.map(item => item.text())).toEqual(['状态视图', '日期视图', '优先级视图', '类别视图'])
    for (const item of items) expect(item.find('svg').exists()).toBe(true)
    expect(items.map(item => item.classes().includes('yp-menu-item--current'))).toEqual([false, true, false, false])
    expect(items[1]!.attributes('aria-current')).toBe('true')
  })

  it('按钮显示当前视图，选择后发出新字段', () => {
    const wrapper = mountMenu()
    const trigger = wrapper.get('[data-tour="my-work-view"]')
    expect(trigger.text()).toBe('日期视图')
    expect(trigger.find('svg').exists()).toBe(true)
    expect(trigger.attributes('aria-label')).toContain('视图选项')
    wrapper.getComponent(DropdownStub).vm.$emit('command', 'PRIORITY')
    expect(wrapper.emitted('update:modelValue')).toEqual([['PRIORITY']])
  })
})
