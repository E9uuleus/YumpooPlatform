import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WorkItemBatchBar from './WorkItemBatchBar.vue'

describe('勾选批量工作台', () => {
  it('播报合计和子项数量，展示进度并把清空切换为停止', async () => {
    const wrapper = mount(WorkItemBatchBar, { props: { count: 10, subitemCount: 3, busy: false, stopping: false,
      progressLabel: '', canCreate: true, canDelete: true, canConvert: true, moveDisabled: false, choosingParent: false, embedded: false, left: 260 } })
    expect(wrapper.get('[role="toolbar"]').attributes('aria-label')).toBe('勾选工作项批量操作')
    expect(wrapper.get('[aria-live="polite"]').text()).toContain('已选择 10 项')
    expect(wrapper.text()).toContain('含 3 个子工作项')
    expect(wrapper.attributes('style')).toContain('left: 260px')
    await wrapper.get('[aria-label="清空勾选"]').trigger('click')
    expect(wrapper.emitted('clear')).toHaveLength(1)
    await wrapper.setProps({ busy: true, progressLabel: '删除中 3/10' })
    expect(wrapper.get('[aria-live="polite"]').text()).toContain('删除中 3/10')
    expect(wrapper.find('[aria-label="清空勾选"]').exists()).toBe(false)
    await wrapper.get('.work-item-batch-close').trigger('click')
    expect(wrapper.emitted('stop')).toHaveLength(1)
    await wrapper.setProps({ stopping: true, embedded: true })
    expect(wrapper.get('.work-item-batch-close').attributes('disabled')).toBeDefined()
    expect(wrapper.classes()).toContain('work-item-batch-bar-host--embedded')
    wrapper.unmount()
  })
})
