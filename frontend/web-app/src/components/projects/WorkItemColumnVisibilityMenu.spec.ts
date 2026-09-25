import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import WorkItemColumnVisibilityMenu from './WorkItemColumnVisibilityMenu.vue'

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })
describe('显示列菜单', () => {
  it('先本地更新勾选，经过绘制帧和任务队列后才提交，并保留其它待提交选择', async () => {
    vi.useFakeTimers()
    const frames: FrameRequestCallback[] = []
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => frames.push(callback))
    vi.stubGlobal('cancelAnimationFrame', vi.fn())
    const wrapper = mount(WorkItemColumnVisibilityMenu, { props: { columns: [
      { key: 'title', label: '名称' }, { key: 'status', label: '状态' }, { key: 'priority', label: '优先级' },
    ], hidden: new Set<string>() } })
    const checkboxes = wrapper.findAll<HTMLInputElement>('input')
    expect(checkboxes[0]!.element.disabled).toBe(true)
    await checkboxes[1]!.setValue(false)
    await checkboxes[2]!.setValue(false)
    expect(checkboxes[1]!.element.checked).toBe(false)
    expect(wrapper.emitted('toggle')).toBeUndefined()
    frames[0]!(0)
    expect(wrapper.emitted('toggle')).toBeUndefined()
    await vi.runOnlyPendingTimersAsync()
    await wrapper.setProps({ hidden: new Set(['status']) })
    expect(checkboxes[2]!.element.checked).toBe(false)
    frames[1]!(0); await vi.runOnlyPendingTimersAsync()
    expect(wrapper.emitted('toggle')).toEqual([['status', false], ['priority', false]])
    wrapper.unmount()
  })
})
