import type { Content, ProjectWorkItemListItem } from '@yumpoo/api-client'
import type { CSSProperties, Ref } from 'vue'
import { workItemLabelColorValue } from './workItemLabelColors'

type ContentsById = ReadonlyMap<string, Content>
type ItemContent = Pick<ProjectWorkItemListItem, 'contentId' | 'contentName' | 'contentColorToken'>
interface StatusOption {
  statusCode: string
  displayName: string
  colorToken?: string
  statusCategory?: string
}
interface PriorityOption {
  code: string
  displayName: string
  colorToken: string
}

function contentName(contentsById: ContentsById, contentId: string): string {
  return contentsById.get(contentId)?.name ?? '未知类别'
}

function contentLabel(contentsById: ContentsById, item: ItemContent) {
  return contentsById.get(item.contentId) ?? { name: item.contentName, colorToken: item.contentColorToken }
}

function statusLabel(workflowStatuses: readonly StatusOption[], statusCode: string): string {
  return workflowStatuses.find(item => item.statusCode === statusCode)?.displayName ?? statusCode
}

function labelCellStyle(colorToken?: string): CSSProperties {
  return {
    ...(colorToken ? { backgroundColor: workItemLabelColorValue(colorToken) } : {}),
    color: 'var(--yp-text-inverse)',
  }
}

function getStatusCellStyle(workflowStatuses: readonly StatusOption[], statusCode: string): CSSProperties {
  return labelCellStyle(workflowStatuses.find(item => item.statusCode === statusCode)?.colorToken)
}

function getPriorityCellStyle(priorityOptions: readonly PriorityOption[], priority: string | null): CSSProperties {
  return labelCellStyle(priorityOptions.find(item => item.code === priority)?.colorToken)
}

export function getStatusTone(workflowStatuses: readonly StatusOption[], statusCode: string): string {
  const option = workflowStatuses.find(item => item.statusCode === statusCode)
  const colorTone: Record<string, string> = {
    GREEN: 'green', TEAL: 'green', BLUE: 'blue', INDIGO: 'blue', PURPLE: 'blue',
    MAGENTA: 'red', RED: 'red', ORANGE: 'yellow', AMBER: 'yellow', LIME: 'green',
    CYAN: 'blue', GRAY: 'gray',
  }
  if (option?.colorToken) return colorTone[option.colorToken] ?? 'gray'
  const category = option?.statusCategory
  if (category === 'DONE') return 'green'
  if (category === 'IN_PROGRESS') return 'yellow'
  if (category === 'CANCELED') return 'gray'

  const upper = statusCode.toUpperCase()
  if (upper.includes('STUCK') || upper.includes('BLOCK') || upper.includes('DEFECT') || upper.includes('BUG')) {
    return 'red'
  }
  if (upper.includes('REVIEW') || upper.includes('AUDIT') || upper.includes('TEST') || upper.includes('INSPECT')) {
    return 'blue'
  }
  if (upper.includes('PLAN') || upper.includes('TODO') || upper.includes('BACKLOG')) {
    return 'neutral'
  }
  return 'gray'
}

export function getPriorityPresentation(priorityOptions: readonly PriorityOption[], priority: string | null): { label: string; tone: string } {
  if (!priority) return { label: '-', tone: 'empty' }
  const option = priorityOptions.find(item => item.code === priority)
  const tokenTone: Record<string, string> = {
    RED: 'urgent', MAGENTA: 'urgent', ORANGE: 'high', AMBER: 'high',
    GREEN: 'low', LIME: 'low', TEAL: 'medium', CYAN: 'medium', BLUE: 'low',
    INDIGO: 'medium', PURPLE: 'medium', GRAY: 'empty',
  }
  if (option) return { label: option.displayName, tone: tokenTone[option.colorToken] ?? 'empty' }
  const upper = priority.toUpperCase()
  if (upper === 'URGENT') return { label: '紧急', tone: 'urgent' }
  if (upper === 'HIGH') return { label: '高', tone: 'high' }
  if (upper === 'MEDIUM') return { label: '中', tone: 'medium' }
  if (upper === 'LOW') return { label: '低', tone: 'low' }
  return { label: priority, tone: 'empty' }
}

export function formatDate(value: Date | string | null): string {
  return value ? new Date(value).toISOString().slice(0, 10) : '—'
}

export function useWorkItemPresentation(catalog: {
  contentsById: Readonly<Ref<ContentsById>>
  workflowStatuses: Readonly<Ref<readonly StatusOption[]>>
  priorityOptions: Readonly<Ref<readonly PriorityOption[]>>
}) {
  return {
    contentName: (contentId: string) => contentName(catalog.contentsById.value, contentId),
    contentLabel: (item: ItemContent) => contentLabel(catalog.contentsById.value, item),
    statusLabel: (code: string) => statusLabel(catalog.workflowStatuses.value, code),
    labelCellStyle,
    getStatusCellStyle: (code: string) => getStatusCellStyle(catalog.workflowStatuses.value, code),
    getPriorityCellStyle: (priority: string | null) => getPriorityCellStyle(catalog.priorityOptions.value, priority),
    getStatusTone: (code: string) => getStatusTone(catalog.workflowStatuses.value, code),
    getPriorityPresentation: (priority: string | null) => getPriorityPresentation(catalog.priorityOptions.value, priority),
    formatDate,
  }
}
