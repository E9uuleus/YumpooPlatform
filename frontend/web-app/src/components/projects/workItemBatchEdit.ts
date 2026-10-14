import type { InjectionKey, Ref } from 'vue'
import type { ProjectWorkItemListItem, WorkItemDetail } from '@yumpoo/api-client'
import { workItemsApi } from '../../api/client'
import { MAX_WORK_ITEM_ASSIGNEES, workItemAssigneeIds } from './workItemAssignees'
import { apiDate, dueDateKey, type DueDateValue } from './workItemDueDate'

export type WorkItemBatchAssigneeMode = 'add' | 'replace'
/** 截止日期中的 undefined 表示保留每一项自己的值。 */
export type WorkItemBatchEdit =
  | { field: 'assignees'; mode: WorkItemBatchAssigneeMode; userIds: readonly string[] }
  | { field: 'priority'; priority: string | null }
  | { field: 'content'; contentId: string }
  | { field: 'status'; statusCode: string; resolution: string | null }
  | { field: 'dueDate'; dueDate: string | null | undefined; dueTime: string | null | undefined }

/** 勾选行同步修改的范围：count 为包含该行的勾选数（不足 2 项为 0），sync 为本次弹层是否同步到全部勾选项。 */
export interface WorkItemBatchEditScope {
  count: (workItemId: string) => number
  sync: Ref<boolean>
}
export const workItemBatchEditScopeKey: InjectionKey<WorkItemBatchEditScope> = Symbol('workItemBatchEditScope')

interface WriteCommon { workItemId: string; xXSRFTOKEN: string; ifMatch: string; idempotencyKey: string }
type BatchEditItem = Pick<ProjectWorkItemListItem, 'statusCode' | 'priority' | 'contentId' | 'dueDate' | 'dueTime'
  | 'assigneeUserId' | 'assigneeDisplayName' | 'assignees' | 'capabilities'>

const fieldLabels: Record<WorkItemBatchEdit['field'], string> = {
  assignees: '处理人', priority: '优先级', content: '类别', status: '状态', dueDate: '截止日期',
}

export function batchEditLabel(edit: WorkItemBatchEdit): string {
  return `修改${fieldLabels[edit.field]}`
}

export function nextAssignees(mode: WorkItemBatchAssigneeMode, current: readonly string[], userIds: readonly string[]): string[] {
  const unique = [...new Set(userIds)]
  return mode === 'replace' ? unique : [...current, ...unique.filter(id => !current.includes(id))]
}

/** 只改日期时保留每项的时间，只改时间时保留每项的日期；清空日期同时清空时间。 */
export function dueDateBatchEdit(anchor: Pick<ProjectWorkItemListItem, 'dueDate' | 'dueTime'>, value: DueDateValue): WorkItemBatchEdit {
  if (!value.dueDate) return { field: 'dueDate', dueDate: null, dueTime: null }
  return {
    field: 'dueDate',
    dueDate: value.dueDate !== dueDateKey(anchor.dueDate) ? value.dueDate : undefined,
    dueTime: value.dueTime !== (anchor.dueTime ?? null) ? value.dueTime : undefined,
  }
}

function targetDueDate(edit: Extract<WorkItemBatchEdit, { field: 'dueDate' }>, item: BatchEditItem) {
  const dueDate = edit.dueDate === undefined ? dueDateKey(item.dueDate) : edit.dueDate
  return { dueDate, dueTime: dueDate === null ? null : edit.dueTime === undefined ? item.dueTime ?? null : edit.dueTime }
}

export function batchEditSkipReason(edit: WorkItemBatchEdit, item: BatchEditItem): string | undefined {
  if (edit.field === 'status') {
    if (item.statusCode === edit.statusCode) return undefined
    return item.capabilities.canMoveInKanban
      && item.capabilities.availableTransitions.some(option => option.toStatus === edit.statusCode)
      ? undefined : '不能迁移到目标状态'
  }
  if (!item.capabilities.canEditFields) return '没有编辑权限'
  if (edit.field === 'assignees'
    && nextAssignees(edit.mode, workItemAssigneeIds(item), edit.userIds).length > MAX_WORK_ITEM_ASSIGNEES)
    return `处理人超过 ${MAX_WORK_ITEM_ASSIGNEES} 位`
  if (edit.field === 'dueDate' && edit.dueDate === undefined && edit.dueTime && !dueDateKey(item.dueDate))
    return '未设置截止日期'
  return undefined
}

export function batchEditUnchanged(edit: WorkItemBatchEdit, item: BatchEditItem): boolean {
  switch (edit.field) {
    case 'assignees': {
      const current = workItemAssigneeIds(item), next = nextAssignees(edit.mode, current, edit.userIds)
      return next.length === current.length && next.every((id, index) => id === current[index])
    }
    case 'priority': return (item.priority ?? null) === edit.priority
    case 'content': return item.contentId === edit.contentId
    case 'status': return item.statusCode === edit.statusCode
    case 'dueDate': {
      const target = targetDueDate(edit, item)
      return dueDateKey(item.dueDate) === target.dueDate && (item.dueTime ?? null) === target.dueTime
    }
  }
}

export function applyBatchEdit(edit: WorkItemBatchEdit, item: BatchEditItem, common: WriteCommon): Promise<WorkItemDetail> {
  switch (edit.field) {
    case 'assignees': return workItemsApi.patchWorkItemAssignees({ ...common, workItemAssigneesPatchRequest: {
      assigneeUserIds: nextAssignees(edit.mode, workItemAssigneeIds(item), edit.userIds) } })
    case 'priority': return workItemsApi.patchWorkItemPriority({ ...common, workItemPriorityPatchRequest: { priority: edit.priority } })
    case 'content': return workItemsApi.patchWorkItemContent({ ...common, workItemContentPatchRequest: { contentId: edit.contentId } })
    case 'status': {
      const transition = item.capabilities.availableTransitions.find(option => option.toStatus === edit.statusCode)
      return workItemsApi.transitionWorkItem({ ...common, workItemTransitionRequest: {
        toStatus: edit.statusCode, resolution: transition?.requiresResolution ? edit.resolution : null } })
    }
    case 'dueDate': {
      const target = targetDueDate(edit, item)
      return workItemsApi.patchWorkItemDueDate({ ...common, workItemDueDatePatchRequest: {
        dueDate: apiDate(target.dueDate), dueTime: target.dueTime } })
    }
  }
}
