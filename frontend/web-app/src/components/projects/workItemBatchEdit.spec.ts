import { describe, expect, it, vi } from 'vitest'
import type { ProjectWorkItemListItem } from '@yumpoo/api-client'
import { workItemsApi } from '../../api/client'
import { applyBatchEdit, batchEditLabel, batchEditSkipReason, batchEditUnchanged, dueDateBatchEdit, nextAssignees } from './workItemBatchEdit'

vi.mock('../../api/client', () => ({ workItemsApi: {
  patchWorkItemAssignees: vi.fn(), patchWorkItemDueDate: vi.fn(), transitionWorkItem: vi.fn(),
} }))

function row(patch: Partial<ProjectWorkItemListItem> = {}): ProjectWorkItemListItem {
  return { id: 'a', statusCode: 'TODO', priority: null, contentId: 'c1', dueDate: null, dueTime: null,
    assigneeUserId: null, assigneeDisplayName: null, assignees: [], etag: '"1"',
    capabilities: { canEditFields: true, canMoveInKanban: true, availableTransitions: [
      { toStatus: 'DONE', displayName: '已完成', requiresResolution: true }] },
    ...patch } as unknown as ProjectWorkItemListItem
}
const assigned = (...ids: string[]) => row({ assignees: ids.map(userId => ({ userId, displayName: userId })), assigneeUserId: ids[0] ?? null })
const common = { workItemId: 'a', xXSRFTOKEN: 'csrf', ifMatch: '"1"', idempotencyKey: 'k' }

describe('勾选行批量修改', () => {
  it('添加保留原顺序只追加新成员，替换使用去重后的所选列表', () => {
    expect(nextAssignees('add', ['u1', 'u2'], ['u2', 'u3', 'u3'])).toEqual(['u1', 'u2', 'u3'])
    expect(nextAssignees('replace', ['u1', 'u2'], ['u3', 'u3'])).toEqual(['u3'])
    expect(nextAssignees('replace', ['u1'], [])).toEqual([])
    expect(batchEditLabel({ field: 'assignees', mode: 'add', userIds: [] })).toBe('修改处理人')
  })

  it('跳过无权限、不可迁移、超过处理人上限和只改时间但无日期的项', () => {
    expect(batchEditSkipReason({ field: 'priority', priority: 'HIGH' },
      row({ capabilities: { ...row().capabilities, canEditFields: false } }))).toBe('没有编辑权限')
    expect(batchEditSkipReason({ field: 'status', statusCode: 'DOING', resolution: null }, row())).toBe('不能迁移到目标状态')
    expect(batchEditSkipReason({ field: 'status', statusCode: 'DONE', resolution: null }, row())).toBeUndefined()
    const many = assigned(...Array.from({ length: 20 }, (_, index) => `u${index}`))
    expect(batchEditSkipReason({ field: 'assignees', mode: 'add', userIds: ['x'] }, many)).toBe('处理人超过 20 位')
    expect(batchEditSkipReason({ field: 'assignees', mode: 'replace', userIds: ['x'] }, many)).toBeUndefined()
    expect(batchEditSkipReason({ field: 'dueDate', dueDate: undefined, dueTime: '09:00' }, row())).toBe('未设置截止日期')
  })

  it('目标值与当前值相同时视为无变化', () => {
    expect(batchEditUnchanged({ field: 'assignees', mode: 'add', userIds: ['u1'] }, assigned('u1', 'u2'))).toBe(true)
    expect(batchEditUnchanged({ field: 'assignees', mode: 'replace', userIds: ['u2', 'u1'] }, assigned('u1', 'u2'))).toBe(false)
    expect(batchEditUnchanged({ field: 'priority', priority: null }, row())).toBe(true)
    expect(batchEditUnchanged({ field: 'content', contentId: 'c1' }, row())).toBe(true)
    expect(batchEditUnchanged({ field: 'dueDate', dueDate: null, dueTime: null }, row())).toBe(true)
    expect(batchEditUnchanged({ field: 'dueDate', dueDate: undefined, dueTime: '09:00' },
      row({ dueDate: new Date('2026-10-20T00:00:00Z'), dueTime: '09:00' }))).toBe(true)
  })

  it('只改日期时保留每项时间，只改时间时保留每项日期，清空日期同时清空时间', async () => {
    const anchor = row({ dueDate: new Date('2026-10-20T00:00:00Z'), dueTime: '09:00' })
    expect(dueDateBatchEdit(anchor, { dueDate: '2026-10-21', dueTime: '09:00' })).toEqual({ field: 'dueDate', dueDate: '2026-10-21', dueTime: undefined })
    expect(dueDateBatchEdit(anchor, { dueDate: '2026-10-20', dueTime: '18:00' })).toEqual({ field: 'dueDate', dueDate: undefined, dueTime: '18:00' })
    expect(dueDateBatchEdit(anchor, { dueDate: null, dueTime: null })).toEqual({ field: 'dueDate', dueDate: null, dueTime: null })

    const other = row({ dueDate: new Date('2026-11-01T00:00:00Z'), dueTime: '14:30' })
    await applyBatchEdit({ field: 'dueDate', dueDate: '2026-10-21', dueTime: undefined }, other, common)
    await applyBatchEdit({ field: 'dueDate', dueDate: undefined, dueTime: '18:00' }, other, common)
    expect(vi.mocked(workItemsApi.patchWorkItemDueDate).mock.calls.map(([request]) => request.workItemDueDatePatchRequest)).toEqual([
      { dueDate: new Date('2026-10-21T00:00:00Z'), dueTime: '14:30' },
      { dueDate: new Date('2026-11-01T00:00:00Z'), dueTime: '18:00' },
    ])
  })

  it('按每项当前处理人计算列表，迁移说明只发给需要说明的迁移', async () => {
    await applyBatchEdit({ field: 'assignees', mode: 'add', userIds: ['u3'] }, assigned('u1'), common)
    expect(workItemsApi.patchWorkItemAssignees).toHaveBeenCalledWith({ ...common, workItemAssigneesPatchRequest: { assigneeUserIds: ['u1', 'u3'] } })
    await applyBatchEdit({ field: 'status', statusCode: 'DONE', resolution: '验收通过' }, row(), common)
    await applyBatchEdit({ field: 'status', statusCode: 'DONE', resolution: '验收通过' }, row({ capabilities: { ...row().capabilities,
      availableTransitions: [{ toStatus: 'DONE', displayName: '已完成', requiresResolution: false }] } as never }), common)
    expect(vi.mocked(workItemsApi.transitionWorkItem).mock.calls.map(([request]) => request.workItemTransitionRequest.resolution))
      .toEqual(['验收通过', null])
  })
})
