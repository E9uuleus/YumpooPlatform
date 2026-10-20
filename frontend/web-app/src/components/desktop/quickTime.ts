import type { TimerCandidate } from '@yumpoo/api-client'
import type { RailProject } from '@yumpoo/preload-contract'
import { addDays, projectColors } from '../team/teamDashboardModel'
import { formatClock, gaps, HOUR, MINUTE, placeSequential, type DayBlock, type Range } from '../my-time/day/dayModel'

export const QUICK_DURATIONS = [15 * MINUTE, 30 * MINUTE, HOUR, 2 * HOUR] as const
export type DateChoice = 'today' | 'yesterday' | 'pick'

/** The candidate carries only the status category, so the pill names the category. */
export const STATUS_PILLS: Record<string, { label: string; tone: 'accent' | 'warning' | 'success' | 'neutral' }> = {
  TODO: { label: '待开始', tone: 'warning' },
  IN_PROGRESS: { label: '进行中', tone: 'accent' },
  DONE: { label: '已完成', tone: 'success' },
  CANCELED: { label: '已取消', tone: 'neutral' },
}

export interface QuickGroup { projectId: string; projectName: string; items: TimerCandidate[] }

/** `5h 30m`, `5h`, `30m`: the header total and the per-row "今日" reading. */
export function shortDuration(ms: number): string {
  const minutes = Math.round(Math.max(0, ms) / MINUTE), hours = Math.floor(minutes / 60), rest = minutes % 60
  return hours && rest ? `${hours}h ${rest}m` : hours ? `${hours}h` : `${rest}m`
}

/** `15 分钟`, `1 小时`, `1 小时 30 分钟`: the record button and the success toast. */
export function spokenDuration(ms: number): string {
  const minutes = Math.round(ms / MINUTE), hours = Math.floor(minutes / 60), rest = minutes % 60
  return [hours ? `${hours} 小时` : '', rest ? `${rest} 分钟` : ''].filter(Boolean).join(' ')
}

/** Rail projects first in rail order, then the other projects in the order their first candidate appears. */
export function groupCandidates(items: TimerCandidate[], rail: RailProject[]): QuickGroup[] {
  const groups = new Map<string, QuickGroup>(rail.map(project => [project.id, { projectId: project.id, projectName: project.name, items: [] }]))
  for (const item of items) {
    const group = groups.get(item.projectId) ?? { projectId: item.projectId, projectName: item.projectName, items: [] }
    group.items.push(item)
    groups.set(item.projectId, group)
  }
  return [...groups.values()].filter(group => group.items.length)
}

/** Rail projects stay distinct among themselves, as on the rail; any other project gets its own hashed colour. */
export function projectTones(rail: RailProject[], others: string[]): Map<string, string> {
  const tones = projectColors(rail.map(project => project.id))
  for (const id of others) if (!tones.has(id)) tones.set(id, projectColors([id]).get(id)!)
  return tones
}

export interface QuickDay {
  blocks: DayBlock[]
  now: number
  workday: Range
  dayRange: Range
  limit: number
}

/** Same placement as My Day's quick add: the free time from the start of work up to now (or the day's end), split across gaps when needed. */
export function quickPlan(day: QuickDay, durationMs: number) {
  const start = Math.max(day.workday.start, day.dayRange.start)
  return placeSequential(durationMs, gaps(day.blocks, day.now, { start, end: day.limit }), start)
}

/** "排入今日空档 14:30 – 15:30"; several slices and a shortfall are spelled out after it. */
export function placementHint(plan: ReturnType<typeof quickPlan>, date: string, today: string, timezone: string, dayEnd: number): string {
  const [month, day] = date.slice(5).split('-').map(Number)
  const label = date === today ? '今日' : date === addDays(today, -1) ? '昨日' : ` ${month}月${day}日 `
  const first = plan.entries[0], last = plan.entries.at(-1)
  if (!first || !last) return `${label.trim()}上班后已没有空档`
  return `排入${label}空档 ${formatClock(first.start, timezone)} – ${formatClock(last.end, timezone, dayEnd)}`
    + (plan.entries.length > 1 ? ` · 分 ${plan.entries.length} 段` : '')
    + (plan.leftoverMs ? ` · 还差 ${spokenDuration(plan.leftoverMs)}` : '')
}

/** First character of a project name, upper-cased for Latin names: the rail icon's letter. */
export function projectInitial(name: string): string {
  return name.trim().charAt(0).toUpperCase()
}
