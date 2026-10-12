import { describe, expect, it } from 'vitest'
import { describeCron, parseCron, toCron } from './scheduledJobPresentation'

describe('定时任务规则', () => {
  it('每天与间隔规则往返转换，未知规则按原文显示', () => {
    const daily = { kind: 'daily' as const, time: '02:30', hours: 6 }
    const interval = { kind: 'interval' as const, time: '02:00', hours: 6 }
    expect(toCron(daily)).toBe('0 30 2 * * *')
    expect(parseCron(toCron(daily))).toEqual(daily)
    expect(describeCron(toCron(daily))).toBe('每天 02:30')
    expect(toCron(interval)).toBe('0 0 */6 * * *')
    expect(parseCron(toCron(interval))).toEqual(interval)
    expect(describeCron(toCron(interval))).toBe('每 6 小时')
    expect(describeCron('0 0 9 * * MON-FRI')).toBe('0 0 9 * * MON-FRI')
  })
})
