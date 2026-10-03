import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { computed, defineComponent, reactive, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ProjectActorAccess, ProjectLifecycle, type ProjectDetail, type ProjectWorkItemListItem } from '@yumpoo/api-client'
import { useConnectTable } from './useConnectTable'
import { connectColumnPrefsKey } from './connectColumnPrefs'
import { connectionCatalog } from './connectTestFixtures'

const api = vi.hoisted(() => ({ listConnectColumns: vi.fn(), listWorkItemConnectionCells: vi.fn() }))
vi.mock('../../../api/client', () => ({ workItemsApi: api }))
vi.mock('../../../composables/useSession', () => ({ useSession: () => ({ authentication: { value: { user: { id: 'user' }, company: { id: 'company' } } } }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }))
enableAutoUnmount(afterEach)
beforeEach(() => {
  localStorage.clear(); vi.resetAllMocks()
  api.listConnectColumns.mockResolvedValue({ ...connectionCatalog, incomingAvailable: true })
  api.listWorkItemConnectionCells.mockResolvedValue({ items: [] })
})
function harness() {
  const projectId = ref('project-1'), enabled = ref(true)
  const project = reactive({ lifecycle: ProjectLifecycle.Active, actorAccess: ProjectActorAccess.Owner } as ProjectDetail)
  const rows = ref([{ id: 'item-1', rowVersion: 1 } as ProjectWorkItemListItem])
  let table!: ReturnType<typeof useConnectTable>
  mount(defineComponent({ setup() { table = useConnectTable({ projectId, enabled, project: computed(() => project), rows: () => rows.value, table: () => undefined }); return () => null } }))
  return { table, projectId, enabled, project, rows }
}
describe('表格连接列集成状态', () => {
  it('服务端顺序后追加被连接列，个人隐藏与宽度不混入内置偏好', async () => {
    const { table } = harness(); await flushPromises()
    expect(table.visibleColumns.value.map(column => column.key)).toEqual(['connect:column-1', 'connect-incoming'])
    table.resizeColumn('connect:column-1', 80); table.toggleColumn('connect-incoming', false)
    expect(table.visibleColumns.value.map(column => column.width)).toEqual([140])
    expect(JSON.parse(localStorage.getItem(connectColumnPrefsKey('company', 'user', 'project-1'))!)).toMatchObject({ hidden: ['connect-incoming'], widths: { 'connect:column-1': 140 } })
    expect(localStorage.getItem('yumpoo:project-work-items:table:v1')).toBeNull()
  })
  it('项目切换恢复各自偏好，嵌入开关清空连接显示', async () => {
    const { table, projectId, enabled } = harness(); await flushPromises()
    table.toggleColumn('connect:column-1', false)
    projectId.value = 'project-2'; await flushPromises()
    expect(table.visibleColumns.value).toHaveLength(2)
    projectId.value = 'project-1'; await flushPromises()
    expect(table.visibleColumns.value.map(column => column.key)).toEqual(['connect-incoming'])
    enabled.value = false; await flushPromises()
    expect(table.visibleColumns.value).toEqual([])
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
