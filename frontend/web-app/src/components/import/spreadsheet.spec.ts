import * as XLSX from 'xlsx'
import { describe, expect, it } from 'vitest'
import {
  cellDate,
  cellNumber,
  cellText,
  decodeCsv,
  readSpreadsheet,
  SpreadsheetError,
  trimTrailingBlanks,
  validateSpreadsheetFile,
} from './spreadsheet'

const fileOf = (parts: BlobPart[], name: string): File => new File(parts, name)

describe('表格单元格工具', () => {
  it('cellText 去掉两端空白并按 ISO 日期输出 Date', () => {
    expect(cellText('  　标题  ')).toBe('标题')
    expect(cellText(null)).toBe('')
    expect(cellText(12)).toBe('12')
    expect(cellText(new Date(2026, 9, 5))).toBe('2026-10-05')
  })

  it('cellNumber 接受数字与数字文本，拒绝其他', () => {
    expect(cellNumber(7.5)).toBe(7.5)
    expect(cellNumber(' 8 ')).toBe(8)
    expect(cellNumber('1,200.5')).toBe(1200.5)
    expect(cellNumber('-2')).toBe(-2)
    expect(cellNumber('.5')).toBe(0.5)
    expect(cellNumber('')).toBeNull()
    expect(cellNumber('8h')).toBeNull()
    expect(cellNumber('合计')).toBeNull()
    expect(cellNumber(Number.NaN)).toBeNull()
    expect(cellNumber(new Date())).toBeNull()
    expect(cellNumber(null)).toBeNull()
  })

  it('cellDate 识别各种日期写法', () => {
    expect(cellDate(new Date(2026, 0, 9))).toBe('2026-01-09')
    expect(cellDate('2026-10-05')).toBe('2026-10-05')
    expect(cellDate('2026/1/9')).toBe('2026-01-09')
    expect(cellDate('2026.1.9')).toBe('2026-01-09')
    expect(cellDate('2026年1月9日')).toBe('2026-01-09')
    expect(cellDate(' 2026-10-05 ')).toBe('2026-10-05')
    expect(cellDate('2026-10-05 08:30:00')).toBe('2026-10-05')
    expect(cellDate(46_000)).toBe('2025-12-09')
    expect(cellDate('46000')).toBe('2025-12-09')
    expect(cellDate(45_292.75)).toBe('2024-01-01')
  })

  it('cellDate 拒绝无法识别或不存在的日期', () => {
    expect(cellDate('2026-02-30')).toBeNull()
    expect(cellDate('2026-13-01')).toBeNull()
    expect(cellDate('10月5日')).toBeNull()
    expect(cellDate('合计')).toBeNull()
    expect(cellDate('')).toBeNull()
    expect(cellDate(null)).toBeNull()
    expect(cellDate(8)).toBeNull()
    expect(cellDate('8')).toBeNull()
    expect(cellDate(0.5)).toBeNull()
    expect(cellDate(19_999)).toBeNull()
    expect(cellDate(73_051)).toBeNull()
    expect(cellDate(Number.NaN)).toBeNull()
    expect(cellDate(new Date(Number.NaN))).toBeNull()
  })
})

describe('文件校验与解码', () => {
  it('校验扩展名、大小与空文件', () => {
    expect(validateSpreadsheetFile({ name: 'a.XLSX', size: 10 })).toBeNull()
    expect(validateSpreadsheetFile({ name: 'a.csv', size: 10 })).toBeNull()
    expect(validateSpreadsheetFile({ name: 'a.xls', size: 10 })).toBeNull()
    expect(validateSpreadsheetFile({ name: 'a.pdf', size: 10 })).toContain('只支持')
    expect(validateSpreadsheetFile({ name: 'noext', size: 10 })).toContain('只支持')
    expect(validateSpreadsheetFile({ name: 'a.csv', size: 5 * 1024 * 1024 + 1 })).toContain('5 MB')
    expect(validateSpreadsheetFile({ name: 'a.csv', size: 5 * 1024 * 1024 })).toBeNull()
    expect(validateSpreadsheetFile({ name: 'a.csv', size: 0 })).toContain('空')
  })

  it('CSV 先按 UTF-8 严格解码并去掉 BOM，失败再回退 gb18030', () => {
    const utf8 = new TextEncoder().encode('﻿标题,状态')
    expect(decodeCsv(utf8.buffer.slice(utf8.byteOffset, utf8.byteOffset + utf8.byteLength) as ArrayBuffer)).toBe('标题,状态')
    // 「标题」的 gb18030 编码，不是合法 UTF-8。
    const gbk = new Uint8Array([0xb1, 0xea, 0xcc, 0xe2])
    expect(decodeCsv(gbk.buffer)).toBe('标题')
  })
})

