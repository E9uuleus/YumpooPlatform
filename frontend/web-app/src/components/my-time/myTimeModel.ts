import { addDays, toApiDate, type TimesheetRow } from '../team/teamDashboardModel'

/** Reference workday for the cell fill level; longer days fill the cell completely. */
export const STANDARD_DAY_MS = 8 * 3_600_000
export const weekdayLabels = ['一', '二', '三', '四', '五', '六', '日'] as const

export interface CalendarCell {
  date: string
  day: number
  row: number
  column: number
  inMonth: boolean
  weekend: boolean
  future: boolean
  today: boolean
}

export interface DaySegment { projectId: string; ms: number }
export interface MonthStats { totalMs: number; recordedDays: number; averageMs: number }
export interface ProjectShare { projectId: string; projectName: string; ms: number }

/** Monday-first 6×7 grid around `month` (`YYYY-MM`); the company calendar only has Monday week starts. */
export function monthGrid(month: string, today: string): CalendarCell[] {
  const first = `${month}-01`
  const start = addDays(first, -((toApiDate(first).getUTCDay() + 6) % 7))
  return Array.from({ length: 42 }, (_, index) => {
    const date = addDays(start, index)
    return { date, day: Number(date.slice(8)), row: Math.floor(index / 7), column: index % 7, inMonth: date.startsWith(month),
      weekend: index % 7 >= 5, future: date > today, today: date === today }
  })
}

export function shiftMonth(month: string, step: number): string {
  const [year, value] = month.split('-').map(Number)
  return new Date(Date.UTC(year!, value! - 1 + step, 1)).toISOString().slice(0, 7)
}

export const monthLabel = (month: string) => `${month.slice(0, 4)}年${Number(month.slice(5))}月`

/** Per-day project totals for the stacked bar under each cell, larger first. */
export function daySegments(row: TimesheetRow | undefined): Record<string, DaySegment[]> {
  const result: Record<string, DaySegment[]> = {}
  for (const item of row?.children ?? []) {
    for (const [date, ms] of Object.entries(item.days)) {
      const segments = result[date] ??= []
      const segment = segments.find(value => value.projectId === item.projectId)
      if (segment) segment.ms += ms
      else segments.push({ projectId: item.projectId, ms })
    }
  }
  Object.values(result).forEach(segments => segments.sort((a, b) => b.ms - a.ms))
  return result
}

export function monthStats(days: Record<string, number>, month: string): MonthStats {
  let totalMs = 0
  let recordedDays = 0
  for (const [date, ms] of Object.entries(days)) {
    if (!date.startsWith(month) || ms <= 0) continue
    totalMs += ms
    recordedDays += 1
  }
  return { totalMs, recordedDays, averageMs: recordedDays ? totalMs / recordedDays : 0 }
}

/** Month totals per project, used as the colour legend. */
export function monthProjects(row: TimesheetRow | undefined, month: string): ProjectShare[] {
  const projects = new Map<string, ProjectShare>()
  for (const item of row?.children ?? []) {
    for (const [date, ms] of Object.entries(item.days)) {
      if (!date.startsWith(month)) continue
      const project = projects.get(item.projectId)
      if (project) project.ms += ms
      else projects.set(item.projectId, { projectId: item.projectId, projectName: item.projectName || '未知项目', ms })
    }
  }
  return [...projects.values()].sort((a, b) => b.ms - a.ms)
}

/** Running time after the sheet's `asOf`; the server already counted everything before it. */
export function runningExtraMs(asOf: Date | undefined, startedAt: Date | undefined, now: number): number {
  return asOf && startedAt ? Math.max(0, now - Math.max(asOf.getTime(), startedAt.getTime())) : 0
}
