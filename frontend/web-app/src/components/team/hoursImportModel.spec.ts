import { describe, expect, it } from 'vitest'
import type { WorkbookSheet } from '../import/spreadsheet'
import { candidatesFor, defaultChoice, describeIssue, duplicateTargets, hoursLabel, hoursToMinutes, importRows, parseHoursDetail, requestRow,
  type ParsedDetail } from './hoursImportModel'

function parsed(sheets: WorkbookSheet[]): ParsedDetail {
  const result = parseHoursDetail(sheets)
  if (!result.ok) throw new Error(result.message)
  return result.detail
}

const headers = ['责任人', '日期', '星期', 'ID', '标题', '花费工时', '工作量单位', '所属项目', '备注']
const overview: WorkbookSheet = { name: 'Overview', rows: [['工时花费报表'], ['责任人', '09-01\n星期二', '总花费工时'], ['张三', 3.5, 6.5]] }
const zhangSan: WorkbookSheet = { name: '张三', rows: [
  ['工时花费报表'], headers,
  ['张三', '2026-09-01', '星期二', '1', '接口开发', 1.5, '人时', '交付项目', ''],
  [null, null, null, '2', '联调测试', 2, '人时', '交付项目', ''],
  [null, '2026-09-05', '星期六', null, null, null, null, null, null],
  [null, '2026-09-07', '星期一', '3', '接口开发', 3, '人时', '交付项目', ''],
  ['总花费工时', null, null, null, null, 6.5, '人时', '', ''],
] }

describe('工时明细解析', () => {
  it('忽略汇总表；责任人与日期沿用合并单元格的上一行；标题为空的行忽略', () => {
    const detail = parsed([overview, zhangSan])
    expect(detail.sheets).toEqual(['张三'])
    expect(detail.ignoredSheets).toEqual(['Overview'])
    expect(detail.errors).toEqual([])
    expect(detail.dates).toEqual(['2026-09-01', '2026-09-07'])
    expect(detail.rows.map(row => [row.name, row.date, row.projectName, row.title, row.minutes])).toEqual([
      ['张三', '2026-09-01', '交付项目', '接口开发', 90], ['张三', '2026-09-01', '交付项目', '联调测试', 120],
      ['张三', '2026-09-07', '交付项目', '接口开发', 180],
    ])
    expect(detail.rows[2]!.rowNumber).toBe(6)
    expect(detail.members).toEqual([{ name: '张三', days: 2, totalMinutes: 390 }])
  })

  it('花费工时不是 0 到 24 的小时数、日期无法识别时报错', () => {
    const detail = parsed([{ name: '张三', rows: [
      ['工时花费报表'], headers,
      ['张三', '2026-09-01', '', '1', '接口开发', '请假', '人时', '交付项目', ''],
      [null, '错误日期', '', '2', '联调测试', 2, '人时', '交付项目', ''],
      [null, '2026-09-07', '', '3', '接口开发', 3, '人时', '交付项目', ''],
    ] }])
    expect(detail.errors).toEqual(['「张三」第 3 行的花费工时「请假」不是 0 到 24 的小时数。', '「张三」第 4 行的日期无法识别。'])
  })

  it('没有明细表时无法解析', () => {
    expect(parseHoursDetail([overview]).ok).toBe(false)
  })

  it('小时换算成分钟，7.5 小时等于 450 分钟', () => {
    expect(hoursToMinutes(7.5)).toBe(450)
    expect(hoursToMinutes(24)).toBe(1440)
    expect(hoursToMinutes(0.1)).toBe(6)
    expect(hoursToMinutes(25)).toBeNull()
    expect(hoursToMinutes(-1)).toBeNull()
    expect(hoursToMinutes(null)).toBeNull()
  })
})

describe('成员匹配与请求条目', () => {
  const candidates = [{ userId: 'u1', displayName: '张三' }, { userId: 'u2', displayName: ' 李四 ' }, { userId: 'u3', displayName: '李四' }]

  it('唯一匹配自动选中，没有匹配跳过，同名多人待选', () => {
    expect(candidatesFor('李四', candidates).map(candidate => candidate.userId)).toEqual(['u2', 'u3'])
    expect(defaultChoice('张三', candidates)).toBe('u1')
    expect(defaultChoice('王五', candidates)).toBeNull()
    expect(defaultChoice('李四', candidates)).toBeUndefined()
  })

  it('找出被选了两次的成员', () => {
    expect(duplicateTargets(['u1', null, 'u1', undefined, 'u2'])).toEqual(['u1'])
    expect(duplicateTargets(['u1', 'u2'])).toEqual([])
  })

  it('跳过的成员不进请求，按文件顺序生成请求行', () => {
    const detail = parsed([zhangSan, { name: '王五', rows: [headers, ['王五', '2026-09-01', '星期二', '4', '接口开发', 4, '人时', '交付项目', '']] }])
    const rows = importRows(detail, ['u1', null]).map(requestRow)
    expect(rows.map(row => [row.userId, row.projectName, row.workItemTitle, row.minutes]))
      .toEqual([['u1', '交付项目', '接口开发', 90], ['u1', '交付项目', '联调测试', 120], ['u1', '交付项目', '接口开发', 180]])
    expect(rows.map(row => row.workDate.toISOString().slice(0, 10))).toEqual(['2026-09-01', '2026-09-01', '2026-09-07'])
  })
})

describe('结果说明', () => {
  it('小时数保留两位，问题码转中文', () => {
    expect(hoursLabel(450)).toBe('7.5')
    expect(hoursLabel(100)).toBe('1.67')
    expect(describeIssue({ code: 'NOT_ENOUGH_FREE_TIME', detail: null })).toBe('当天空闲时间不够，未写入')
    expect(describeIssue({ code: 'REPLACES_PREVIOUS', detail: '480' })).toBe('覆盖原有迁入工时（原 8 小时）')
    expect(describeIssue({ code: 'SOMETHING_NEW', detail: null })).toBe('SOMETHING_NEW')
  })
})
