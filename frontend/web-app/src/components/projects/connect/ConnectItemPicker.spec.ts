import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { computed } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElCheckbox, ElDropdown, ElPopover } from 'element-plus'
import { ConnectCandidateField, ConnectCandidateSort, ProjectLifecycle, WorkItemLabelColorToken, WorkItemStatusCategory } from '@yumpoo/api-client'
import ConnectItemPicker from './ConnectItemPicker.vue'
import ConnectSearchFieldsMenu from './ConnectSearchFieldsMenu.vue'
import { connectTableActions } from './connectTableActions'
import { connectionTestContext } from './connectTestSupport'
import { connectColumn, connection, sourceCard, sourceItem, targetCard } from './connectTestFixtures'

enableAutoUnmount(afterEach)
beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())
const todo = { code: 'TODO', name: '未开始', colorToken: WorkItemLabelColorToken.Gray, category: WorkItemStatusCategory.Todo }
const page = (items: unknown[]) => ({ items, page: 0, size: 20, totalElements: items.length, totalPages: 1 }) as never
const reverse = { columnId: connectColumn.id, columnName: connectColumn.name, projectId: sourceCard.projectId, projectCode: sourceCard.projectCode,
  projectName: sourceCard.projectName, projectLifecycle: ProjectLifecycle.Active, actorCanLinkExisting: true }
function render(props: Record<string, unknown> = {}, actions?: { canManage: boolean; openSettings: () => void }) {
  const provided = connectionTestContext()
  const provide = { ...provided.global.provide, ...(actions ? { [connectTableActions as symbol]: { canManage: computed(() => actions.canManage), openSettings: actions.openSettings } } : {}) }
  const wrapper = mount(ConnectItemPicker, { attachTo: document.body, global: { provide },
    props: { item: sourceItem, column: connectColumn, connections: [connection], total: 1, readOnly: false, ...props } })
  return { wrapper, ...provided }
}
const titles = (wrapper: ReturnType<typeof render>['wrapper'], selector: string) => wrapper.findAll(`${selector} .connect-picker__text strong`).map(node => node.text())

