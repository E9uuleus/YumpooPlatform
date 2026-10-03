import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ProjectLifecycle } from '@yumpoo/api-client'
import ConnectCellPopover from './ConnectCellPopover.vue'
import { connectionTestContext } from './connectTestSupport'
import { connectColumn, connection, sourceItem, targetCard } from './connectTestFixtures'

enableAutoUnmount(afterEach)
beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())
function render(readOnly = false, member = true) {
  const provided = connectionTestContext()
  const wrapper = mount(ConnectCellPopover, { props: { item: sourceItem, column: { ...connectColumn, targets: connectColumn.targets.map(target => ({ ...target, actorCanLinkExisting: member })) }, connections: [connection], readOnly }, global: provided.global, attachTo: document.body })
  return { wrapper, ...provided }
}
describe('连接单元格弹窗', () => {
  it('非成员不发候选请求，搜索词优先用于新建，否则使用当前行标题', async () => {
    const { wrapper, context } = render(false, false)
    expect(wrapper.text()).toContain('你不是「Yumpoo 门户」的成员，只能新建并关联。')
    await wrapper.get('.connect-cell-popover__create').trigger('click')
    expect(wrapper.emitted('requestCreate')?.[0]).toEqual(['target-project', sourceItem.title])
    await wrapper.get('input').setValue('新的打印问题'); await vi.advanceTimersByTimeAsync(300)
    expect(context.searchCandidates).not.toHaveBeenCalled()
    await wrapper.get('.connect-cell-popover__create').trigger('click')
    expect(wrapper.emitted('requestCreate')?.[1]).toEqual(['target-project', '新的打印问题'])
  })
  it('300ms 防抖搜索、已连接候选禁用、子项带父项说明', async () => {
    const { wrapper, context } = render()
    vi.mocked(context.searchCandidates).mockResolvedValue({ items: [{ card: targetCard, parent: null, alreadyConnected: true }, { card: { ...targetCard, workItemId: 'child', title: '子项任务' }, parent: { workItemId: 'parent', title: '父项任务' }, alreadyConnected: false }], page: 0, size: 20, totalPages: 1, totalElements: 2 })
    await wrapper.get('input').setValue('打印')
    await vi.advanceTimersByTimeAsync(299); expect(context.searchCandidates).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1); await flushPromises()
    expect(context.searchCandidates).toHaveBeenCalledWith(connectColumn.id, 'target-project', sourceItem.id, '打印', 0, expect.any(AbortSignal))
    expect(wrapper.findAll('.connect-candidate')[0]!.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('子项 · 父项任务')
    await wrapper.findAll('.connect-candidate')[1]!.trigger('click'); await flushPromises()
    expect(context.link).toHaveBeenCalledWith(sourceItem.id, connectColumn.id, 'child')
  })
  it('只读或目标项目归档时只显示已有列表，Esc 发出关闭事件', async () => {
    const { wrapper, context } = render(true)
    expect(wrapper.find('input').exists()).toBe(false); expect(wrapper.find('.connect-cell-popover__create').exists()).toBe(false)
    await wrapper.trigger('keydown', { key: 'Escape' }); expect(wrapper.emitted('close')).toHaveLength(1)
    await wrapper.setProps({ readOnly: false, column: { ...connectColumn, targets: connectColumn.targets.map(target => ({ ...target, lifecycle: ProjectLifecycle.Archived })) } })
    expect(wrapper.text()).toContain('已归档'); expect(wrapper.find('input').exists()).toBe(false)
    expect(context.searchCandidates).not.toHaveBeenCalled()
  })
  it('快速切换目标时中止并丢弃旧候选', async () => {
    const { wrapper, context } = render()
    await wrapper.setProps({ column: { ...connectColumn, targets: [...connectColumn.targets, { ...connectColumn.targets[0]!, projectId: 'other-project', name: '平台组件' }] } })
    let resolve!: (value: Awaited<ReturnType<typeof context.searchCandidates>>) => void
    vi.mocked(context.searchCandidates).mockImplementationOnce(() => new Promise(done => { resolve = done }))
    await wrapper.get('input').setValue('打印'); await vi.advanceTimersByTimeAsync(300)
    const oldSignal = vi.mocked(context.searchCandidates).mock.calls[0]![5]!
    await wrapper.findAll('[role="tab"]')[1]!.trigger('click'); await vi.advanceTimersByTimeAsync(300)
    resolve({ items: [{ card: targetCard, parent: null, alreadyConnected: false }], page: 0, size: 20, totalPages: 1, totalElements: 1 }); await flushPromises()
    expect(oldSignal.aborted).toBe(true); expect(wrapper.find('.connect-candidate').exists()).toBe(false)
  })
})
