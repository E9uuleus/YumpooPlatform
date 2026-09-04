export interface WorkItemDueDateFacts {
  dueDate: Date | string | null
  dueTime?: string | null | undefined
  statusCategory: string
  completedAt?: Date | string | null | undefined
}

export interface DueDateValue {
  dueDate: string | null
  dueTime: string | null
}

export interface DueDatePresentation {
  text: string
  fullText: string
  tooltip: string
  tone: 'none' | 'red' | 'green' | 'today'
  strike: boolean
}

const dayMs = 86_400_000
const formatters = new Map<string, Intl.DateTimeFormat>()

function wallParts(value: Date, timezone: string): Record<string, string> {
  let formatter = formatters.get(timezone)
  if (!formatter) {
    formatter = new Intl.DateTimeFormat('en-CA', {
      timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
    })
    formatters.set(timezone, formatter)
  }
  return Object.fromEntries(formatter.formatToParts(value).map(part => [part.type, part.value]))
}

export function companyDate(value: Date, timezone: string): string {
  const parts = wallParts(value, timezone)
  return `${parts.year}-${parts.month}-${parts.day}`
}

export function dueDateKey(value: Date | string | null): string | null {
  if (!value) return null
  const date = value instanceof Date
    ? Number.isNaN(value.getTime()) ? '' : value.toISOString().slice(0, 10)
    : value
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return null
  const parsed = new Date(`${date}T00:00:00Z`)
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === date ? date : null
}

function wallMillis(instant: number, timezone: string): number {
  const parts = wallParts(new Date(instant), timezone)
  return Date.parse(`${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}Z`)
}

/** Match ZonedDateTime's earlier overlap and forward-gap interpretation without browser-local parsing. */
export function dueDateInstant(date: string, time: string, timezone: string): number {
  const wall = Date.parse(`${date}T${time}:00Z`)
  const offsets = new Set([-36, 0, 36].map(hours => {
    const sample = wall + hours * 3_600_000
    return wallMillis(sample, timezone) - sample
  }))
  const candidates = [...offsets].map(offset => wall - offset).sort((a, b) => a - b)
  return candidates.find(candidate => wallMillis(candidate, timezone) === wall)
    ?? candidates.find(candidate => wallMillis(candidate, timezone) > wall)!
}

export function presentDueDate(item: WorkItemDueDateFacts, timezone: string, now: Date): DueDatePresentation {
  const date = dueDateKey(item.dueDate)
  const empty: DueDatePresentation = { text: '—', fullText: '', tooltip: '', tone: 'none', strike: false }
  if (!date) return empty
  const today = companyDate(now, timezone)
  const time = item.dueTime && /^(?:[01]\d|2[0-3]):[0-5]\d$/.test(item.dueTime) ? item.dueTime : null
  const suffix = time ? ` ${time}` : ''
  const fullText = date + suffix
  const result = { ...empty, text: (date.slice(0, 4) === today.slice(0, 4) ? date.slice(5) : date) + suffix, fullText, tooltip: fullText }
  if (item.statusCategory === 'CANCELED') return result
  const done = item.statusCategory === 'DONE'
  const reference = done ? item.completedAt ? new Date(item.completedAt) : null : now
  if (!reference || Number.isNaN(reference.getTime())) return { ...result, tooltip: `完成时间未记录 · ${fullText}` }
  const difference = time
    ? reference.getTime() - dueDateInstant(date, time, timezone)
    : Date.parse(`${companyDate(reference, timezone)}T00:00:00Z`) - Date.parse(`${date}T00:00:00Z`)
  const days = Math.floor(Math.abs(difference) / dayMs)
  const duration = days === 0 ? '不足 1 天' : ` ${days} 天`
  if (difference > 0) return { ...result, tone: 'red', tooltip: `逾期${duration} · ${fullText}` }
  if (done) return { ...result, tone: 'green', strike: true, tooltip: `${difference === 0 ? '按时完成' : `提前${duration}`} · ${fullText}` }
  if (date === today) return { ...result, tone: 'today', tooltip: `今日截止 · ${fullText}` }
  return result
}
