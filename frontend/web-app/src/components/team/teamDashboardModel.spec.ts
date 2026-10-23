import { describe, expect, it } from 'vitest'
import { CalendarDayType, type TeamTimesheet } from '@yumpoo/api-client'
import { buildTimesheet, dayBreakdown, hoursMinutes, periodFor, periodLabel, projectColors, rangeDays, restrictTimesheet, shiftPeriod, shortHours, timesheetColors, timesheetColumns, toApiDate } from './teamDashboardModel'

describe('team dashboard periods', () => {
  it('restricts member, item and daily totals to Saturday and Sunday', () => {
    const sheet: TeamTimesheet = {
      from: toApiDate('2026-10-05'), to: toApiDate('2026-10-11'), timezone: 'Asia/Shanghai', asOf: new Date(), days: [],
      members: [{ userId: 'u1', displayName: '李四', active: true }, { userId: 'u2', displayName: '王五', active: true }],
      workItems: [{ id: 'w1', projectId: 'p', projectName: '交付', itemNo: 'DEL-1', title: '周内任务' },
        { id: 'w2', projectId: 'p', projectName: '交付', itemNo: 'DEL-2', title: '周末任务' }],
      entries: [
        { userId: 'u1', workItemId: 'w1', date: toApiDate('2026-10-05'), durationMs: 28_800_000 },
        { userId: 'u1', workItemId: 'w2', date: toApiDate('2026-10-10'), durationMs: 3_600_000 },
        { userId: 'u2', workItemId: 'w2', date: toApiDate('2026-10-11'), durationMs: 7_200_000 },
      ],
    }
    const columns = timesheetColumns(sheet, true)
    expect(columns.map(column => column.day)).toEqual(['2026-10-10', '2026-10-11'])
    expect(columns.every(column => column.rest)).toBe(true)
    expect(timesheetColumns(sheet).filter(column => column.rest)).toEqual(columns)
    const model = restrictTimesheet(buildTimesheet(sheet), columns.map(column => column.day))
    expect(model.rows.map(row => row.totalMs)).toEqual([3_600_000, 7_200_000])
    expect(model.rows[0]!.children.map(child => child.subjectKey)).toEqual(['w2'])
    expect(model.rows[0]!.children[0]!.totalMs).toBe(3_600_000)
    expect(model.dayTotals).toEqual({ '2026-10-10': 3_600_000, '2026-10-11': 7_200_000 })
    expect(model.totalMs).toBe(10_800_000)
    sheet.days = [{ date: toApiDate('2026-10-10'), dayType: CalendarDayType.Workday, note: '补班', standardMinutes: 480 },
      { date: toApiDate('2026-10-06'), dayType: CalendarDayType.Holiday, note: '国庆节', standardMinutes: 0 }]
    expect(timesheetColumns(sheet, true).map(column => [column.day, column.holiday]))
      .toEqual([['2026-10-06', true], ['2026-10-11', false]])
  })
  it('builds Monday weeks, calendar months and quarters and steps between them', () => {
    expect(periodFor('WEEK', '2026-10-08')).toEqual({ kind: 'WEEK', from: '2026-10-05', to: '2026-10-11' })
    expect(periodFor('MONTH', '2026-02-10')).toEqual({ kind: 'MONTH', from: '2026-02-01', to: '2026-02-28' })
    expect(periodFor('QUARTER', '2026-11-30')).toEqual({ kind: 'QUARTER', from: '2026-10-01', to: '2026-12-31' })
    expect(shiftPeriod(periodFor('QUARTER', '2026-01-15'), -1)).toEqual({ kind: 'QUARTER', from: '2025-10-01', to: '2025-12-31' })
    expect(periodLabel(periodFor('QUARTER', '2026-11-30'))).toBe('2026年第4季度')
    expect(rangeDays('2026-10-30', '2026-11-02')).toEqual(['2026-10-30', '2026-10-31', '2026-11-01', '2026-11-02'])
    expect(toApiDate('2026-10-01').toISOString().slice(0, 10)).toBe('2026-10-01')
  })
  it('adds non-project category time as children of the 非项目工时 group, after project work', () => {
    const sheet: TeamTimesheet = {
      from: toApiDate('2026-10-05'), to: toApiDate('2026-10-05'), timezone: 'Asia/Shanghai', asOf: new Date(), days: [],
      members: [{ userId: 'u1', displayName: '李四', active: true }],
      workItems: [{ id: 'w1', projectId: 'p', projectName: '交付', itemNo: 'DEL-1', title: '任务' }],
      entries: [{ userId: 'u1', workItemId: 'w1', date: toApiDate('2026-10-05'), durationMs: 1_800_000 }],
      categoryEntries: [{ userId: 'u1', timeCategory: 'MEETING' as never, date: toApiDate('2026-10-05'), durationMs: 3_600_000 }],
    }
    const model = buildTimesheet(sheet)
    expect(model.totalMs).toBe(5_400_000)
    expect(model.rows[0]!.children.map(child => [child.subjectKey, child.projectName, child.label])).toEqual([
      ['w1', '交付', 'DEL-1 任务'], ['category:MEETING', '非项目工时', '会议']])
    expect(dayBreakdown(model.rows[0]!, '2026-10-05').map(project => project.projectName)).toEqual(['非项目工时', '交付'])
    expect(timesheetColors(sheet).get('__non_project__')).toBe('var(--yp-label-american-gray)')
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
    expect([dayTotals['2026-10-05'], totalMs]).toEqual([5_400_000, 7_200_000])
  })
  it('shows short hours without rounding recorded time down to zero', () => {
    expect([8 * 3_600_000, 6.5 * 3_600_000, 0.95 * 3_600_000, 30_000, 0].map(shortHours)).toEqual(['8', '6.5', '1', '0.1', '0'])
  })
  it('shows exact hours and minutes for drill-downs, flooring like the timer', () => {
    expect([0, 30_000, 45 * 60_000 + 59_000, 2 * 3_600_000 + 5 * 60_000 + 40_000, 3 * 3_600_000].map(hoursMinutes))
      .toEqual(['0m', '< 1m', '45m', '2h 05m', '3h 00m'])
  })
  it('groups one member day by project with larger totals first', () => {
    const sheet = {
      from: toApiDate('2026-10-05'), to: toApiDate('2026-10-11'), timezone: 'Asia/Shanghai', asOf: new Date(),
      members: [{ userId: 'u1', displayName: '李四', active: true }],
      workItems: [{ id: 'w1', projectId: 'p1', projectName: '交付', itemNo: 'DEL-1', title: '登录页' },
        { id: 'w2', projectId: 'p2', projectName: '运营', itemNo: 'OPS-1', title: '巡检' },
        { id: 'w3', projectId: 'p2', projectName: '运营', itemNo: 'OPS-2', title: '值班' }],
      entries: [
        { userId: 'u1', workItemId: 'w1', date: toApiDate('2026-10-05'), durationMs: 3_600_000 },
        { userId: 'u1', workItemId: 'w2', date: toApiDate('2026-10-05'), durationMs: 1_800_000 },
        { userId: 'u1', workItemId: 'w3', date: toApiDate('2026-10-05'), durationMs: 2_700_000 },
        { userId: 'u1', workItemId: 'w1', date: toApiDate('2026-10-06'), durationMs: 7_200_000 },
        { userId: 'u1', workItemId: 'missing', date: toApiDate('2026-10-06'), durationMs: 600_000 },
      ],
    } as TeamTimesheet
    const row = buildTimesheet(sheet).rows[0]!
    expect(dayBreakdown(row, '2026-10-05').map(project => [project.projectName, project.totalMs, project.items.map(item => item.itemNo)]))
      .toEqual([['运营', 4_500_000, ['OPS-2', 'OPS-1']], ['交付', 3_600_000, ['DEL-1']]])
    expect(dayBreakdown(row, '2026-10-06').map(project => project.projectName)).toEqual(['交付', '未知项目'])
    expect(dayBreakdown(row, '2026-10-07')).toEqual([])
  })
  it('gives up to ten projects distinct preset colours regardless of input order', () => {
    const ids = Array.from({ length: 10 }, (_, index) => `project-${index}`)
    const colors = projectColors([...ids, 'project-0'])
    expect(new Set(colors.values()).size).toBe(10)
    expect(projectColors([...ids].reverse())).toEqual(colors)
  })
})
