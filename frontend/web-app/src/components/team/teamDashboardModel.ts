import { WorkItemLabelColorToken, type TeamTimesheet } from '@yumpoo/api-client'
import { formatDateOnly } from '../../design-system/dates'
import { workItemLabelColorValue } from '../projects/workItemLabelColors'
import { categoryKey, NON_PROJECT_COLOR, NON_PROJECT_KEY, NON_PROJECT_LABEL, timeCategory } from '../timer/timeCategories'

export type PeriodKind = 'WEEK' | 'MONTH' | 'QUARTER'
/** Inclusive company-calendar dates as `YYYY-MM-DD`. */
export interface Period { kind: PeriodKind; from: string; to: string }
export const MAX_RANGE_DAYS = 93
export const periodOptions = [{ value: 'WEEK', label: '周' }, { value: 'MONTH', label: '月' }, { value: 'QUARTER', label: '季' }] as const
export type TeamView = 'TIMESHEET' | 'OVERTIME' | 'WORKLOAD'

const DAY = 86_400_000
const weekdays = ['日', '一', '二', '三', '四', '五', '六']
/** Generated date fields serialize with `toISOString()`, so calendar dates travel as UTC midnight. */
export const toApiDate = (date: string) => new Date(`${date}T00:00:00Z`)
export const fromApiDate = (date: Date) => date.toISOString().slice(0, 10)
export const addDays = (date: string, days: number) => fromApiDate(new Date(toApiDate(date).getTime() + days * DAY))
export const todayIn = (timezone: string, now = new Date()) => formatDateOnly(now, timezone)
export const weekday = (date: string) => weekdays[toApiDate(date).getUTCDay()]!

export function rangeDays(from: string, to: string): string[] {
  const days: string[] = []
  for (let day = from; day <= to && days.length <= MAX_RANGE_DAYS; day = addDays(day, 1)) days.push(day)
  return days
}

export function timesheetColumns(sheet: TeamTimesheet, overtime = false) {
  const calendar = new Map(sheet.days.map(day => [fromApiDate(day.date), day]))
  return rangeDays(fromApiDate(sheet.from), fromApiDate(sheet.to)).map(day => {
    const info = calendar.get(day)
    const dayType = info?.dayType ?? ([0, 6].includes(toApiDate(day).getUTCDay()) ? 'NON_WORKDAY' : 'WORKDAY')
    return { day, label: day.slice(5), weekday: info?.note || `周${weekday(day)}`, rest: dayType !== 'WORKDAY', holiday: dayType === 'HOLIDAY' }
  }).filter(column => !overtime || column.rest)
}

export function periodFor(kind: PeriodKind, anchor: string): Period {
  const date = toApiDate(anchor), year = date.getUTCFullYear(), month = date.getUTCMonth()
  if (kind === 'WEEK') {
    const from = addDays(anchor, -((date.getUTCDay() + 6) % 7))
    return { kind, from, to: addDays(from, 6) }
  }
  const first = kind === 'MONTH' ? month : month - month % 3, span = kind === 'MONTH' ? 1 : 3
  return { kind, from: fromApiDate(new Date(Date.UTC(year, first, 1))), to: fromApiDate(new Date(Date.UTC(year, first + span, 0))) }
}

export function shiftPeriod(period: Period, step: -1 | 1): Period {
  return periodFor(period.kind, step < 0 ? addDays(period.from, -1) : addDays(period.to, 1))
}

export function periodLabel(period: Period): string {
  const [year, month] = period.from.split('-')
  if (period.kind === 'MONTH') return `${year}年${Number(month)}月`
  if (period.kind === 'QUARTER') return `${year}年第${Math.floor((Number(month) - 1) / 3) + 1}季度`
  return `${period.from} – ${period.from.slice(0, 4) === period.to.slice(0, 4) ? period.to.slice(5) : period.to}`
}

export const hours = (ms: number) => Math.round(ms / 36_000) / 100
/** Reference-sheet style `8`, `6.5`, `0`; recorded time never rounds down to `0`. */
export const shortHours = (ms: number) => ms > 0 ? String(Math.max(1, Math.round(ms / 360_000)) / 10) : '0'
/** Exact `2h 05m` / `45m` for drill-downs; floors to whole minutes like the running timer. */
export function hoursMinutes(ms: number): string {
  const minutes = Math.floor(Math.max(0, ms) / 60_000)
  if (!minutes) return ms > 0 ? '< 1m' : '0m'
  return minutes < 60 ? `${minutes}m` : `${Math.floor(minutes / 60)}h ${String(minutes % 60).padStart(2, '0')}m`
}

/** `subjectKey` is the work item id or a category key; category rows group under the 非项目工时 pseudo project. */
export interface TimesheetItemRow { key: string; subjectKey: string; projectId: string; projectName: string; itemNo: string; title: string; label: string; totalMs: number; days: Record<string, number> }
export interface TimesheetRow { key: string; userId: string; name: string; active: boolean; totalMs: number; days: Record<string, number>; children: TimesheetItemRow[] }
export interface DayItem { key: string; itemNo: string; title: string; ms: number }

export type TimesheetModel = ReturnType<typeof buildTimesheet>
/** Keeps only `days` (the period's non-workdays) and recomputes every total from what remains. */
export function restrictTimesheet(model: TimesheetModel, days: string[]): TimesheetModel {
  const keep = new Set(days)
  const pick = (source: Record<string, number>) => Object.fromEntries(Object.entries(source).filter(([day]) => keep.has(day)))
  const sum = (source: Record<string, number>) => Object.values(source).reduce((total, ms) => total + ms, 0)
  const rows = model.rows.map(row => {
    const children = row.children.map(child => { const d = pick(child.days); return { ...child, days: d, totalMs: sum(d) } }).filter(child => child.totalMs > 0)
    const d = pick(row.days)
    return { ...row, days: d, totalMs: sum(d), children }
  })
  const dayTotals = pick(model.dayTotals)
  return { rows, dayTotals, totalMs: sum(dayTotals) }
}
export interface DayProject { projectId: string; projectName: string; totalMs: number; items: DayItem[] }

