import type { Workbook as WorkbookType } from 'exceljs'
import { XLSX_TYPE } from '../dashboard/exportFile'

export const COMPANY_ACTIVITY_EXPORT_LIMIT = 5000

export interface CompanyActivityExportRow {
  occurredAt: string
  actor: string
  projectName: string
  category: string
  content: string
  entityRef: string
}

export interface CompanyActivityWorkbookInput {
  rows: CompanyActivityExportRow[]
  timezone: string
  filterSummary: string
  exportedAt: string
  truncated: boolean
}

// ExcelJS takes ARGB strings; these mirror the light-theme brand and table tokens.
const BRAND = 'FF0073EA', WHITE = 'FFFFFFFF', MUTED = 'FF5B6070', BORDER = 'FFD0D4E4'
const HEADERS = ['操作时间', '操作人', '项目名称', '分类', '具体内容', '关联对象']

/** `公司_活动日志查询_20261012153045.xlsx`, stamped in the browser's local time like `exportFileName`. */
export function companyActivityFileName(now = new Date()): string {
  const pad = (value: number) => String(value).padStart(2, '0')
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`
  return `公司_活动日志查询_${stamp}.xlsx`
}

export async function buildCompanyActivityWorkbook(input: CompanyActivityWorkbookInput): Promise<Blob> {
  const module = await import('exceljs')
  const Workbook = (module.Workbook ?? (module as unknown as { default: { Workbook: typeof WorkbookType } }).default.Workbook)
  const workbook = new Workbook()
  workbook.creator = 'Yumpoo'
  const sheet = workbook.addWorksheet('活动日志', { views: [{ state: 'frozen', ySplit: 3 }] })
  sheet.columns = [
    { key: 'occurredAt', width: 18 }, { key: 'actor', width: 14 }, { key: 'projectName', width: 24 },
    { key: 'category', width: 10 }, { key: 'content', width: 64 }, { key: 'entityRef', width: 28 },
  ]
  sheet.mergeCells(1, 1, 1, HEADERS.length)
  sheet.getCell(1, 1).value = '公司活动日志'
  sheet.getCell(1, 1).font = { bold: true, size: 14 }
  sheet.mergeCells(2, 1, 2, HEADERS.length)
  sheet.getCell(2, 1).value = `时区 ${input.timezone} · 筛选：${input.filterSummary} · 导出时间 ${input.exportedAt}`
    + (input.truncated ? ` · 仅包含最近 ${COMPANY_ACTIVITY_EXPORT_LIMIT} 条` : '')
  sheet.getCell(2, 1).font = { color: { argb: MUTED } }
  const header = sheet.getRow(3)
  header.values = HEADERS
  header.eachCell(cell => {
    cell.font = { bold: true, color: { argb: WHITE } }
    cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: BRAND } }
    cell.alignment = { vertical: 'middle' }
  })
  header.height = 22
  for (const row of input.rows) {
    const added = sheet.addRow(row)
    added.getCell('content').alignment = { wrapText: true, vertical: 'top' }
    added.eachCell({ includeEmpty: true }, cell => { cell.border = { bottom: { style: 'thin', color: { argb: BORDER } } } })
  }
  sheet.autoFilter = { from: { row: 3, column: 1 }, to: { row: 3, column: HEADERS.length } }
  return new Blob([await workbook.xlsx.writeBuffer()], { type: XLSX_TYPE })
}
