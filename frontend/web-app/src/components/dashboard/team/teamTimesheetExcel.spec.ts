import ExcelJS from 'exceljs'
import { describe, expect, it } from 'vitest'
import { buildTimesheetWorkbook } from './teamTimesheetExcel'

describe('timesheet workbook scope', () => {
  it.each([
    { scopeLabel: '产品项目', memberScopeLabel: '张三、李四' },
    { scopeLabel: '全部项目', memberScopeLabel: '全部成员' },
  ])('records project and member filters in the exported workbook: $memberScopeLabel', async scope => {
    const blob = await buildTimesheetWorkbook({ rows: [], days: ['2026-10-08'], timezone: 'Asia/Shanghai',
      ...scope, exportedAt: '2026/10/08 15:00' })
    const workbook = new ExcelJS.Workbook()
    await workbook.xlsx.load(await blob.arrayBuffer())
    expect(workbook.getWorksheet('工时汇总')!.getCell('A2').value).toBe(
      `时区 Asia/Shanghai · 项目范围：${scope.scopeLabel} · 成员范围：${scope.memberScopeLabel} · 导出时间 2026/10/08 15:00`,
    )
  })
})
