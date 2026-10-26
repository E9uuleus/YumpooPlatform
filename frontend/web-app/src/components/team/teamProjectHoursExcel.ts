import type { Workbook as WorkbookType } from 'exceljs'
import { XLSX_TYPE } from '../dashboard/exportFile'
import { hours, weekday, type ProjectHoursRow } from './teamDashboardModel'
import { BORDER, BRAND, HOURS_FORMAT, TOTAL, WHITE } from './teamTimesheetExcel'

export interface ProjectHoursWorkbookInput { rows: ProjectHoursRow[]; days: string[]; timezone: string; exportedAt: string }

export async function buildProjectHoursWorkbook(input: ProjectHoursWorkbookInput): Promise<Blob> {
  const module = await import('exceljs')
  const Workbook = (module.Workbook ?? (module as unknown as { default: { Workbook: typeof WorkbookType } }).default.Workbook)
  const workbook = new Workbook()
  workbook.creator = 'Yumpoo'
  const sheet = workbook.addWorksheet('项目工时', { views: [{ state: 'frozen', xSplit: 3, ySplit: 3 }] })
  const lastColumn = 3 + input.days.length
  sheet.mergeCells(1, 1, 1, lastColumn)
  sheet.getCell(1, 1).value = `项目工时统计（小时） ${input.days[0]} 至 ${input.days[input.days.length - 1]}`
  sheet.getCell(1, 1).font = { bold: true, size: 14 }
  sheet.mergeCells(2, 1, 2, lastColumn)
  sheet.getCell(2, 1).value = `时区 ${input.timezone} · 导出时间 ${input.exportedAt}`
  sheet.getCell(2, 1).font = { color: { argb: 'FF5B6070' } }
  const header = sheet.getRow(3)
  header.values = ['项目', '成员 / 分类', '合计', ...input.days.map(day => `${day.slice(5)} ${weekday(day)}`)]
  header.height = 22
  header.eachCell((cell, column) => {
    cell.font = { bold: true, color: { argb: WHITE } }
    cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: BRAND } }
    cell.alignment = { horizontal: column > 2 ? 'center' : 'left', vertical: 'middle' }
  })
  for (const row of input.rows) {
    sheet.addRow([row.name, '', hours(row.totalMs), ...input.days.map(day => row.days[day] ? hours(row.days[day]!) : null)]).font = { bold: true }
    for (const child of row.children) sheet.addRow(['', child.label, hours(child.totalMs), ...input.days.map(day => child.days[day] ? hours(child.days[day]!) : null)])
  }
  for (let index = 4; index <= sheet.rowCount; index++) sheet.getRow(index).eachCell({ includeEmpty: true }, (cell, column) => {
    if (column > 2) cell.numFmt = HOURS_FORMAT
    cell.border = { bottom: { style: 'thin', color: { argb: BORDER } } }
  })
  const totals = sheet.addRow(['合计', '', hours(input.rows.reduce((sum, row) => sum + row.totalMs, 0)),
    ...input.days.map(day => { const ms = input.rows.reduce((sum, row) => sum + (row.days[day] ?? 0), 0); return ms ? hours(ms) : null })])
  totals.eachCell({ includeEmpty: true }, (cell, column) => {
    cell.font = { bold: true }
    cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: TOTAL } }
    if (column > 2) cell.numFmt = HOURS_FORMAT
  })
  sheet.columns.forEach((column, index) => { column.width = index === 0 ? 22 : index === 1 ? 16 : index === 2 ? 10 : 11 })
  return new Blob([await workbook.xlsx.writeBuffer()], { type: XLSX_TYPE })
}
