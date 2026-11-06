import { effectScope, ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { useWorkItemFilters } from './useWorkItemFilters'

function setup() {
  const scope = effectScope()
  const searchInput = ref('  搜索  ')
  const syncUrl = vi.fn().mockResolvedValue(undefined)
  const filter = scope.run(() => useWorkItemFilters({
    route: { query: {} }, projectId: ref('project'), activeMembers: ref([]), tableItems: ref([]),
    tableSource: { listProjectWorkItemFilterOptions: vi.fn() }, listRequest: () => ({ projectId: 'project' }),
    searchInput, error: ref(), syncUrl, getRevision: () => 0, getSignal: () => null,
  }))!
  return { filter, searchInput, syncUrl, scope }
}

describe('工作项筛选', () => {
  it('多选逐项计数，日期范围和更新时间各算一个，搜索词不计数', () => {
    const { filter, scope } = setup()
    expect(filter.activeFilterCount.value).toBe(0)
    const { filters } = filter
    filters.assignees = new Set(['a', 'b'])
    filters.statuses.add('BACKLOG')
    filters.priorities.add('HIGH')
    filters.contents.add('content')
    filters.dueRange = [new Date(2026, 0, 1), new Date(2026, 0, 31)]
    filters.updatedAfter = new Date(2026, 0, 2)
    expect(filter.activeFilterCount.value).toBe(7)
    scope.stop()
  })

  it('将搜索与筛选映射为请求字段，状态由调用方按视图补入', () => {
    const { filter, searchInput, scope } = setup()
    searchInput.value = '  '
    expect(filter.filterRequest()).toEqual({})
    searchInput.value = '  搜索  '
    const { filters } = filter
    filters.statuses.add('BACKLOG')
    filters.priorities.add('HIGH')
    filters.assignees.add('a')
    filters.contents.add('content')
    const from = new Date(2026, 0, 1), to = new Date(2026, 0, 31), after = new Date(2026, 0, 2)
    filters.dueRange = [from, to]
    filters.updatedAfter = after
    expect(filter.filterRequest()).toEqual({
      q: '搜索', priority: new Set(['HIGH']), assigneeUserId: new Set(['a']), contentId: new Set(['content']),
      dueFrom: from, dueTo: to, updatedAfter: after,
    })
    scope.stop()
  })

  it('清除全部重置所有筛选条件，保留搜索词并同步视图查询', () => {
    const { filter, syncUrl, scope } = setup()
    const { filters } = filter
    for (const selection of [filters.assignees, filters.statuses, filters.priorities, filters.contents]) selection.add('selected')
    filters.dueRange = [new Date(2026, 0, 1), new Date(2026, 0, 31)]
    filters.updatedAfter = new Date(2026, 0, 2)
    filter.clearFilters()
    expect(filter.activeFilterCount.value).toBe(0)
    expect(filter.filterRequest()).toEqual({ q: '搜索' })
    expect(syncUrl).toHaveBeenCalledTimes(1)
    scope.stop()
  })
})
