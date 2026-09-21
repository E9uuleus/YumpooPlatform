import type { WorkItemsApi } from '@yumpoo/api-client'

export type WorkItemTableSource = Pick<WorkItemsApi,
  'listProjectWorkItems' | 'listProjectWorkItemFilterOptions' | 'listWorkItemSubitems'> & {
  isContextRow?: (id: string) => boolean
  subitemCount?: (id: string) => number
}
