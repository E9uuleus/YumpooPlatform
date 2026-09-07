import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import TimeSessionForm from './TimeSessionForm.vue'
const props = { start: '2026-09-07T13:00:00', end: '2026-09-07T14:00:00', timezone: 'Asia/Shanghai', editing: false, busy: false }
beforeEach(() => { vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-09-07T08:00:00Z')) })
afterEach(() => vi.useRealTimers())
describe('手动计时日历', () => {
  it('从两周展开完整日历，支持闰年二月并禁用未来日期', async () => {
    const wrapper = mount(TimeSessionForm, { props })
    expect(wrapper.findAll('.calendar-grid button')).toHaveLength(14)
    expect(wrapper.get('[aria-label="2026-09-08"]').attributes('disabled')).toBeDefined()
    await wrapper.get('.expand-calendar').trigger('click')
    expect(wrapper.findAll('.calendar-grid button')).toHaveLength(42)
    await wrapper.get('[aria-label="选择年份"]').setValue('2024')
    await wrapper.get('[aria-label="选择月份"]').setValue('2')
    expect(wrapper.get('[aria-label="2024-02-29"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('[aria-label="2024-02-29"]').trigger('click')
    expect(wrapper.emitted('update:start')?.[0]).toEqual(['2024-02-29T13:00:00'])
    expect(wrapper.emitted('update:end')?.[0]).toEqual(['2024-02-29T14:00:00'])
    wrapper.unmount()
  })
  it('选择日期保持跨日时长，预览使用公司时区', async () => {
    const wrapper = mount(TimeSessionForm, { props: { ...props, start: '2026-09-06T23:30:00', end: '2026-09-07T00:30:00' } })
    expect(wrapper.get('output').text()).toBe('01h 00m')
    await wrapper.get('[aria-label="2026-09-05"]').trigger('click')
    expect(wrapper.emitted('update:end')?.[0]).toEqual(['2026-09-06T00:30:00'])
    await wrapper.setProps({ start: '2026-09-06T23:30:00', end: '2026-09-06T00:30:00' })
    expect(wrapper.get('.save-session').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
  it('浏览其他月份后收起恢复选中日期的两周视图', async () => {
    const wrapper = mount(TimeSessionForm, { props })
    await wrapper.get('.expand-calendar').trigger('click')
    await wrapper.get('[aria-label="上个月"]').trigger('click')
    await wrapper.get('.expand-calendar').trigger('click')
    expect(wrapper.get('h4').text()).toBe('2026年9月')
    expect(wrapper.get('[aria-label="2026-09-07"]').attributes('aria-pressed')).toBe('true')
    wrapper.unmount()
  })
})
