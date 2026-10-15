import { TimeSessionFillMode } from '@yumpoo/api-client'
import { addDays } from '../../team/teamDashboardModel'
import { companyDateTime, parseCompanyDateTime } from '../../timer/timeFormat'

export const MINUTE = 60_000
export const HOUR = 60 * MINUTE
/** Drag and click snap; Alt switches to the fine step. */
export const SNAP_MS = 15 * MINUTE
export const FINE_SNAP_MS = 5 * MINUTE
/** Timeline scale: 15 minutes are 24px, the snap grid the canvas dots mark. */
export const PX_PER_MINUTE = 1.6
/** Shortest record the timeline creates by click, drop, drag or resize, and the shortest manual entry anywhere. */
export const MIN_BLOCK_MS = 15 * MINUTE
/** Shortest height a record is drawn at; layout counts short records at this length so they never cover a neighbour. */
export const MIN_VISUAL_MS = 15 * MINUTE
export const MAX_FILL_ENTRIES = 20
export const MAX_PARALLEL_ITEMS = 10

export interface DayBlock {
  id: string
  workItemId: string | null
  projectId: string | null
  title: string
  itemNo: string | null
  projectName: string | null
  colorToken: string | null
  start: number
  /** `null` while the timer is running; the block then ends at `now`. */
  end: number | null
  canEdit: boolean
  etag: string
}

export interface Range { start: number; end: number }
export interface BlockLayout { lane: number; lanes: number }
export interface BlockShare { allocatedMs: number; maxParallel: number }
export interface ItemTotal { workItemId: string; title: string; itemNo: string | null; projectName: string | null; colorToken: string | null; ms: number }

export const blockEnd = (block: DayBlock, now: number) => block.end ?? Math.max(block.start, now)

/** Instant of `minute` (0..1440) on the company-local `date`; 1440 is the next midnight. */
export function atMinute(date: string, minute: number, timezone: string): number {
  if (minute >= 1440) return parseCompanyDateTime(`${addDays(date, 1)}T00:00:00`, timezone).getTime()
  const hh = String(Math.floor(minute / 60)).padStart(2, '0'), mm = String(minute % 60).padStart(2, '0')
  return parseCompanyDateTime(`${date}T${hh}:${mm}:00`, timezone).getTime()
}

export function dayWindow(date: string, timezone: string): Range {
  return { start: atMinute(date, 0, timezone), end: atMinute(date, 1440, timezone) }
}

/** `HH:mm` in the company zone; the day's own end reads 24:00 instead of the next day's 00:00. */
export function formatClock(ms: number, timezone: string, dayEnd?: number): string {
  if (dayEnd !== undefined && ms >= dayEnd) return '24:00'
  return companyDateTime(new Date(ms), timezone).slice(11, 16)
}

/** Accepts `9`, `930`, `0930`, `9:30` and full-width colons; returns minutes of day or null. */
export function parseClock(text: string): number | null {
  const value = text.trim().replace('：', ':')
  const match = /^(\d{1,2})(?::?(\d{2}))?$/.exec(value)
  if (!match) return null
  const hours = Number(match[1]), minutes = Number(match[2] ?? 0)
  if (minutes > 59 || hours > 24 || (hours === 24 && minutes > 0)) return null
  return hours * 60 + minutes
}

/** Accepts `1.5`, `2h`, `1h30`, `1h30m`, `90m`, `1:30` and Chinese 小时/分钟 units; returns milliseconds or null. */
export function parseDuration(text: string): number | null {
  const value = text.trim().toLowerCase().replace(/\s+/g, '').replace('：', ':')
    .replace(/小时|时/g, 'h').replace(/分钟|分/g, 'm')
  let minutes: number | undefined
  const clock = /^(\d{1,2}):(\d{2})$/.exec(value)
  const hm = /^(?:(\d+(?:\.\d+)?)h)?(?:(\d+)m?)?$/.exec(value)
  if (clock) minutes = Number(clock[1]) * 60 + Number(clock[2])
  else if (/^\d+(?:\.\d+)?$/.test(value)) minutes = Math.round(Number(value) * 60)
  else if (/^\d+m$/.test(value)) minutes = Number(value.slice(0, -1))
  else if (hm && (hm[1] || hm[2]) && value.includes('h')) minutes = Math.round(Number(hm[1] ?? 0) * 60) + Number(hm[2] ?? 0)
  if (minutes === undefined || !Number.isFinite(minutes) || minutes <= 0 || minutes > 24 * 60) return null
  return minutes * MINUTE
}

export function durationLabel(ms: number): string {
  const minutes = Math.round(Math.max(0, ms) / MINUTE)
  if (!minutes && ms > 0) return '<1m'
  const hours = Math.floor(minutes / 60), rest = minutes % 60
  if (!hours) return `${rest}m`
  return rest ? `${hours}h${rest}m` : `${hours}h`
}

