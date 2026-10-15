import { describe, expect, it } from 'vitest'
import { TimeSessionFillMode } from '@yumpoo/api-client'
import {
  atMinute, clampMove, clampResize, coveredMs, dayWindow, defaultMode, formatClock, freeInterval, gaps, HOUR, itemTotals, layout,
  MINUTE, overlapEffect, parseClock, parseDuration, placeSequential, rangeInGap, shares, snap, type DayBlock,
} from './dayModel'

const zone = 'Asia/Shanghai'
const day = dayWindow('2026-10-08', zone)
const at = (clock: string) => atMinute('2026-10-08', parseClock(clock)!, zone)
let sequence = 0
function block(start: string, end: string | null, item = `item-${++sequence}`, extra: Partial<DayBlock> = {}): DayBlock {
  return { id: `b-${++sequence}`, workItemId: item, projectId: 'project', title: item, itemNo: null, projectName: null, colorToken: null,
    start: at(start), end: end === null ? null : at(end), canEdit: end !== null, etag: '"0"', ...extra }
}

describe('dayModel', () => {
  it('splits width only inside clusters of overlapping blocks', () => {
    const a = block('09:00', '10:00'), b = block('09:30', '10:30'), c = block('11:00', '12:00'), d = block('10:00', '10:15')
    const result = layout([a, b, c, d], at('16:00'), day)
    expect(result.get(a.id)).toEqual({ lane: 0, lanes: 2 })
    expect(result.get(b.id)).toEqual({ lane: 1, lanes: 2 })
    expect(result.get(d.id)).toEqual({ lane: 0, lanes: 2 })
    expect(result.get(c.id)).toEqual({ lane: 0, lanes: 1 })
  })

  it('splits overlapping time evenly so the counted total equals the covered wall time', () => {
    const a = block('09:00', '11:00'), b = block('10:00', '12:00'), running = block('11:30', null)
    const now = at('12:30')
    const result = shares([a, b, running], now, day)
    expect(result.get(a.id)).toEqual({ allocatedMs: 1.5 * HOUR, maxParallel: 2 })
    expect(result.get(b.id)!.allocatedMs).toBe(1.25 * HOUR)
    expect(result.get(running.id)!.allocatedMs).toBe(0.75 * HOUR)
    const total = [...result.values()].reduce((sum, share) => sum + share.allocatedMs, 0)
    expect(total).toBe(coveredMs([a, b, running], now, day))
    expect(itemTotals([a, b, running], now, day).map(item => item.ms)).toEqual([1.5 * HOUR, 1.25 * HOUR, 0.75 * HOUR])
  })

  it('gives a short record its own lane without changing real overlap shares', () => {
    const short = block('10:00', '10:03'), next = block('10:03', '11:00')
    const blocks = [short, next], now = at('18:00')
    const result = layout(blocks, now, day)
    expect(result.get(short.id)).toEqual({ lane: 0, lanes: 2 })
    expect(result.get(next.id)).toEqual({ lane: 1, lanes: 2 })
    expect(shares(blocks, now, day).get(short.id)).toEqual({ allocatedMs: 3 * MINUTE, maxParallel: 1 })
    expect(coveredMs(blocks, now, day)).toBe(HOUR)
  })

  it('extends a cross-midnight short tail from the clipped day start', () => {
    const overnight = block('00:00', '00:05', 'overnight', { start: day.start - 2 * HOUR })
    const next = block('00:05', '01:00'), outside = block('00:00', '00:00', 'outside', { start: day.start - HOUR })
    const result = layout([overnight, next, outside], at('18:00'), day)
    expect(result.get(overnight.id)).toEqual({ lane: 0, lanes: 2 })
    expect(result.get(next.id)).toEqual({ lane: 1, lanes: 2 })
    expect(shares([overnight, next], at('18:00'), day).get(overnight.id)).toEqual({ allocatedMs: 5 * MINUTE, maxParallel: 1 })
  })

  it('clips sessions crossing midnight to the day', () => {
    const overnight = { ...block('09:00', '10:00'), start: day.start - 2 * HOUR }
    expect(shares([overnight], at('12:00'), day).get(overnight.id)!.allocatedMs).toBe(10 * HOUR)
    expect(gaps([overnight], at('12:00'), { start: day.start, end: at('12:00') })).toEqual([{ start: at('10:00'), end: at('12:00') }])
  })

  it('finds gaps with running blocks covering up to now', () => {
    const a = block('09:00', '10:00'), running = block('11:00', null)
    expect(gaps([a, running], at('11:30'), { start: at('08:00'), end: at('12:00') }))
      .toEqual([{ start: at('08:00'), end: at('09:00') }, { start: at('10:00'), end: at('11:00') }, { start: at('11:30'), end: at('12:00') }])
  })

  it('snaps from the local day start, including zones with quarter-hour offsets', () => {
    const kathmandu = dayWindow('2026-10-08', 'Asia/Kathmandu')
    const raw = kathmandu.start + 9 * HOUR + 8 * MINUTE
    expect(formatClock(snap(raw, kathmandu.start), 'Asia/Kathmandu')).toBe('09:15')
    expect(formatClock(snap(raw, kathmandu.start, 5 * MINUTE), 'Asia/Kathmandu')).toBe('09:10')
    expect(formatClock(day.end, zone, day.end)).toBe('24:00')
  })

  it('parses clocks and durations in the forms people type', () => {
    expect(['9', '930', '0930', '9:30', '09：30', '24:00'].map(parseClock)).toEqual([540, 570, 570, 570, 570, 1440])
    expect(['25', '9:75', 'abc', '24:30'].map(parseClock)).toEqual([null, null, null, null])
    expect(['1.5', '2h', '1h30', '1h30m', '90m', '1:30', '2小时', '45分钟'].map(parseDuration))
      .toEqual([90, 120, 90, 90, 90, 90, 120, 45].map(minutes => minutes * MINUTE))
    expect(['0', '', 'abc', '25h'].map(parseDuration)).toEqual([null, null, null, null])
  })

  it('describes each overlap mode before saving', () => {
    const editable = block('09:30', '10:00', 'a'), locked = block('09:00', '09:15', 'b', { canEdit: false })
    const effect = overlapEffect({ start: at('09:00'), end: at('10:30') }, ['target'], [editable, locked], at('12:00'))
    expect(effect.freeMs).toBe(45 * MINUTE)
    expect(effect.fullyCovered).toBe(false)
    expect(effect.parallelItems).toEqual(['a', 'b'])
    expect(effect.trimmed).toEqual([editable])
    expect(effect.kept).toEqual([locked])
    expect(defaultMode(effect)).toBe(TimeSessionFillMode.Gaps)
    const covered = overlapEffect({ start: at('09:30'), end: at('10:00') }, ['target'], [editable], at('12:00'))
    expect(covered.fullyCovered).toBe(true)
    expect(defaultMode(covered)).toBe(TimeSessionFillMode.Parallel)
  })

  it('keeps moves and resizes between neighbours it did not already overlap', () => {
    const before = block('08:00', '09:00'), moving = block('10:00', '11:00', 'x'), partner = block('10:30', '12:00')
    const after = block('13:00', '14:00'), sameItem = block('11:30', '11:45', 'x')
    const bounds = freeInterval(moving, [before, moving, partner, after, sameItem], at('16:00'), day)
    expect(bounds).toEqual({ start: at('09:00'), end: at('11:30') })
    expect(clampMove(moving, 2 * HOUR, bounds)).toEqual({ start: at('10:30'), end: at('11:30') })
    expect(clampMove(moving, -3 * HOUR, bounds)).toEqual({ start: at('09:00'), end: at('10:00') })
    expect(clampResize(moving, 'end', at('10:05'), bounds)).toEqual({ start: at('10:00'), end: at('10:15') })
    expect(clampResize(moving, 'start', at('08:30'), bounds)).toEqual({ start: at('09:00'), end: at('11:00') })
    expect(freeInterval(moving, [moving], at('10:45'), day).end).toBe(at('10:45'))
  })

  it('fits a dropped or clicked hour into the gap under the pointer', () => {
    const free = [{ start: at('09:00'), end: at('10:30') }, { start: at('11:00'), end: at('11:10') }]
    expect(rangeInGap(at('09:15'), HOUR, free)).toEqual({ start: at('09:15'), end: at('10:15') })
    expect(rangeInGap(at('10:00'), HOUR, free)).toEqual({ start: at('09:30'), end: at('10:30') })
    expect(rangeInGap(at('11:05'), HOUR, free)).toBeNull()
    expect(rangeInGap(at('10:45'), HOUR, free)).toBeNull()
  })

  it('places a duration across gaps from the start time and reports what does not fit', () => {
    const free = [{ start: at('08:00'), end: at('10:00') }, { start: at('11:00'), end: at('12:00') }, { start: at('13:00'), end: at('13:30') }]
    expect(placeSequential(2 * HOUR, free, at('09:00'))).toEqual({
      entries: [{ start: at('09:00'), end: at('10:00') }, { start: at('11:00'), end: at('12:00') }], leftoverMs: 0, truncated: false })
    expect(placeSequential(4 * HOUR, free, at('09:00')).leftoverMs).toBe(1.5 * HOUR)
    expect(placeSequential(4 * HOUR, free, at('09:00'), 1)).toEqual({ entries: [{ start: at('09:00'), end: at('10:00') }], leftoverMs: 3 * HOUR, truncated: true })
  })

  it('skips short gaps and reports a short unplaceable remainder', () => {
    const free = [{ start: at('09:00'), end: at('09:10') }, { start: at('10:00'), end: at('10:20') }]
    expect(placeSequential(25 * MINUTE, free, at('09:00'))).toEqual({
      entries: [{ start: at('10:00'), end: at('10:20') }], leftoverMs: 5 * MINUTE, truncated: false })
    expect(placeSequential(10 * MINUTE, free, at('09:00'))).toEqual({ entries: [], leftoverMs: 10 * MINUTE, truncated: false })
  })

  it('reserves at least fifteen minutes for the next gap instead of stranding a short tail', () => {
    const free = [{ start: at('09:00'), end: at('09:50') }, { start: at('10:00'), end: at('12:00') }]
    expect(placeSequential(HOUR, free, at('09:00'))).toEqual({
      entries: [{ start: at('09:00'), end: at('09:45') }, { start: at('10:00'), end: at('10:15') }], leftoverMs: 0, truncated: false })
    expect(placeSequential(HOUR, free, at('09:00'), 1)).toEqual({
      entries: [{ start: at('10:00'), end: at('11:00') }], leftoverMs: 0, truncated: false })
  })

  it('keeps a duration under two minimum slices whole when a later gap fits it', () => {
    const free = [{ start: at('09:00'), end: at('09:18') }, { start: at('10:00'), end: at('12:00') }]
    expect(placeSequential(20 * MINUTE, free, at('09:00'))).toEqual({
      entries: [{ start: at('10:00'), end: at('10:20') }], leftoverMs: 0, truncated: false })
    expect(placeSequential(20 * MINUTE, free.slice(0, 1), at('09:00'))).toEqual({
      entries: [{ start: at('09:00'), end: at('09:18') }], leftoverMs: 2 * MINUTE, truncated: false })
  })
})
