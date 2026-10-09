import { describe, expect, it } from 'vitest'
import type { TeamTimesheet } from '@yumpoo/api-client'
import { buildTimesheet, formatHours, periodFor, periodLabel, rangeDays, shiftPeriod, toApiDate } from './teamDashboardModel'

describe('team dashboard periods', () => {
  it('builds Monday weeks, calendar months and quarters and steps between them', () => {
    expect(periodFor('WEEK', '2026-10-08')).toEqual({ kind: 'WEEK', from: '2026-10-05', to: '2026-10-11' })
    expect(periodFor('MONTH', '2026-02-10')).toEqual({ kind: 'MONTH', from: '2026-02-01', to: '2026-02-28' })
    expect(periodFor('QUARTER', '2026-11-30')).toEqual({ kind: 'QUARTER', from: '2026-10-01', to: '2026-12-31' })
    expect(shiftPeriod(periodFor('QUARTER', '2026-01-15'), -1)).toEqual({ kind: 'QUARTER', from: '2025-10-01', to: '2025-12-31' })
    expect(shiftPeriod({ kind: 'CUSTOM', from: '2026-10-01', to: '2026-10-10' }, 1)).toEqual({ kind: 'CUSTOM', from: '2026-10-11', to: '2026-10-20' })
    expect(periodLabel(periodFor('QUARTER', '2026-11-30'))).toBe('2026年第4季度')
    expect(rangeDays('2026-10-30', '2026-11-02')).toEqual(['2026-10-30', '2026-10-31', '2026-11-01', '2026-11-02'])
    expect(toApiDate('2026-10-01').toISOString().slice(0, 10)).toBe('2026-10-01')
  })
  it('groups entries by member and work item and keeps members without time', () => {
    const sheet = {
      from: toApiDate('2026-10-05'), to: toApiDate('2026-10-11'), timezone: 'Asia/Shanghai', asOf: new Date(),
      members: [{ userId: 'u2', displayName: '王五', active: true }, { userId: 'u1', displayName: '李四', active: false }],
      workItems: [{ id: 'w1', projectId: 'p', projectName: '交付', itemNo: 'DEL-1', title: '登录页' }],
      entries: [
        { userId: 'u1', workItemId: 'w1', date: toApiDate('2026-10-05'), durationMs: 5_400_000 },
        { userId: 'u1', workItemId: 'w1', date: toApiDate('2026-10-06'), durationMs: 1_800_000 },
      ],
    } as TeamTimesheet
    const { rows, dayTotals, totalMs } = buildTimesheet(sheet)
    expect(rows.map(row => [row.name, row.totalMs, row.children.length])).toEqual([['李四', 7_200_000, 1], ['王五', 0, 0]])
    expect(rows[0]!.children[0]).toMatchObject({ label: 'DEL-1 登录页', projectName: '交付', days: { '2026-10-05': 5_400_000, '2026-10-06': 1_800_000 } })
    expect([dayTotals['2026-10-05'], totalMs, formatHours(totalMs), formatHours(0)]).toEqual([5_400_000, 7_200_000, '2.0', ''])
  })
})
