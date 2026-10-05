import type { GroupField } from './workItemGrouping'

export type ColumnSortDirection = 'ASC' | 'DESC'
export type ColumnMenuAction =
  | { type: 'settings' }
  | { type: 'filter' }
  | { type: 'sort'; direction: ColumnSortDirection | null }
  | { type: 'collapse' }
  | { type: 'group' }

export interface ColumnMenuState {
  label: string
  /** 暂只开放状态、优先级、工作项类别；readonly 表示当前角色不能编辑标签。 */
  settings: 'editable' | 'readonly' | 'unavailable'
  sortDirection: ColumnSortDirection | undefined
  sortDisabled: boolean
  collapsible: boolean
  /** undefined 表示该列不支持分组。 */
  groupField: GroupField | undefined
  groupedByThis: boolean
  groupDisabled: boolean
}

export const COLLAPSED_COLUMN_WIDTH = 40
