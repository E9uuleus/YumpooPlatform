/** 浏览器端读取 csv / xlsx / xls 的第一个工作表；SheetJS 体积大，只在读取时加载。 */
export type SheetCell = string | number | Date | null

export const SPREADSHEET_MAX_BYTES = 5 * 1024 * 1024
export const SPREADSHEET_EXTENSIONS = ['.csv', '.xlsx', '.xls'] as const

export class SpreadsheetError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'SpreadsheetError'
  }
}

const extensionOf = (name: string): string => {
  const dot = name.lastIndexOf('.')
  return dot < 0 ? '' : name.slice(dot).toLowerCase()
}

/** 文件名与大小的校验；通过时返回 null，否则是可直接展示的原因。 */
export function validateSpreadsheetFile(file: Pick<File, 'name' | 'size'>): string | null {
  if (!(SPREADSHEET_EXTENSIONS as readonly string[]).includes(extensionOf(file.name))) {
    return '只支持 .csv、.xlsx、.xls 文件。'
  }
  if (file.size > SPREADSHEET_MAX_BYTES) return '文件超过 5 MB。'
  if (file.size === 0) return '文件是空的。'
  return null
}

/** UTF-8 严格解码，失败再按 gb18030 解码；去掉 UTF-8 BOM。 */
export function decodeCsv(buffer: ArrayBuffer): string {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(buffer).replace(/^﻿/u, '')
  } catch {
    return new TextDecoder('gb18030').decode(buffer)
  }
}

/** 只读第一个工作表，保留原始单元格；日期单元格读成 Date，空单元格是 null。 */
export async function readSpreadsheet(file: File): Promise<SheetCell[][]> {
  const invalid = validateSpreadsheetFile(file)
  if (invalid) throw new SpreadsheetError(invalid)
  const buffer = await file.arrayBuffer()
  const XLSX = await import('xlsx')
  let book
  try {
    book = extensionOf(file.name) === '.csv'
      ? XLSX.read(decodeCsv(buffer), { type: 'string', raw: true })
      : XLSX.read(buffer, { type: 'array', cellDates: true })
  } catch {
    throw new SpreadsheetError('无法读取该文件，请确认它是有效的表格文件。')
  }
  const sheet = book.Sheets[book.SheetNames[0] ?? '']
  if (!sheet) throw new SpreadsheetError('文件里没有工作表。')
  const rows = XLSX.utils.sheet_to_json<unknown[]>(sheet, { header: 1, raw: true, defval: null, blankrows: true })
  return trimTrailingBlanks(rows.map(row => row.map(normalizeCell)))
}

/** 去掉尾部全空的行与列（工作表范围常比内容大），其余行补齐到同一宽度；中间的空行保留，行号不变。 */
export function trimTrailingBlanks(rows: SheetCell[][]): SheetCell[][] {
  const filled = (cell: SheetCell | undefined): boolean => cell !== null && cell !== undefined && cell !== ''
  let height = rows.length
  while (height > 0 && !rows[height - 1]!.some(filled)) height -= 1
  const kept = rows.slice(0, height)
  const width = kept.reduce((most, row) => {
    let last = row.length
    while (last > 0 && !filled(row[last - 1])) last -= 1
    return Math.max(most, last)
  }, 0)
  return kept.map(row => Array.from({ length: width }, (_, index) => filled(row[index]) ? row[index]! : null))
}

function normalizeCell(value: unknown): SheetCell {
  if (value === null || value === undefined) return null
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  if (typeof value === 'boolean') return value ? 'TRUE' : 'FALSE'
  return String(value)
}

/** 单元格文本，两端空白（含全角空格与不换行空格）去掉；日期按 ISO 日期输出；空白返回空串。 */
export function cellText(cell: SheetCell | undefined): string {
  if (cell === null || cell === undefined) return ''
  if (cell instanceof Date) return isoDate(cell)
  return String(cell).replace(/^[\s　 ]+|[\s　 ]+$/gu, '')
}

/** 数字单元格，或可解析为数字的文本（允许千分位逗号）；其他返回 null。 */
export function cellNumber(cell: SheetCell | undefined): number | null {
  if (typeof cell === 'number') return Number.isFinite(cell) ? cell : null
  if (cell === null || cell === undefined || cell instanceof Date) return null
  const text = cellText(cell).replace(/,/gu, '')
  if (!/^[+-]?(\d+(\.\d*)?|\.\d+)$/u.test(text)) return null
  return Number(text)
}

const pad = (value: number): string => String(value).padStart(2, '0')

function isoDate(date: Date): string {
  return `${String(date.getFullYear()).padStart(4, '0')}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function validDate(year: number, month: number, day: number): string | null {
  if (year < 1900 || year > 2200 || month < 1 || month > 12 || day < 1) return null
  const last = new Date(year, month, 0).getDate()
  if (day > last) return null
  return `${String(year).padStart(4, '0')}-${pad(month)}-${pad(day)}`
}

const EXCEL_EPOCH = Date.UTC(1899, 11, 30)
const DAY_MS = 86_400_000

/**
 * 识别 Date、Excel 序列号（含数字文本）、`YYYY-MM-DD`、`YYYY/M/D`、`YYYY.M.D`、`YYYY年M月D日`，输出 ISO 日期；无法识别返回 null。
 * 序列号只接受 20000 到 73050（1954 到 2099 年），避免把工时、数量之类的小数当成日期。
 */
export function cellDate(cell: SheetCell | undefined): string | null {
  if (cell instanceof Date) return Number.isNaN(cell.getTime()) ? null : isoDate(cell)
  if (typeof cell === 'number') {
    if (!Number.isFinite(cell) || cell < 20000 || cell > 73050) return null
    const date = new Date(EXCEL_EPOCH + Math.floor(cell) * DAY_MS)
    return `${String(date.getUTCFullYear()).padStart(4, '0')}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`
  }
  const text = cellText(cell)
  if (!text) return null
  if (/^\d+(?:\.\d+)?$/u.test(text)) return cellDate(Number(text))
  const match = /^(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*日?(?:[ T]+\d{1,2}:\d{2}(?::\d{2})?)?$/u.exec(text)
  return match ? validDate(Number(match[1]), Number(match[2]), Number(match[3])) : null
}
