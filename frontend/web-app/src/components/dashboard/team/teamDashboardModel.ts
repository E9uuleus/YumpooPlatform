import type { TeamTimesheet } from '@yumpoo/api-client'
import { formatDateOnly } from '../../../design-system/dates'

export type PeriodKind = 'WEEK' | 'MONTH' | 'QUARTER' | 'CUSTOM'
/** Inclusive company-calendar dates as `YYYY-MM-DD`. */
export interface Period { kind: PeriodKind; from: string; to: string }
export const MAX_RANGE_DAYS = 93
export const periodOptions = [
  { value: 'WEEK', label: '周' }, { value: 'MONTH', label: '月' }, { value: 'QUARTER', label: '季' }, { value: 'CUSTOM', label: '自定义' },
] as const

const DAY = 86_400_000
const weekdays = ['日', '一', '二', '三', '四', '五', '六']
/** Generated date fields serialize with `toISOString()`, so calendar dates travel as UTC midnight. */
export const toApiDate = (date: string) => new Date(`${date}T00:00:00Z`)
export const fromApiDate = (date: Date) => date.toISOString().slice(0, 10)
export const addDays = (date: string, days: number) => fromApiDate(new Date(toApiDate(date).getTime() + days * DAY))
export const todayIn = (timezone: string, now = new Date()) => formatDateOnly(now, timezone)
export const weekday = (date: string) => weekdays[toApiDate(date).getUTCDay()]!
export const isWeekend = (date: string) => [0, 6].includes(toApiDate(date).getUTCDay())

export function rangeDays(from: string, to: string): string[] {
  const days: string[] = []
  for (let day = from; day <= to && days.length <= MAX_RANGE_DAYS; day = addDays(day, 1)) days.push(day)
  return days
}

export function periodFor(kind: Exclude<PeriodKind, 'CUSTOM'>, anchor: string): Period {
  const date = toApiDate(anchor), year = date.getUTCFullYear(), month = date.getUTCMonth()
  if (kind === 'WEEK') {
    const from = addDays(anchor, -((date.getUTCDay() + 6) % 7))
    return { kind, from, to: addDays(from, 6) }
  }
  const first = kind === 'MONTH' ? month : month - month % 3, span = kind === 'MONTH' ? 1 : 3
  return { kind, from: fromApiDate(new Date(Date.UTC(year, first, 1))), to: fromApiDate(new Date(Date.UTC(year, first + span, 0))) }
}

export function shiftPeriod(period: Period, step: -1 | 1): Period {
  if (period.kind !== 'CUSTOM') return periodFor(period.kind, step < 0 ? addDays(period.from, -1) : addDays(period.to, 1))
  const length = rangeDays(period.from, period.to).length
  return { kind: 'CUSTOM', from: addDays(period.from, step * length), to: addDays(period.to, step * length) }
}

export function periodLabel(period: Period): string {
  const [year, month] = period.from.split('-')
  if (period.kind === 'MONTH') return `${year}年${Number(month)}月`
  if (period.kind === 'QUARTER') return `${year}年第${Math.floor((Number(month) - 1) / 3) + 1}季度`
  return `${period.from} – ${period.from.slice(0, 4) === period.to.slice(0, 4) ? period.to.slice(5) : period.to}`
}

export const hours = (ms: number) => Math.round(ms / 36_000) / 100
export const formatHours = (ms: number) => ms > 0 ? (Math.round(ms / 360_000) / 10).toFixed(1) : ''

export interface TimesheetItemRow { key: string; workItemId: string; label: string; projectName: string; totalMs: number; days: Record<string, number> }
export interface TimesheetRow { key: string; userId: string; name: string; active: boolean; totalMs: number; days: Record<string, number>; children: TimesheetItemRow[] }

/** Member rows sorted by name with per-item children; the server already split cross-midnight sessions. */
export function buildTimesheet(sheet: TeamTimesheet): { rows: TimesheetRow[]; dayTotals: Record<string, number>; totalMs: number } {
  const items = new Map(sheet.workItems.map(item => [item.id, item]))
  const rows = new Map<string, TimesheetRow>(sheet.members.map(member => [member.userId,
    { key: member.userId, userId: member.userId, name: member.displayName, active: member.active, totalMs: 0, days: {}, children: [] }]))
  const dayTotals: Record<string, number> = {}
  let totalMs = 0
  for (const entry of sheet.entries) {
    const row = rows.get(entry.userId)
    if (!row) continue
    const date = fromApiDate(entry.date), item = items.get(entry.workItemId)
    let child = row.children.find(c => c.workItemId === entry.workItemId)
    if (!child) {
      child = { key: `${row.userId}:${entry.workItemId}`, workItemId: entry.workItemId, label: item ? `${item.itemNo} ${item.title}` : '已删除的工作项',
        projectName: item?.projectName ?? '', totalMs: 0, days: {} }
      row.children.push(child)
    }
    for (const target of [row, child]) { target.totalMs += entry.durationMs; target.days[date] = (target.days[date] ?? 0) + entry.durationMs }
    dayTotals[date] = (dayTotals[date] ?? 0) + entry.durationMs
    totalMs += entry.durationMs
  }
  const sorted = [...rows.values()].sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'))
  sorted.forEach(row => row.children.sort((a, b) => b.totalMs - a.totalMs || a.label.localeCompare(b.label, 'zh-CN')))
  return { rows: sorted, dayTotals, totalMs }
}
