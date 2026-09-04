import { describe, expect, it } from 'vitest'
import { companyDate, dueDateInstant, dueDateKey, presentDueDate, type WorkItemDueDateFacts } from './workItemDueDate'

const zone = 'Asia/Shanghai'
const now = new Date('2026-09-03T04:00:00Z')
const item: WorkItemDueDateFacts = { dueDate: '2026-09-03', statusCategory: 'TODO' }
const present = (changes: Partial<WorkItemDueDateFacts> = {}, at = now) => presentDueDate({ ...item, ...changes }, zone, at)

describe('截止日期展示', () => {
  it('当年省略年份，跨年保留，时分和完整提示保留', () => {
    expect(present().text).toBe('09-03')
    expect(present({ dueDate: '2025-09-03' }).text).toBe('2025-09-03')
    expect(present({ dueTime: '18:05' })).toMatchObject({ text: '09-03 18:05', fullText: '2026-09-03 18:05' })
  })
  it('按企业日历跨午夜及跨年，纯日期不按浏览器时区换日', () => {
    expect(companyDate(new Date('2026-12-31T16:01:00Z'), zone)).toBe('2027-01-01')
    expect(present({ dueDate: '2026-12-31' }, new Date('2026-12-31T16:01:00Z')).text).toBe('2026-12-31')
    expect(dueDateKey(new Date('2026-09-03T00:00:00Z'))).toBe('2026-09-03')
    expect(presentDueDate(item, 'America/Los_Angeles', new Date('2026-09-03T00:00:00Z')).tone).toBe('none')
  })
  it.each([
    ['2026-09-02', 'red', '逾期 1 天'],
    ['2026-09-03', 'today', '今日截止'],
    ['2026-09-04', 'none', '2026-09-04'],
  ])('未完成日期 %s 的标记和提示', (dueDate, tone, message) => {
    expect(present({ dueDate })).toMatchObject({ tone, strike: false })
    expect(present({ dueDate }).tooltip).toContain(message)
  })
  it('纯日期到当天结束后才逾期', () => {
    expect(present({}, new Date('2026-09-03T15:59:59.999Z')).tone).toBe('today')
    expect(present({}, new Date('2026-09-03T16:00:00Z')).tooltip).toContain('逾期 1 天')
  })
  it.each([
    ['2026-09-01T10:00:00Z', 'green', true, '提前 2 天'],
    ['2026-09-03T15:59:59Z', 'green', true, '按时完成'],
    ['2026-09-04T00:00:00Z', 'red', false, '逾期 1 天'],
  ])('以完成事实 %s 判断，不以当前日期判断', (completedAt, tone, strike, message) => {
    const result = present({ statusCategory: 'DONE', completedAt }, new Date('2027-10-01T00:00:00Z'))
    expect(result).toMatchObject({ tone, strike })
    expect(result.tooltip).toContain(message)
  })
  it('已完成优先于今日截止；历史未知完成时间不推算', () => {
    expect(present({ statusCategory: 'DONE', completedAt: now })).toMatchObject({ tone: 'green', strike: true })
    expect(present({ statusCategory: 'DONE' })).toMatchObject({ tone: 'none', strike: false })
    expect(present({ statusCategory: 'DONE' }).tooltip).toContain('完成时间未记录')
  })
  it.each([
    ['2026-09-03T09:59:00Z', 'green', true, '提前不足 1 天'],
    ['2026-09-03T10:00:00Z', 'green', true, '按时完成'],
    ['2026-09-03T10:01:00Z', 'red', false, '逾期不足 1 天'],
    ['2026-09-04T11:01:00Z', 'red', false, '逾期 1 天'],
  ])('带时分按实际时刻 %s 判定', (completedAt, tone, strike, message) => {
    const result = present({ statusCategory: 'DONE', dueTime: '18:00', completedAt })
    expect(result).toMatchObject({ tone, strike })
    expect(result.tooltip).toContain(message)
  })
  it('今日超过具体截止时刻后由灰转红', () => {
    expect(present({ dueTime: '18:00' }, new Date('2026-09-03T09:59:00Z')).tone).toBe('today')
    expect(present({ dueTime: '18:00' }, new Date('2026-09-03T10:01:00Z')).tone).toBe('red')
  })
  it('空值、无效日期和取消状态不误报', () => {
    expect(present({ dueDate: null }).text).toBe('—')
    expect(present({ dueDate: '2026-02-30' }).text).toBe('—')
    expect(present({ dueDate: new Date('invalid') }).text).toBe('—')
    expect(present({ dueDate: '2025-01-01', statusCategory: 'CANCELED' }).tone).toBe('none')
  })
  it('夏令时重叠选择较早时刻，缺口向前平移', () => {
    expect(new Date(dueDateInstant('2026-11-01', '01:30', 'America/New_York')).toISOString()).toBe('2026-11-01T05:30:00.000Z')
    expect(new Date(dueDateInstant('2026-03-08', '02:30', 'America/New_York')).toISOString()).toBe('2026-03-08T07:30:00.000Z')
  })
})