/** Snaps to `step` measured from `origin` (the local day start), so offsets like +05:45 still land on :00/:15. */
export function snap(ms: number, origin: number, step = SNAP_MS): number {
  return origin + Math.round((ms - origin) / step) * step
}

export function snapDown(ms: number, origin: number, step = SNAP_MS): number {
  return origin + Math.floor((ms - origin) / step) * step
}

export const overlaps = (a: Range, b: Range) => a.start < b.end && b.start < a.end

function spans(blocks: DayBlock[], now: number, window?: Range): (Range & { block: DayBlock })[] {
  return blocks.map(block => ({ block, start: block.start, end: blockEnd(block, now) }))
    .map(span => window ? { ...span, start: Math.max(span.start, window.start), end: Math.min(span.end, window.end) } : span)
    .filter(span => span.end > span.start)
}

/** Lanes per overlap cluster, so only blocks that actually overlap share the width; short blocks count at their drawn height. */
export function layout(blocks: DayBlock[], now: number): Map<string, BlockLayout> {
  const result = new Map<string, BlockLayout>()
  const sorted = spans(blocks, now).map(span => ({ ...span, end: Math.max(span.end, span.start + MIN_VISUAL_MS) }))
    .sort((a, b) => a.start - b.start || b.end - a.end)
  let cluster: { id: string; lane: number }[] = [], laneEnds: number[] = [], clusterEnd = -Infinity
  const close = () => {
    cluster.forEach(entry => result.set(entry.id, { lane: entry.lane, lanes: laneEnds.length }))
    cluster = []; laneEnds = []; clusterEnd = -Infinity
  }
  for (const span of sorted) {
    if (span.start >= clusterEnd) close()
    let lane = laneEnds.findIndex(end => end <= span.start)
    if (lane < 0) lane = laneEnds.length
    laneEnds[lane] = span.end
    cluster.push({ id: span.block.id, lane })
    clusterEnd = Math.max(clusterEnd, span.end)
  }
  close()
  for (const block of blocks) if (!result.has(block.id)) result.set(block.id, { lane: 0, lanes: 1 })
  return result
}

/** Elementary segments between every boundary, each with the blocks covering it — the client mirror of the server share table. */
function segments(blocks: DayBlock[], now: number, window: Range): { start: number; end: number; covering: DayBlock[] }[] {
  const list = spans(blocks, now, window)
  const cuts = [...new Set(list.flatMap(span => [span.start, span.end]))].sort((a, b) => a - b)
  const result: { start: number; end: number; covering: DayBlock[] }[] = []
  for (let i = 0; i + 1 < cuts.length; i++) {
    const start = cuts[i]!, end = cuts[i + 1]!
    const covering = list.filter(span => span.start <= start && span.end >= end).map(span => span.block)
    if (covering.length) result.push({ start, end, covering })
  }
  return result
}

/** Per-block time counted inside `window` after the 1/N split, and the most items it ever runs alongside. */
export function shares(blocks: DayBlock[], now: number, window: Range): Map<string, BlockShare> {
  const result = new Map<string, BlockShare>(blocks.map(block => [block.id, { allocatedMs: 0, maxParallel: 1 }]))
  for (const segment of segments(blocks, now, window)) {
    for (const block of segment.covering) {
      const share = result.get(block.id)!
      share.allocatedMs += (segment.end - segment.start) / segment.covering.length
      share.maxParallel = Math.max(share.maxParallel, segment.covering.length)
    }
  }
  return result
}

/** Counted time per work item inside `window`, largest first. */
export function itemTotals(blocks: DayBlock[], now: number, window: Range): ItemTotal[] {
  const allocated = shares(blocks, now, window)
  const totals = new Map<string, ItemTotal>()
  for (const block of blocks) {
    const ms = allocated.get(block.id)?.allocatedMs ?? 0
    if (!block.workItemId || ms <= 0) continue
    const total = totals.get(block.workItemId)
    if (total) total.ms += ms
    else totals.set(block.workItemId, { workItemId: block.workItemId, title: block.title, itemNo: block.itemNo, projectName: block.projectName, colorToken: block.colorToken, ms })
  }
  return [...totals.values()].sort((a, b) => b.ms - a.ms)
}

/** Wall-clock time covered by at least one block inside `window`. */
export function coveredMs(blocks: DayBlock[], now: number, window: Range): number {
  return segments(blocks, now, window).reduce((sum, segment) => sum + segment.end - segment.start, 0)
}

/** Uncovered intervals inside `window`; running blocks cover up to `now`. */
export function gaps(blocks: DayBlock[], now: number, window: Range): Range[] {
  if (window.end <= window.start) return []
  const result: Range[] = []
  let cursor = window.start
  for (const span of spans(blocks, now, window).sort((a, b) => a.start - b.start)) {
    if (span.start > cursor) result.push({ start: cursor, end: span.start })
    cursor = Math.max(cursor, span.end)
  }
  if (cursor < window.end) result.push({ start: cursor, end: window.end })
  return result
}

