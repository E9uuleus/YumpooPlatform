import { CalendarDaySource, CalendarDayType } from '@yumpoo/api-client'
import ExcelJS from 'exceljs'
import { describe, expect, it } from 'vitest'
import { buildCalendarTemplate, calendarOverrides, parseCalendarWorkbook, type CalendarOverride } from './workCalendarModel'

describe('work calendar workbook', () => {
  it('round-trips explicit days and reports a cross-year row', async () => {
    const overrides: CalendarOverride[] = [
      { date: '2026-10-01', dayType: CalendarDayType.Holiday, standardMinutes: null, note: '国庆节', source: CalendarDaySource.Import },
      { date: '2026-10-10', dayType: CalendarDayType.Workday, standardMinutes: 450, note: '补班', source: CalendarDaySource.Import },
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

  it('keeps inherited hours distinct from an explicit value equal to the company default', () => {
    const base = { dayType: CalendarDayType.Workday, standardMinutes: 480, explicit: true, note: '补班', source: CalendarDaySource.Import }
    const days = [
      { ...base, date: new Date('2026-10-10'), configuredStandardMinutes: null },
      { ...base, date: new Date('2026-10-11'), configuredStandardMinutes: 480 },
    ]
    expect(calendarOverrides(days).map(day => [day.standardMinutes, day.source]))
      .toEqual([[null, CalendarDaySource.Import], [480, CalendarDaySource.Import]])
    expect(calendarOverrides(days.map(day => ({ ...day, standardMinutes: 420 })))[0]?.standardMinutes).toBeNull()
  })

  it('reports tiny hours by row and handles cached formulas and rich text', async () => {
    const workbook = new ExcelJS.Workbook(), sheet = workbook.addWorksheet('工作日历')
    sheet.addRow(['日期', '类型', '名称', '标准工时（小时）'])
    sheet.addRow(['2026-10-10', '工作日', '补班', 0.005])
    sheet.addRow([{ formula: '"2026-10-11"', result: '2026-10-11' }, { richText: [{ text: '工作' }, { text: '日' }] },
      { richText: [{ text: '补' }, { text: '班' }] }, { formula: '15/2', result: 7.5 }])
    sheet.addRow(['2026-10-12', '工作日', '', { formula: '15/2' }])
    sheet.addRow(['2026-10-13', '工作日', '', { error: '#VALUE!' }])
    const result = await parseCalendarWorkbook(await workbook.xlsx.writeBuffer(), 2026)
    expect(result.overrides).toEqual([{ date: '2026-10-11', dayType: CalendarDayType.Workday, standardMinutes: 450, note: '补班', source: CalendarDaySource.Import }])
    expect(result.errors).toEqual([
      '第 2 行：仅工作日可填写标准工时，换算后须为 1–720 分钟（最多 12 小时）',
      '第 4 行：标准工时：公式缺少缓存结果，请在 Excel 中重新计算并保存',
      '第 5 行：标准工时：单元格包含 Excel 错误 #VALUE!',
    ])
  })
})
