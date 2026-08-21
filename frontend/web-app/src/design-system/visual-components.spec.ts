import { flushPromises, mount } from '@vue/test-utils'
import { ElTooltip } from 'element-plus'
import { h } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import YpAssignee from '../components/yp/YpAssignee.vue'
import YpDateBadge from '../components/yp/YpDateBadge.vue'
import YpEmptyState from '../components/yp/YpEmptyState.vue'
import YpFilterBar from '../components/yp/YpFilterBar.vue'
import YpPriorityBadge from '../components/yp/YpPriorityBadge.vue'
import YpProgress from '../components/yp/YpProgress.vue'
import YpStatusTag from '../components/yp/YpStatusTag.vue'
import YpThemeSwitcher from '../components/yp/YpThemeSwitcher.vue'

describe('视觉语义组件', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    vi.useRealTimers()
  })

  it('集中显示已知和未知业务状态', () => {
    expect(mount(YpStatusTag, {
      props: { domain: 'project-lifecycle', status: 'ACTIVE' },
    }).text()).toBe('活跃')
    expect(mount(YpStatusTag, {
      props: { domain: 'account', status: 'FUTURE_STATE' },
    }).text()).toBe('未知（FUTURE_STATE）')
  })

  it('同一稳定 ID 生成一致头像颜色，并表达停用状态', () => {
    const props = { userId: 'user-42', displayName: '张三', accountStatus: 'DISABLED' }
    const first = mount(YpAssignee, { props })
    const second = mount(YpAssignee, { props })
    expect(first.get('.el-avatar').attributes('style')).toBe(second.get('.el-avatar').attributes('style'))
    expect(first.get('.yp-assignee__name').text()).toContain('张三')
    expect(first.get('.yp-assignee__name').text()).toContain('（已停用）')
  })

  it('优先级保留中文语义并灰显未知值', () => {
    expect(mount(YpPriorityBadge, { props: { priority: 'URGENT' } }).text()).toContain('紧急')
    const unknown = mount(YpPriorityBadge, { props: { priority: 'P5' } })
    expect(unknown.text()).toContain('未知（P5）')
    expect(unknown.classes()).toContain('yp-priority--gray')
  })

  it('按公司时区计算自然日，而不是浏览器本地日期', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-21T15:30:00.000Z'))
    const shanghai = mount(YpDateBadge, {
      props: { date: '2026-08-22T00:30:00+08:00', timezone: 'Asia/Shanghai' },
    })
    const losAngeles = mount(YpDateBadge, {
      props: { date: '2026-08-22T00:30:00+08:00', timezone: 'America/Los_Angeles' },
    })
    expect(shanghai.text()).toContain('2026-08-22 · 临期')
    expect(losAngeles.text()).toContain('2026-08-21 · 临期')
  })

  it('进度钳制边界，未知总量不伪造百分比', () => {
    expect(mount(YpProgress, { props: { value: 2, max: 0 } }).text()).toBe('进度未知')
    expect(mount(YpProgress, { props: { percent: 140 } }).text()).toBe('100%')
    expect(mount(YpProgress, { props: { percent: -20 } }).text()).toBe('0%')
  })

  it('搜索与筛选默认折叠，展开后发送移除与清空事件', async () => {
    const wrapper = mount(YpFilterBar, {
      attachTo: document.body,
      props: {
        filters: [{ key: 'status', label: '状态', valueLabel: '活跃' }],
        resultCount: 3,
        labeledTools: true,
      },
      slots: {
        search: () => h('input', { 'aria-label': '测试搜索' }),
        filters: () => h('div', '测试筛选'),
      },
    })
    expect(wrapper.find('[aria-label="测试搜索"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('搜索')
    expect(wrapper.text()).toContain('筛选')
    await wrapper.get('[aria-label="展开搜索"]').trigger('click')
    expect(wrapper.get('[aria-label="测试搜索"]').element).toBe(document.activeElement)
    await wrapper.get('.el-tag__close').trigger('click')
    await wrapper.get('[aria-label="展开筛选"]').trigger('click')
    await flushPromises()
    const clearButton = Array.from(document.body.querySelectorAll('button'))
      .find(button => button.textContent?.includes('清除全部'))
    clearButton?.click()
    expect(wrapper.emitted('remove')).toEqual([['status']])
    expect(wrapper.emitted('clear')).toHaveLength(1)
  })

  it('仅图标筛选入口保留 Tooltip 文案', () => {
    const warning = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    const wrapper = mount(YpFilterBar, {
      attachTo: document.body,
      props: { filters: [] },
      slots: { filters: () => h('div', '筛选内容') },
    })
    const tooltip = wrapper.findAllComponents(ElTooltip)
      .find(item => item.props('content') === '筛选')
    expect(tooltip?.props('content')).toBe('筛选')
    expect(tooltip?.props('disabled')).toBe(false)
    expect(warning.mock.calls.flat().join(' ')).not.toContain('non-element root node')
    warning.mockRestore()
  })

  it('紧凑空态保留语义与键盘可聚焦操作', async () => {
    const wrapper = mount(YpEmptyState, {
      attachTo: document.body,
      props: { reason: 'no-results', compact: true },
      slots: { action: () => h('button', { type: 'button' }, '清除筛选') },
    })
    expect(wrapper.classes()).toContain('yp-empty-state--compact')
    expect(wrapper.text()).toContain('没有匹配结果')
    const action = wrapper.get('button')
    action.element.focus()
    expect(document.activeElement).toBe(action.element)
  })

  it('主题切换器使用可聚焦单选控件并发送双向绑定事件', async () => {
    const wrapper = mount(YpThemeSwitcher, {
      attachTo: document.body,
      props: { theme: 'system', density: 'comfortable' },
    })
    await wrapper.get('.yp-theme-trigger').trigger('click')
    await flushPromises()
    const radios = Array.from(document.body.querySelectorAll<HTMLInputElement>('input[type="radio"]'))
    expect(radios).toHaveLength(6)
    expect(radios.some(radio => radio.tabIndex === 0)).toBe(true)
    radios[1]?.click()
    radios[5]?.click()
    await flushPromises()
    expect(wrapper.emitted('update:theme')?.flat()).toContain('light')
    expect(wrapper.emitted('update:density')?.flat()).toContain('compact')
  })
})