describe('选择工作项弹层', () => {
  it('打开即浏览目标项目，已连接置顶并排除，候选按状态分组', async () => {
    const { wrapper, context } = render()
    vi.mocked(context.searchCandidates).mockResolvedValue(page([
      { card: targetCard, parent: null, alreadyConnected: true },
      { card: { ...targetCard, workItemId: 'todo-1', title: '补充打印日志', status: todo }, parent: null, alreadyConnected: false },
      { card: { ...targetCard, workItemId: 'doing-1', title: '排查网关超时' }, parent: { workItemId: 'parent', title: '打印稳定性' }, alreadyConnected: false },
      { card: { ...targetCard, workItemId: 'todo-2', title: '回归测试', status: todo, archived: true }, parent: null, alreadyConnected: false },
    ]))
    await vi.advanceTimersByTimeAsync(0); await flushPromises()
    expect(context.searchCandidates).toHaveBeenCalledWith(connectColumn.id, 'target-project', sourceItem.id, '', 0, expect.any(AbortSignal),
      { fields: [ConnectCandidateField.Name, ConnectCandidateField.Assignee], sort: ConnectCandidateSort.Recent })
    expect(wrapper.text()).toContain('已连接 · 1')
    expect(titles(wrapper, '.is-connected')).toEqual([targetCard.title])
    expect(wrapper.findAll('.connect-picker__group h4').map(node => node.text())).toEqual(['未开始2', '进行中1'])
    expect(titles(wrapper, '.connect-picker__group')).toEqual(['补充打印日志', '回归测试', '排查网关超时'])
    expect(wrapper.text()).toContain('子项 · 打印稳定性')
    expect(wrapper.findAll('.connect-picker__group .connect-picker__row')[1]!.classes()).toContain('is-disabled')
  })
  it('输入 300ms 防抖，切换排序与搜索字段立即重新搜索并标记非默认字段', async () => {
    const { wrapper, context } = render()
    await vi.advanceTimersByTimeAsync(0)
    await wrapper.get('input').setValue('打印')
    await vi.advanceTimersByTimeAsync(299); expect(context.searchCandidates).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(context.searchCandidates).toHaveBeenLastCalledWith(connectColumn.id, 'target-project', sourceItem.id, '打印', 0, expect.any(AbortSignal), expect.anything())
    await wrapper.get('.connect-picker__icon').trigger('click'); await vi.advanceTimersByTimeAsync(0)
    expect(vi.mocked(context.searchCandidates).mock.lastCall?.[6]).toMatchObject({ sort: ConnectCandidateSort.Title })
    wrapper.getComponent(ConnectSearchFieldsMenu).vm.$emit('update:modelValue', [ConnectCandidateField.Status]); await vi.advanceTimersByTimeAsync(0)
    expect(vi.mocked(context.searchCandidates).mock.lastCall?.[6]).toMatchObject({ fields: [ConnectCandidateField.Status] })
    expect(wrapper.get('.connect-picker__filter').classes()).toContain('is-active')
    expect(wrapper.getComponent(ElPopover).props('teleported')).toBe(false)
  })
  it('勾选候选即关联，取消已连接即解除并可撤销', async () => {
    const { wrapper, context } = render()
    vi.mocked(context.searchCandidates).mockResolvedValue(page([{ card: { ...targetCard, workItemId: 'todo-1', title: '补充打印日志', status: todo }, parent: null, alreadyConnected: false }]))
    await vi.advanceTimersByTimeAsync(0); await flushPromises()
    await wrapper.get('.connect-picker__group .connect-picker__row').trigger('click'); await flushPromises()
    expect(context.link).toHaveBeenCalledWith(sourceItem.id, connectColumn.id, 'todo-1')
    expect(wrapper.emitted('changed')).toHaveLength(1)
    expect(wrapper.find('.connect-picker__group').exists()).toBe(false)
    wrapper.get('.is-connected').getComponent(ElCheckbox).vm.$emit('change', false); await flushPromises()
    expect(context.unlink).toHaveBeenCalledWith(connection)
    expect(wrapper.get('.connect-picker__undo').text()).toContain(`已解除与「${targetCard.title}」的连接`)
    await wrapper.get('.connect-picker__undo button').trigger('click'); await flushPromises()
    expect(context.link).toHaveBeenLastCalledWith(sourceItem.id, connectColumn.id, targetCard.workItemId)
    expect(wrapper.find('.connect-picker__undo').exists()).toBe(false)
  })
  it('非目标成员不请求候选，输入标题用 Add task 行新建并关联，可切换类别', async () => {
    const { wrapper, context } = render({ column: { ...connectColumn, targets: connectColumn.targets.map(target => ({ ...target, actorCanLinkExisting: false })) } })
    vi.mocked(context.createOptions).mockResolvedValue({ targetProjectId: 'target-project', targetProjectName: 'Yumpoo 门户', defaultContentId: 'bug',
      categories: [{ id: 'bug', name: '缺陷', colorToken: WorkItemLabelColorToken.Lipstick }, { id: 'task', name: '任务', colorToken: WorkItemLabelColorToken.Green }] })
    await vi.advanceTimersByTimeAsync(300)
    expect(wrapper.text()).toContain('你不是「Yumpoo 门户」的成员，只能新建并关联。')
    expect(context.searchCandidates).not.toHaveBeenCalled()
    expect(wrapper.find('.connect-picker__add').exists()).toBe(false)
    await wrapper.get('input').setValue('  新的打印问题 '); await flushPromises()
    expect(wrapper.get('.connect-picker__add-title').text()).toBe('「新的打印问题」')
    expect(wrapper.get('.connect-picker__category').text()).toContain('缺陷')
    wrapper.getComponent(ElDropdown).vm.$emit('command', 'task'); await flushPromises()
    await wrapper.get('.connect-picker__add-button').trigger('click'); await flushPromises()
    expect(context.createAndLink).toHaveBeenCalledWith(sourceItem.id, { columnId: connectColumn.id, targetProjectId: 'target-project', title: '新的打印问题', contentId: 'task' })
    expect((wrapper.get('input').element as HTMLInputElement).value).toBe('')
  })
  it('反向列在连接列所在项目搜索、关联、新建，并按列分页加载更多已连接', async () => {
    const { wrapper, context } = render({ column: undefined, reverse, total: 51 })
    vi.mocked(context.reverseCandidates).mockResolvedValue(page([{ card: { ...sourceCard, workItemId: 'source-2', title: '来源补充' }, parent: null, alreadyConnected: false }]))
    vi.mocked(context.incoming).mockResolvedValue({ items: [{ ...connection, id: 'connection-2', source: { ...sourceCard, workItemId: 'source-3', title: '更早的来源' } }], page: 1, size: 50, totalElements: 51, totalPages: 2 })
    await vi.advanceTimersByTimeAsync(0); await flushPromises()
    expect(context.reverseCandidates).toHaveBeenCalledWith(connectColumn.id, sourceItem.id, '', 0, expect.any(AbortSignal), expect.anything())
    expect(wrapper.text()).toContain('华东现场实施 · 双向连接')
    expect(titles(wrapper, '.is-connected')).toEqual([sourceCard.title])
    await wrapper.get('.connect-picker__more').trigger('click'); await flushPromises()
    expect(context.incoming).toHaveBeenCalledWith(sourceItem.id, 1, undefined, connectColumn.id)
    expect(titles(wrapper, '.is-connected')).toEqual([sourceCard.title, '更早的来源'])
    await wrapper.get('.connect-picker__group .connect-picker__row').trigger('click'); await flushPromises()
    expect(context.reverseLink).toHaveBeenCalledWith(sourceItem.id, connectColumn.id, 'source-2')
    await wrapper.get('input').setValue('来源新需求'); await flushPromises()
    await wrapper.get('.connect-picker__add-button').trigger('click'); await flushPromises()
    expect(context.reverseCreateAndLink).toHaveBeenCalledWith(sourceItem.id, { columnId: connectColumn.id, title: '来源新需求', contentId: 'bug' })
  })
  it('反向列非来源成员只能查看与解除，归档或只读时没有搜索框', async () => {
    const { wrapper, context } = render({ column: undefined, reverse: { ...reverse, actorCanLinkExisting: false } })
    await vi.advanceTimersByTimeAsync(300)
    expect(wrapper.text()).toContain('你不是「华东现场实施」的成员，只能查看和解除这里的连接。')
    expect(wrapper.find('.connect-picker__search').exists()).toBe(false)
    expect(context.reverseCandidates).not.toHaveBeenCalled()
    await wrapper.setProps({ reverse, readOnly: true })
    expect(wrapper.find('.connect-picker__search').exists()).toBe(false)
    expect(wrapper.get('.is-connected').getComponent(ElCheckbox).props('disabled')).toBe(true)
  })
  it('连接设置只对可管理成员显示，点击关闭弹层并打开表头设置；Esc 关闭', async () => {
    const openSettings = vi.fn()
    const { wrapper } = render({}, { canManage: true, openSettings })
    await wrapper.get('.connect-picker__settings').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
    expect(openSettings).toHaveBeenCalledWith(connectColumn)
    await wrapper.trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toHaveLength(2)
    const other = render({}, { canManage: false, openSettings })
    expect(other.wrapper.find('.connect-picker__settings').exists()).toBe(false)
  })
  it('多目标列以页签切换并显示连接数，切换时中止旧搜索', async () => {
    const column = { ...connectColumn, targets: [...connectColumn.targets, { ...connectColumn.targets[0]!, projectId: 'other-project', name: '平台组件' }] }
    const { wrapper, context } = render({ column })
    let resolve!: (value: never) => void
    vi.mocked(context.searchCandidates).mockImplementationOnce(() => new Promise(done => { resolve = done }))
    await vi.advanceTimersByTimeAsync(0)
    expect(wrapper.findAll('[role="tab"]').map(tab => tab.text())).toEqual(['Yumpoo 门户1', '平台组件'])
    const oldSignal = vi.mocked(context.searchCandidates).mock.calls[0]![5]!
    await wrapper.findAll('[role="tab"]')[1]!.trigger('click'); await vi.advanceTimersByTimeAsync(0)
    expect(oldSignal.aborted).toBe(true)
    expect(vi.mocked(context.searchCandidates).mock.lastCall?.[1]).toBe('other-project')
    resolve(page([{ card: { ...targetCard, workItemId: 'stale' }, parent: null, alreadyConnected: false }])); await flushPromises()
    expect(titles(wrapper, '.connect-picker__group')).not.toContain(targetCard.title)
    expect(wrapper.find('.is-connected').exists()).toBe(false)
  })
})
