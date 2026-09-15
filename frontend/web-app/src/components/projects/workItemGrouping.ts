import { ListProjectWorkItemsEmptyFieldEnum, type ListProjectWorkItemsRequest, type ProjectWorkItemListItem,
  type ProjectWorkItemFilterOption, type WorkItemLabelColorToken } from '@yumpoo/api-client'
import { dueDateKey } from './workItemDueDate'
import { mondayWorkItemLabelColors, workItemLabelColorValue } from './workItemLabelColors'

export const groupingFields = [
  { value: 'ASSIGNEE', label: '处理人' }, { value: 'DUE_DATE', label: '截止日期' },
  { value: 'STATUS', label: '状态' }, { value: 'PRIORITY', label: '优先级' },
  { value: 'CONTENT', label: '工作项类别' },
] as const
export type GroupField = typeof groupingFields[number]['value']
export type GroupOrder = 'DEFAULT' | 'REVERSE_DEFAULT' | 'NAME_ASC' | 'NAME_DESC' | 'DATE_ASC' | 'DATE_DESC'
export const EMPTY_GROUP = '__NULL__'
export interface WorkItemGroup {
  key: string
  label: string
  color: string
  count: number
  rank: number
  from?: string | null
  to?: string | null
}
export interface GroupSources {
  members: readonly { userId: string; displayName: string }[]
  statuses: readonly { code: string; displayName: string; colorToken?: WorkItemLabelColorToken; sortOrder: number; active: boolean }[]
  priorities: readonly { code: string; displayName: string; colorToken: WorkItemLabelColorToken; sortOrder: number; active: boolean }[]
  contents: readonly { id: string; name: string; colorToken: WorkItemLabelColorToken; sortOrder: number; active: boolean }[]
}

export function groupOrders(field: GroupField | ''): Array<{ value: GroupOrder; label: string }> {
  const names = [{ value: 'NAME_ASC' as const, label: '名称正序' }, { value: 'NAME_DESC' as const, label: '名称倒序' }]
  if (field === 'ASSIGNEE') return names
  if (field === 'DUE_DATE') return [
    { value: 'DEFAULT', label: '系统默认' }, { value: 'REVERSE_DEFAULT', label: '反向默认' },
    { value: 'DATE_ASC', label: '时间正序' }, { value: 'DATE_DESC', label: '时间倒序' },
  ]
  const label = field === 'CONTENT' ? '类别顺序' : '标签顺序'
  return [{ value: 'DEFAULT', label }, { value: 'REVERSE_DEFAULT', label: `反向${label}` }, ...names]
}

const day = 86_400_000
const shift = (date: string, days: number) => new Date(Date.parse(`${date}T00:00:00Z`) + days * day).toISOString().slice(0, 10)
const later = (left: string, right: string) => left > right ? left : right

export function dateGroups(today: string): WorkItemGroup[] {
  const weekday = new Date(`${today}T00:00:00Z`).getUTCDay()
  const nextMonday = shift(today, 7 - ((weekday + 6) % 7))
  const afterTomorrow = shift(today, 2)
  return [
    { key: 'TODAY', label: '今天', from: today, to: today, color: 'BRIGHT_BLUE' },
    { key: 'TOMORROW', label: '明天', from: shift(today, 1), to: shift(today, 1), color: 'AQUAMARINE' },
    { key: 'THIS_WEEK', label: '本周稍后', from: afterTomorrow, to: shift(nextMonday, -1), color: 'DARK_PURPLE' },
    { key: 'NEXT_WEEK', label: '下周', from: later(nextMonday, afterTomorrow), to: shift(nextMonday, 6), color: 'SALADISH' },
    { key: 'LATER', label: '更晚', from: shift(nextMonday, 7), to: null, color: 'ORCHID' },
    { key: 'PAST', label: '已过去', from: null, to: shift(today, -1), color: 'DARK_RED' },
    { key: EMPTY_GROUP, label: '未设置截止日期', from: null, to: null, color: 'GRAY' },
  ].map((group, rank) => ({ ...group, rank, count: 0, color: workItemLabelColorValue(group.color) }))
}

export function dateGroupKey(value: Date | string | null, today: string): string {
  const date = dueDateKey(value)
  if (!date) return EMPTY_GROUP
  return dateGroups(today).find(group => group.key !== EMPTY_GROUP
    && (!group.from || date >= group.from) && (!group.to || date <= group.to))!.key
}

export function itemGroupKey(item: ProjectWorkItemListItem, field: GroupField, today: string): string {
  switch (field) {
    case 'ASSIGNEE': return item.assigneeUserId ?? EMPTY_GROUP
    case 'STATUS': return item.statusCode
    case 'PRIORITY': return item.priority ?? EMPTY_GROUP
    case 'CONTENT': return item.contentId
    case 'DUE_DATE': return dateGroupKey(item.dueDate, today)
  }
}

