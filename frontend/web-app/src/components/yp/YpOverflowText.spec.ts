import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ElTooltip } from 'element-plus'
import YpOverflowText from './YpOverflowText.vue'

afterEach(() => { vi.useRealTimers() })

describe('YpOverflowText', () => {
  it('只在文字被截断时片刻后显示统一提示，离开即消失', async () => {
    vi.useFakeTimers()
    const wrapper = mount(YpOverflowText, { props: { text: '很长的标题', tag: 'strong' }, attrs: { class: 'title' } })
    const element = wrapper.get('strong.title').element as HTMLElement
    expect(element.textContent).toBe('很长的标题')
    const tooltip = wrapper.findComponent(ElTooltip)
    expect(tooltip.props()).toMatchObject({ content: '很长的标题', visible: false, enterable: false, hideAfter: 0 })
    await wrapper.get('strong').trigger('mouseenter')
    await vi.advanceTimersByTimeAsync(400)
    expect(tooltip.props('visible')).toBe(false)
    vi.spyOn(element, 'scrollWidth', 'get').mockReturnValue(300)
    vi.spyOn(element, 'clientWidth', 'get').mockReturnValue(120)
    await wrapper.get('strong').trigger('mouseenter')
    await vi.advanceTimersByTimeAsync(400)
    expect(tooltip.props('visible')).toBe(true)
    await wrapper.get('strong').trigger('mouseleave')
    expect(tooltip.props('visible')).toBe(false)
  })
})
