export type TimeLogPlacement = 'bottom' | 'top'

/** Popover offset plus arrow between the cell edge and the panel. */
const GAP = 26
const MIN_HEIGHT = 240

/** Prefers below; opens above only when below cannot fit and above has more room. `keep` pins the side while open. */
export function timeLogPlacement(anchor: { top: number; bottom: number }, viewportHeight: number, height: number, keep?: TimeLogPlacement) {
  const below = viewportHeight - anchor.bottom - GAP
  const above = anchor.top - GAP
  const placement: TimeLogPlacement = keep ?? (below >= height || below >= above ? 'bottom' : 'top')
  return { placement, maxHeight: Math.max(MIN_HEIGHT, Math.floor(placement === 'bottom' ? below : above)) }
}
