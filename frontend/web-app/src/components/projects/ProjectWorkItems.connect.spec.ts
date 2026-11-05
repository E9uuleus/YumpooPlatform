import {
  ResponseError,
} from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { ElDropdown, ElMessageBox } from 'element-plus'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ConnectCell from './connect/ConnectCell.vue'
import ConnectColumnAddButton from './connect/ConnectColumnAddButton.vue'
import ConnectColumnSetupPopover from './connect/ConnectColumnSetupPopover.vue'
import ConnectedItemCard from './connect/ConnectedItemCard.vue'
import { connection, connectionCatalog } from './connect/connectTestFixtures'
import { state } from './projectWorkItemsTestMocks'
import { item, mountView, page, installWorkItemsTestDefaults, restoreWorkItemsTestGlobals } from './projectWorkItemsTestSupport'

vi.mock('../../onboarding/useOnboarding', async () => (await import('./projectWorkItemsTestMocks')).onboardingModule())
vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))
vi.mock('vue-router', async () => (await import('./projectWorkItemsTestMocks')).vueRouterModule())
vi.mock('../../api/client', async () => (await import('./projectWorkItemsTestMocks')).apiClientModule())

enableAutoUnmount(afterEach)

describe('项目级工作项首页 · 连接列', () => {
  beforeEach(installWorkItemsTestDefaults)
  afterEach(restoreWorkItemsTestGlobals)

  it('连接列固定在内置列之后，列宽按 key 更新，加号打开真实列中心', async () => {
    state.listConnectColumns.mockResolvedValue(connectionCatalog)
    state.listWorkItemConnectionCells.mockResolvedValue({ items: [{ workItemId: 'item-1', outgoing: [{ columnId: connection.columnId, connections: [connection] }], incoming: [], incomingTotal: 0, incomingByColumn: [] }] })
    const wrapper = mountView(); await flushPromises()
    expect(wrapper.findComponent(ConnectCell).props('connections')).toEqual([connection])
    const keys = wrapper.findAllComponents({ name: 'ElTableColumn' }).map(column => column.props('prop') || column.props('columnKey'))
    expect(keys.indexOf('connect:column-1')).toBeGreaterThan(keys.indexOf('updatedAt'))
    expect(keys.indexOf('connect:column-1')).toBeLessThan(keys.indexOf('add-column'))
    expect(wrapper.get('th.monday-connect-column-header').classes()).not.toContain('monday-movable-column-header')
    const vm = wrapper.vm as unknown as { onHeaderDragEnd: (next: number, old: number, column: { property: string }) => void }
    vm.onHeaderDragEnd(275, 200, { property: 'connect:column-1' }); await nextTick()
    expect(wrapper.findAllComponents({ name: 'ElTableColumn' }).find(column => column.props('prop') === 'connect:column-1')?.props('width')).toBe(275)
    expect(wrapper.findAllComponents({ name: 'ElTableColumn' }).find(column => column.props('prop') === 'priority')?.props('width')).toBe(90)
    wrapper.findComponent(ConnectColumnAddButton).vm.$emit('addConnectColumn'); await flushPromises()
    const withDraft = wrapper.findAllComponents({ name: 'ElTableColumn' }).map(column => column.props('prop') || column.props('columnKey'))
    expect(withDraft.indexOf('connect-draft')).toBe(withDraft.indexOf('connect:column-1') + 1)
    expect(wrapper.getComponent(ConnectColumnSetupPopover).props()).toMatchObject({ visible: true, mode: 'create' })
    wrapper.getComponent(ConnectColumnSetupPopover).vm.$emit('update:visible', false); await flushPromises()
    expect(wrapper.findAllComponents({ name: 'ElTableColumn' }).some(column => column.props('prop') === 'connect-draft')).toBe(false)
    expect(state.listWorkItemConnectionCells).toHaveBeenCalledTimes(1)
  })

  it('嵌入表格不加载、不渲染连接列和连接弹窗', async () => {
    state.listConnectColumns.mockResolvedValue(connectionCatalog)
    const wrapper = mountView(undefined, { embeddedProjectId: 'project-1', preferenceScope: 'dashboard:test' })
    await flushPromises()
    expect(state.listConnectColumns).not.toHaveBeenCalled(); expect(state.listWorkItemConnectionCells).not.toHaveBeenCalled()
    expect(wrapper.find('.monday-connect-column').exists()).toBe(false)
    expect(wrapper.findComponent(ConnectColumnSetupPopover).exists()).toBe(false)
  })

  it.each([200, 404])('从卡片解除连接返回 %i 时只刷新一次当前行', async status => {
    const current = { ...connection, source: { ...connection.source, projectId: 'project-1', workItemId: 'item-1' } }
    state.listConnectColumns.mockResolvedValue(connectionCatalog)
    state.listWorkItemConnectionCells.mockResolvedValue({ items: [{ workItemId: 'item-1', outgoing: [{ columnId: current.columnId, connections: [current] }], incoming: [], incomingTotal: 0, incomingByColumn: [] }] })
    state.getWorkItemConnection.mockResolvedValue(current)
    if (status === 404) {
      state.unlinkWorkItemConnection.mockRejectedValue(new ResponseError(new Response(JSON.stringify({ code: 'RESOURCE_NOT_FOUND', message: '不存在', requestId: 'test', retryable: false, fieldErrors: [], details: {} }), { status })))
    } else state.unlinkWorkItemConnection.mockResolvedValue({ ...current, active: false })
    const wrapper = mountView(); await flushPromises()
    wrapper.getComponent(ConnectCell).vm.$emit('openCard', current); await flushPromises()
    state.listWorkItemConnectionCells.mockClear()
    state.listWorkItemConnectionCells.mockResolvedValue({ items: [{ workItemId: 'item-1', outgoing: [], incoming: [], incomingTotal: 0, incomingByColumn: [] }] })
    const card = wrapper.getComponent(ConnectedItemCard)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    card.getComponent(ElDropdown).vm.$emit('command', 'unlink'); await flushPromises()
    expect(state.unlinkWorkItemConnection).toHaveBeenCalledTimes(1)
    expect(state.listWorkItemConnectionCells).toHaveBeenCalledTimes(1)
    expect(state.listWorkItemConnectionCells.mock.calls[0]![0].workItemIds).toEqual(['item-1'])
    expect(card.props('open')).toBe(false)
    expect(wrapper.getComponent(ConnectCell).props('connections')).toEqual([])
  })

  it('重新加载连接包含展开的子项行，恢复子项请求失败的单元格', async () => {
    state.listConnectColumns.mockResolvedValue(connectionCatalog)
    state.listProjectWorkItems.mockResolvedValue(page([{ ...item('parent'), subitemCount: 1 }]))
    state.listWorkItemSubitems.mockResolvedValue({ items: [item('child')] })
    const wrapper = mountView(); await flushPromises()
    state.listWorkItemConnectionCells.mockRejectedValueOnce(new Error('子项连接请求失败'))
    await wrapper.get('button[aria-label="展开子项"]').trigger('click'); await flushPromises()
    const retry = wrapper.findAll('button').find(button => button.text() === '重新加载连接')!
    expect(retry.exists()).toBe(true)
    state.listWorkItemConnectionCells.mockClear()
    state.listWorkItemConnectionCells.mockResolvedValue({ items: [{ workItemId: 'child', outgoing: [{ columnId: connection.columnId, connections: [connection] }], incoming: [], incomingTotal: 0, incomingByColumn: [] }] })
    await retry.trigger('click'); await flushPromises()
    expect(state.listWorkItemConnectionCells).toHaveBeenCalledTimes(1)
    expect(state.listWorkItemConnectionCells.mock.calls[0]![0].workItemIds).toEqual(['parent', 'child'])
    expect(wrapper.findAllComponents(ConnectCell).find(cell => cell.props('item').id === 'child')?.props('connections')).toEqual([connection])
  })
})
