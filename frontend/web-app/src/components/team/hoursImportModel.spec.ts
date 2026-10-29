import { describe, expect, it } from 'vitest'
import type { SheetCell } from '../import/spreadsheet'
import { buildEntries, candidatesFor, columnLabel, defaultChoice, describeIssue, duplicateTargets, hoursLabel, hoursToMinutes, parseHoursMatrix,
  type ParsedMatrix } from './hoursImportModel'

function parsed(rows: SheetCell[][]): ParsedMatrix {
  const result = parseHoursMatrix(rows)
  if (!result.ok) throw new Error(result.message)
  return result.matrix
}

describe('迁入工时矩阵解析', () => {
  it('跳过表头前的标题行，按文本日期识别日期列', () => {
    const matrix = parsed([
      ['工时统计表', null, null],
      ['2026 年 9 月', null, null],
      ['姓名', '2026-09-01', '2026-09-02'],
      ['张三', 8, 7.5],
    ])
    expect(matrix.headerRow).toBe(3)
    expect(matrix.dates).toEqual(['2026-09-01', '2026-09-02'])
    expect(matrix.errors).toEqual([])
    expect(matrix.members).toEqual([{ name: '张三', rowNumber: 4, totalMinutes: 930, days: [{ date: '2026-09-01', minutes: 480 }, { date: '2026-09-02', minutes: 450 }] }])
  })

  it('表头可以是 Excel 日期或序列号，姓名列按表头文字查找', () => {
    const matrix = parsed([
      ['序号', '成员', new Date(2026, 8, 1), 46267],
      [1, '李四', '4', '2'],
    ])
    expect(matrix.nameColumn).toBe(1)
    expect(matrix.dates).toEqual(['2026-09-01', '2026-09-02'])
    expect(matrix.ignoredColumns).toEqual(['序号'])
    expect(matrix.members[0]!.days).toEqual([{ date: '2026-09-01', minutes: 240 }, { date: '2026-09-02', minutes: 120 }])
  })

  it('找不到姓名表头时用第 1 列，合计列作为已忽略列，合计行被忽略', () => {
    const matrix = parsed([
      ['', '2026/9/1', '2026/9/2', '合计'],
      ['张三', 8, 8, 16],
      ['王五', 4, null, 4],
      ['合 计', 12, 8, 20],
      ['总计', 12, 8, 20],
    ])
    expect(matrix.ignoredColumns).toEqual(['合计'])
    expect(matrix.ignoredRows).toEqual(['合 计', '总计'])
    expect(matrix.members.map(member => member.name)).toEqual(['张三', '王五'])
    expect(matrix.errors).toEqual([])
  })

  it('空白单元格跳过，0 保留为清除', () => {
    const matrix = parsed([
      ['姓名', '2026-09-01', '2026-09-02', '2026-09-03'],
      ['张三', null, 0, '  '],
      [null, null, null, null],
    ])
    expect(matrix.members).toHaveLength(1)
    expect(matrix.members[0]!.days).toEqual([{ date: '2026-09-02', minutes: 0 }])
  })

  it('小时换算成分钟，7.5 小时等于 450 分钟', () => {
    expect(hoursToMinutes(7.5)).toBe(450)
    expect(hoursToMinutes(24)).toBe(1440)
    expect(hoursToMinutes(0.1)).toBe(6)
    expect(hoursToMinutes(25)).toBeNull()
    expect(hoursToMinutes(-1)).toBeNull()
    expect(hoursToMinutes(null)).toBeNull()
    expect(parsed([['姓名', '2026-09-01'], ['张三', '7.5']]).members[0]!.days[0]!.minutes).toBe(450)
  })

  it('非数字、负数和大于 24 的单元格报错并指出位置', () => {
    const matrix = parsed([
      ['姓名', '2026-09-01', '2026-09-02', '2026-09-03', '2026-09-04'],
      ['张三', '请假', 25, -1, 8],
    ])
    expect(matrix.errors.map(error => error.message)).toEqual([
      'B2（张三，2026-09-01）「请假」不是 0 到 24 的小时数。',
      'C2（张三，2026-09-02）「25」不是 0 到 24 的小时数。',
      'D2（张三，2026-09-03）「-1」不是 0 到 24 的小时数。',
    ])
    expect(matrix.members[0]!.days).toEqual([{ date: '2026-09-04', minutes: 480 }])
  })

  it('成员重复和日期列重复报错', () => {
    const matrix = parsed([
      ['姓名', '2026-09-01', '2026-09-01'],
      ['张三', 8, 8],
      ['张三', 4, 4],
    ])
    expect(matrix.errors.map(error => error.message)).toEqual([
      '日期 2026-09-01 在 B 列和 C 列重复。',
      '成员「张三」在第 2 行和第 3 行重复。',
    ])
    expect(matrix.members).toHaveLength(1)
  })

  it('有工时但没有姓名的行报错', () => {
    expect(parsed([['姓名', '2026-09-01'], [null, 8], ['张三', 8]]).errors.map(error => error.message)).toEqual(['第 2 行有工时但没有姓名。'])
  })

  it('前 10 行没有日期表头时无法解析', () => {
    expect(parseHoursMatrix([['姓名', '部门'], ['张三', '研发']]).ok).toBe(false)
    const late = Array.from({ length: 10 }, () => ['x', 'y'] as SheetCell[])
    expect(parseHoursMatrix([...late, ['姓名', '2026-09-01'], ['张三', 8]]).ok).toBe(false)
  })

  it('没有成员行时报错', () => {
    expect(parsed([['姓名', '2026-09-01']]).errors.map(error => error.message)).toEqual(['没有找到成员行。'])
  })

  it('列名与表格软件一致', () => {
    expect([0, 1, 25, 26, 27, 701, 702].map(columnLabel)).toEqual(['A', 'B', 'Z', 'AA', 'AB', 'ZZ', 'AAA'])
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

  it('跳过的成员不进请求，日期按自然日序列化', () => {
    const matrix = parsed([['姓名', '2026-09-01', '2026-09-02'], ['张三', 8, 7.5], ['王五', 4, 4]])
    const entries = buildEntries(matrix.members, ['u1', null])
    expect(entries.map(entry => [entry.userId, entry.minutes])).toEqual([['u1', 480], ['u1', 450]])
    expect(entries.map(entry => entry.workDate.toISOString().slice(0, 10))).toEqual(['2026-09-01', '2026-09-02'])
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
