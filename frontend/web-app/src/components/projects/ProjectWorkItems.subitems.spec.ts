import {
  WorkItemRelationRole,
  WorkItemRelationType,
  WorkItemRelationStatusEnum,
  type ProjectWorkItemListItem,
  type WorkItemRelation,
} from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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

describe('项目级工作项首页 · 子项', () => {
  beforeEach(installWorkItemsTestDefaults)
  afterEach(restoreWorkItemsTestGlobals)

  it('我的工作取消主子表勾选和新建行占位，仍保留展开与行菜单', async () => {
    state.listProjectWorkItems.mockResolvedValue(page([{ ...item(), subitemCount: 1 }]))
    state.listWorkItemSubitems.mockResolvedValue({ items: [item('child')] })
    const wrapper = mountView(undefined, { embeddedProjectId: 'project-1', myWork: true })
    await flushPromises()
    await wrapper.get('.subitem-expand-button').trigger('click'); await flushPromises()
    expect(wrapper.find('.monday-selection-column, .subitem-selection-column, .subitem-quick-checkbox, .work-item-batch-spacer').exists()).toBe(false)
    const subtable = wrapper.findComponent({ name: 'ProjectWorkItemSubitemsTable' })
    expect(subtable.props('selectionEnabled')).toBe(false)
    expect(subtable.findComponent({ name: 'WorkItemRowActions' }).exists()).toBe(true)
    expect(subtable.find('.subitem-add').exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'WorkItemBatchBar' }).exists()).toBe(false)
  })

  it('子项移动后刷新同级顺序并保持子项名称选中', async () => {
    const parent = { ...item(), subitemCount: 2 }
    const first = { ...item('child-1'), title: '子项一' }
    const second = { ...item('child-2'), title: '子项二' }
    state.listProjectWorkItems.mockResolvedValue(page([parent]))
    state.listWorkItemSubitems.mockResolvedValue({ items: [first, second] })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('.subitem-expand-button').trigger('click')
    await flushPromises()
    const subtable = wrapper.findComponent({ name: 'ProjectWorkItemSubitemsTable' })
    state.listWorkItemSubitems.mockResolvedValue({ items: [second, first] })
    subtable.findComponent({ name: 'WorkItemRowActions' }).vm.$emit('moved', first)
    await flushPromises()
    expect(subtable.get('.work-item-name-cell.monday-cell--selected').text()).toBe(first.title)
    expect(subtable.findAll('.work-item-title-text').map(cell => cell.text())).toEqual([second.title, first.title])
  })

  it('首次展开时懒加载直接子项，折叠后复用缓存且不显示递归入口', async () => {
    const parent = { ...item('parent-1'), subitemCount: 1 }
    const child = { ...item('child-1'), title: '直接子项' }
    state.listProjectWorkItems.mockResolvedValue(page([parent]))
    state.listWorkItemSubitems.mockResolvedValue({ items: [child] })
    const wrapper = mountView()
    await flushPromises()
    const expand = wrapper.get('button[aria-label="展开子项"]')
    expect(expand.attributes('aria-expanded')).toBe('false')
    await expand.trigger('click')
    await flushPromises()

    expect(state.listWorkItemSubitems).toHaveBeenCalledTimes(1)
    expect(state.listWorkItemSubitems).toHaveBeenCalledWith({ parentWorkItemId: parent.id })
    expect(wrapper.text()).toContain('直接子项')
    expect(wrapper.findComponent({ name: 'ProjectWorkItemSubitemsTable' }).props('columnWidths'))
      .toMatchObject({ status: 96, priority: 90, content: 110 })
    expect(wrapper.get('.monday-subitems-counter-component__subitems-count').text()).toBe('1')
    expect(wrapper.findAll('.subitem-hierarchy-branch--data')).toHaveLength(1)
    expect(wrapper.findAll('.monday-subitem-table .el-table__expand-icon')).toHaveLength(0)

    const collapse = wrapper.get('button[aria-label="收起子项"]')
    expect(collapse.attributes('aria-expanded')).toBe('true')
    await collapse.trigger('click')
    await nextTick()
    await wrapper.get('button[aria-label="展开子项"]').trigger('click')
    await flushPromises()
    expect(state.listWorkItemSubitems).toHaveBeenCalledTimes(1)
  })

  it('搜索命中子项时展开父项并只请求命中的子项，展开不写入本机记录', async () => {
    state.route.query = { q: 'child' }
    state.listProjectWorkItems.mockResolvedValue(page([{ ...item('parent'), subitemCount: 2, matchedSubitemCount: 1 }]))
    state.listWorkItemSubitems.mockResolvedValue({ items: [item('child')] })
    const wrapper = mountView(); await flushPromises()
    expect(state.listWorkItemSubitems).toHaveBeenCalledWith(expect.objectContaining({ parentWorkItemId: 'parent', q: 'child' }))
    expect(wrapper.findAll('.subitem-table-row')).toHaveLength(1)
    const saved = Object.entries(localStorage).filter(([key]) => key.includes('expanded-subitems') && key.endsWith(':project-1'))
    expect(saved.map(([, value]) => value).join('')).not.toContain('parent')
  })

  it('从页面外打开子工作项时展开父项并选中该行', async () => {
    state.route.query = { workItemId: 'child' }
    const relation: WorkItemRelation = {
      id: 'parent-child', relationType: WorkItemRelationType.ParentChild,
      currentRole: WorkItemRelationRole.Child, counterpartRole: WorkItemRelationRole.Parent,
      counterpartVisible: true, counterpart: { ...item('parent'), deleted: false },
      status: WorkItemRelationStatusEnum.Active, createdByUserId: 'owner-1', createdAt: new Date(),
      deletedByUserId: null, deletedAt: null, deleteReason: null, rowVersion: 1, etag: '"1"',
      capabilities: { canDelete: true, canChangeParent: true },
    }
    state.listWorkItemRelations.mockResolvedValue({ items: [relation], page: 0, size: 100, totalElements: 1, totalPages: 1 })
    state.listProjectWorkItems.mockResolvedValue(page([{ ...item('parent'), subitemCount: 1 }]))
    state.listWorkItemSubitems.mockResolvedValue({ items: [item('child')] })
    const wrapper = mountView(); await flushPromises()
    expect(state.listWorkItemSubitems).toHaveBeenCalledWith(expect.objectContaining({ parentWorkItemId: 'parent' }))
    expect(wrapper.find('.subitem-table-row--selected').exists()).toBe(true)
  })

  it('子表拖动结果写回当前父项缓存，更新版本和折叠重开后仍保留顺序', async () => {
    const first = item('child-1')
    const second = item('child-2')
    const other = item('other-child')
    state.listProjectWorkItems.mockResolvedValue(page([
      { ...item('parent-1'), subitemCount: 2 }, { ...item('parent-2'), subitemCount: 1 },
    ]))
    state.listWorkItemSubitems.mockImplementation(({ parentWorkItemId }: { parentWorkItemId: string }) =>
      Promise.resolve({ items: parentWorkItemId === 'parent-1' ? [first, second] : [other] }))
    const wrapper = mountView()
    await flushPromises()
    await wrapper.findAll('button[aria-label="展开子项"]')[0]!.trigger('click')
    await flushPromises()
    await wrapper.get('button[aria-label="展开子项"]').trigger('click')
    await flushPromises()
    const tables = wrapper.findAllComponents({ name: 'ProjectWorkItemSubitemsTable' })
    tables[0]!.vm.$emit('reorder', [second, first])
    tables[0]!.vm.$emit('updated', first.id, { ...first, rowVersion: 2, etag: '"2"' })
    await nextTick()
    expect(tables[0]!.props('items')).toEqual([second, expect.objectContaining({ id: first.id, etag: '"2"' })])
    expect(tables[1]!.props('items')).toEqual([other])
    await wrapper.findAll('button[aria-label="收起子项"]')[0]!.trigger('click')
    await nextTick()
    await wrapper.get('button[aria-label="展开子项"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAllComponents({ name: 'ProjectWorkItemSubitemsTable' })[0]!.props('items'))
      .toEqual([second, expect.objectContaining({ id: first.id, etag: '"2"' })])
    expect(state.listWorkItemSubitems).toHaveBeenCalledTimes(2)
  })

  it('连续创建子项时保留旧行并补查刷新期间的新建结果', async () => {
    const wrapper = mountView()
    await flushPromises()
    const view = wrapper.vm as unknown as {
      loadSubitems: (id: string, force?: boolean) => Promise<void>
      onSubitemCreated: (parent: ProjectWorkItemListItem) => void
      subitemState: (id: string) => { items: ProjectWorkItemListItem[], loaded: boolean, loading: boolean }
    }
    state.listWorkItemSubitems.mockResolvedValueOnce({ items: [item('old-child')] })
    await view.loadSubitems('parent')
    let resolveRefresh: ((value: { items: ProjectWorkItemListItem[] }) => void) | undefined
    state.listWorkItemSubitems.mockImplementationOnce(() => new Promise(resolve => { resolveRefresh = resolve }))
    state.listWorkItemSubitems.mockResolvedValueOnce({ items: [item('old-child'), item('child-1'), item('child-2')] })
    view.onSubitemCreated(item('parent'))
    view.onSubitemCreated(item('parent'))
    expect(view.subitemState('parent').loaded).toBe(true)
    expect(view.subitemState('parent').items.map(item => item.id)).toEqual(['old-child'])
    expect(state.listWorkItemSubitems).toHaveBeenCalledTimes(2)
    resolveRefresh?.({ items: [item('old-child'), item('child-1')] })
    await flushPromises()
    expect(state.listWorkItemSubitems).toHaveBeenCalledTimes(3)
    expect(view.subitemState('parent').items.map(item => item.id)).toEqual(['old-child', 'child-1', 'child-2'])
    expect(view.subitemState('parent').loading).toBe(false)
  })

  it('子项换父事件重取主表及两个已展开子表，移除旧父项中的缓存行', async () => {
    const oldParent = { ...item('old-parent'), subitemCount: 1 }
    const newParent = { ...item('new-parent'), subitemCount: 1 }
    const child = { ...item('child'), title: '待移动子项' }
    const otherChild = { ...item('other-child'), title: '新父项已有子项' }
    state.listProjectWorkItems.mockResolvedValue(page([oldParent, newParent]))
    state.listWorkItemSubitems.mockImplementation(({ parentWorkItemId }: { parentWorkItemId: string }) =>
      Promise.resolve({ items: parentWorkItemId === oldParent.id ? [child] : [otherChild] }))
    const wrapper = mountView()
    await flushPromises()
    await wrapper.findAll('button[aria-label="展开子项"]')[0]!.trigger('click')
    await flushPromises()
    await wrapper.get('button[aria-label="展开子项"]').trigger('click')
    await flushPromises()
    const oldTable = wrapper.findAllComponents({ name: 'ProjectWorkItemSubitemsTable' })
      .find(table => table.props('parent').id === oldParent.id)!
    const rootCalls = state.listProjectWorkItems.mock.calls.length
    const childCalls = state.listWorkItemSubitems.mock.calls.length
    state.listProjectWorkItems.mockResolvedValue(page([{ ...oldParent, subitemCount: 0 }, { ...newParent, subitemCount: 2 }]))
    state.listWorkItemSubitems.mockImplementation(({ parentWorkItemId }: { parentWorkItemId: string }) =>
      Promise.resolve({ items: parentWorkItemId === oldParent.id ? [] : [otherChild, child] }))
    oldTable.vm.$emit('rowChanged', [child.id, oldParent.id, newParent.id])
    await flushPromises()
    expect(state.listProjectWorkItems).toHaveBeenCalledTimes(rootCalls + 1)
    expect(state.listWorkItemSubitems).toHaveBeenCalledTimes(childCalls + 2)
    const view = wrapper.vm as unknown as { subitemState: (id: string) => { items: ProjectWorkItemListItem[] } }
    expect(view.subitemState(oldParent.id).items).toEqual([])
    expect(view.subitemState(newParent.id).items).toEqual([otherChild, child])
    expect(wrapper.findAll('.monday-subitem-table .work-item-title-text').map(title => title.text()))
      .toEqual(['新父项已有子项', '待移动子项'])
  })
})
