import { mount } from '@vue/test-utils'
import { defineComponent, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ProjectWorkItemListItem } from '@yumpoo/api-client'
import WorkItemTimerCell from './WorkItemTimerCell.vue'

const Popover = defineComponent({
  name: 'ElPopover', props: ['visible', 'placement', 'popperStyle'], emits: ['update:visible'],
  template: '<div><slot name="reference" /><div v-if="visible" class="time-log-popover"><slot /></div></div>',
})
const frames = new Map<number, FrameRequestCallback>()
let frameId = 0
let wrapper: ReturnType<typeof mount>
beforeEach(() => {
  vi.stubGlobal('innerHeight', 300)
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => { frames.set(++frameId, callback); return frameId })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id))
})
afterEach(() => { wrapper?.unmount(); frames.clear(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
async function renderFrame() {
  const callbacks = [...frames.values()]
  frames.clear()
  callbacks.forEach(callback => callback(0))
  await nextTick()
}
async function setup() {
  wrapper = mount(WorkItemTimerCell, { attachTo: document.body,
    props: { item: { id: 'item', title: '任务', capabilities: { canEditFields: true } } as ProjectWorkItemListItem, projectId: 'project' },
    global: { stubs: { ElPopover: Popover, TimeSessionDialog: true } },
  })
  const bounds = vi.spyOn(wrapper.get('.timer-cell').element, 'getBoundingClientRect')
    .mockReturnValue({ top: 80, bottom: 116 } as DOMRect)
  const popover = wrapper.getComponent(Popover)
  popover.vm.$emit('update:visible', true)
  await nextTick()
  return { bounds, popover }
}

describe('timer popover viewport changes', () => {
  it('repositions on ancestor scroll and resize, keeping height within the new space', async () => {
    const { bounds, popover } = await setup()
    expect(popover.props()).toMatchObject({ placement: 'bottom', popperStyle: { '--time-log-max-height': '158px' } })
    bounds.mockReturnValue({ top: 180, bottom: 216 } as DOMRect)
    wrapper.element.dispatchEvent(new Event('scroll'))
    wrapper.element.dispatchEvent(new Event('scroll'))
    expect(frames.size).toBe(1)
    await renderFrame()
    expect(popover.props()).toMatchObject({ placement: 'top', popperStyle: { '--time-log-max-height': '154px' } })

    wrapper.getComponent({ name: 'TimeSessionDialog' }).vm.$emit('resize', 580)
    await nextTick()
    expect(popover.props('placement')).toBe('top')
    vi.stubGlobal('innerHeight', 200)
    bounds.mockReturnValue({ top: 60, bottom: 96 } as DOMRect)
    window.dispatchEvent(new Event('resize'))
    await renderFrame()
    expect(popover.props()).toMatchObject({ placement: 'bottom', popperStyle: { '--time-log-max-height': '78px' } })
  })

  it('ignores internal log scrolling and removes listeners and pending frames when closed', async () => {
    const { bounds, popover } = await setup()
    wrapper.get('.time-log-popover').element.dispatchEvent(new Event('scroll'))
    expect(frames.size).toBe(0)
    wrapper.element.dispatchEvent(new Event('scroll'))
    expect(frames.size).toBe(1)
    popover.vm.$emit('update:visible', false)
    await nextTick()
    expect(frames.size).toBe(0)
    const calls = bounds.mock.calls.length
    window.dispatchEvent(new Event('scroll'))
    window.dispatchEvent(new Event('resize'))
    await renderFrame()
    expect(bounds).toHaveBeenCalledTimes(calls)
  })

  it('cancels an outstanding layout callback when unmounted while open', async () => {
    await setup()
    window.dispatchEvent(new Event('resize'))
    expect(frames.size).toBe(1)
    wrapper.unmount()
    expect(frames.size).toBe(0)
    window.dispatchEvent(new Event('resize'))
    expect(frames.size).toBe(0)
  })
})
