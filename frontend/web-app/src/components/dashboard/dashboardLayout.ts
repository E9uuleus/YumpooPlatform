import type { DashboardPosition, DashboardWidget } from '@yumpoo/api-client'

/** Preserve chosen gaps unless an operation explicitly requests compaction. */
export function settleDashboardLayout(widgets: DashboardWidget[], compact = false): DashboardWidget[] {
  const result = widgets.map(widget => ({ ...widget, wide: { ...widget.wide }, medium: { ...widget.medium, h: widget.wide.h } }))
  for (const breakpoint of ['wide', 'medium'] as const) {
    const placed: DashboardPosition[] = []
    for (const widget of [...result].sort((a, b) => a[breakpoint].y - b[breakpoint].y || a[breakpoint].x - b[breakpoint].x)) {
      const position = widget[breakpoint]
      position.y = placed.reduce((y, above) => position.x < above.x + above.w && above.x < position.x + position.w
        ? Math.max(y, above.y + above.h) : y, compact ? 0 : position.y)
      placed.push(position)
    }
  }
  return result
}
