import type { TimerDockSide, TimerOrbLayout, TimerOrbSize } from '@yumpoo/preload-contract'

export interface Rect { x: number; y: number; width: number; height: number }

/** Native shapes clip painting too, so every surface keeps its soft shadow inside this margin. */
export const SHADOW_PAD = 10
export const PANEL_MARGIN = 12
export const ORB_SIZES: Readonly<Record<TimerOrbSize, number>> = { small: 56, medium: 64, large: 80 }
export const CAPSULE_WIDTH = 216
export const DOCK_TAB = { width: 32, height: 112 } as const
export const DOCK_CARD = { width: 288, height: 152 } as const
export const PANEL = { width: 360 + PANEL_MARGIN * 2, height: 480 + PANEL_MARGIN * 2 } as const
export const MENU = { width: 288 + PANEL_MARGIN * 2, height: 372 + PANEL_MARGIN * 2 } as const

const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(value, max))

export function clampRect(rect: Rect, area: Rect): Rect {
  const width = Math.min(rect.width, area.width), height = Math.min(rect.height, area.height)
  return { width, height, x: clamp(Math.round(rect.x), area.x, area.x + area.width - width), y: clamp(Math.round(rect.y), area.y, area.y + area.height - height) }
}

/** One rectangle per pixel row of a pill; a circle is the case where length equals height. */
export function stadiumRows(x0: number, top: number, length: number, height: number): Rect[] {
  const radius = height / 2
  return Array.from({ length: height }, (_, y) => {
    const half = Math.sqrt(Math.max(0, radius ** 2 - (y + .5 - radius) ** 2)), inset = radius - half
    return { x: Math.floor(x0 + inset), y: top + y, width: Math.max(1, Math.ceil(length - 2 * inset)), height: 1 }
  })
}

export const hitTest = (rects: readonly Rect[], x: number, y: number) =>
  rects.some(rect => x >= rect.x && x < rect.x + rect.width && y >= rect.y && y < rect.y + rect.height)

/** Places the orb so its visible circle starts at `circleX` and ends at `circleBottom`, reserving transparent room for the capsule on each side. */
export function placeOrb(requestedSize: number, circleX: number, circleBottom: number, area: Rect): { bounds: Rect; layout: TimerOrbLayout } {
  const size = Math.min(requestedSize, area.width - SHADOW_PAD * 2, area.height - SHADOW_PAD * 2)
  const slot = size + SHADOW_PAD * 2
  const x = clamp(Math.round(circleX) - SHADOW_PAD, area.x, area.x + area.width - slot)
  const y = clamp(Math.round(circleBottom) + SHADOW_PAD - slot, area.y, area.y + area.height - slot)
  const left = Math.min(CAPSULE_WIDTH, x - area.x), right = Math.min(CAPSULE_WIDTH, area.x + area.width - x - slot)
  const canvas = { left: left + SHADOW_PAD, top: SHADOW_PAD, width: left + slot + right, height: slot }
  return { bounds: { x: x - left, y, width: canvas.width, height: slot }, layout: { size, side: null, detailWidth: 0, canvas } }
}

/** Opens the capsule toward the roomier side; its width never exceeds the reserved canvas. */
export function orbDetails(layout: TimerOrbLayout, open: boolean): TimerOrbLayout {
  const canvas = layout.canvas!
  const left = canvas.left - SHADOW_PAD, right = canvas.width - canvas.left - layout.size - SHADOW_PAD
  const side = open && Math.max(left, right) > 0 ? (left >= right ? 'left' : 'right') : null
  return { ...layout, side, detailWidth: side ? Math.max(left, right) : 0 }
}

export function orbShape(layout: TimerOrbLayout): Rect[] {
  const canvas = layout.canvas!
  const slot = layout.size + SHADOW_PAD * 2
  return stadiumRows(canvas.left - SHADOW_PAD - (layout.side === 'left' ? layout.detailWidth : 0), 0, slot + layout.detailWidth, slot)
}

export function placeDock(side: TimerDockSide, dockY: number, area: Rect, expanded = false): { bounds: Rect; layout: TimerOrbLayout } {
  const width = Math.min(DOCK_CARD.width + SHADOW_PAD, area.width), height = Math.min(DOCK_CARD.height + SHADOW_PAD * 2, area.height)
  const y = clamp(Math.round(area.y + dockY * area.height - height / 2), area.y, area.y + area.height - height)
  const x = side === 'left' ? area.x : area.x + area.width - width
  const canvas = { left: side === 'left' ? 0 : SHADOW_PAD, top: SHADOW_PAD, width, height }
  return { bounds: { x, y, width, height }, layout: { size: DOCK_TAB.height, side: null, detailWidth: 0, canvas, dock: { side, expanded } } }
}

