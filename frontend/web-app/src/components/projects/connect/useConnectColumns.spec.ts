import { effectScope, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ResponseError, type WorkItemConnectionCellList } from '@yumpoo/api-client'
import { useConnectColumns } from './useConnectColumns'
import { connectColumn, connection, connectionCatalog } from './connectTestFixtures'

const api = vi.hoisted(() => ({ listConnectColumns: vi.fn(), listWorkItemConnectionCells: vi.fn(), createConnectColumn: vi.fn(),
  updateConnectColumn: vi.fn(), deleteConnectColumn: vi.fn(), linkWorkItemConnection: vi.fn(), createConnectedWorkItem: vi.fn(), unlinkWorkItemConnection: vi.fn(), getWorkItemConnection: vi.fn() }))
vi.mock('../../../api/client', () => ({ workItemsApi: api }))
const csrf = vi.hoisted(() => ({ token: 'csrf' as string | undefined }))
vi.mock('@yumpoo/api-client', async original => ({ ...await original<typeof import('@yumpoo/api-client')>(), readCsrfToken: () => csrf.token }))
const scopes: ReturnType<typeof effectScope>[] = []
function harness() {
  const scope = effectScope(); scopes.push(scope)
  const projectId = ref('source-project'), enabled = ref(true)
  const data = scope.run(() => useConnectColumns(projectId, enabled))!
  return { data, projectId, enabled }
}
function response(ids: string[]): WorkItemConnectionCellList { return { items: ids.map(workItemId => ({ workItemId, outgoing: [], incoming: [], incomingTotal: 0 })) } }
beforeEach(() => {
  vi.resetAllMocks(); csrf.token = 'csrf'
  api.listConnectColumns.mockResolvedValue(connectionCatalog)
  api.listWorkItemConnectionCells.mockImplementation(async ({ workItemIds }: { workItemIds: string[] }) => response(workItemIds))
  api.linkWorkItemConnection.mockResolvedValue(connection); api.createConnectedWorkItem.mockResolvedValue(connection)
  api.unlinkWorkItemConnection.mockResolvedValue({ ...connection, active: false })
  api.createConnectColumn.mockResolvedValue(connectColumn); api.updateConnectColumn.mockResolvedValue(connectColumn)
  api.deleteConnectColumn.mockResolvedValue({ columnId: connectColumn.id, removedConnectionCount: 3 })
})
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()) })
describe('连接目录与批量单元格', () => {
  it('每批最多 100 个、去重、缓存命中不再请求，重叠在途请求共享', async () => {
    const { data } = harness()
    const ids = Array.from({ length: 205 }, (_, i) => `item-${i}`)
    await Promise.all([data.ensureCells([...ids, ids[0]!]), data.ensureCells(ids)])
    expect(api.listWorkItemConnectionCells.mock.calls.map(call => call[0].workItemIds.length)).toEqual([100, 100, 5])
    await data.ensureCells(ids)
    expect(api.listWorkItemConnectionCells).toHaveBeenCalledTimes(3)
    expect(data.cells.value.size).toBe(205)
  })
  it('项目切换中止目录与单元格请求，迟到响应不污染新项目', async () => {
    const { data, projectId } = harness()
    let finishCatalog!: (value: typeof connectionCatalog) => void, finishCells!: (value: WorkItemConnectionCellList) => void
    api.listConnectColumns.mockImplementationOnce(() => new Promise(resolve => { finishCatalog = resolve }))
    api.listWorkItemConnectionCells.mockImplementationOnce(() => new Promise(resolve => { finishCells = resolve }))
    const oldCatalog = data.loadCatalog(), oldCells = data.ensureCells(['old'])
    projectId.value = 'new-project'
    expect(api.listConnectColumns.mock.calls[0]![1].signal.aborted).toBe(true)
    expect(api.listWorkItemConnectionCells.mock.calls[0]![1].signal.aborted).toBe(true)
    finishCatalog(connectionCatalog); finishCells(response(['old']))
    await Promise.all([oldCatalog, oldCells])
    expect(data.catalog.value).toBeUndefined(); expect(data.cells.value.size).toBe(0)
  })
  it('同项目强制刷新不会被较早请求的旧单元格覆盖', async () => {
    const { data } = harness()
    let finish!: (value: WorkItemConnectionCellList) => void
    api.listWorkItemConnectionCells.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const old = data.ensureCells(['item'])
    api.listWorkItemConnectionCells.mockResolvedValueOnce({ items: [{ ...response(['item']).items[0], incoming: [connection], incomingTotal: 1 }] })
    await data.refreshCells(['item']); finish(response(['item'])); await old
    expect(data.cells.value.get('item')?.incomingTotal).toBe(1)
  })
  it('嵌入模式禁用时不会加载目录或单元格', async () => {
    const { data, enabled } = harness(); enabled.value = false
    await data.loadCatalog(); await data.ensureCells(['item']); await data.refreshCells(['item'])
    expect(api.listConnectColumns).not.toHaveBeenCalled(); expect(api.listWorkItemConnectionCells).not.toHaveBeenCalled()
  })
  it('写入带 CSRF、幂等键与 ETag，成功后批量刷新当前端', async () => {
    const { data } = harness()
    await data.link('source-item', connectColumn.id, 'target-item')
    expect(api.linkWorkItemConnection.mock.calls[0]![0]).toMatchObject({ xXSRFTOKEN: 'csrf', idempotencyKey: expect.any(String) })
    expect(api.listWorkItemConnectionCells.mock.calls[0]![0].workItemIds).toEqual(['source-item'])
    await data.unlink(connection)
    expect(api.unlinkWorkItemConnection.mock.calls[0]![0]).toMatchObject({ ifMatch: '"1"', xXSRFTOKEN: 'csrf' })
    await data.updateColumn(connectColumn, { name: '新列名', targetProjectIds: new Set(['target-project']) })
    expect(api.updateConnectColumn.mock.calls[0]![0].ifMatch).toBe('"1"')
    expect(api.listConnectColumns).toHaveBeenCalled()
    expect(await data.deleteColumn(connectColumn)).toBe(3)
  })
  it('409/412 先刷新目录和受影响单元格，再交给 UI 提示', async () => {
    const { data } = harness()
    const failure = new ResponseError(new Response(JSON.stringify({ code: 'INVALID_STATE_TRANSITION', message: '冲突', requestId: 'test', retryable: false, fieldErrors: [], details: { reason: 'CONNECTION_LIMIT' } }), { status: 409 }))
    api.linkWorkItemConnection.mockRejectedValueOnce(failure)
    await expect(data.link('source-item', connectColumn.id, 'target-item')).rejects.toBe(failure)
    expect(api.listConnectColumns).toHaveBeenCalledTimes(1)
    expect(api.listWorkItemConnectionCells.mock.calls[0]![0].workItemIds).toEqual(['source-item'])
  })
  it('缺少 CSRF 不发送写请求', async () => {
    csrf.token = undefined
    const { data } = harness()
    await expect(data.unlink(connection)).rejects.toMatchObject({ problem: { message: expect.stringContaining('CSRF') } })
    expect(api.unlinkWorkItemConnection).not.toHaveBeenCalled()
  })
})
