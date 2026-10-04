import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { computed, defineComponent, reactive, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ProjectActorAccess, ProjectLifecycle, type ProjectDetail, type ProjectWorkItemListItem } from '@yumpoo/api-client'
import { useConnectTable } from './useConnectTable'
import { connectColumnPrefsKey } from './connectColumnPrefs'
import { connectionCatalog } from './connectTestFixtures'

const reverseColumn = { columnId: 'reverse-1', columnName: '实施问题', projectId: 'other-project', projectCode: 'P9', projectName: '华南现场',
  projectLifecycle: ProjectLifecycle.Active, actorCanLinkExisting: true }

const api = vi.hoisted(() => ({ listConnectColumns: vi.fn(), listWorkItemConnectionCells: vi.fn(), createConnectColumn: vi.fn(), updateConnectColumn: vi.fn() }))
const replace = vi.hoisted(() => vi.fn())
const route = reactive({ path: '/projects/project-1/overview', query: {} as Record<string, string>, hash: '#table' })
vi.mock('../../../api/client', () => ({ workItemsApi: api }))
vi.mock('@yumpoo/api-client', async original => ({ ...await original<typeof import('@yumpoo/api-client')>(), readCsrfToken: () => 'csrf' }))
vi.mock('../../../composables/useSession', () => ({ useSession: () => ({ authentication: { value: { user: { id: 'user' }, company: { id: 'company' } } } }) }))
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ replace }) }))
enableAutoUnmount(afterEach)
beforeEach(() => {
  localStorage.clear(); vi.resetAllMocks()
  route.query = {}
  replace.mockImplementation(async ({ query }: { query: Record<string, string> }) => { route.query = query })
  api.listConnectColumns.mockResolvedValue({ ...connectionCatalog, incomingAvailable: true, incomingColumns: [reverseColumn] })
  api.listWorkItemConnectionCells.mockResolvedValue({ items: [] })
})
function harness() {
  const projectId = ref('project-1'), enabled = ref(true)
  const project = reactive({ lifecycle: ProjectLifecycle.Active, actorAccess: ProjectActorAccess.Owner } as ProjectDetail)
  const rows = ref([{ id: 'item-1', rowVersion: 1 } as ProjectWorkItemListItem])
  let table!: ReturnType<typeof useConnectTable>
  const wrapper = mount(defineComponent({ setup() { table = useConnectTable({ projectId, enabled, project: computed(() => project), rows: () => rows.value, table: () => undefined }); return () => null } }))
  return { table, projectId, enabled, project, rows, wrapper }
}
describe('表格连接列集成状态', () => {
  it('目录加载后只定位一次并清除参数，保留其他路由状态和随后隐藏的偏好', async () => {
    route.query = { connectColumn: 'column-1', view: 'table', workItemId: 'item-1' }
    let finish!: (catalog: typeof connectionCatalog) => void
    api.listConnectColumns.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    localStorage.setItem(connectColumnPrefsKey('company', 'user', 'project-1'), JSON.stringify({ hidden: ['connect:column-1'], widths: {} }))
    const { table, wrapper } = harness(); await flushPromises()
    expect(replace).not.toHaveBeenCalled()
    finish(connectionCatalog); await flushPromises()
    expect(table.visibleColumns.value.map(column => column.key)).toContain('connect:column-1')
    expect(replace).toHaveBeenCalledExactlyOnceWith({ path: route.path, query: { view: 'table', workItemId: 'item-1' }, hash: '#table' })
    table.toggleColumn('connect:column-1', false)
    api.listConnectColumns.mockResolvedValue({ ...connectionCatalog, items: [...connectionCatalog.items, { ...connectionCatalog.items[0]!, id: 'column-2' }] })
    await table.loadCatalog(); await flushPromises()
    expect(table.visibleColumns.value.map(column => column.key)).not.toContain('connect:column-1')
    api.listConnectColumns.mockResolvedValue(connectionCatalog)
    await table.loadCatalog(); await flushPromises()
    wrapper.unmount()
    const reloaded = harness(); await flushPromises()
    expect(reloaded.table.visibleColumns.value.map(column => column.key)).not.toContain('connect:column-1')
    expect(replace).toHaveBeenCalledTimes(1)
  })
  it('拖动期间只更新显示宽度，完成时才保存偏好', async () => {
    const { table } = harness(); await flushPromises()
    const key = connectColumnPrefsKey('company', 'user', 'project-1')
    const before = localStorage.getItem(key)
    table.resizeColumn('connect:column-1', 220, false)
    table.resizeColumn('connect:column-1', 250, false)
    expect(table.width('connect:column-1')).toBe(250)
    expect(localStorage.getItem(key)).toBe(before)
    table.resizeColumn('connect:column-1', 250, true)
    expect(JSON.parse(localStorage.getItem(key)!)).toMatchObject({ widths: { 'connect:column-1': 250 } })
  })
  it('服务端顺序后追加以来源项目命名的反向列，个人隐藏与宽度不混入内置偏好', async () => {
    const { table } = harness(); await flushPromises()
    expect(table.visibleColumns.value.map(column => [column.key, column.kind, column.label])).toEqual([
      ['connect:column-1', 'connect', '产品缺陷'], ['connect-reverse:reverse-1', 'reverse', '华南现场']])
    table.resizeColumn('connect:column-1', 80); table.toggleColumn('connect-reverse:reverse-1', false)
    expect(table.visibleColumns.value.map(column => column.width)).toEqual([140])
    expect(JSON.parse(localStorage.getItem(connectColumnPrefsKey('company', 'user', 'project-1'))!)).toMatchObject({ hidden: ['connect-reverse:reverse-1'], widths: { 'connect:column-1': 140 } })
    expect(localStorage.getItem('yumpoo:project-work-items:table:v1')).toBeNull()
  })
  it('项目切换恢复各自偏好，嵌入开关清空连接显示', async () => {
    const { table, projectId, enabled } = harness(); await flushPromises()
    table.toggleColumn('connect:column-1', false)
    projectId.value = 'project-2'; await flushPromises()
    expect(table.visibleColumns.value).toHaveLength(2)
    projectId.value = 'project-1'; await flushPromises()
    expect(table.visibleColumns.value.map(column => column.key)).toEqual(['connect-reverse:reverse-1'])
    enabled.value = false; await flushPromises()
    expect(table.visibleColumns.value).toEqual([])
  })
  it('草稿列占据新列位置，连接后以项目名创建并定位；改目标时自动名随项目更新，手动名保留', async () => {
    const { table } = harness(); await flushPromises()
    table.startDraft()
    expect(table.visibleColumns.value.map(column => column.key)).toEqual(['connect:column-1', 'connect-draft', 'connect-reverse:reverse-1'])
    expect(table.setup.value).toEqual({ key: 'connect-draft', mode: 'create' })
    table.toggleColumn('connect-draft', false)
    expect(table.visibleColumns.value.map(column => column.key)).not.toContain('connect-draft')
    table.startDraft()
    const created = { ...connectionCatalog.items[0]!, id: 'column-2', name: 'Yumpoo 门户 2' }
    api.createConnectColumn.mockResolvedValue(created)
    api.listConnectColumns.mockResolvedValue({ ...connectionCatalog, items: [...connectionCatalog.items, created], incomingColumns: [reverseColumn] })
    await table.connectProjects([{ id: 'target-project', name: '产品缺陷', code: 'P003' }])
    expect(api.createConnectColumn).toHaveBeenCalledWith(expect.objectContaining({ connectColumnCreateRequest: { name: '产品缺陷 2', targetProjectIds: new Set(['target-project']) } }), expect.anything())
    expect(table.draft.value).toBe(false); expect(table.setup.value).toBeUndefined()
    expect(table.celebrated.value).toBe('connect:column-2')
    const automatic = { ...connectionCatalog.items[0]!, name: 'Yumpoo 门户' }
    api.listConnectColumns.mockResolvedValue({ ...connectionCatalog, items: [automatic, created], incomingColumns: [reverseColumn] })
    await table.loadCatalog()
    api.updateConnectColumn.mockResolvedValue(automatic)
    await table.connectProjects([{ id: 'target-project', name: 'Yumpoo 门户', code: 'P003' }, { id: 'p4', name: '华南现场', code: 'P4' }], automatic)
    expect(api.updateConnectColumn).toHaveBeenLastCalledWith(expect.objectContaining({ connectColumnUpdateRequest: { name: 'Yumpoo 门户、华南现场', targetProjectIds: new Set(['target-project', 'p4']) } }), expect.anything())
    api.listConnectColumns.mockResolvedValue({ ...connectionCatalog, items: [connectionCatalog.items[0]!, created], incomingColumns: [reverseColumn] })
    await table.loadCatalog()
    await table.connectProjects([{ id: 'p4', name: '华南现场', code: 'P4' }], connectionCatalog.items[0]!)
    expect(api.updateConnectColumn).toHaveBeenLastCalledWith(expect.objectContaining({ connectColumnUpdateRequest: { name: '产品缺陷', targetProjectIds: new Set(['p4']) } }), expect.anything())
  })
  it('归档和企业管理员只读使列管理不可用，项目行更新强制刷新对应单元格', async () => {
    const { table, project, rows } = harness(); await flushPromises()
    expect(table.canManage.value).toBe(true)
    rows.value = [{ id: 'item-1', rowVersion: 2 } as ProjectWorkItemListItem]; await flushPromises()
    expect(api.listWorkItemConnectionCells).toHaveBeenCalledTimes(2)
    project.actorAccess = ProjectActorAccess.CompanyAdmin; await flushPromises()
    expect(table.canManage.value).toBe(false); expect(table.canDelete.value).toBe(false)
    project.actorAccess = ProjectActorAccess.Owner; project.lifecycle = ProjectLifecycle.Archived; await flushPromises()
    expect(table.readOnly.value).toBe(true)
  })
})
