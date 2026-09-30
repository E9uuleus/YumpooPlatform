import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import OpsSegmented from './OpsSegmented.vue'

describe('运维分段控件', () => {
  it('单选时只标记当前值，点击发出所选值', async () => {
    const wrapper = mount(OpsSegmented, {
      props: {
        groupLabel: '刷新间隔',
        options: [
          { value: 0, label: '关' },
          { value: 60, label: '60 秒' },
        ],
        selected: 60,
      },
    })
    expect(wrapper.get('[role="group"]').attributes('aria-label')).toBe('刷新间隔')
    const buttons = wrapper.findAll('button')
    expect(buttons.map((button) => button.attributes('aria-pressed'))).toEqual(['false', 'true'])
    await buttons[0]!.trigger('click')
    expect(wrapper.emitted('select')).toEqual([[0]])
  })

  it('多选时按集合标记，并在标签后显示计数', () => {
    const wrapper = mount(OpsSegmented, {
      props: {
        groupLabel: '日志级别',
        options: [
          { value: 'ERROR', label: 'ERROR', count: 2, tone: 'red' },
          { value: 'DEBUG', label: 'DEBUG', count: 9, tone: 'gray' },
        ],
        selected: ['ERROR'],
      },
    })
    const buttons = wrapper.findAll('button')
    expect(buttons[0]!.attributes('aria-pressed')).toBe('true')
    expect(buttons[0]!.classes()).toContain('ops-tone-red')
    expect(buttons[1]!.attributes('aria-pressed')).toBe('false')
    expect(buttons[1]!.text()).toBe('DEBUG 9')
  })
})
