import { describe, expect, it } from 'vitest'
import { TimeCategory } from '@yumpoo/api-client'
import { categoryKey, categoryOf, matchCategories, resumeSubjects, sessionKey, subjectFields, subjectSlot, TIME_CATEGORIES, timerTitle } from './timeCategories'

describe('非项目分类', () => {
  it('按固定顺序列出四类，键与线上枚举一致', () => {
    expect(TIME_CATEGORIES.map(option => option.label)).toEqual(['出差', '学习', '商务', '会议'])
    expect(TIME_CATEGORIES.map(option => option.code)).toEqual([TimeCategory.BusinessTrip, TimeCategory.Learning, TimeCategory.Business, TimeCategory.Meeting])
    expect(new Set(TIME_CATEGORIES.map(option => option.colorToken)).size).toBe(4)
  })

  it('键在工作项与分类之间往返，请求字段只带其一', () => {
    expect(categoryOf(categoryKey(TimeCategory.Meeting))?.label).toBe('会议')
    expect(categoryOf('3f0d6a2e-0000-4000-8000-000000000001')).toBeUndefined()
    expect(subjectFields('category:LEARNING')).toEqual({ timeCategory: TimeCategory.Learning })
    expect(subjectFields('w1')).toEqual({ workItemId: 'w1' })
    expect(subjectSlot('category:MEETING:p1')).toBe(subjectSlot('category:MEETING'))
    expect(subjectSlot('w1')).toBe('w1')
    expect(sessionKey({ workItemId: null, timeCategory: TimeCategory.Business })).toBe('category:BUSINESS')
    expect(sessionKey({ workItemId: 'w1', timeCategory: null })).toBe('w1')
    expect(sessionKey({ workItemId: null })).toBeNull()
  })

  it('恢复组先列工作项再列分类，计时标题优先使用分类名', () => {
    expect(resumeSubjects({ resumeItems: [{ workItemId: 'w1' }], resumeCategorySubjects: [{ timeCategory: TimeCategory.Meeting }] })).toEqual(['w1', 'category:MEETING'])
    expect(resumeSubjects({ resumeItems: [] })).toEqual([])
    expect(timerTitle({ session: { timeCategory: TimeCategory.BusinessTrip }, workItemTitle: null })).toBe('出差')
    expect(timerTitle({ session: {}, workItemTitle: null })).toBe('不可见工作项')
  })

  it('按名称过滤，空查询返回全部', () => {
    expect(matchCategories('')).toHaveLength(4)
    expect(matchCategories(' 会 ').map(option => option.label)).toEqual(['会议'])
    expect(matchCategories('周报')).toEqual([])
  })
})
