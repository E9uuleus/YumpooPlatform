import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { GridStack } from 'gridstack'
import DashboardGrid from './DashboardGrid.vue'
import { newWidget } from './dashboardModel'

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('真实仪表板断点加载', () => {
  it('拖动改变宽屏顺序后，六列仍精确加载自己的坐标', async () => {
    let width = 1100, resize!: ResizeObserverCallback
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(() => width)
    vi.stubGlobal('ResizeObserver', class { constructor(callback: ResizeObserverCallback) { resize = callback } observe() {} disconnect() {} })
    let widgets = [
      ['wide', 2, 8, 10, 5, 8], ['small', 2, 2, 2, 3, 2], ['lower', 2, 5, 3, 3, 5], ['side', 0, 0, 2, 12, 0],
    ].map(([id, x, y, w, h, mediumY]) => ({ ...newWidget('CHART'), id: String(id), wide: { x: Number(x), y: Number(y), w: Number(w), h: Number(h) }, medium: { x: Number(x), y: Number(mediumY), w: Math.min(Number(w), 4), h: Number(h) } }))
    const wrapper = mount(DashboardGrid, { props: { widgets }, attachTo: document.body }); await flushPromises()
    const grid = (wrapper.element as HTMLElement & { gridstack: GridStack }).gridstack
    for (const y of [2, 6, 12]) {
      const moving = grid.engine.nodes.find(n => n.id === 'wide')!
      grid.engine.beginUpdate(moving); Object.assign(moving, { _moving: true })
      grid.engine.moveNodeCheck(moving, { x: 2, y })
      Reflect.deleteProperty(moving, '_moving'); grid.engine.endUpdate()
      widgets = widgets.map(widget => {
        const node = grid.engine.nodes.find(n => n.id === widget.id)!
        return { ...widget, wide: { x: node.x!, y: node.y!, w: node.w!, h: node.h! } }
      })
      await wrapper.setProps({ widgets }); await flushPromises()
    }
    for (const next of [840, 1100, 840]) {
      width = next
      resize([{ contentRect: { width } }] as ResizeObserverEntry[], {} as ResizeObserver); await flushPromises()
      for (const widget of widgets) expect(grid.engine.nodes.find(n => n.id === widget.id)).toMatchObject(widget[width === 1100 ? 'wide' : 'medium'])
    }
    wrapper.unmount()
  })
})
