import { CalendarDayType } from '@yumpoo/api-client'
import ExcelJS from 'exceljs'
import { describe, expect, it } from 'vitest'
import { buildCalendarTemplate, parseCalendarWorkbook, type CalendarOverride } from './workCalendarModel'

describe('work calendar workbook', () => {
  it('round-trips explicit days and reports a cross-year row', async () => {
    const overrides: CalendarOverride[] = [
      { date: '2026-10-01', dayType: CalendarDayType.Holiday, standardMinutes: null, note: '国庆节' },
      { date: '2026-10-10', dayType: CalendarDayType.Workday, standardMinutes: 450, note: '补班' },
    ]
    const blob = await buildCalendarTemplate(overrides)
    expect(await parseCalendarWorkbook(await blob.arrayBuffer(), 2026)).toEqual({ overrides, errors: [] })
    const workbook = new ExcelJS.Workbook()
    await workbook.xlsx.load(await blob.arrayBuffer())
    workbook.worksheets[0]!.addRow(['2027-01-01', '法定节假日', '元旦', ''])
    const parsed = await parseCalendarWorkbook(await workbook.xlsx.writeBuffer(), 2026)
    expect(parsed.overrides).toEqual(overrides)
    expect(parsed.errors).toEqual(['第 4 行：日期须为 2026 年的 YYYY-MM-DD'])
  })
})
