export interface DayBlock {
  id: string
  workItemId: string | null
  title: string
  itemNo: string | null
  projectName: string | null
  colorToken: string | null
  start: number
  end: number | null
  canEdit: boolean
  allocatedMs: number
  parallelItemCount: number
}

export function gaps(blocks: DayBlock[], dayStart: number, dayEnd: number, now: number): [number, number][] {
  const end = Math.min(dayEnd, now)
  const occupied = blocks.map(block => [Math.max(dayStart, block.start), Math.min(end, block.end ?? now)] as const)
    .filter(([start, stop]) => stop > start).sort((a, b) => a[0] - b[0])
  const result: [number, number][] = []
  let cursor = dayStart
  for (const [start, stop] of occupied) {
    if (start > cursor) result.push([cursor, start])
    cursor = Math.max(cursor, stop)
  }
  if (cursor < end) result.push([cursor, end])
  return result
}

export function lanes(blocks: DayBlock[], now: number): Map<string, number> {
  const ends: number[] = []
  const result = new Map<string, number>()
  for (const block of [...blocks].sort((a, b) => a.start - b.start || (a.end ?? now) - (b.end ?? now))) {
    let lane = ends.findIndex(end => end <= block.start)
    if (lane < 0) lane = ends.length
    ends[lane] = block.end ?? now
    result.set(block.id, lane)
  }
  return result
}

export function snap(ms: number, step = 300_000): number {
  return Math.round(ms / step) * step
}

export function placeSequential(rows: { workItemId: string; durationMs: number }[], free: [number, number][], startAt: number)
  : { entries: { workItemId: string; start: number; end: number }[]; leftoverMs: number } {
  const entries: { workItemId: string; start: number; end: number }[] = []
  let gap = 0
  let cursor = startAt
  let leftoverMs = 0
  for (const row of rows) {
    let remaining = row.durationMs
    while (remaining > 0 && gap < free.length) {
      const [start, end] = free[gap]!
      cursor = Math.max(cursor, start)
      if (cursor >= end) { gap++; continue }
      const duration = Math.min(remaining, end - cursor)
      entries.push({ workItemId: row.workItemId, start: cursor, end: cursor + duration })
      cursor += duration
      remaining -= duration
    }
    leftoverMs += remaining
  }
  return { entries, leftoverMs }
}
