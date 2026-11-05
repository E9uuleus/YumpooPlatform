import {
  WorkItemViewType,
  WorkItemStatusCategory,
  type WorkItemDetail,
} from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ConnectedItemCard from './connect/ConnectedItemCard.vue'
import ConnectKanbanConnections from './connect/ConnectKanbanConnections.vue'
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

describe('项目级工作项首页 · 看板', () => {
  beforeEach(installWorkItemsTestDefaults)
  afterEach(restoreWorkItemsTestGlobals)

  it('看板批量加载出站连接，chip 打开只读连接卡片且不打开工作项抽屉', async () => {
    state.route.query = { view: 'kanban' }
    state.listConnectColumns.mockResolvedValue(connectionCatalog)
    state.listProjectWorkItems.mockImplementation(async request => request.status.has('BACKLOG') ? page([item()]) : page([]))
    state.listWorkItemConnectionCells.mockResolvedValue({ items: [{ workItemId: 'item-1',
      outgoing: [{ columnId: connection.columnId, connections: [connection] }], incoming: [], incomingTotal: 0, incomingByColumn: [] }] })
    const wrapper = mountView(); await flushPromises()
    expect(state.listProjectWorkItems).toHaveBeenCalledWith(expect.objectContaining({
      view: WorkItemViewType.Kanban,
    }), expect.anything())
    expect(state.listWorkItemConnectionCells).toHaveBeenCalledWith(expect.objectContaining({ workItemIds: ['item-1'] }), expect.anything())
    const chips = wrapper.getComponent(ConnectKanbanConnections)
    expect(chips.props('cell')?.outgoing[0]?.connections).toEqual([connection])
    await chips.get('.connection-chip').trigger('click'); await flushPromises()
    const dialog = wrapper.getComponent(ConnectedItemCard)
    expect(dialog.props()).toMatchObject({ open: true, readOnly: true, perspective: 'source' })
    expect(state.getWorkItem).not.toHaveBeenCalledWith(expect.objectContaining({ workItemId: 'item-1' }))
    expect(wrapper.find('.kanban-card button button').exists()).toBe(false)
  })

  it.each(['catalog', 'cells'])('看板连接 %s 请求失败时显示重试并恢复 chip', async failure => {
    state.route.query = { view: 'kanban' }
    state.listConnectColumns.mockResolvedValue(connectionCatalog)
    state.listProjectWorkItems.mockImplementation(async request => request.status.has('BACKLOG') ? page([item()]) : page([]))
    state.listWorkItemConnectionCells.mockResolvedValue({ items: [{ workItemId: 'item-1',
      outgoing: [{ columnId: connection.columnId, connections: [connection] }], incoming: [], incomingTotal: 0, incomingByColumn: [] }] })
    const request = failure === 'catalog' ? state.listConnectColumns : state.listWorkItemConnectionCells
    request.mockRejectedValueOnce(new Error('连接加载失败'))
    const wrapper = mountView(); await flushPromises()
    const retry = wrapper.findAll('button').find(button => button.text() === '重新加载连接')
    expect(retry).toBeDefined()
    expect(wrapper.getComponent(ConnectKanbanConnections).props('cell')).toBeUndefined()
    await retry!.trigger('click'); await flushPromises()
    expect(wrapper.findAll('button').some(button => button.text() === '重新加载连接')).toBe(false)
    expect(wrapper.getComponent(ConnectKanbanConnections).props('cell')?.outgoing[0]?.connections).toEqual([connection])
  })

  it('看板只允许 availableTransitions 中的目标状态', async () => {
    state.route.query = { view: 'kanban' }
    const movable = item()
    movable.capabilities = {
      ...movable.capabilities,
      availableTransitions: [{
        toStatus: 'DONE', displayName: '已完成', statusCategory: WorkItemStatusCategory.Done,
        requiresResolution: false,
      }],
    }
    state.listProjectWorkItems.mockImplementation(({ status }: { status?: Set<string> }) =>
      Promise.resolve(page(status?.has('BACKLOG') ? [movable] : [])))
    state.transitionWorkItem.mockResolvedValue({
      ...movable, statusCode: 'DONE', statusCategory: WorkItemStatusCategory.Done,
      rowVersion: 2, etag: '"2"',
    } as unknown as WorkItemDetail)
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('.kanban-card').trigger('dragstart')
    await wrapper.findAll('.kanban-lane')[1]!.trigger('drop')
    await flushPromises()
    expect(state.transitionWorkItem).toHaveBeenCalledWith(expect.objectContaining({
      workItemId: 'item-1',
      workItemTransitionRequest: { toStatus: 'DONE', resolution: null },
    }))

    movable.capabilities = { ...movable.capabilities, availableTransitions: [] }
    state.transitionWorkItem.mockClear()
    await wrapper.get('.kanban-card').trigger('dragstart')
    await wrapper.findAll('.kanban-lane')[1]!.trigger('drop')
    await flushPromises()
    expect(state.transitionWorkItem).not.toHaveBeenCalled()
  })

  it('需说明的看板迁移携带用户输入，并在冲突后刷新泳道', async () => {
    state.route.query = { view: 'kanban' }
    const movable = item()
    movable.capabilities = {
      ...movable.capabilities,
      availableTransitions: [{
        toStatus: 'DONE', displayName: '已完成', statusCategory: WorkItemStatusCategory.Done,
        requiresResolution: true,
      }],
    }
    state.listProjectWorkItems.mockImplementation(({ status }: { status?: Set<string> }) =>
      Promise.resolve(page(status?.has('BACKLOG') ? [movable] : [])))
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '验收完成', action: 'confirm' } as never)
    state.transitionWorkItem.mockRejectedValue(new Error('conflict'))
    const wrapper = mountView()
    await flushPromises()
    const callsBeforeDrop = state.listProjectWorkItems.mock.calls.length

    await wrapper.get('.kanban-card').trigger('dragstart')
    await wrapper.findAll('.kanban-lane')[1]!.trigger('drop')
    await flushPromises()

    expect(state.transitionWorkItem).toHaveBeenCalledWith(expect.objectContaining({
      workItemTransitionRequest: { toStatus: 'DONE', resolution: '验收完成' },
    }))
    expect(state.listProjectWorkItems.mock.calls.length).toBeGreaterThan(callsBeforeDrop)
  })
})
