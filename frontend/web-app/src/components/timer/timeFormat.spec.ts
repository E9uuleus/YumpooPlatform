import { describe, expect, it } from 'vitest'
import { companyDateTime, parseCompanyDateTime } from './timeFormat'

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
