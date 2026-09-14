import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import WorkItemUpdatedCell from './WorkItemUpdatedCell.vue'
import YpAssignee from '../yp/YpAssignee.vue'

vi.mock('../../composables/useSession', () => ({
  useSession: () => ({ authentication: { value: { company: { timezone: 'Asia/Shanghai' } } } }),
}))
afterEach(() => vi.useRealTimers())

describe('最后更新单元格', () => {
  it('使用更新人、公司时区和分钟刷新，更新后同步展示', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-09-11T02:05:00Z'))
    const wrapper = mount(WorkItemUpdatedCell, { props: { item: {
      updatedAt: new Date('2026-09-11T02:00:00Z'), updatedByUserId: 'updater', updatedByDisplayName: '林晓',
    } } })
    expect(wrapper.text()).toContain('5分钟前')
    expect(wrapper.get('.work-item-updated-cell').attributes('aria-label')).toBe('由林晓更新于2026年9月11日 10:00')
    expect(wrapper.getComponent(YpAssignee).props()).toMatchObject({ userId: 'updater', tooltipDisabled: true })
    await vi.advanceTimersByTimeAsync(60_000)
    expect(wrapper.text()).toContain('6分钟前')
    await wrapper.setProps({ item: { updatedAt: new Date(), updatedByUserId: 'next', updatedByDisplayName: '周衡' } })
    expect(wrapper.text()).toContain('刚刚')
    expect(wrapper.get('.work-item-updated-cell').attributes('aria-label')).toContain('由周衡更新于')
    wrapper.unmount()
  })
  it('兼容缺少更新人资料的历史响应', () => {
    const wrapper = mount(WorkItemUpdatedCell, { props: { item: { updatedAt: new Date() } } })
    expect(wrapper.get('.work-item-updated-cell').attributes('aria-label')).toContain('由历史成员更新于')
    wrapper.unmount()
  })
})
