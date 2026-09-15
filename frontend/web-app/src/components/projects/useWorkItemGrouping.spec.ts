import { mount, enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ProjectWorkItemListItem, ListProjectWorkItemsRequest } from '@yumpoo/api-client'
import { useWorkItemGrouping } from './useWorkItemGrouping'
import { EMPTY_GROUP } from './workItemGrouping'

const api = vi.hoisted(() => ({ listProjectWorkItems: vi.fn(), listProjectWorkItemFilterOptions: vi.fn() }))
vi.mock('../../api/client', () => ({ workItemsApi: api }))
enableAutoUnmount(afterEach)
const item = (id: string, assigneeUserId: string | null = 'a') => ({ id, assigneeUserId, statusCode: 'TODO', priority: null, dueDate: null } as ProjectWorkItemListItem)
function harness(initial: ProjectWorkItemListItem[] = [], protectedIds = new Set<string>()) {
  const items = ref(initial)
  const today = ref('2026-09-14')
  const preferenceKey = ref('grouping:user:company:project')
  const restore = vi.fn(async () => {})
  const request = ref<ListProjectWorkItemsRequest>({ projectId: 'project', limit: 2, q: '筛选' })
  let grouping!: ReturnType<typeof useWorkItemGrouping>
  mount(defineComponent({ setup() {
    grouping = useWorkItemGrouping({ items, request: () => request.value, preferenceKey: () => preferenceKey.value,
      sources: () => ({ members: [], contents: [], statuses: [], priorities: [] }), today: () => today.value,
      ready: () => true, restore, protectedIds: () => protectedIds })
    return () => null
  } }))
  return { grouping, items, today, preferenceKey, restore, request }
}
const facet = (value: string, count = 3) => ({ value, label: value, count })
beforeEach(() => {
  localStorage.clear(); vi.resetAllMocks()
  api.listProjectWorkItemFilterOptions.mockResolvedValue({ items: [facet('a')], nextCursor: null })
  api.listProjectWorkItems.mockResolvedValue({ items: [item('a1')], nextCursor: null })
})

