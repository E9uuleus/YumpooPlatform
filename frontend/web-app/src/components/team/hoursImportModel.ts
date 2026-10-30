import type { MigratedTimeImportIssue, MigratedTimeImportRow } from '@yumpoo/api-client'
import { cellDate, cellNumber, cellText, type SheetCell, type WorkbookSheet } from '../import/spreadsheet'
import { toApiDate } from './teamDashboardModel'

/** 原平台导出的「工时花费报表」：每位成员一个明细工作表，每行是成员某天在某个工作项上的工时；汇总表没有明细表头，会被忽略。 */
export const HEADER_SEARCH_ROWS = 10
export const DETAIL_HEADERS = { name: '责任人', date: '日期', title: '标题', hours: '花费工时', project: '所属项目' } as const
export const MAX_DAY_HOURS = 24
export const MAX_IMPORT_ROWS = 20_000

export interface DetailRow {
  sheet: string
  /** 工作表中的行号，从 1 开始。 */
  rowNumber: number
  name: string
  date: string
  projectName: string
  title: string
  minutes: number
}
export interface DetailMember { name: string; days: number; totalMinutes: number }
export interface ParsedDetail {
  sheets: string[]
  ignoredSheets: string[]
  dates: string[]
  rows: DetailRow[]
  members: DetailMember[]
  errors: string[]
}
export type DetailParse = { ok: true; detail: ParsedDetail } | { ok: false; message: string }

type DetailColumns = Record<keyof typeof DETAIL_HEADERS, number>

const compact = (text: string): string => text.replace(/\s+/gu, '')

/** 小时数换算成分钟；超过 24 小时、负数或非数字返回 null。 */
export function hoursToMinutes(hours: number | null): number | null {
  if (hours === null || !Number.isFinite(hours) || hours < 0 || hours > MAX_DAY_HOURS) return null
  return Math.round(hours * 60)
}

function detailColumns(row: readonly SheetCell[]): DetailColumns | null {
  const headers = row.map(cell => compact(cellText(cell)))
  const columns = Object.fromEntries(Object.entries(DETAIL_HEADERS).map(([key, label]) => [key, headers.indexOf(label)])) as DetailColumns
  return Object.values(columns).every(column => column >= 0) ? columns : null
}

/** 责任人与日期按成员、日期合并单元格，空白沿用上一行；标题为空的行（周末占位行、末尾合计行）忽略，0 小时的行不迁入。 */
export function parseHoursDetail(sheets: readonly WorkbookSheet[]): DetailParse {
  const used: string[] = [], ignoredSheets: string[] = [], rows: DetailRow[] = [], errors: string[] = []
  for (const sheet of sheets) {
    const headerIndex = sheet.rows.slice(0, HEADER_SEARCH_ROWS).findIndex(row => detailColumns(row) !== null)
    if (headerIndex < 0) { ignoredSheets.push(sheet.name); continue }
    used.push(sheet.name)
    const columns = detailColumns(sheet.rows[headerIndex]!)!
    let name = ''
    let date: string | null = null
    for (const [offset, row] of sheet.rows.slice(headerIndex + 1).entries()) {
      const rowNumber = headerIndex + offset + 2
      name = cellText(row[columns.name]) || name
      if (cellText(row[columns.date])) date = cellDate(row[columns.date])
      const title = cellText(row[columns.title])
      if (!title) continue
      const where = `「${sheet.name}」第 ${rowNumber} 行`
      if (date === null) { errors.push(`${where}的日期无法识别。`); continue }
      const minutes = hoursToMinutes(cellNumber(row[columns.hours]))
      if (minutes === null) { errors.push(`${where}的花费工时「${cellText(row[columns.hours])}」不是 0 到 ${MAX_DAY_HOURS} 的小时数。`); continue }
      if (minutes > 0) rows.push({ sheet: sheet.name, rowNumber, name, date, projectName: cellText(row[columns.project]), title, minutes })
    }
  }
  if (!used.length) return { ok: false, message: '没有找到工时明细表。请上传原平台导出的「工时花费报表」，明细表需要包含「责任人、日期、标题、花费工时、所属项目」列。' }
  if (!rows.length) errors.push('明细表里没有可迁入的工时。')
  if (rows.length > MAX_IMPORT_ROWS) errors.push(`一次最多迁入 ${MAX_IMPORT_ROWS} 条工时，当前有 ${rows.length} 条，请拆分文件。`)
  const members = new Map<string, { dates: Set<string>; totalMinutes: number }>()
  for (const row of rows) {
    const member = members.get(row.name) ?? { dates: new Set<string>(), totalMinutes: 0 }
    member.dates.add(row.date)
    member.totalMinutes += row.minutes
    members.set(row.name, member)
  }
  return { ok: true, detail: {
    sheets: used, ignoredSheets, dates: [...new Set(rows.map(row => row.date))].sort(), rows, errors,
    members: [...members].map(([name, member]) => ({ name, days: member.dates.size, totalMinutes: member.totalMinutes })),
  } }
}

