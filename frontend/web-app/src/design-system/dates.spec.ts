import { describe, expect, it } from 'vitest'
import { formatChineseTimestamp, formatDateOnly, formatRelativeTime, formatTimestamp } from './dates'

describe('日期格式化约定', () => {
  it('短日期只显示到日（列表/表格默认）', () => {
    expect(formatDateOnly('2026-08-23T10:30:00Z', 'Asia/Shanghai')).toBe('2026-08-23')
  })

  it('完整时间戳包含时分（tooltip/title 用）', () => {
    expect(formatTimestamp('2026-08-23T10:30:00Z', 'Asia/Shanghai')).toBe('2026-08-23 18:30')
  })

  it('按公司时区换算日期边界', () => {
    expect(formatDateOnly('2026-08-22T20:30:00Z', 'Asia/Shanghai')).toBe('2026-08-23')
    expect(formatDateOnly('2026-08-22T20:30:00Z', 'UTC')).toBe('2026-08-22')
    expect(formatTimestamp('2026-08-22T20:30:00Z', 'UTC')).toBe('2026-08-22 20:30')
  })

  it('接受 Date 实例与字符串两种入参', () => {
    const instant = new Date('2026-08-23T02:00:00Z')
    expect(formatDateOnly(instant, 'Asia/Shanghai')).toBe(formatDateOnly('2026-08-23T02:00:00Z', 'Asia/Shanghai'))
  })

  it('动态使用中文完整时间与相对时间', () => {
    expect(formatChineseTimestamp('2026-09-01T10:30:00Z', 'Asia/Shanghai')).toBe('2026年9月1日 18:30')
    expect(formatRelativeTime('2026-09-01T10:00:00Z', new Date('2026-09-01T10:42:00Z'))).toBe('42分钟前')
    expect(formatRelativeTime('2026-08-30T10:00:00Z', new Date('2026-09-01T10:00:00Z'))).toBe('2天前')
  })
  it('评论按整数分钟小时天向下取整，超过一年仍以天显示', () => {
    const start = new Date('2020-01-01T00:00:00Z')
    for (const [seconds, expected] of [[59, '刚刚'], [60, '1分钟前'], [3599, '59分钟前'], [3600, '1小时前'], [86399, '23小时前'], [86400, '1天前'], [400 * 86400, '400天前']] as const) {
      expect(formatRelativeTime(start, new Date(start.getTime() + seconds * 1000), 'day')).toBe(expected)
    }
    expect(formatRelativeTime(start, new Date(start.getTime() + 400 * 86400 * 1000))).toBe('1年前')
  })

})
