import { ref } from 'vue'
import { readCsrfToken, type DashboardTableCriteria, type DashboardTableQuery } from '@yumpoo/api-client'
import { dashboardsApi } from '../../api/client'
import type { WorkItemTableSource } from '../projects/workItemTableSource'
import { clone } from './dashboardModel'

export function dashboardTableSource(dashboardId: () => string, scope: () => Omit<DashboardTableQuery, 'table'>): WorkItemTableSource {
  const contextIds = ref(new Set<string>())
  const subitemCounts = ref(new Map<string, number>())
  async function query(request: object, init?: Parameters<WorkItemTableSource['listProjectWorkItems']>[1]) {
    const snapshot = clone(scope())
    for (const filters of [snapshot.filters, snapshot.widget.chart?.filters]) {
      if (filters?.dueFrom) filters.dueFrom = new Date(filters.dueFrom)
      if (filters?.dueTo) filters.dueTo = new Date(filters.dueTo)
    }
    const table = Object.fromEntries(Object.entries(request)
      .filter(([key, value]) => key !== 'projectId' && key !== 'view' && value !== undefined && value !== null)
      .map(([key, value]) => [key, value instanceof Set ? [...value] : value])) as DashboardTableCriteria
    return dashboardsApi.queryDashboardTable({ id: dashboardId(), xXSRFTOKEN: readCsrfToken() || '',
      dashboardTableQuery: { ...snapshot, table } }, init)
  }
  return {
    listProjectWorkItems: async (request, init) => {
      const result = await query(request, init)
      const next = new Set(contextIds.value)
      result.items.forEach(item => next.delete(item.id))
      result.contextIds.forEach(id => next.add(id))
      contextIds.value = next
      const counts = new Map(subitemCounts.value)
      result.items.forEach(item => counts.set(item.id, result.subitemCounts[item.id] ?? 0))
      subitemCounts.value = counts
      return { items: result.items, nextCursor: result.nextCursor }
    },
    listProjectWorkItemFilterOptions: async (request, init) => {
      const result = await query(request, init)
      return { items: result.options, nextCursor: result.nextCursor }
    },
    listWorkItemSubitems: async (request, init) => {
      const result = await query(request, init)
      return { items: result.items }
    },
    isContextRow: id => contextIds.value.has(id),
    subitemCount: id => subitemCounts.value.get(id) ?? 0,
  }
}