export function dockShape(layout: TimerOrbLayout): Rect[] {
  const canvas = layout.canvas!, dock = layout.dock!
  if (dock.expanded) return [{ x: 0, y: 0, width: canvas.width, height: canvas.height }]
  const width = DOCK_TAB.width + SHADOW_PAD
  return [{ x: dock.side === 'right' ? canvas.width - width : 0, y: Math.round((canvas.height - DOCK_TAB.height) / 2) - SHADOW_PAD, width, height: DOCK_TAB.height + SHADOW_PAD * 2 }]
}

/** Snaps a dragged dock to the edge nearest its tab and keeps the vertical position as a fraction of the work area. */
export function snapDock(bounds: Rect, side: TimerDockSide, area: Rect): { side: TimerDockSide; dockY: number } {
  const tab = side === 'right' ? bounds.x + bounds.width - DOCK_TAB.width / 2 : bounds.x + DOCK_TAB.width / 2
  const next = tab < area.x + area.width / 2 ? 'left' : 'right'
  const y = clamp(bounds.y, area.y, area.y + area.height - bounds.height)
  return { side: next, dockY: clamp((y + bounds.height / 2 - area.y) / area.height, 0, 1) }
}

/** Visible rectangle of the compact surface in screen coordinates. */
export function compactVisual(bounds: Rect, layout: TimerOrbLayout): Rect {
  const canvas = layout.canvas ?? { left: 0, top: 0, width: bounds.width, height: bounds.height }
  if (layout.dock) {
    const top = bounds.y + Math.round((canvas.height - DOCK_TAB.height) / 2)
    return { x: layout.dock.side === 'right' ? bounds.x + bounds.width - DOCK_TAB.width : bounds.x, y: top, width: DOCK_TAB.width, height: DOCK_TAB.height }
  }
  return { x: bounds.x + canvas.left, y: bounds.y + canvas.top, width: layout.size, height: layout.size }
}

/** Aligns the work panel with the compact surface: bottom-right for the orb, vertically centred beside a dock. */
export function placePanel(anchor: Rect, area: Rect, dock?: TimerDockSide): Rect {
  const width = PANEL.width, height = PANEL.height
  const x = dock === 'left' ? anchor.x - PANEL_MARGIN : anchor.x + anchor.width + PANEL_MARGIN - width
  const y = dock ? anchor.y + anchor.height / 2 - height / 2 : anchor.y + anchor.height + PANEL_MARGIN - height
  return clampRect({ x, y, width, height }, area)
}

export function panelVisual(bounds: Rect): Rect {
  return { x: bounds.x + PANEL_MARGIN, y: bounds.y + PANEL_MARGIN, width: bounds.width - PANEL_MARGIN * 2, height: bounds.height - PANEL_MARGIN * 2 }
}

/**
 * Tray menus open against the taskbar edge (inferred from the work area because tray bounds are unreliable for
 * overflow icons); surface menus open at the pointer and flip when they would overflow.
 */
export function placeMenu(kind: 'tray' | 'pointer', cursor: { x: number; y: number }, display: { bounds: Rect; workArea: Rect }): Rect {
  const { width, height } = MENU, work = display.workArea, screen = display.bounds
  let x: number, y: number
  if (kind === 'tray') {
    const edge = work.y > screen.y ? 'top' : work.x > screen.x ? 'left' : work.x + work.width < screen.x + screen.width ? 'right' : 'bottom'
    x = edge === 'left' ? work.x : edge === 'right' ? work.x + work.width - width : cursor.x - width / 2
    y = edge === 'top' ? work.y : edge === 'bottom' ? work.y + work.height - height : cursor.y - height / 2
  } else {
    x = cursor.x - PANEL_MARGIN + 2
    y = cursor.y - PANEL_MARGIN + 2
    if (x + width > work.x + work.width) x = cursor.x - width + PANEL_MARGIN - 2
    if (y + height > work.y + work.height) y = cursor.y - height + PANEL_MARGIN - 2
  }
  return clampRect({ x, y, width, height }, work)
}
