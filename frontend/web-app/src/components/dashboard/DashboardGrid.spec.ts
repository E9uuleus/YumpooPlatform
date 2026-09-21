import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DashboardGrid from './DashboardGrid.vue'
import { newWidget } from './dashboardModel'

const gridMocks = vi.hoisted(() => ({ stop: undefined as ((event: Event) => void) | undefined, save: vi.fn(), load: vi.fn() }))
vi.mock('gridstack', () => ({ GridStack: { init: () => ({
  engine: { nodes: [] }, batchUpdate: vi.fn(), load: gridMocks.load, setStatic: vi.fn(), column: vi.fn(), save: gridMocks.save, on: (_event: string, callback: (event: Event) => void) => { gridMocks.stop = callback }, destroy: vi.fn(),
  makeWidget: (element: HTMLElement, node: unknown) => Object.assign(element, { gridstackNode: node }),
}) } }))
let resize: ResizeObserverCallback
let finish!: () => void
let scroll: ReturnType<typeof vi.fn>
const originalAnimations = Object.getOwnPropertyDescriptor(Element.prototype, 'getAnimations')
const originalScroll = Object.getOwnPropertyDescriptor(Element.prototype, 'scrollIntoView')
beforeEach(() => {
  const finished = new Promise<void>(resolve => { finish = resolve })
  scroll = vi.fn()
  vi.stubGlobal('ResizeObserver', class { constructor(callback: ResizeObserverCallback) { resize = callback } observe() {} disconnect() {} })
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => { callback(0); return 0 })
  vi.stubGlobal('matchMedia', () => ({ matches: true }))
  Object.defineProperty(Element.prototype, 'getAnimations', { configurable: true, value: () => [{ finished }] })
  Object.defineProperty(Element.prototype, 'scrollIntoView', { configurable: true, value: scroll })
})
afterEach(() => {
  for (const [key, descriptor] of [['getAnimations', originalAnimations], ['scrollIntoView', originalScroll]] as const) {
    if (descriptor) Object.defineProperty(Element.prototype, key, descriptor)
    else Reflect.deleteProperty(Element.prototype, key)
  }
  vi.restoreAllMocks(); vi.unstubAllGlobals()
})
describe('new widget positioning', () => {
  it('waits for the grid height transition before scrolling to the new card', async () => {
    const widget = newWidget('CHART')
    const wrapper = mount(DashboardGrid, { props: { widgets: [widget] } })
    const reveal = wrapper.vm.reveal(widget.id)
    await flushPromises()
    expect(scroll).not.toHaveBeenCalled()
    finish(); await reveal
    expect(scroll).toHaveBeenCalledWith({ behavior: 'instant', block: 'center' })
    wrapper.unmount()
  })
  it('cancels a pending reveal when the dashboard grid is replaced', async () => {
    const widget = newWidget('CHART')
    const wrapper = mount(DashboardGrid, { props: { widgets: [widget] } })
    const reveal = wrapper.vm.reveal(widget.id)
    await flushPromises(); wrapper.unmount(); finish(); await reveal
    expect(scroll).not.toHaveBeenCalled()
  })
  it('saves the active six-column layout and restores omitted minimum dimensions', async () => {
    vi.useFakeTimers(); vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(840)
    const widget = newWidget('CHART')
    gridMocks.save.mockReturnValue([{ id: widget.id, x: 0, y: 0, minW: 2, minH: 7 }])
    const wrapper = mount(DashboardGrid, { props: { widgets: [widget] } }); await flushPromises()
    gridMocks.stop?.(new Event('resizestop')); await vi.runAllTimersAsync()
    expect(gridMocks.save).toHaveBeenLastCalledWith(false, false, undefined, 6)
    expect(wrapper.emitted('layout')?.[0]?.[0]).toEqual([expect.objectContaining({ wide: { ...widget.wide, h: 7 }, medium: { x: 0, y: 0, w: 2, h: 7 } })])
    wrapper.unmount(); vi.useRealTimers()
  })
  it('does not save during resize or from a gesture that outlives its breakpoint', async () => {
    vi.useFakeTimers(); vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(1200)
    const wrapper = mount(DashboardGrid, { props: { widgets: [newWidget('CHART')] } }); await flushPromises()
    gridMocks.stop?.(new Event('dragstop'))
    resize([{ contentRect: { width: 840 } }] as ResizeObserverEntry[], {} as ResizeObserver)
    await vi.runAllTimersAsync()
    expect(wrapper.emitted('layout')).toBeUndefined()
    gridMocks.stop?.(new Event('dragstop')); wrapper.unmount(); await vi.runAllTimersAsync()
    expect(wrapper.emitted('layout')).toBeUndefined(); vi.useRealTimers()
  })

  it('keeps each saved height across viewport widths and stacks narrow cards without overlap', async () => {
    let width = 1200
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(() => width)
    const widgets = [newWidget('CHART'), newWidget('CHART')]
    widgets[0]!.wide.h = 5; widgets[0]!.medium.h = 9
    widgets[1]!.wide.h = 11; widgets[1]!.medium.h = 4
    const wrapper = mount(DashboardGrid, { props: { widgets } }); await flushPromises()
    for (const next of [1800, 840, 620, 390, 1200]) {
      width = next
      resize([{ contentRect: { width } }] as ResizeObserverEntry[], {} as ResizeObserver)
      await flushPromises()
      const nodes = gridMocks.load.mock.calls.at(-1)![0] as { h: number; y: number; minH: number }[]
      expect(nodes.map(n => n.h)).toEqual([5, 11])
      expect(nodes.map(n => n.minH)).toEqual([3, 3])
      if (width === 390) expect(nodes.map(n => n.y)).toEqual([0, 5])
    }
    expect(wrapper.emitted('layout')).toBeUndefined()
    wrapper.unmount()
  })

  it('keeps the exact drop row through emission and reloading props', async () => {
    vi.useFakeTimers(); vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(1200)
    const widget = newWidget('CHART')
    gridMocks.save.mockReturnValue([{ id: widget.id, x: 2, y: 9, w: 7, h: 6 }])
    const wrapper = mount(DashboardGrid, { props: { widgets: [widget] } }); await flushPromises()
    gridMocks.stop?.(new Event('dragstop')); await vi.runAllTimersAsync()
    const widgets = wrapper.emitted('layout')![0]![0] as typeof widget[]
    expect(widgets[0]!.wide).toEqual({ x: 2, y: 9, w: 7, h: 6 })
    await wrapper.setProps({ widgets }); await flushPromises()
    expect(gridMocks.load.mock.calls.at(-1)![0][0]).toMatchObject({ x: 2, y: 9, w: 7, h: 6 })
    wrapper.unmount(); vi.useRealTimers()
  })

})
