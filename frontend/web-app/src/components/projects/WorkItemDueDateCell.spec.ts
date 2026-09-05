import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { defineComponent, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import WorkItemDueDateCell from './WorkItemDueDateCell.vue'

vi.mock('../../composables/useSession', () => ({ useSession: () => ({ authentication: { value: { company: { timezone: 'Asia/Shanghai' } } } }) }))
enableAutoUnmount(afterEach)

const Popover = defineComponent({ name: 'ElPopover', template: '<div><slot name="reference" /><slot /></div>' })
const DatePicker = defineComponent({ name: 'ElDatePicker', template: '<span />' })
const TimePicker = defineComponent({ name: 'ElTimePicker', template: '<span />', props: ['modelValue', 'saveOnBlur'] })
function cell(dueDate: string | null = '2026-09-03', dueTime: string | null = null) {
  return mount(WorkItemDueDateCell, {
    props: { item: { dueDate, dueTime, statusCategory: 'TODO' }, canEdit: true },
    global: { stubs: { ElPopover: Popover, ElDatePicker: DatePicker, ElTimePicker: TimePicker, ElTooltip: { template: '<span><slot /></span>' } } },
  })
}

describe('共享截止日期单元格', () => {
  beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(new Date('2026-09-03T04:00:00Z')) })
  afterEach(() => { vi.useRealTimers() })

  it('时钟仅展开，选择草稿和取消不提交，确认才提交分钟时间', async () => {
    const wrapper = cell()
    wrapper.getComponent(Popover).vm.$emit('show')
    await wrapper.get('.deadline-clock').trigger('click')
    expect(wrapper.emitted('change')).toBeUndefined()
    const picker = wrapper.getComponent(TimePicker)
    expect(picker.props('saveOnBlur')).toBe(false)
    picker.vm.$emit('update:modelValue', '18:05')
    await nextTick()
    expect(wrapper.emitted('change')).toBeUndefined()
    picker.vm.$emit('update:modelValue', null)
    expect(wrapper.emitted('change')).toBeUndefined()
    picker.vm.$emit('change', '18:05')
    expect(wrapper.emitted('change')).toEqual([[{ dueDate: '2026-09-03', dueTime: '18:05' }]])
  })
  it('真实时间选择器仅打开再失焦不提交，确认按钮才提交', async () => {
    vi.useRealTimers()
    const outside = document.createElement('button')
    document.body.appendChild(outside)
    const wrapper = mount(WorkItemDueDateCell, {
      attachTo: document.body,
      props: { item: { dueDate: '2026-09-03', statusCategory: 'TODO' }, canEdit: true },
      global: { stubs: { ElPopover: Popover, ElDatePicker: DatePicker } },
    })
    try {
      await wrapper.get('.deadline-clock').trigger('click')
      const input = wrapper.get<HTMLInputElement>('input')
      input.element.focus()
      await flushPromises()
      expect(wrapper.emitted('change')).toBeUndefined()
      outside.focus()
      await flushPromises()
      expect(wrapper.emitted('change')).toBeUndefined()
      input.element.focus()
      await flushPromises()
      const confirm = document.querySelector<HTMLButtonElement>('.el-time-panel__btn.confirm')
      expect(confirm).not.toBeNull()
      confirm!.click()
      await flushPromises()
      expect(wrapper.emitted('change')).toHaveLength(1)
      expect(wrapper.emitted('change')?.[0]?.[0]).toMatchObject({ dueDate: '2026-09-03', dueTime: expect.stringMatching(/^\d{2}:\d{2}$/) })
    } finally {
      wrapper.unmount()
      outside.remove()
    }
  })

  it('真实嵌套弹窗点击小时和分钟后保持打开，仅确认提交，点击外部仍关闭', async () => {
    const outside = document.createElement('button')
    document.body.appendChild(outside)
    const wrapper = mount(WorkItemDueDateCell, {
      attachTo: document.body,
      props: { item: { dueDate: '2026-09-03', statusCategory: 'TODO' }, canEdit: true },
      global: { stubs: { transition: false } },
    })
    const settle = async () => {
      await flushPromises()
      await vi.advanceTimersByTimeAsync(300)
      await flushPromises()
    }
    const click = async (element: Element) => {
      element.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }))
      element.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
      element.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
      element.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 1 }))
      await settle()
    }
    try {
      const trigger = wrapper.get('.monday-due-date-cell')
      await click(trigger.element)
      const popover = wrapper.getComponent({ name: 'ElPopover' })
      expect(popover.props('visible')).toBe(true)
      const editor = document.querySelector('.work-item-deadline-popover')!
      await click(editor.querySelector('.deadline-clock')!)
      const input = editor.querySelector<HTMLInputElement>('input[aria-label="截止时间"]')!
      input.focus()
      await settle()
      const panel = document.querySelector('.el-time-panel')!
      const lists = panel.querySelectorAll('.el-time-spinner__list')
      await click(lists[0]!.querySelectorAll('li')[10]!)
      expect(popover.props('visible')).toBe(true)
      expect(input.getAttribute('aria-expanded')).toBe('true')
      await click(lists[1]!.querySelectorAll('li')[15]!)
      expect(popover.props('visible')).toBe(true)
      expect(input.getAttribute('aria-expanded')).toBe('true')
      expect(wrapper.emitted('change')).toBeUndefined()
      await click(panel.querySelector('.el-time-panel__btn.confirm')!)
      expect(wrapper.emitted('change')).toEqual([[{ dueDate: '2026-09-03', dueTime: '10:15' }]])
      expect(popover.props('visible')).toBe(true)
      await click(outside)
      expect(popover.props('visible')).toBe(false)
    } finally {
      wrapper.unmount()
      outside.remove()
    }
  })

  it('日期改变保留时分，时间可单独清空', async () => {
    const wrapper = cell('2026-09-03', '18:05')
    wrapper.getComponent(Popover).vm.$emit('show')
    await nextTick()
    wrapper.getComponent(DatePicker).vm.$emit('update:modelValue', '2026-09-04')
    wrapper.getComponent(TimePicker).vm.$emit('change', null)
    expect(wrapper.emitted('change')).toEqual([
      [{ dueDate: '2026-09-04', dueTime: '18:05' }], [{ dueDate: '2026-09-03', dueTime: null }],
    ])
  })
  it('已有截止时分改为 Today 后显示今日到期标识', async () => {
    const wrapper = cell('2026-09-04', '00:00')
    wrapper.getComponent(Popover).vm.$emit('show')
    await wrapper.getComponent({ name: 'ElButton' }).trigger('click')
    expect(wrapper.emitted('change')).toEqual([[{ dueDate: '2026-09-03', dueTime: '00:00' }]])
    await wrapper.setProps({ item: { dueDate: '2026-09-03', dueTime: '00:00', statusCategory: 'TODO' } })
    expect(wrapper.find('.deadline-indicator--today').exists()).toBe(true)
    expect(wrapper.get('.monday-due-date-cell').attributes('aria-description')).toBe('今日截止 · 2026-09-03 00:00')
  })
  it('清空仅发出一次请求意图，不在成功响应前假清空或触发选择', async () => {
    const wrapper = cell('2026-09-03', '18:05')
    await wrapper.get('.deadline-clear').trigger('click')
    expect(wrapper.emitted('change')).toEqual([[{ dueDate: null, dueTime: null }]])
    expect(wrapper.emitted('select')).toBeUndefined()
    expect(wrapper.get('.deadline-text').text()).toBe('09-03 18:05')
    await wrapper.setProps({ item: { dueDate: null, statusCategory: 'TODO' } })
    expect(wrapper.find('.deadline-clear').exists()).toBe(false)
    expect(wrapper.get('.deadline-text').text()).toBe('—')
  })
  it('空日期、只读及提交中不允许清空或保存时间', async () => {
    const wrapper = cell(null)
    expect(wrapper.find('.deadline-clear').exists()).toBe(false)
    expect(wrapper.get('.deadline-clock').attributes('disabled')).toBeDefined()
    await wrapper.setProps({ item: { dueDate: '2026-09-03', statusCategory: 'TODO' }, canEdit: false })
    expect(wrapper.find('.deadline-clear').exists()).toBe(false)
    expect(wrapper.get('.monday-due-date-cell').attributes('disabled')).toBeDefined()
    wrapper.getComponent(DatePicker).vm.$emit('update:modelValue', '2026-09-04')
    await wrapper.setProps({ canEdit: true, busy: true })
    expect(wrapper.get('.deadline-clear').attributes('disabled')).toBeDefined()
    wrapper.getComponent(DatePicker).vm.$emit('update:modelValue', '2026-09-04')
    expect(wrapper.emitted('change')).toBeUndefined()
  })
  it('无变化不保存，跨分钟和跨年更新展示，并在卸载后清理时钟', async () => {
    vi.setSystemTime(new Date('2026-12-31T15:59:59Z'))
    const wrapper = cell('2026-12-31')
    expect(wrapper.get('.deadline-text').text()).toBe('12-31')
    wrapper.getComponent(DatePicker).vm.$emit('update:modelValue', '2026-12-31')
    expect(wrapper.emitted('change')).toBeUndefined()
    await vi.advanceTimersByTimeAsync(1000)
    expect(wrapper.get('.deadline-text').text()).toBe('2026-12-31')
    expect(wrapper.find('.deadline-indicator--red').exists()).toBe(true)
    wrapper.unmount()
  })
  it('提前完成使用绿色感叹号和划线，逾期完成不划线', async () => {
    const wrapper = cell()
    await wrapper.setProps({ item: { dueDate: '2026-09-03', statusCategory: 'DONE', completedAt: '2026-09-01T00:00:00Z' } })
    expect(wrapper.find('.deadline-indicator--green svg path').exists()).toBe(true)
    expect(wrapper.find('.deadline-text--done').exists()).toBe(true)
    await wrapper.setProps({ item: { dueDate: '2026-09-03', statusCategory: 'DONE', completedAt: '2026-09-04T00:00:00Z' } })
    expect(wrapper.find('.deadline-indicator--red').exists()).toBe(true)
    expect(wrapper.find('.deadline-text--done').exists()).toBe(false)
  })
})
