import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { ElTooltip } from 'element-plus'
import YpOverflowText from './YpOverflowText.vue'

describe('YpOverflowText', () => {
  it('只在文字被截断时启用统一提示', async () => {
    const wrapper = mount(YpOverflowText, { props: { text: '很长的标题', tag: 'strong' }, attrs: { class: 'title' } })
    const element = wrapper.get('strong.title').element as HTMLElement
    expect(element.textContent).toBe('很长的标题')
    const tooltip = wrapper.findComponent(ElTooltip)
    expect(tooltip.props()).toMatchObject({ content: '很长的标题', disabled: true, enterable: false, showAfter: 300, hideAfter: 0 })
    vi.spyOn(element, 'scrollWidth', 'get').mockReturnValue(300)
    vi.spyOn(element, 'clientWidth', 'get').mockReturnValue(120)
    await wrapper.get('strong').trigger('mouseenter')
    expect(tooltip.props('disabled')).toBe(false)
  })
})