/** Member rows sorted by name with per-item children, non-project categories last; the server already split cross-midnight sessions. */
export function buildTimesheet(sheet: TeamTimesheet): { rows: TimesheetRow[]; dayTotals: Record<string, number>; totalMs: number } {
  const items = new Map(sheet.workItems.map(item => [item.id, item]))
  const rows = new Map<string, TimesheetRow>(sheet.members.map(member => [member.userId,
    { key: member.userId, userId: member.userId, name: member.displayName, active: member.active, totalMs: 0, days: {}, children: [] }]))
  const dayTotals: Record<string, number> = {}
  let totalMs = 0
  function add(userId: string, subjectKey: string, day: Date, durationMs: number, describe: () => Omit<TimesheetItemRow, 'key' | 'subjectKey' | 'totalMs' | 'days'>) {
    const row = rows.get(userId)
    if (!row) return
    const date = fromApiDate(day)
    let child = row.children.find(c => c.subjectKey === subjectKey)
    if (!child) row.children.push(child = { key: `${row.userId}:${subjectKey}`, subjectKey, ...describe(), totalMs: 0, days: {} })
    for (const target of [row, child]) { target.totalMs += durationMs; target.days[date] = (target.days[date] ?? 0) + durationMs }
    dayTotals[date] = (dayTotals[date] ?? 0) + durationMs
    totalMs += durationMs
  }
  for (const entry of sheet.entries) add(entry.userId, entry.workItemId, entry.date, entry.durationMs, () => {
    const item = items.get(entry.workItemId)
    return { projectId: item?.projectId ?? '', projectName: item?.projectName ?? '', itemNo: item?.itemNo ?? '',
      title: item?.title ?? '已删除的工作项', label: item ? `${item.itemNo} ${item.title}` : '已删除的工作项' }
  })
  for (const entry of sheet.categoryEntries ?? []) add(entry.userId, categoryKey(entry.timeCategory), entry.date, entry.durationMs, () => {
    const label = timeCategory(entry.timeCategory)?.label ?? '其他'
    return { projectId: NON_PROJECT_KEY, projectName: NON_PROJECT_LABEL, itemNo: '', title: label, label }
  })
  const nonProject = (item: TimesheetItemRow) => item.projectId === NON_PROJECT_KEY ? 1 : 0
  const sorted = [...rows.values()].sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'))
  sorted.forEach(row => row.children.sort((a, b) => nonProject(a) - nonProject(b) || b.totalMs - a.totalMs || a.label.localeCompare(b.label, 'zh-CN')))
  return { rows: sorted, dayTotals, totalMs }
}

/** One member's work on one day, grouped by project, larger totals first. */
export function dayBreakdown(row: TimesheetRow, day: string): DayProject[] {
  const projects = new Map<string, DayProject>()
  for (const item of row.children) {
    const ms = item.days[day]
    if (!ms) continue
    let project = projects.get(item.projectId)
    if (!project) projects.set(item.projectId, project = { projectId: item.projectId, projectName: item.projectName || '未知项目', totalMs: 0, items: [] })
    project.totalMs += ms
    project.items.push({ key: item.key, itemNo: item.itemNo, title: item.title, ms })
  }
  const list = [...projects.values()].sort((a, b) => b.totalMs - a.totalMs || a.projectName.localeCompare(b.projectName, 'zh-CN'))
  list.forEach(project => project.items.sort((a, b) => b.ms - a.ms))
  return list
}

const PROJECT_COLORS = [WorkItemLabelColorToken.BrightBlue, WorkItemLabelColorToken.DarkOrange, WorkItemLabelColorToken.DarkPurple, WorkItemLabelColorToken.Aquamarine,
  WorkItemLabelColorToken.SofiaPink, WorkItemLabelColorToken.Indigo, WorkItemLabelColorToken.BrightGreen, WorkItemLabelColorToken.Lipstick,
  WorkItemLabelColorToken.Royal, WorkItemLabelColorToken.Sunset].map(workItemLabelColorValue)

/** Project colours of a sheet, with the 非项目工时 group kept on its own neutral colour. */
export function timesheetColors(sheet: TeamTimesheet | undefined): Map<string, string> {
  const colors = projectColors(sheet?.workItems.map(item => item.projectId) ?? [])
  colors.set(NON_PROJECT_KEY, NON_PROJECT_COLOR)
  return colors
}

/** Hashes each project id into the preset palette and probes past taken colours: projects in one sheet stay distinct, but a colour may change when the sheet's project set changes. */
export function projectColors(projectIds: Iterable<string>): Map<string, string> {
  const colors = new Map<string, string>(), used = new Set<number>()
  for (const id of [...new Set(projectIds)].sort()) {
    const hash = [...id].reduce((value, char) => (value * 31 + char.charCodeAt(0)) >>> 0, 0)
    const index = Array.from({ length: PROJECT_COLORS.length }, (_, offset) => (hash + offset) % PROJECT_COLORS.length)
      .find(candidate => !used.has(candidate)) ?? hash % PROJECT_COLORS.length
    used.add(index)
    colors.set(id, PROJECT_COLORS[index]!)
  }
  return colors
}
