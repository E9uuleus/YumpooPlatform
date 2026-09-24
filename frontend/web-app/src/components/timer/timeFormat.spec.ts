import { describe, expect, it } from 'vitest'
import { companyDateTime, dockDuration, orbDuration, parseCompanyDateTime } from './timeFormat'

describe('company time tracking dates', () => {
  it('round trips across midnight in the company timezone independently of device timezone', () => {
    const instant = new Date('2026-09-06T18:12:34Z')
    const local = companyDateTime(instant, 'Asia/Shanghai')
    expect(local).toBe('2026-09-07T02:12:34')
    expect(parseCompanyDateTime(local, 'Asia/Shanghai')).toEqual(instant)
  })
  it('rejects a nonexistent time during the daylight saving gap', () => {
    expect(() => parseCompanyDateTime('2026-03-08T02:30:00', 'America/New_York')).toThrow()
  })
})

describe('compact timer durations', () => {
  it('keeps minutes and seconds within the first hour and switches to hours so it never reads as mm:ss', () => {
    expect(orbDuration(-5)).toBe('00:00')
    expect(orbDuration(1_508_999)).toBe('25:08')
    expect(orbDuration(3_600_000)).toBe('1h00')
    expect(orbDuration(5_100_000)).toBe('1h25')
    expect(orbDuration(36_300_000)).toBe('10h05')
  })
  it('stacks two short lines for the edge tab', () => {
    expect(dockDuration(0)).toEqual(['0m', '00s'])
    expect(dockDuration(1_508_000)).toEqual(['25m', '08s'])
    expect(dockDuration(5_100_000)).toEqual(['1h', '25m'])
  })
})
