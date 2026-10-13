export type TimeLogPlacement = 'bottom' | 'top'

/** Popover offset plus arrow between the cell edge and the panel. */
const GAP = 26

/** Prefers below; opens above only when below cannot fit and above has more room. `keep` pins the side while open. */
export function timeLogPlacement(anchor: { top: number; bottom: number }, viewportHeight: number, height: number, keep?: TimeLogPlacement) {
  const below = viewportHeight - Math.max(0, anchor.bottom) - GAP
  const above = Math.min(viewportHeight, anchor.top) - GAP
  const placement: TimeLogPlacement = keep ?? (below >= height || below >= above ? 'bottom' : 'top')
  return { placement, maxHeight: Math.max(0, Math.floor(placement === 'bottom' ? below : above)) }
}
