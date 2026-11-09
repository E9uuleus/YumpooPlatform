import type { WorkItemActionIconName } from '../projects/WorkItemActionIcon.vue'
import type { GroupField } from '../projects/workItemGrouping'

export type MyWorkViewField = Extract<GroupField, 'STATUS' | 'DUE_DATE' | 'PRIORITY' | 'CONTENT'>

export const myWorkViews: ReadonlyArray<{ field: MyWorkViewField; label: string; icon: WorkItemActionIconName }> = [
  { field: 'STATUS', label: '状态视图', icon: 'status' },
  { field: 'DUE_DATE', label: '日期视图', icon: 'calendar' },
  { field: 'PRIORITY', label: '优先级视图', icon: 'priority' },
  { field: 'CONTENT', label: '类别视图', icon: 'category' },
]

export const DEFAULT_MY_WORK_VIEW: MyWorkViewField = 'STATUS'

export function isMyWorkViewField(value: unknown): value is MyWorkViewField {
  return myWorkViews.some(view => view.field === value)
}
