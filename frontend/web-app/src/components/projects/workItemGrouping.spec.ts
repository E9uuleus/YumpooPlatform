import { describe, expect, it } from 'vitest'
import { ListProjectWorkItemsEmptyFieldEnum, WorkItemLabelColorToken, type ListProjectWorkItemsRequest,
  type ProjectWorkItemListItem } from '@yumpoo/api-client'
import { assigneeGroupColor, buildWorkItemGroups, dateGroupKey, dateGroups, EMPTY_GROUP,
  groupListRequest, itemGroupKey, type GroupSources } from './workItemGrouping'
import { companyDate } from './workItemDueDate'

const sources: GroupSources = {
  members: [{ userId: 'a', displayName: '同名' }, { userId: 'b', displayName: '同名' }],
  statuses: [
    { code: 'TODO', displayName: '待处理', colorToken: WorkItemLabelColorToken.BrightBlue, sortOrder: 2, active: true },
    { code: 'DONE', displayName: '完成', colorToken: WorkItemLabelColorToken.BrightGreen, sortOrder: 1, active: false },
    { code: 'OLD', displayName: '停用', colorToken: WorkItemLabelColorToken.Gray, sortOrder: 3, active: false },
  ],
  priorities: [{ code: 'HIGH', displayName: '高', colorToken: WorkItemLabelColorToken.DarkRed, sortOrder: 1, active: true }],
  contents: [{ id: 'bug', name: '缺陷', colorToken: WorkItemLabelColorToken.DarkRed, sortOrder: 0, active: true }],
}
const base: ListProjectWorkItemsRequest = { projectId: 'project', limit: 25 }
const sample = { assigneeUserId: 'a', priority: null, contentId: 'bug', statusCode: 'TODO', dueDate: new Date('2026-09-15T00:00:00Z') } as ProjectWorkItemListItem

describe('工作项分组规则', () => {
  it('根项按字段稳定身份分组，空值独立归组', () => {
    expect(['ASSIGNEE', 'PRIORITY', 'CONTENT', 'STATUS', 'DUE_DATE'].map(field =>
      itemGroupKey(sample, field as Parameters<typeof itemGroupKey>[1], '2026-09-14'))).toEqual(['a', EMPTY_GROUP, 'bug', 'TODO', 'TOMORROW'])
  })
  it('标签顺序和颜色来自目录，已使用的停用标签仍展示', () => {
    const groups = buildWorkItemGroups('STATUS', 'DEFAULT', true,
      [{ value: 'DONE', label: '完成', count: 3 }], sources, '2026-09-14', {})
    expect(groups.map(group => [group.key, group.count])).toEqual([['DONE', 3], ['TODO', 0]])
    expect(groups[0]!.color).toBe('var(--yp-label-bright-green)')
  })
  it('空值在正反排序均置底，名称相同使用稳定 ID', () => {
    const options = [{ value: EMPTY_GROUP, label: '未分配', count: 1 }, { value: 'b', label: '同名', count: 2 }]
    for (const order of ['NAME_ASC', 'NAME_DESC'] as const) {
      const groups = buildWorkItemGroups('ASSIGNEE', order, true, options, sources, '2026-09-14', {})
      expect(groups.map(group => group.key)).toEqual(['a', 'b', EMPTY_GROUP])
    }
  })
  it('优先级和类别沿用颜色，默认不展示空组', () => {
    expect(buildWorkItemGroups('PRIORITY', 'DEFAULT', false, [], sources, '2026-09-14', {})).toEqual([])
    expect(buildWorkItemGroups('CONTENT', 'DEFAULT', false, [{ value: 'bug', label: '缺陷', count: 2 }], sources, '2026-09-14', {})[0]!.color).toBe('var(--yp-label-dark-red)')
  })
  it('处理人颜色避免初期碰撞并在筛选、新增和排序后保持稳定', () => {
    const colors = {}
    const first = assigneeGroupColor('a', colors)
    const second = assigneeGroupColor('b', colors)
    expect(first).not.toBe(second)
    assigneeGroupColor('new-user', colors)
    expect(assigneeGroupColor('a', JSON.parse(JSON.stringify(colors)))).toBe(first)
  })
  it.each(['2026-09-12', '2026-09-13', '2026-09-14', '2026-12-31', '2028-02-28'])('日期区间在 %s 前后互斥且完整', today => {
    const groups = dateGroups(today).filter(group => group.key !== EMPTY_GROUP)
    for (let offset = -40; offset <= 45; offset++) {
      const date = new Date(Date.parse(`${today}T00:00:00Z`) + offset * 86_400_000).toISOString().slice(0, 10)
      const matching = groups.filter(group => (!group.from || date >= group.from) && (!group.to || date <= group.to))
      expect(matching, date).toHaveLength(1)
      expect(dateGroupKey(date, today)).toBe(matching[0]!.key)
    }
  })
  it('企业时区跨午夜会改变今天，完成状态不参与日期归组', () => {
    const instant = new Date('2026-09-14T17:00:00Z')
    expect(dateGroupKey(sample.dueDate, companyDate(instant, 'Asia/Shanghai'))).toBe('TODAY')
    expect(dateGroupKey(sample.dueDate, companyDate(instant, 'America/New_York'))).toBe('TOMORROW')
  })
  it('汇总同一日期组内的全量计数，时间排序将过去放最前、空值放最后', () => {
    const groups = buildWorkItemGroups('DUE_DATE', 'DATE_ASC', false, [
      { value: '2026-09-16', label: '', count: 2 }, { value: '2026-09-17', label: '', count: 5 },
      { value: '2026-09-12', label: '', count: 1 }, { value: EMPTY_GROUP, label: '', count: 3 },
    ], sources, '2026-09-14', {})
    expect(groups.map(group => [group.key, group.count])).toEqual([['PAST', 1], ['THIS_WEEK', 7], [EMPTY_GROUP, 3]])
  })
  it('组内查询与原筛选交集，不覆盖日期边界', () => {
    const group = dateGroups('2026-09-14').find(group => group.key === 'THIS_WEEK')!
    const request = groupListRequest({ ...base, dueFrom: new Date('2026-09-17'), dueTo: new Date('2026-09-18') }, 'DUE_DATE', group)!
    expect(request.dueFrom!.toISOString().slice(0, 10)).toBe('2026-09-17')
    expect(request.dueTo!.toISOString().slice(0, 10)).toBe('2026-09-18')
    expect(groupListRequest({ ...base, dueTo: new Date('2026-09-15') }, 'DUE_DATE', group)).toBeNull()
    expect(groupListRequest({ ...base, status: new Set(['DONE']) }, 'STATUS', { ...group, key: 'TODO' })).toBeNull()
  })
  it('空值组使用新增查询参数，并保留互斥筛选供服务端求交集', () => {
    const group = dateGroups('2026-09-14').at(-1)!
    const request = groupListRequest({ ...base, priority: new Set(['HIGH']) }, 'PRIORITY', group)!
    expect(request.emptyField).toBe(ListProjectWorkItemsEmptyFieldEnum.Priority)
    expect(request.priority).toEqual(new Set(['HIGH']))
  })
})