export interface MemberCandidate { userId: string; displayName: string }
/** 对某个姓名的选择：成员 ID；`null` 表示跳过；`undefined` 表示同名多人、尚未选择。 */
export type MemberChoice = string | null | undefined

/** 姓名 trim 后完全相同的候选成员。 */
export function candidatesFor(name: string, candidates: readonly MemberCandidate[]): MemberCandidate[] {
  return candidates.filter(candidate => candidate.displayName.trim() === name)
}

/** 唯一匹配自动选中，没有匹配默认跳过，同名多人留空待选。 */
export function defaultChoice(name: string, candidates: readonly MemberCandidate[]): MemberChoice {
  const matches = candidatesFor(name, candidates)
  return matches.length === 1 ? matches[0]!.userId : matches.length === 0 ? null : undefined
}

/** 两个姓名选了同一个成员的成员 ID；同一成员同一天只能写一次。 */
export function duplicateTargets(choices: readonly MemberChoice[]): string[] {
  const seen = new Set<string>(), repeated = new Set<string>()
  for (const choice of choices) {
    if (!choice) continue
    if (seen.has(choice)) repeated.add(choice)
    seen.add(choice)
  }
  return [...repeated]
}

export interface ImportRow extends DetailRow { userId: string }

/** 选定了平台成员的行，保持文件顺序（同一成员同一天按此顺序排入空档）；`choices` 与 `detail.members` 一一对应。 */
export function importRows(detail: ParsedDetail, choices: readonly MemberChoice[]): ImportRow[] {
  const users = new Map(detail.members.map((member, index) => [member.name, choices[index]]))
  return detail.rows.flatMap(row => {
    const userId = users.get(row.name)
    return userId ? [{ ...row, userId }] : []
  })
}

/** 请求行；日期按自然日取 UTC 零点，序列化后仍是同一天。 */
export const requestRow = (row: ImportRow): MigratedTimeImportRow => ({
  userId: row.userId, workDate: toApiDate(row.date), projectName: row.projectName, workItemTitle: row.title, minutes: row.minutes,
})

export const hoursLabel = (minutes: number): string => String(Math.round(minutes / 60 * 100) / 100)

export const MIGRATED_ISSUE_LABELS: Readonly<Record<string, string>> = {
  USER_INVALID: '成员已停用或不属于本公司',
  NOT_ENOUGH_FREE_TIME: '当天空闲时间不够，未写入',
  PAST_WORKDAY_END: '排布后超过下班时间',
  FUTURE_DATE: '只能迁入今天之前的日期，未写入',
  REPLACES_PREVIOUS: '覆盖原有迁入工时',
  PROJECT_NOT_FOUND: '项目不存在',
  PROJECT_AMBIGUOUS: '有多个同名项目，无法确定',
  WORK_ITEM_NOT_FOUND: '项目中没有该工作项',
  WORK_ITEM_AMBIGUOUS: '项目中有多个同名工作项，无法确定',
  NOT_PROJECT_MEMBER: '成员不在该项目中',
}

/** 覆盖提示不算问题，只在统计里计数。 */
export const isInformational = (issue: MigratedTimeImportIssue): boolean => issue.code === 'REPLACES_PREVIOUS'

export function describeIssue(issue: MigratedTimeImportIssue): string {
  const label = MIGRATED_ISSUE_LABELS[issue.code] ?? issue.code
  const previous = issue.code === 'REPLACES_PREVIOUS' && issue.detail ? Number(issue.detail) : Number.NaN
  return Number.isFinite(previous) ? `${label}（原 ${hoursLabel(previous)} 小时）` : label
}
