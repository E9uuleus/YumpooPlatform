import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { WorkItemDetail } from '@yumpoo/api-client'
import { duplicateWorkItem, type WorkItemDuplicateOptions } from './workItemDuplicate'

const api = vi.hoisted(() => ({ getWorkItem: vi.fn(), createWorkItem: vi.fn(), createWorkItemSubitem: vi.fn(),
  moveProjectWorkItemOrder: vi.fn(), moveWorkItemSubitemOrder: vi.fn() }))
vi.mock('../../api/client', () => ({ workItemsApi: api }))
vi.mock('@yumpoo/api-client', async original => ({ ...await original<typeof import('@yumpoo/api-client')>(), readCsrfToken: () => 'csrf' }))
const detail = { id: 'original', projectId: 'p', title: '完整详情标题', contentId: 'c', priority: 'HIGH', assigneeUserId: 'u',
  description: '<p>描述<img src="/api/v1/attachments/image/content"></p>', notes: '备注',
  timelineStartDate: new Date('2026-09-01'), timelineEndDate: new Date('2026-09-25'), dueDate: new Date('2026-10-01'), dueTime: '16:30',
  statusCode: 'DONE', attachments: ['image'], subitemCount: 3, etag: '"8"' } as unknown as WorkItemDetail
function options(): WorkItemDuplicateOptions {
  return { item: { id: 'original', projectId: 'p' }, sorted: false,
    catalog: { items: [{ id: 'c', active: true }] }, labels: { priorities: [{ code: 'HIGH', active: true }] },
    members: [{ userId: 'u', membershipStatus: 'ACTIVE', employmentStatus: 'ACTIVE', accountStatus: 'ENABLED' }],
  } as WorkItemDuplicateOptions
}
beforeEach(() => {
  Object.values(api).forEach(mock => mock.mockReset())
  api.getWorkItem.mockResolvedValue(detail)
  api.createWorkItem.mockResolvedValue({ ...detail, id: 'copy', etag: '"1"' })
  api.createWorkItemSubitem.mockResolvedValue({ ...detail, id: 'child-copy', etag: '"1"' })
  api.moveProjectWorkItemOrder.mockResolvedValue({ ...detail, id: 'copy', etag: '"2"' })
  api.moveWorkItemSubitemOrder.mockResolvedValue({ ...detail, id: 'child-copy', etag: '"2"' })
})
describe('复制工作项', () => {
  it('从详情映射允许的字段，保留图片地址，状态和关联数据不进入创建请求', async () => {
    const result = await duplicateWorkItem(options())
    expect(api.getWorkItem).toHaveBeenCalledWith({ workItemId: 'original' })
    expect(api.createWorkItem.mock.calls[0]![0].workItemCreateRequest).toEqual({ contentId: 'c', title: '完整详情标题（副本）',
      priority: 'HIGH', assigneeUserId: 'u', description: detail.description, notes: '备注',
      timelineStartDate: detail.timelineStartDate, timelineEndDate: detail.timelineEndDate, dueDate: detail.dueDate, dueTime: '16:30' })
    expect(api.moveProjectWorkItemOrder).toHaveBeenCalledWith(expect.objectContaining({ projectId: 'p', workItemId: 'copy', ifMatch: '"1"',
      projectWorkItemOrderMoveRequest: { previousVisibleWorkItemId: 'original', nextVisibleWorkItemId: null } }))
    expect(api.moveProjectWorkItemOrder.mock.calls[0]![0].idempotencyKey).not.toBe(api.createWorkItem.mock.calls[0]![0].idempotencyKey)
    expect(result).toMatchObject({ status: 'created', item: { id: 'copy', etag: '"2"' } })
  })
  it('子项沿用同一个父项创建和放置', async () => {
    await duplicateWorkItem({ ...options(), parentId: 'parent' })
    expect(api.createWorkItem).not.toHaveBeenCalled()
    expect(api.createWorkItemSubitem).toHaveBeenCalledWith(expect.objectContaining({ parentWorkItemId: 'parent' }))
    expect(api.moveWorkItemSubitemOrder).toHaveBeenCalledWith(expect.objectContaining({ parentWorkItemId: 'parent', subitemId: 'child-copy', ifMatch: '"1"' }))
  })
  it.each(['membershipStatus', 'employmentStatus', 'accountStatus'] as const)('无效 %s 与停用优先级置空，标题含后缀最多 300', async field => {
    const input = options()
    input.members[0] = { ...input.members[0]!, [field]: 'INACTIVE' }
    input.labels.priorities[0]!.active = false
    api.getWorkItem.mockResolvedValue({ ...detail, title: '长'.repeat(300) })
    await duplicateWorkItem(input)
    expect(api.createWorkItem.mock.calls[0]![0].workItemCreateRequest).toMatchObject({ title: '长'.repeat(296) + '（副本）', priority: null, assigneeUserId: null })
  })
  it('类别停用时跳过，并明确原因', async () => {
    const input = options(); input.catalog.items[0]!.active = false
    expect(await duplicateWorkItem(input)).toEqual({ status: 'skipped', reason: '类别已停用或不可用' })
    expect(api.createWorkItem).not.toHaveBeenCalled()
  })
  it('放置失败仍返回创建成功和警告，显式排序不放置', async () => {
    api.moveProjectWorkItemOrder.mockRejectedValue(new Error('move failed'))
    expect(await duplicateWorkItem(options())).toMatchObject({ status: 'created', item: { id: 'copy' }, warning: expect.stringContaining('放置到原项下方失败') })
    api.moveProjectWorkItemOrder.mockClear()
    expect(await duplicateWorkItem({ ...options(), sorted: true })).toMatchObject({ status: 'created' })
    expect(api.moveProjectWorkItemOrder).not.toHaveBeenCalled()
  })
})