describe('trimTrailingBlanks', () => {
  it('去掉尾部全空的行列、补齐宽度，保留中间的空行', () => {
    expect(trimTrailingBlanks([
      ['a', 'b', null, null],
      [null, null, null, null],
      ['c'],
      [null, '', null, null],
      [],
    ])).toEqual([
      ['a', 'b'],
      [null, null],
      ['c', null],
    ])
    expect(trimTrailingBlanks([[null], []])).toEqual([])
  })
})

describe('readSpreadsheet', () => {
  it('读取 xlsx 第一个工作表，保留数字、日期与空单元格', async () => {
    const book = XLSX.utils.book_new()
    XLSX.utils.book_append_sheet(book, XLSX.utils.aoa_to_sheet([
      ['标题', '数量', '截止'],
      ['  子项', 3.5, new Date(2026, 9, 5)],
      ['空', null, '2026-10-06'],
    ], { cellDates: true }), '第一页')
    XLSX.utils.book_append_sheet(book, XLSX.utils.aoa_to_sheet([['忽略']]), '第二页')
    const bytes = XLSX.write(book, { type: 'array', bookType: 'xlsx', cellDates: true }) as ArrayBuffer
    const rows = await readSpreadsheet(fileOf([bytes], 'a.xlsx'))
    expect(rows).toHaveLength(3)
    expect(rows[0]).toEqual(['标题', '数量', '截止'])
    expect(rows[1]![0]).toBe('  子项')
    expect(rows[1]![1]).toBe(3.5)
    expect(cellDate(rows[1]![2])).toBe('2026-10-05')
    expect(rows[2]![1]).toBeNull()
    expect(cellDate(rows[2]![2])).toBe('2026-10-06')
  })

  it('读取 UTF-8 与 gb18030 的 CSV', async () => {
    const utf8 = await readSpreadsheet(fileOf(['﻿标题,状态\n任务,已完成\n'], 'a.csv'))
    expect(utf8).toEqual([['标题', '状态'], ['任务', '已完成']])
    const gbk = await readSpreadsheet(fileOf([new Uint8Array([0xb1, 0xea, 0xcc, 0xe2, 0x2c, 0xd7, 0xb4, 0xcc, 0xac, 0x0a])], 'b.csv'))
    expect(gbk[0]).toEqual(['标题', '状态'])
  })

  it('CSV 保留原文，由映射字段决定数字和日期的含义', async () => {
    const rows = await readSpreadsheet(fileOf(['标题,父需求,截止日期,工时\n00123,1-2,2026-02-30,8\n1-2,,2026-10-20,7.5\n'], 'a.csv'))
    expect(rows[1]).toEqual(['00123', '1-2', '2026-02-30', '8'])
    expect(rows[2]).toEqual(['1-2', null, '2026-10-20', '7.5'])
    expect(cellDate(rows[1]![2])).toBeNull()
    expect(cellDate(rows[2]![2])).toBe('2026-10-20')
    expect(cellNumber(rows[2]![3])).toBe(7.5)
  })

  it('拒绝不支持的文件与损坏的文件', async () => {
    await expect(readSpreadsheet(fileOf(['x'], 'a.txt'))).rejects.toBeInstanceOf(SpreadsheetError)
    await expect(readSpreadsheet(fileOf([], 'a.xlsx'))).rejects.toThrow('空')
  })
})
