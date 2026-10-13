import { CalendarDayType } from '@yumpoo/api-client'
import { XLSX_TYPE } from '../dashboard/exportFile'
import { addDays, toApiDate } from '../dashboard/team/teamDashboardModel'

export const DAY_TYPE_LABELS: Record<string, string> = { WORKDAY: '工作日', NON_WORKDAY: '休息日', HOLIDAY: '法定节假日' }
export interface CalendarOverride { date: string; dayType: CalendarDayType; standardMinutes: number | null; note: string | null }

export const isWeekendDate = (date: string) => [0, 6].includes(toApiDate(date).getUTCDay())
export const defaultDayType = (date: string): CalendarDayType => isWeekendDate(date) ? CalendarDayType.NonWorkday : CalendarDayType.Workday
/** Overrides that only restate the weekday rule are dropped so the stored calendar holds real exceptions only. */
export const isRedundant = (o: CalendarOverride) => o.dayType === defaultDayType(o.date) && o.standardMinutes === null && !o.note

export function yearDates(year: number): string[] {
  const dates: string[] = []
  for (let day = `${year}-01-01`; day.startsWith(`${year}-`); day = addDays(day, 1)) dates.push(day)
  return dates
}

async function workbookClass() {
  const module = await import('exceljs')
  return module.Workbook ?? (module as unknown as { default: { Workbook: typeof module.Workbook } }).default.Workbook
}

const TYPE_BY_LABEL = new Map(Object.entries(DAY_TYPE_LABELS).map(([type, label]) => [label, type as CalendarDayType]))
const validDate = (value: string) => /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(value)) && toApiDate(value).toISOString().startsWith(value)

/** Sheet 1, from row 2: 日期 | 类型 | 名称 | 标准工时（小时）. */
export async function parseCalendarWorkbook(data: ArrayBuffer, year: number): Promise<{ overrides: CalendarOverride[]; errors: string[] }> {
  const Workbook = await workbookClass()
  const workbook = new Workbook()
  await workbook.xlsx.load(data)
  const overrides: CalendarOverride[] = [], errors: string[] = [], seen = new Set<string>()
  workbook.worksheets[0]?.eachRow((row, index) => {
    if (index === 1) return
    const [rawDate, rawType, rawNote, rawHours] = [1, 2, 3, 4].map(column => row.getCell(column).value)
    const date = rawDate instanceof Date ? rawDate.toISOString().slice(0, 10) : String(rawDate ?? '').trim()
    const dayType = TYPE_BY_LABEL.get(String(rawType ?? '').trim())
    const note = String(rawNote ?? '').trim() || null
    const hours = rawHours === null || rawHours === undefined || rawHours === '' ? null : Number(rawHours)
    if (!validDate(date) || !date.startsWith(`${year}-`)) errors.push(`第 ${index} 行：日期须为 ${year} 年的 YYYY-MM-DD`)
    else if (seen.has(date)) errors.push(`第 ${index} 行：日期 ${date} 重复`)
    else if (!dayType) errors.push(`第 ${index} 行：类型须为 工作日 / 休息日 / 法定节假日`)
    else if (note && note.length > 30) errors.push(`第 ${index} 行：名称不超过 30 字`)
    else if (hours !== null && (dayType !== 'WORKDAY' || !(hours > 0 && hours <= 12))) errors.push(`第 ${index} 行：仅工作日可填写 0–12 小时的标准工时`)
    else {
      seen.add(date)
      overrides.push({ date, dayType, standardMinutes: hours === null ? null : Math.round(hours * 60), note })
    }
  })
  return { overrides: overrides.filter(o => !isRedundant(o)), errors }
}

/** Header row plus the year's current explicit days, so the template doubles as an export. */
export async function buildCalendarTemplate(overrides: CalendarOverride[]): Promise<Blob> {
  const Workbook = await workbookClass()
  const workbook = new Workbook()
  const sheet = workbook.addWorksheet('工作日历')
  sheet.columns = [{ header: '日期', width: 14 }, { header: '类型', width: 12 }, { header: '名称', width: 18 }, { header: '标准工时（小时）', width: 16 }]
  sheet.getRow(1).font = { bold: true }
  for (const o of [...overrides].sort((a, b) => a.date.localeCompare(b.date)))
    sheet.addRow([o.date, DAY_TYPE_LABELS[o.dayType], o.note ?? '', o.standardMinutes === null ? '' : o.standardMinutes / 60])
  return new Blob([await workbook.xlsx.writeBuffer()], { type: XLSX_TYPE })
}
