import type { Workbook as WorkbookType } from 'exceljs'
import { XLSX_TYPE } from '../exportFile'
import { hours, isWeekend, weekday, type TimesheetRow } from './teamDashboardModel'

export interface TimesheetWorkbookInput { rows: TimesheetRow[]; days: string[]; timezone: string; scopeLabel: string; exportedAt: string }

// ExcelJS takes ARGB strings; these mirror the light-theme brand and table tokens.
const BRAND = 'FF0073EA', WHITE = 'FFFFFFFF', WEEKEND = 'FFE6E9EF', TOTAL = 'FFF6F7FB', BORDER = 'FFD0D4E4'
const HOURS_FORMAT = '0.00'

/** Summary sheet mirrors the on-screen matrix; detail sheet lists one row per member, day and work item. */
export async function buildTimesheetWorkbook(input: TimesheetWorkbookInput): Promise<Blob> {
  const module = await import('exceljs')
  const Workbook = (module.Workbook ?? (module as unknown as { default: { Workbook: typeof WorkbookType } }).default.Workbook)
  const workbook = new Workbook()
  workbook.creator = 'Yumpoo'
  const range = `${input.days[0]} 至 ${input.days[input.days.length - 1]}`

  const summary = workbook.addWorksheet('工时汇总', { views: [{ state: 'frozen', xSplit: 3, ySplit: 3 }] })
  const lastColumn = 3 + input.days.length
  summary.mergeCells(1, 1, 1, lastColumn)
  summary.getCell(1, 1).value = `成员工时统计（小时） ${range}`
  summary.getCell(1, 1).font = { bold: true, size: 14 }
  summary.mergeCells(2, 1, 2, lastColumn)
  summary.getCell(2, 1).value = `时区 ${input.timezone} · 项目范围：${input.scopeLabel} · 导出时间 ${input.exportedAt}`
  summary.getCell(2, 1).font = { color: { argb: 'FF5B6070' } }
  const header = summary.getRow(3)
  header.values = ['成员', '状态', '合计', ...input.days.map(day => `${day.slice(5)} ${weekday(day)}`)]
  header.eachCell((cell, column) => {
    const weekend = column > 3 && isWeekend(input.days[column - 4]!)
    cell.font = { bold: true, color: { argb: weekend ? 'FF25272C' : WHITE } }
    cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: weekend ? WEEKEND : BRAND } }
    cell.alignment = { horizontal: column > 2 ? 'center' : 'left', vertical: 'middle' }
  })
  header.height = 22
  const dayTotals = input.days.map(() => 0)
  for (const row of input.rows) {
    const values = input.days.map((day, index) => { const ms = row.days[day] ?? 0; dayTotals[index]! += ms; return ms ? hours(ms) : null })
    const added = summary.addRow([row.name, row.active ? '在职' : '非在职', row.totalMs ? hours(row.totalMs) : 0, ...values])
    added.eachCell({ includeEmpty: true }, (cell, column) => {
      if (column > 2) cell.numFmt = HOURS_FORMAT
      if (column > 3 && isWeekend(input.days[column - 4]!)) cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: TOTAL } }
      cell.border = { bottom: { style: 'thin', color: { argb: BORDER } } }
    })
  }
  const totals = summary.addRow(['合计', '', hours(input.rows.reduce((sum, row) => sum + row.totalMs, 0)), ...dayTotals.map(ms => ms ? hours(ms) : null)])
  totals.eachCell({ includeEmpty: true }, (cell, column) => {
    cell.font = { bold: true }
    cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: TOTAL } }
    if (column > 2) cell.numFmt = HOURS_FORMAT
  })
  summary.columns.forEach((column, index) => { column.width = index === 0 ? 18 : index === 1 ? 8 : index === 2 ? 10 : 11 })

  const detail = workbook.addWorksheet('工时明细', { views: [{ state: 'frozen', ySplit: 1 }] })
  detail.columns = [
    { header: '成员', key: 'member', width: 16 }, { header: '日期', key: 'date', width: 12 }, { header: '星期', key: 'weekday', width: 6 },
    { header: '项目', key: 'project', width: 22 }, { header: '工作项', key: 'item', width: 48 }, { header: '耗时（小时）', key: 'hours', width: 12, style: { numFmt: HOURS_FORMAT } },
  ]
  detail.getRow(1).eachCell(cell => {
    cell.font = { bold: true, color: { argb: WHITE } }
    cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: BRAND } }
  })
  for (const row of input.rows) {
    for (const day of input.days) {
      for (const item of row.children) {
        const ms = item.days[day] ?? 0
        if (ms) detail.addRow({ member: row.name, date: day, weekday: weekday(day), project: item.projectName, item: item.label, hours: hours(ms) })
      }
    }
  }
  detail.autoFilter = { from: { row: 1, column: 1 }, to: { row: 1, column: 6 } }

  return new Blob([await workbook.xlsx.writeBuffer()], { type: XLSX_TYPE })
}
