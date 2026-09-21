import { describe, expect, it, vi } from 'vitest'
import { ListProjectWorkItemFilterOptionsFieldEnum } from '@yumpoo/api-client'
import { dashboardTableSource } from './dashboardTableSource'
import { emptyFilters, newWidget } from './dashboardModel'

const api = vi.hoisted(() => ({ queryDashboardTable: vi.fn() }))
vi.mock('../../api/client', () => ({ dashboardsApi: api }))

describe('图表范围中的共享表格查询', () => {
  it('对行、分组选项与子项保持同一图表条件，保留日期和分页并转换集合', async () => {
    api.queryDashboardTable.mockResolvedValue({ items: [{ id: 'parent', subitemCount: 2 }], options: [], nextCursor: 'next', contextIds: ['parent'], subitemCounts: { parent: 1 } })
    const widget = newWidget('CHART')
    const filters = { ...emptyFilters(), dueFrom: new Date('2026-09-01') }
    let selection = { key: 'category', seriesKey: 'series' }
    const source = dashboardTableSource(() => 'dashboard', () => ({ widget, filters, selection, projectId: 'p' }))
    const signal = new AbortController().signal
    await source.listProjectWorkItems({ projectId: 'p', cursor: 'cursor', status: new Set(['DONE']), dueFrom: new Date('2026-09-10') }, { signal })
    let query = api.queryDashboardTable.mock.calls.at(-1)!
    expect(query[0].id).toBe('dashboard')
    expect(query[0].dashboardTableQuery).toMatchObject({ projectId: 'p', selection,
      filters: { dueFrom: new Date('2026-09-01') }, table: { cursor: 'cursor', status: ['DONE'], dueFrom: new Date('2026-09-10') } })
    expect(query[1].signal).toBe(signal)
    expect(source.isContextRow?.('parent')).toBe(true)
    expect(source.subitemCount?.('parent')).toBe(1)
    selection = { key: 'next-category', seriesKey: 'series' }
    await source.listWorkItemSubitems({ parentWorkItemId: 'parent' })
    query = api.queryDashboardTable.mock.calls.at(-1)!
    expect(query[0].dashboardTableQuery).toMatchObject({ selection, table: { parentWorkItemId: 'parent' } })
    await source.listProjectWorkItemFilterOptions({ projectId: 'p', field: ListProjectWorkItemFilterOptionsFieldEnum.Status, cursor: 'facet' })
    expect(api.queryDashboardTable.mock.calls.at(-1)![0].dashboardTableQuery).toMatchObject({ selection, table: { field: 'STATUS', cursor: 'facet' } })
  })
})
