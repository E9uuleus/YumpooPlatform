import {
  WorkItemColoringColumn,
  WorkItemColoringOperator,
  WorkItemColoringTarget,
  WorkItemTableHeight,
  type ProjectWorkItemListItem,
  type WorkItemColoringRule,
  type WorkItemCreateDefaultValues,
  type WorkItemCreateRequest,
  type WorkItemTableSettingsUpdateRequest,
} from '@yumpoo/api-client'
import { workItemAssigneeIds } from './workItemAssignees'
import { dueDateKey } from './workItemDueDate'
import { workItemLabelColorValue } from './workItemLabelColors'

export type TableSettings = WorkItemTableSettingsUpdateRequest
export type ColoringColumnKey = 'title' | 'assignee' | 'status' | 'priority' | 'content' | 'dueDate'
export type DefaultValueColumnKey = 'assignee' | 'status' | 'priority' | 'content' | 'dueDate'
export type ColoringValueKind = 'list' | 'text' | 'date' | 'none'

export const MAX_COLORING_RULES = 20
/** 后端新建接口固定从该状态开始。 */
export const INITIAL_STATUS_CODE = 'NOT_STARTED'
export const DEFAULT_VALUE_COLUMN_KEYS: readonly DefaultValueColumnKey[] = ['assignee', 'status', 'priority', 'content', 'dueDate']

/** 表头像素高度由 CSS 类决定（38/56/76px）；这里只保留行拖拽偏移需要的行高。 */
export const HEIGHT_OPTIONS = [
  { value: WorkItemTableHeight.Single, label: '单行', row: 36 },
  { value: WorkItemTableHeight.Double, label: '双行', row: 56 },
  { value: WorkItemTableHeight.Triple, label: '三行', row: 76 },
] as const

export function heightOption(value: WorkItemTableHeight): (typeof HEIGHT_OPTIONS)[number] {
  return HEIGHT_OPTIONS.find(option => option.value === value) ?? HEIGHT_OPTIONS[0]
}

export interface TableSettingsCatalog {
  members: ReadonlyArray<{ userId: string; displayName: string }>
  statuses: ReadonlyArray<{ code: string; displayName: string; active: boolean }>
  priorities: ReadonlyArray<{ code: string; displayName: string; active: boolean }>
  contents: ReadonlyArray<{ id: string; name: string; active: boolean }>
}

interface ColoringColumnOption {
  value: WorkItemColoringColumn
  key: ColoringColumnKey
  label: string
  operators: readonly WorkItemColoringOperator[]
  /** 状态、优先级、类别单元格被标签色块占满，只能作用于整行。 */
  cell: boolean
}

const O = WorkItemColoringOperator
export const COLORING_COLUMNS: readonly ColoringColumnOption[] = [
  { value: WorkItemColoringColumn.Title, key: 'title', label: '工作项名称', operators: [O.Contains, O.NotContains], cell: true },
  { value: WorkItemColoringColumn.Assignee, key: 'assignee', label: '处理人', operators: [O.Is, O.IsNot, O.IsEmpty, O.IsNotEmpty], cell: true },
  { value: WorkItemColoringColumn.Status, key: 'status', label: '状态', operators: [O.Is, O.IsNot], cell: false },
  { value: WorkItemColoringColumn.Priority, key: 'priority', label: '优先级', operators: [O.Is, O.IsNot, O.IsEmpty, O.IsNotEmpty], cell: false },
  { value: WorkItemColoringColumn.Content, key: 'content', label: '工作项类别', operators: [O.Is, O.IsNot], cell: false },
  { value: WorkItemColoringColumn.DueDate, key: 'dueDate', label: '截止日期', operators: [O.IsEmpty, O.IsNotEmpty, O.Past, O.Today, O.Before, O.After], cell: true },
]

export const COLORING_OPERATOR_LABELS: Readonly<Record<string, string>> = {
  [O.Is]: '是', [O.IsNot]: '不是', [O.IsEmpty]: '为空', [O.IsNotEmpty]: '不为空',
  [O.Contains]: '包含', [O.NotContains]: '不包含', [O.Past]: '已过去', [O.Today]: '是今天',
  [O.Before]: '早于', [O.After]: '晚于',
}

export function coloringColumn(value: WorkItemColoringColumn | null): ColoringColumnOption | undefined {
  return COLORING_COLUMNS.find(column => column.value === value)
}

export function coloringValueKind(operator: WorkItemColoringOperator | null): ColoringValueKind {
  switch (operator) {
    case O.Is:
    case O.IsNot: return 'list'
    case O.Contains:
    case O.NotContains: return 'text'
    case O.Before:
    case O.After: return 'date'
    default: return 'none'
  }
}

export function isColoringRuleComplete(rule: WorkItemColoringRule): boolean {
  const column = coloringColumn(rule.column)
  if (!column || !rule.operator || !column.operators.includes(rule.operator)) return false
  if (rule.target === WorkItemColoringTarget.Cell && !column.cell) return false
  switch (coloringValueKind(rule.operator)) {
    case 'list': return rule.values.length > 0
    case 'text': return Boolean(rule.values[0]?.trim())
    case 'date': return dueDateKey(rule.values[0] ?? null) !== null
    default: return true
  }
}