describe('分组分页与本地偏好', () => {
  it('遍历超过100个分组的全部元数据，工作项只按需加载并传递筛选交集', async () => {
    api.listProjectWorkItemFilterOptions.mockResolvedValueOnce({ items: Array.from({ length: 100 }, (_, i) => facet(`u${i}`)), nextCursor: 'next' })
      .mockResolvedValueOnce({ items: [facet('u100')], nextCursor: null })
    const { grouping } = harness()
    await grouping.setField('ASSIGNEE')
    expect(grouping.groups.value).toHaveLength(101)
    expect(api.listProjectWorkItemFilterOptions.mock.calls[1]![0]).toMatchObject({ cursor: 'next', q: '筛选', limit: 100 })
    expect(api.listProjectWorkItems).not.toHaveBeenCalled()
    await grouping.load('u100')
    expect(api.listProjectWorkItems.mock.calls[0]![0]).toMatchObject({ q: '筛选', assigneeUserId: new Set(['u100']), limit: 2 })
  })
  it('每组独立分页，失败保留已有行，重试复用该组游标', async () => {
    api.listProjectWorkItemFilterOptions.mockResolvedValue({ items: [facet('a'), facet(EMPTY_GROUP)], nextCursor: null })
    api.listProjectWorkItems.mockResolvedValueOnce({ items: [item('a1')], nextCursor: 'a-next' })
      .mockResolvedValueOnce({ items: [item('none', null)], nextCursor: 'none-next' })
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ items: [item('a2')], nextCursor: null })
    const { grouping, items } = harness()
    await grouping.setField('ASSIGNEE'); await grouping.load('a'); await grouping.load(EMPTY_GROUP)
    expect(api.listProjectWorkItems.mock.calls[1]![0]).toMatchObject({ emptyField: 'ASSIGNEE' })
    await grouping.load('a')
    expect(grouping.page('a').error).toBeDefined()
    expect(items.value.map(v => v.id)).toEqual(['a1', 'none'])
    await grouping.load('a')
    expect(api.listProjectWorkItems.mock.calls[3]![0].cursor).toBe('a-next')
    expect(grouping.page(EMPTY_GROUP).nextCursor).toBe('none-next')
    expect(items.value.map(v => v.id)).toEqual(['a1', 'a2', 'none'])
  })
  it('快速切换会取消旧元数据和分页请求，迟到响应不覆盖新字段', async () => {
    let resolveOld!: (value: unknown) => void
    api.listProjectWorkItemFilterOptions.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    const { grouping } = harness()
    const first = grouping.setField('ASSIGNEE')
    api.listProjectWorkItemFilterOptions.mockResolvedValueOnce({ items: [facet('TODO')], nextCursor: null })
    await grouping.setField('STATUS')
    resolveOld({ items: [facet('old')], nextCursor: null }); await first
    expect(grouping.groups.value.map(v => v.key)).toEqual(['TODO'])
    expect(api.listProjectWorkItemFilterOptions.mock.calls[0]![1].signal.aborted).toBe(true)
    api.listProjectWorkItems.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    const oldPage = grouping.load('TODO')
    await grouping.setField('ASSIGNEE')
    resolveOld({ items: [item('old')], nextCursor: null }); await oldPage
    expect(grouping.page('TODO').items).toEqual([])
  })
  it('刷新保留正在编辑的远端行，但普通暖数据不会导致下载整个分组', async () => {
    const { grouping, items } = harness([item('far')], new Set(['far']))
    api.listProjectWorkItems.mockResolvedValueOnce({ items: [item('first')], nextCursor: 'next' })
      .mockResolvedValueOnce({ items: [item('far')], nextCursor: 'rest' })
    await grouping.setField('ASSIGNEE'); await grouping.load('a')
    expect(items.value.map(v => v.id)).toEqual(['first', 'far'])
    expect(grouping.page('a').nextCursor).toBe('rest')
    localStorage.clear()
    const second = harness([item('ordinary')])
    api.listProjectWorkItems.mockResolvedValueOnce({ items: [item('first')], nextCursor: 'next' })
    await second.grouping.setField('ASSIGNEE'); await second.grouping.load('a')
    expect(api.listProjectWorkItems).toHaveBeenCalledTimes(3)
  })
  it('折叠不移除行，偏好按账户企业项目隔离，清除恢复平铺', async () => {
    const { grouping, items, preferenceKey, restore } = harness()
    await grouping.setField('ASSIGNEE'); await grouping.load('a')
    grouping.toggle('a'); grouping.order.value = 'NAME_DESC'; grouping.showEmpty.value = true
    await flushPromises()
    expect(items.value.map(v => v.id)).toEqual(['a1'])
    expect(harness().grouping.isCollapsed('a')).toBe(true)
    preferenceKey.value = 'grouping:another:company:project'; await flushPromises()
    expect(grouping.field.value).toBe('')
    preferenceKey.value = 'grouping:user:company:project'; await flushPromises()
    expect(grouping.order.value).toBe('NAME_DESC')
    expect(grouping.showEmpty.value).toBe(true)
    await grouping.setField(''); expect(restore).toHaveBeenCalledOnce()
  })
  it('编辑字段后重新归组并校准完整数量，跨午夜重算日期', async () => {
    const { grouping, items, today } = harness([item('a1')])
    await grouping.setField('ASSIGNEE')
    items.value = [item('a1', 'b')]
    api.listProjectWorkItemFilterOptions.mockResolvedValueOnce({ items: [facet('b', 1)], nextCursor: null })
    await grouping.changed()
    expect(grouping.groups.value.map(v => [v.key, v.count])).toEqual([['b', 1]])
    api.listProjectWorkItemFilterOptions.mockResolvedValue({ items: [facet('2026-09-15', 1)], nextCursor: null })
    await grouping.setField('DUE_DATE')
    expect(grouping.groups.value[0]!.key).toBe('TOMORROW')
    today.value = '2026-09-15'; await flushPromises()
    expect(grouping.groups.value[0]!.key).toBe('TODAY')
  })
  it('元数据失败保留当前内容并可重新加载，重复游标中断', async () => {
    const { grouping, items } = harness([item('a1')])
    api.listProjectWorkItemFilterOptions.mockRejectedValueOnce(new Error('offline'))
    await grouping.setField('ASSIGNEE')
    expect(grouping.error.value).toBeDefined(); expect(items.value).toHaveLength(1)
    await grouping.refresh(); expect(grouping.countsReady.value).toBe(true)
    api.listProjectWorkItemFilterOptions.mockResolvedValue({ items: [], nextCursor: 'repeated' })
    await grouping.refresh(); expect(grouping.error.value).toBeDefined()
  })
})
