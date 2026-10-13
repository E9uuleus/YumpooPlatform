import { readCsrfToken, type DashboardItemPage, type DashboardItemsQuery, type DashboardTablePage, type DashboardTableQuery } from '@yumpoo/api-client'
import { dashboardsApi, projectStatisticsApi } from '../../api/client'
import type { WorkItemTableSource } from '../projects/workItemTableSource'

type Init = Parameters<WorkItemTableSource['listProjectWorkItems']>[1]

/** Where explore dialogs read chart details: a personal dashboard or one project's fixed statistics. */
export interface StatisticsSource {
  key: string
  items: (query: DashboardItemsQuery) => Promise<DashboardItemPage>
  table: (query: DashboardTableQuery, init?: Init) => Promise<DashboardTablePage>
}

export function dashboardSource(id: string): StatisticsSource {
  return {
    key: id,
    items: query => dashboardsApi.queryDashboardItems({ id, xXSRFTOKEN: readCsrfToken() || '', dashboardItemsQuery: query }),
    table: (query, init) => dashboardsApi.queryDashboardTable({ id, xXSRFTOKEN: readCsrfToken() || '', dashboardTableQuery: query }, init),
  }
}

export function projectStatisticsSource(projectId: string): StatisticsSource {
  return {
    key: `project:${projectId}`,
    items: query => projectStatisticsApi.queryProjectStatisticsItems({ projectId, xXSRFTOKEN: readCsrfToken() || '', dashboardItemsQuery: query }),
    table: (query, init) => projectStatisticsApi.queryProjectStatisticsTable({ projectId, xXSRFTOKEN: readCsrfToken() || '', dashboardTableQuery: query }, init),
  }
}
