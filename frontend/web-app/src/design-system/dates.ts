/**
 * 日期格式化约定（Monday 设计语言）：
 * - 列表/表格默认只显示到日：YYYY-MM-DD
 * - 完整时间戳（YYYY-MM-DD HH:mm）仅用于 title/tooltip 等次级信息
 * 两个函数都按公司时区换算，调用方传入 session 中的 company.timezone。
 */

function toDate(value: Date | string): Date {
  return value instanceof Date ? value : new Date(value)
}

function dateParts(value: Date | string, timezone: string, withTime: boolean): Intl.DateTimeFormatPart[] {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    ...(withTime ? { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' as const } : {}),
  }).formatToParts(toDate(value))
}

function part(parts: Intl.DateTimeFormatPart[], type: Intl.DateTimeFormatPartTypes): string {
  return parts.find(item => item.type === type)?.value ?? ''
}

/** 短日期：YYYY-MM-DD（列表/表格默认） */
export function formatDateOnly(value: Date | string, timezone: string): string {
  const parts = dateParts(value, timezone, false)
  return `${part(parts, 'year')}-${part(parts, 'month')}-${part(parts, 'day')}`
}

/** 完整时间戳：YYYY-MM-DD HH:mm（tooltip/title 等次级信息） */
export function formatTimestamp(value: Date | string, timezone: string): string {
  const parts = dateParts(value, timezone, true)
  return `${part(parts, 'year')}-${part(parts, 'month')}-${part(parts, 'day')} ${part(parts, 'hour')}:${part(parts, 'minute')}`
}

/** 中文完整时间戳：YYYY年M月D日 HH:mm（动态时间悬停提示） */
export function formatChineseTimestamp(value: Date | string, timezone: string): string {
  const date = toDate(value)
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: timezone,
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(date).replace(/\s+/, ' ')
}

/** Monday 风格中文相对时间；now 可注入，便于每分钟刷新及单元测试。 */
export function formatRelativeTime(value: Date | string, now: Date = new Date()): string {
  const elapsedSeconds = Math.max(0, Math.floor((now.getTime() - toDate(value).getTime()) / 1000))
  if (elapsedSeconds < 60) return '刚刚'
  const minutes = Math.floor(elapsedSeconds / 60)
  if (minutes < 60) return `${minutes}分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}小时前`
  const days = Math.floor(hours / 24)
  if (days < 30) return `${days}天前`
  const months = Math.floor(days / 30)
  if (months < 12) return `${months}个月前`
  return `${Math.floor(months / 12)}年前`
}
