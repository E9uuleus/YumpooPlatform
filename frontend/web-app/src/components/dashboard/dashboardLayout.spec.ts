import { describe, expect, it } from 'vitest'
import type { DashboardWidget } from '@yumpoo/api-client'
import { GridStackEngine } from 'gridstack/dist/gridstack-engine'
import { defaultConfiguration } from './dashboardModel'
import { settleDashboardLayout } from './dashboardLayout'

function valid(widgets: DashboardWidget[]) {
  for (const breakpoint of ['wide', 'medium'] as const) {
    for (const [index, widget] of widgets.entries()) {
      const a = widget[breakpoint]
      expect(a.h).toBe(widget.wide.h)
      expect(a.x + a.w).toBeLessThanOrEqual(breakpoint === 'wide' ? 12 : 6)
      for (const other of widgets.slice(index + 1)) {
        const b = other[breakpoint]
        expect(a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h).toBe(false)
      }
    }
  }
}

describe('dashboard collision and compaction', () => {
  it('moves the inactive breakpoint out of the way when the shared height grows and follows when it shrinks', () => {
    const widgets = defaultConfiguration().widgets
    widgets[0]!.wide.h = 10
    const grown = settleDashboardLayout(widgets, true)
    valid(grown)
    expect(grown[2]!.medium.y).toBe(10)
    expect(grown[4]!.wide.y).toBe(10)
    grown[0]!.wide.h = 3
    const shrunk = settleDashboardLayout(grown, true)
    valid(shrunk)
    expect(shrunk[2]!.medium.y).toBe(3)
    expect(shrunk[4]!.wide.y).toBe(4)
    expect(widgets[2]!.medium.y).toBe(4)
    expect(settleDashboardLayout(shrunk)).toEqual(shrunk)
  })
  it.each([6, 12])('persists all displaced engine nodes at %i columns across movement, growth, shrink and reload', columns => {
    let widgets = settleDashboardLayout(defaultConfiguration().widgets)
    const breakpoint = columns === 12 ? 'wide' : 'medium'
    const engine = new GridStackEngine({ column: columns, float: false })
    engine.batchUpdate()
    widgets.forEach(w => engine.addNode({ id: w.id, ...w[breakpoint] }))
    engine.batchUpdate(false)
    const moving = engine.nodes.find(n => n.id === widgets[0]!.id)!
    for (const position of [{ x: 3, y: 0, w: 3, h: 4 }, { w: 6, h: 12 }, { w: 3, h: 3 }]) {
      engine.beginUpdate(moving); engine.moveNode(moving, position); engine.endUpdate()
      widgets = settleDashboardLayout(widgets.map(widget => {
        const node = engine.nodes.find(n => n.id === widget.id)!
        return { ...widget, wide: { ...widget.wide, h: node.h! }, medium: { ...widget.medium, h: node.h! },
          [breakpoint]: { x: node.x!, y: node.y!, w: node.w!, h: node.h! } }
      }))
      valid(widgets)
      const restored = new GridStackEngine({ column: columns, float: false })
      restored.batchUpdate()
      widgets.forEach(w => restored.addNode({ id: w.id, ...w[breakpoint] }))
      restored.batchUpdate(false)
      for (const widget of widgets) expect(restored.nodes.find(n => n.id === widget.id)).toMatchObject(widget[breakpoint])
    }
  })
})