/** 不透明的浅色背景：固定列的 sticky 单元格下不会透出滚动内容。 */
export function coloringBackground(colorToken: string): string {
  return `color-mix(in srgb, ${workItemLabelColorValue(colorToken)} 32%, var(--yp-bg-surface))`
}

export interface WorkItemColoring {
  row: string | undefined
  cells: Partial<Record<ColoringColumnKey, string>>
}

function matchesSet(operator: WorkItemColoringOperator, actual: readonly string[], values: readonly string[]): boolean {
  switch (operator) {
    case O.IsEmpty: return actual.length === 0
    case O.IsNotEmpty: return actual.length > 0
    case O.Is: return actual.some(value => values.includes(value))
    case O.IsNot: return !actual.some(value => values.includes(value))
    default: return false
  }
}

function matchesRule(rule: WorkItemColoringRule, item: ProjectWorkItemListItem, today: string): boolean {
  const operator = rule.operator!
  switch (rule.column) {
    case WorkItemColoringColumn.Title: {
      const found = item.title.toLocaleLowerCase().includes((rule.values[0] ?? '').trim().toLocaleLowerCase())
      return operator === O.Contains ? found : !found
    }
    case WorkItemColoringColumn.Assignee: return matchesSet(operator, workItemAssigneeIds(item), rule.values)
    case WorkItemColoringColumn.Status: return matchesSet(operator, [item.statusCode], rule.values)
    case WorkItemColoringColumn.Priority: return matchesSet(operator, item.priority ? [item.priority] : [], rule.values)
    case WorkItemColoringColumn.Content: return matchesSet(operator, [item.contentId], rule.values)
    case WorkItemColoringColumn.DueDate: {
      const due = dueDateKey(item.dueDate)
      const value = rule.values[0] ?? ''
      switch (operator) {
        case O.IsEmpty: return due === null
        case O.IsNotEmpty: return due !== null
        case O.Past: return due !== null && due < today
        case O.Today: return due === today
        case O.Before: return due !== null && due < value
        case O.After: return due !== null && due > value
        default: return false
      }
    }
    default: return false
  }
}

/** 行与每个单元格分别取列表中第一条命中的完整规则；无命中返回 undefined。 */
export function evaluateWorkItemColoring(rules: readonly WorkItemColoringRule[], item: ProjectWorkItemListItem,
  today: string): WorkItemColoring | undefined {
  let result: WorkItemColoring | undefined
  for (const rule of rules) {
    if (!isColoringRuleComplete(rule) || !matchesRule(rule, item, today)) continue
    result ??= { row: undefined, cells: {} }
    const color = coloringBackground(rule.colorToken)
    if (rule.target === WorkItemColoringTarget.Row) result.row ??= color
    else result.cells[coloringColumn(rule.column)!.key] ??= color
  }
  return result
}

export interface WorkItemCreateDefaults {
  /** undefined 时沿用"第一个启用类别"。 */
  contentId: string | undefined
  /** 创建成功后迁移到的状态；null 表示保持初始状态。 */
  statusCode: string | null
  fields: Partial<Pick<WorkItemCreateRequest, 'assigneeUserIds' | 'assigneeUserId' | 'priority' | 'dueDate'>>
  count: number
}

export interface CreateDefaultsContext {
  memberIds: ReadonlySet<string>
  statusCodes: ReadonlySet<string>
  priorityCodes: ReadonlySet<string>
  contentIds: ReadonlySet<string>
  /** 企业时区的今天，YYYY-MM-DD。 */
  today: string
}

/** 失效的成员、标签或类别静默忽略；截止日期按企业时区今天偏移。 */
export function resolveWorkItemCreateDefaults(values: WorkItemCreateDefaultValues,
  context: CreateDefaultsContext): WorkItemCreateDefaults {
  const fields: WorkItemCreateDefaults['fields'] = {}
  const assignees = [...values.assigneeUserIds].filter(id => context.memberIds.has(id))
  if (assignees.length) {
    fields.assigneeUserIds = assignees
    fields.assigneeUserId = assignees[0]!
  }
  if (values.priority && context.priorityCodes.has(values.priority)) fields.priority = values.priority
  if (values.dueDateOffsetDays !== null)
    fields.dueDate = new Date(Date.parse(`${context.today}T00:00:00Z`) + values.dueDateOffsetDays * 86_400_000)
  const contentId = values.contentId && context.contentIds.has(values.contentId) ? values.contentId : undefined
  const statusCode = values.statusCode && values.statusCode !== INITIAL_STATUS_CODE && context.statusCodes.has(values.statusCode)
    ? values.statusCode : null
  const count = [assignees.length > 0, fields.priority !== undefined, fields.dueDate !== undefined,
    contentId !== undefined, statusCode !== null].filter(Boolean).length
  return { contentId, statusCode, fields, count }
}

export function emptyTableSettings(): TableSettings {
  return {
    pinnedColumnCount: 0,
    headerHeight: WorkItemTableHeight.Single,
    rowHeight: WorkItemTableHeight.Single,
    coloringRules: [],
    defaultValues: { assigneeUserIds: new Set<string>(), statusCode: null, priority: null, contentId: null, dueDateOffsetDays: null },
  }
}
