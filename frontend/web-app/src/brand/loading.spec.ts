import { mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { vBrandLoading } from './loading'

describe('品牌加载指令', () => {
  afterEach(() => vi.useRealTimers())

  it('沿用加载条件，独立生成 SVG 引用，结束和卸载后清理遮罩', async () => {
    vi.useFakeTimers()
    const busy = ref(false)
    const wrapper = mount(defineComponent({
      directives: { loading: vBrandLoading },
      setup: () => ({ busy }),
      template: '<main><section v-loading="busy"></section><section v-loading="busy" element-loading-text="读取工作项…"></section></main>',
    }), { attachTo: document.body })
    expect(document.querySelectorAll('.el-loading-mask')).toHaveLength(0)
    busy.value = true
    await nextTick()
    expect(document.querySelectorAll('.yp-brand-loading')).toHaveLength(2)
    expect(wrapper.findAll('section').every(section => section.attributes('aria-busy') === 'true')).toBe(true)
    const ids = Array.from(document.querySelectorAll('.yp-brand-loading [id]'), element => element.id)
    expect(new Set(ids).size).toBe(ids.length)
    expect(wrapper.text()).toContain('正在加载…')
    expect(wrapper.text()).toContain('读取工作项…')
    busy.value = false
    await nextTick()
    expect(wrapper.findAll('section').every(section => section.attributes('aria-busy') === 'false')).toBe(true)
    await vi.advanceTimersByTimeAsync(500)
    expect(document.querySelectorAll('.el-loading-mask')).toHaveLength(0)
    busy.value = true
    await nextTick()
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(500)
    expect(document.querySelectorAll('.el-loading-mask')).toHaveLength(0)
  })
})
