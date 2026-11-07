import { describe, expect, it } from 'vitest'
import { formatDate, getPriorityPresentation, getStatusTone } from './workItemPresentation'

describe('工作项表格呈现', () => {
  it.each([
    ['TEAL', 'green'], ['BLUE', 'blue'], ['RED', 'red'], ['AMBER', 'yellow'], ['GRAY', 'gray'], ['CUSTOM', 'gray'],
  ])('状态优先使用目录颜色 %s', (colorToken, tone) => {
    expect(getStatusTone([{ statusCode: 'BLOCKED', displayName: '阻塞', colorToken, statusCategory: 'DONE' }], 'BLOCKED')).toBe(tone)
  })

  it.each([['DONE', 'green'], ['IN_PROGRESS', 'yellow'], ['CANCELED', 'gray']])('无颜色时使用状态类别 %s', (statusCategory, tone) => {
    expect(getStatusTone([{ statusCode: 'BLOCKED', displayName: '阻塞', statusCategory }], 'BLOCKED')).toBe(tone)
  })

  it.each([['bug', 'red'], ['review', 'blue'], ['plan', 'neutral'], ['custom', 'gray']])('按状态代码 %s 回退', (code, tone) => {
    expect(getStatusTone([], code)).toBe(tone)
  })

  it.each([['RED', 'urgent'], ['AMBER', 'high'], ['LIME', 'low'], ['CYAN', 'medium'], ['GRAY', 'empty'], ['CUSTOM', 'empty']])(
    '目录优先级使用显示名及颜色 %s', (colorToken, tone) => {
      expect(getPriorityPresentation([{ code: 'LOW', displayName: '稍后', colorToken }], 'LOW')).toEqual({ label: '稍后', tone })
    },
  )

  it.each([
    [null, '-', 'empty'], ['urgent', '紧急', 'urgent'], ['high', '高', 'high'], ['medium', '中', 'medium'],
    ['low', '低', 'low'], ['Custom', 'Custom', 'empty'],
  ])('目录缺少优先级 %s 时保留对应默认文字与颜色', (code, label, tone) => {
    expect(getPriorityPresentation([], code)).toEqual({ label, tone })
  })

  it('日期按 UTC 日历截取，空日期显示占位符', () => {
    expect(formatDate(new Date('2026-10-31T20:00:00-04:00'))).toBe('2026-11-01')
    expect(formatDate('2026-11-01')).toBe('2026-11-01')
    expect(formatDate(null)).toBe('—')
  })
})