const length = (ranges: Range[]) => ranges.reduce((sum, range) => sum + range.end - range.start, 0)

export interface OverlapEffect {
  overlapping: DayBlock[]
  /** Part of the range with no record at all. */
  freeMs: number
  fullyCovered: boolean
  /** Per mode: created time, and for REPLACE the blocks that are trimmed or deleted and the ones that stay parallel. */
  gapsMs: number
  parallelItems: string[]
  trimmed: DayBlock[]
  kept: DayBlock[]
}

/** Mirrors TimeSessionFillPlanner so the composer can describe each mode before saving. */
export function overlapEffect(range: Range, itemIds: string[], blocks: DayBlock[], now: number): OverlapEffect {
  const overlapping = blocks.filter(block => overlaps(range, { start: block.start, end: blockEnd(block, now) }))
  const free = gaps(blocks, now, range)
  const others = overlapping.filter(block => !block.workItemId || !itemIds.includes(block.workItemId))
  const parallelItems = [...new Set(others.map(block => block.title))]
  const replaceable = (block: DayBlock) => block.canEdit && block.end !== null
  return {
    overlapping,
    freeMs: length(free),
    fullyCovered: overlapping.length > 0 && length(free) === 0,
    gapsMs: length(free),
    parallelItems,
    trimmed: others.filter(replaceable),
    kept: others.filter(block => !replaceable(block)),
  }
}

/** Default mode for a new range: silent GAPS without overlap, GAPS with overlap, PARALLEL when GAPS would add nothing. */
export function defaultMode(effect: OverlapEffect): TimeSessionFillMode {
  return effect.fullyCovered ? TimeSessionFillMode.Parallel : TimeSessionFillMode.Gaps
}

/** Bounds a block may move or resize within: neighbours it did not overlap when the drag began, same-item blocks, the day and now. */
export function freeInterval(block: DayBlock, blocks: DayBlock[], now: number, window: Range): Range {
  const own = { start: block.start, end: blockEnd(block, now) }
  let start = window.start, end = Math.min(window.end, now)
  for (const other of blocks) {
    if (other.id === block.id) continue
    const span = { start: other.start, end: blockEnd(other, now) }
    const sameItem = !!block.workItemId && other.workItemId === block.workItemId
    if (!sameItem && overlaps(own, span)) continue
    if (span.end <= own.start) start = Math.max(start, span.end)
    else if (span.start >= own.end) end = Math.min(end, span.start)
  }
  return { start, end }
}

export function clampMove(block: DayBlock, delta: number, bounds: Range): Range {
  const end = blockEnd(block, 0)
  const shift = Math.min(Math.max(delta, bounds.start - block.start), bounds.end - end)
  return { start: block.start + shift, end: end + shift }
}

export function clampResize(block: DayBlock, edge: 'start' | 'end', value: number, bounds: Range, minimum = MIN_BLOCK_MS): Range {
  const end = blockEnd(block, 0)
  if (edge === 'start') return { start: Math.min(Math.max(value, bounds.start), end - minimum), end }
  return { start: block.start, end: Math.max(Math.min(value, bounds.end), block.start + minimum) }
}

/** A `duration`-long range starting at `at` inside the gap holding it, shifted up near the gap's end; null when under the minimum fits. */
export function rangeInGap(at: number, duration: number, free: Range[]): Range | null {
  const gap = free.find(range => range.start <= at && at < range.end)
  if (!gap) return null
  let start = Math.max(at, gap.start)
  const end = Math.min(start + duration, gap.end)
  if (end - start < duration) start = Math.max(gap.start, end - duration)
  return end - start >= MIN_BLOCK_MS ? { start, end } : null
}

/** Lays `durationMs` into the free ranges from `startAt`, splitting across gaps; skips gaps under `minimum` and leaves a shorter tail over; capped at `maxSlices` pieces. */
export function placeSequential(durationMs: number, free: Range[], startAt: number, maxSlices = MAX_FILL_ENTRIES, minimum = MIN_BLOCK_MS)
  : { entries: Range[]; leftoverMs: number; truncated: boolean } {
  const entries: Range[] = []
  let remaining = durationMs
  for (const gap of free) {
    if (remaining < minimum) break
    const start = Math.max(gap.start, startAt)
    if (gap.end - start < minimum) continue
    if (entries.length === maxSlices) return { entries, leftoverMs: remaining, truncated: true }
    const end = Math.min(gap.end, start + remaining)
    entries.push({ start, end })
    remaining -= end - start
  }
  return { entries, leftoverMs: Math.max(0, remaining), truncated: false }
}