export function assigneeGroupColor(id: string, assigned: Record<string, string>): string {
  const palette: WorkItemLabelColorToken[] = mondayWorkItemLabelColors.map(option => option.token)
  let token = assigned[id]
  if (!token || !palette.includes(token as WorkItemLabelColorToken)) {
    const hash = [...id].reduce((value, char) => ((value * 31 + char.charCodeAt(0)) >>> 0), 0)
    const used = new Set(Object.values(assigned))
    token = Array.from({ length: palette.length }, (_, offset) => palette[(hash + offset) % palette.length]!)
      .find(candidate => !used.has(candidate)) ?? palette[hash % palette.length]!
    assigned[id] = token
  }
  return workItemLabelColorValue(token)
}

export function buildWorkItemGroups(field: GroupField, order: GroupOrder, showEmpty: boolean,
  options: readonly ProjectWorkItemFilterOption[], sources: GroupSources, today: string,
  colors: Record<string, string>): WorkItemGroup[] {
  const groups = new Map<string, WorkItemGroup>()
  const add = (key: string, label: string, color: string, rank: number) => {
    groups.set(key, { key, label, color, rank, count: 0 })
  }
  if (field === 'DUE_DATE') dateGroups(today).forEach(group => groups.set(group.key, group))
  else if (field === 'ASSIGNEE') {
    [...sources.members].sort((a, b) => a.userId.localeCompare(b.userId))
      .forEach(member => add(member.userId, member.displayName, assigneeGroupColor(member.userId, colors), 0))
    add(EMPTY_GROUP, '未分配', workItemLabelColorValue(), Infinity)
  } else if (field === 'CONTENT') {
    sources.contents.forEach(content => {
      if (content.active || options.some(option => option.value === content.id))
        add(content.id, content.name, workItemLabelColorValue(content.colorToken), content.sortOrder)
    })
  } else {
    const labels = field === 'STATUS' ? sources.statuses : sources.priorities
    labels.forEach(label => {
      if (label.active || options.some(option => option.value === label.code))
        add(label.code, label.displayName, workItemLabelColorValue(label.colorToken), label.sortOrder)
    })
    if (field === 'PRIORITY') add(EMPTY_GROUP, '未设置优先级', workItemLabelColorValue(), Infinity)
  }
  for (const option of options) {
    const key = field === 'DUE_DATE' ? dateGroupKey(option.value === EMPTY_GROUP ? null : option.value, today) : option.value
    if (!groups.has(key)) add(key, option.label, field === 'ASSIGNEE' ? assigneeGroupColor(key, colors) : workItemLabelColorValue(), Number.MAX_SAFE_INTEGER)
    groups.get(key)!.count += option.count
  }
  const compareNames = new Intl.Collator('zh-CN', { numeric: true, sensitivity: 'base' })
  return [...groups.values()].filter(group => showEmpty || group.count > 0).sort((a, b) => {
    if (a.key === EMPTY_GROUP || b.key === EMPTY_GROUP) return a.key === b.key ? 0 : a.key === EMPTY_GROUP ? 1 : -1
    let difference: number
    if (order.startsWith('NAME')) difference = compareNames.compare(a.label, b.label)
    else if (order.startsWith('DATE')) difference = (a.from ?? '').localeCompare(b.from ?? '')
    else difference = a.rank - b.rank
    if (order === 'REVERSE_DEFAULT' || order.endsWith('DESC')) difference *= -1
    return difference || a.key.localeCompare(b.key)
  })
}

export function groupListRequest(base: ListProjectWorkItemsRequest, field: GroupField,
  group: WorkItemGroup): ListProjectWorkItemsRequest | null {
  const request = { ...base }
  const intersects = (values: Set<string> | undefined) => !values?.size || values.has(group.key)
  if (group.key === EMPTY_GROUP) {
    if (field === 'ASSIGNEE') request.emptyField = ListProjectWorkItemsEmptyFieldEnum.Assignee
    else if (field === 'PRIORITY') request.emptyField = ListProjectWorkItemsEmptyFieldEnum.Priority
    else if (field === 'DUE_DATE') request.emptyField = ListProjectWorkItemsEmptyFieldEnum.DueDate
    else return null
  } else if (field === 'ASSIGNEE') {
    if (!intersects(base.assigneeUserId)) return null
    request.assigneeUserId = new Set([group.key])
  } else if (field === 'STATUS') {
    if (!intersects(base.status)) return null
    request.status = new Set([group.key])
  } else if (field === 'PRIORITY') {
    if (!intersects(base.priority)) return null
    request.priority = new Set([group.key])
  } else if (field === 'CONTENT') {
    if (!intersects(base.contentId)) return null
    request.contentId = new Set([group.key])
  } else {
    const from = [dueDateKey(base.dueFrom ?? null), group.from].filter((v): v is string => Boolean(v)).sort().at(-1)
    const to = [dueDateKey(base.dueTo ?? null), group.to].filter((v): v is string => Boolean(v)).sort()[0]
    if (from && to && from > to) return null
    if (from) request.dueFrom = new Date(`${from}T00:00:00Z`)
    if (to) request.dueTo = new Date(`${to}T00:00:00Z`)
  }
  return request
}
