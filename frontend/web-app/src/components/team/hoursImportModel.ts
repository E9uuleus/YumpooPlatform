import type { MigratedTimeImportEntry, MigratedTimeImportIssue } from '@yumpoo/api-client'
import { cellDate, cellNumber, cellText, type SheetCell } from '../import/spreadsheet'
import { toApiDate } from './teamDashboardModel'

/** 迁入工时矩阵：行是成员、列是日期、单元格是当天的小时数。 */
export const HEADER_SEARCH_ROWS = 10
export const NAME_HEADERS: readonly string[] = ['成员', '姓名', '人员', '处理人']
export const TOTAL_ROW_LABELS: readonly string[] = ['合计', '总计', '小计']
export const MAX_DAY_HOURS = 24
export const MAX_IMPORT_ENTRIES = 20_000

export interface MatrixDay { date: string; minutes: number }
export interface MatrixMember {
  name: string
  /** 表格中的行号，从 1 开始。 */
  rowNumber: number
  days: MatrixDay[]
  totalMinutes: number
}
export interface MatrixError { rowNumber: number; message: string }
export interface ParsedMatrix {
  headerRow: number
  nameColumn: number
  dates: string[]
  ignoredColumns: string[]
  ignoredRows: string[]
  members: MatrixMember[]
  errors: MatrixError[]
}
export type MatrixParse = { ok: true; matrix: ParsedMatrix } | { ok: false; message: string }

const compact = (text: string): string => text.replace(/\s+/gu, '')

/** `A`、`B`、…、`AA`，与表格软件的列名一致。 */
export function columnLabel(index: number): string {
  let label = ''
  for (let value = index + 1; value > 0; value = Math.floor((value - 1) / 26)) label = String.fromCharCode(65 + (value - 1) % 26) + label
  return label
}

const hasDate = (row: SheetCell[]): boolean => row.some((cell, index) => index > 0 && cellDate(cell) !== null)

/** 小时数换算成分钟；超过 24 小时、负数或非数字返回 null。 */
export function hoursToMinutes(hours: number | null): number | null {
  if (hours === null || !Number.isFinite(hours) || hours < 0 || hours > MAX_DAY_HOURS) return null
  return Math.round(hours * 60)
}

export function parseHoursMatrix(rows: readonly SheetCell[][]): MatrixParse {
  const headerIndex = rows.slice(0, HEADER_SEARCH_ROWS).findIndex(hasDate)
  if (headerIndex < 0) return { ok: false, message: `前 ${HEADER_SEARCH_ROWS} 行里没有找到日期表头，请确认表格的列是日期。` }
  const header = rows[headerIndex]!
  const found = header.findIndex(cell => NAME_HEADERS.includes(compact(cellText(cell))))
  const nameColumn = found < 0 ? 0 : found
  const errors: MatrixError[] = []
  const headerRow = headerIndex + 1

  const dateColumns: { column: number; date: string }[] = []
  const ignoredColumns: string[] = []
  const firstColumn = new Map<string, number>()
  header.forEach((cell, column) => {
    if (column === nameColumn) return
    const date = cellDate(cell)
    if (date === null) {
      const text = cellText(cell)
      if (text) ignoredColumns.push(text)
      else if (rows.slice(headerIndex + 1).some(row => cellText(row[column]) !== '')) ignoredColumns.push(`${columnLabel(column)} 列（无表头）`)
      return
    }
    const earlier = firstColumn.get(date)
    if (earlier !== undefined) {
      errors.push({ rowNumber: headerRow, message: `日期 ${date} 在 ${columnLabel(earlier)} 列和 ${columnLabel(column)} 列重复。` })
      return
    }
    firstColumn.set(date, column)
    dateColumns.push({ column, date })
  })

  const members: MatrixMember[] = []
  const ignoredRows: string[] = []
  const seen = new Map<string, number>()
  let cellCount = 0
  rows.slice(headerIndex + 1).forEach((row, offset) => {
    const rowNumber = headerIndex + offset + 2
    const name = cellText(row[nameColumn])
    const filled = dateColumns.filter(({ column }) => cellText(row[column]) !== '')
    if (!name && !filled.length) return
    if (TOTAL_ROW_LABELS.includes(compact(name))) { ignoredRows.push(name); return }
    if (!name) { errors.push({ rowNumber, message: `第 ${rowNumber} 行有工时但没有姓名。` }); return }
    const days: MatrixDay[] = []
    for (const { column, date } of filled) {
      const minutes = hoursToMinutes(cellNumber(row[column]))
      if (minutes === null) {
        errors.push({ rowNumber, message: `${columnLabel(column)}${rowNumber}（${name}，${date}）「${cellText(row[column])}」不是 0 到 ${MAX_DAY_HOURS} 的小时数。` })
        continue
      }
      days.push({ date, minutes })
    }
    const earlier = seen.get(name)
    if (earlier !== undefined) {
      errors.push({ rowNumber, message: `成员「${name}」在第 ${earlier} 行和第 ${rowNumber} 行重复。` })
      return
    }
    seen.set(name, rowNumber)
    cellCount += days.length
    members.push({ name, rowNumber, days, totalMinutes: days.reduce((sum, day) => sum + day.minutes, 0) })
  })

  if (!dateColumns.length) errors.push({ rowNumber: headerRow, message: '没有可识别的日期列。' })
  if (!members.length) errors.push({ rowNumber: headerRow, message: '没有找到成员行。' })
  if (cellCount > MAX_IMPORT_ENTRIES) errors.push({ rowNumber: headerRow, message: `一次最多迁入 ${MAX_IMPORT_ENTRIES} 个成员日，当前有 ${cellCount} 个，请拆分文件。` })
  return { ok: true, matrix: { headerRow, nameColumn, dates: dateColumns.map(({ date }) => date), ignoredColumns, ignoredRows, members, errors } }
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

/** 请求条目；日期按自然日取 UTC 零点，序列化后仍是同一天。 */
export function buildEntries(members: readonly MatrixMember[], choices: readonly MemberChoice[]): MigratedTimeImportEntry[] {
  return members.flatMap((member, index) => {
    const userId = choices[index]
    return userId ? member.days.map(day => ({ userId, workDate: toApiDate(day.date), minutes: day.minutes })) : []
  })
}

export const hoursLabel = (minutes: number): string => String(Math.round(minutes / 60 * 100) / 100)

export const MIGRATED_ISSUE_LABELS: Readonly<Record<string, string>> = {
  USER_INVALID: '成员已停用或不属于本公司',
  NOT_ENOUGH_FREE_TIME: '当天空闲时间不够，未写入',
  PAST_WORKDAY_END: '排布后超过下班时间',
  FUTURE_DATE: '日期不早于今天',
  REPLACES_PREVIOUS: '覆盖原有迁入工时',
}

/** 覆盖提示不算问题，只在统计里计数。 */
export const isInformational = (issue: MigratedTimeImportIssue): boolean => issue.code === 'REPLACES_PREVIOUS'

export function describeIssue(issue: MigratedTimeImportIssue): string {
  const label = MIGRATED_ISSUE_LABELS[issue.code] ?? issue.code
  const previous = issue.code === 'REPLACES_PREVIOUS' && issue.detail ? Number(issue.detail) : Number.NaN
  return Number.isFinite(previous) ? `${label}（原 ${hoursLabel(previous)} 小时）` : label
}
