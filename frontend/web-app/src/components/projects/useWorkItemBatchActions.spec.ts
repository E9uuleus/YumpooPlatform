import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ProjectWorkItemListItem, WorkItemRelationCounterpart } from '@yumpoo/api-client'
import { useWorkItemBatchActions, type WorkItemSelection } from './useWorkItemBatchActions'
const api = vi.hoisted(() => ({ archiveWorkItem: vi.fn(), deleteWorkItem: vi.fn(), createWorkItemRelation: vi.fn(),
  moveProjectWorkItemOrder: vi.fn(), moveWorkItemSubitemOrder: vi.fn() }))
const duplicate = vi.hoisted(() => vi.fn())
vi.mock('../../api/client', () => ({ workItemsApi: api }))
vi.mock('./workItemDuplicate', () => ({ duplicateWorkItem: duplicate }))
vi.mock('@yumpoo/api-client', async original => ({ ...await original<typeof import('@yumpoo/api-client')>(), readCsrfToken: () => 'csrf' }))
function row(id: string): ProjectWorkItemListItem {
  return { id, title: id, projectId: 'p', contentId: 'c', etag: '"1"', subitemCount: 0,
    capabilities: { canDelete: true, canEditFields: true, canMoveInProjectOrder: true } } as ProjectWorkItemListItem
}
function setup(ids = ['a', 'b', 'c']) {
  const items = new Map(ids.map(id => [id, row(id)]))
  const selections: WorkItemSelection[] = ids.map(id => ({ id }))
  const refresh = vi.fn().mockResolvedValue(undefined), deselect = vi.fn(), orderItems = vi.fn().mockResolvedValue([...items.values()])
  let sorted = false, context = 'p'
  const batch = useWorkItemBatchActions({ contextId: () => context, selection: () => selections,
    resolve: id => items.get(id), canCreate: () => true, sorted: () => sorted,
    duplicateOptions: () => ({ catalog: { items: [] }, labels: { priorities: [] }, members: [] }) as never,
    scopeKey: (selection, item) => selection.parentId ?? item.contentId,
    orderItems, deselect, refresh, beforeRemove: async () => true })
  return { batch, items, selections, refresh, deselect, orderItems, sort: () => { sorted = true }, switchContext: () => { context = 'other' } }
}
beforeEach(() => {
  vi.restoreAllMocks()
  Object.values(api).forEach(mock => mock.mockReset().mockResolvedValue({}))
  duplicate.mockReset().mockResolvedValue({ status: 'created', item: { id: 'copy' } })
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  for (const level of ['success', 'warning', 'error'] as const) vi.spyOn(ElMessage, level).mockImplementation(() => ({ close() {} }))
})
describe('批量工作项执行', () => {
  it('串行使用每项最新 ETag 和新幂等键；部分失败只取消成功项并只刷新一次', async () => {
    const f = setup()
    let finish!: () => void
    api.deleteWorkItem.mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve }))
      .mockRejectedValueOnce(new Error('412')).mockResolvedValueOnce({})
    const task = f.batch.run('delete')
    await vi.waitFor(() => expect(api.deleteWorkItem).toHaveBeenCalledOnce())
    expect(f.refresh).not.toHaveBeenCalled()
    f.items.set('b', { ...row('b'), etag: '"9"' })
    finish()
    const result = await task
    expect(api.deleteWorkItem.mock.calls.map(([request]) => request.ifMatch)).toEqual(['"1"', '"9"', '"1"'])
    expect(new Set(api.deleteWorkItem.mock.calls.map(([request]) => request.idempotencyKey)).size).toBe(3)
    expect(result).toMatchObject({ succeeded: ['a', 'c'], failed: [{ id: 'b' }], remaining: 0 })
    expect(f.deselect.mock.calls.map(([selection]) => selection.id)).toEqual(['a', 'c'])
    expect(f.refresh).toHaveBeenCalledOnce()
    expect(ElMessageBox.confirm).toHaveBeenCalledOnce()
  })
  it('停止等待当前项完成，不继续下一项，刷新一次且保留未执行的勾选', async () => {
    const f = setup()
    let finish!: () => void
    api.archiveWorkItem.mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve }))
    const task = f.batch.run('archive')
    await vi.waitFor(() => expect(api.archiveWorkItem).toHaveBeenCalledOnce())
    expect(f.batch.progressLabel.value).toBe('归档中 1/3')
    f.batch.stop(); finish()
    expect(await task).toMatchObject({ succeeded: ['a'], remaining: 2 })
    expect(api.archiveWorkItem).toHaveBeenCalledOnce()
    expect(f.refresh).toHaveBeenCalledOnce()
  })
  it('删除汇总确认包含子项、跳过数和父项隐藏提示，子项先执行', async () => {
    const f = setup()
    f.items.set('a', { ...row('a'), subitemCount: 2 })
    f.selections[1]!.parentId = 'a'
    f.items.set('c', { ...row('c'), capabilities: { ...row('c').capabilities, canDelete: false } })
    const result = await f.batch.run('delete')
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringMatching(/含 1 个子工作项；跳过 1 项.*父子关系保留/), '批量删除', expect.anything())
    expect(api.deleteWorkItem.mock.calls.map(([request]) => request.workItemId)).toEqual(['b', 'a'])
    expect(result?.skipped).toEqual([{ id: 'c', reason: '没有删除权限' }])
  })
  it('转换跳过子项、真实有子项的主项和无编辑权限项，并排除选中父项', async () => {
    const f = setup(['a', 'b', 'c', 'd'])
    f.selections[1]!.parentId = 'parent'
    f.items.set('c', { ...row('c'), subitemCount: 3 })
    f.items.set('d', { ...row('d'), capabilities: { ...row('d').capabilities, canEditFields: false } })
    const parent = { id: 'parent', projectId: 'p' } as WorkItemRelationCounterpart
    expect(await f.batch.run('convert', undefined, { ...parent, id: 'a' })).toBeUndefined()
    const result = await f.batch.run('convert', undefined, parent)
    expect(result).toMatchObject({ succeeded: ['a'], skipped: [{ id: 'b' }, { id: 'c' }, { id: 'd' }] })
    expect(api.createWorkItemRelation).toHaveBeenCalledOnce()
    expect(api.createWorkItemRelation).toHaveBeenCalledWith(expect.objectContaining({ workItemId: 'a', workItemRelationCreateRequest: expect.objectContaining({ targetWorkItemId: 'parent' }) }))
    expect(f.refresh).toHaveBeenCalledWith(['a', 'parent'])
    expect(ElMessageBox.confirm).not.toHaveBeenCalled()
  })
  it('复制保留勾选、跳过停用类别；放置警告不算失败，超过 20 项才确认', async () => {
    const f = setup()
    duplicate.mockResolvedValueOnce({ status: 'skipped', reason: '类别已停用' })
      .mockResolvedValueOnce({ status: 'created', item: { id: 'copy' }, warning: '放置失败' })
    expect(await f.batch.run('duplicate')).toMatchObject({ succeeded: ['b', 'c'], skipped: [{ id: 'a' }], warnings: ['放置失败'] })
    expect(f.deselect).not.toHaveBeenCalled()
    expect(f.refresh).toHaveBeenCalledOnce()
    expect(ElMessageBox.confirm).not.toHaveBeenCalled()
    const large = setup(Array.from({ length: 21 }, (_, i) => String(i)))
    await large.batch.run('duplicate')
    expect(ElMessageBox.confirm).toHaveBeenCalledOnce()
  })
  it('按分组与父项整块移动，权限失败不推进锚点，显式排序禁用整个动作', async () => {
    const f = setup(['a', 'b', 'c', 'd', 'e'])
    f.items.get('c')!.contentId = 'group-2'
    f.selections[3]!.parentId = 'parent'
    f.items.set('e', { ...row('e'), capabilities: { ...row('e').capabilities, canMoveInProjectOrder: false } })
    f.orderItems.mockImplementation(async (item: ProjectWorkItemListItem) => item.id === 'a'
      ? [row('anchor-1'), row('a'), row('b')] : item.id === 'c' ? [row('c'), row('anchor-2')] : [row('d'), row('sibling')])
    api.moveProjectWorkItemOrder.mockRejectedValueOnce(new Error('failed'))
    const result = await f.batch.run('top')
    expect(result).toMatchObject({ succeeded: ['b', 'c', 'd'], failed: [{ id: 'a' }], skipped: [{ id: 'e' }] })
    expect(api.moveProjectWorkItemOrder.mock.calls.map(([r]) => r.projectWorkItemOrderMoveRequest)).toEqual([
      { previousVisibleWorkItemId: null, nextVisibleWorkItemId: 'anchor-1' },
      { previousVisibleWorkItemId: null, nextVisibleWorkItemId: 'anchor-1' },
      { previousVisibleWorkItemId: null, nextVisibleWorkItemId: 'anchor-2' },
    ])
    expect(api.moveWorkItemSubitemOrder).toHaveBeenCalledWith(expect.objectContaining({ parentWorkItemId: 'parent', subitemId: 'd' }))
    expect(f.deselect).not.toHaveBeenCalled()
    f.sort(); await f.batch.run('bottom')
    expect(api.moveProjectWorkItemOrder).toHaveBeenCalledTimes(3)
  })
  it('取消确认或上下文改变不操作新项目，刷新失败不把成功项变成失败', async () => {
    const f = setup()
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
    await f.batch.run('delete')
    expect(api.deleteWorkItem).not.toHaveBeenCalled()
    expect(f.refresh).not.toHaveBeenCalled()
    f.refresh.mockRejectedValue(new Error('refresh failed'))
    expect(await f.batch.run('delete')).toMatchObject({ succeeded: ['a', 'b', 'c'], failed: [], warnings: [expect.stringContaining('刷新失败')] })
    api.archiveWorkItem.mockImplementationOnce(async () => { f.switchContext() })
    const calls = f.refresh.mock.calls.length
    expect(await f.batch.run('archive')).toMatchObject({ succeeded: ['a'], remaining: 2 })
    expect(f.refresh).toHaveBeenCalledTimes(calls)
  })
})
