import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import type { ProjectWorkItemListItem, WorkItemRelationCandidate } from '@yumpoo/api-client'
import WorkItemParentPicker from './WorkItemParentPicker.vue'

const api = vi.hoisted(() => ({ listWorkItemRelationCandidates: vi.fn(), createWorkItemRelation: vi.fn() }))
vi.mock('../../api/client', () => ({ workItemsApi: api }))
vi.mock('@yumpoo/api-client', async original => ({ ...await original<typeof import('@yumpoo/api-client')>(), readCsrfToken: () => 'csrf' }))

function candidate(id: string, eligible = true): WorkItemRelationCandidate {
  return { item: { id, itemNo: `PROJ-${id}`, title: id }, eligibility: eligible ? 'ELIGIBLE' : 'INELIGIBLE',
    reasonCode: eligible ? null : 'PARENT_IS_CHILD' } as WorkItemRelationCandidate
}
function page(items: WorkItemRelationCandidate[], current = 0, pages = 1) {
  return { items, page: current, totalPages: pages }
}
function render() {
  return mount(WorkItemParentPicker, { props: { item: { id: 'child', projectId: 'project', itemNo: 'MY-PROJ-12', title: '待转换工作项' } as ProjectWorkItemListItem },
    global: { stubs: {
      ElInput: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
      InlineProblem: true,
    } } })
}

describe('选择父工作项', () => {
  beforeEach(() => {
    Object.values(api).forEach(mock => mock.mockReset())
    vi.spyOn(ElMessage, 'success').mockImplementation(() => ({ close() {} }))
  })
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks() })

  it('按同项目编号预加载候选，服从服务端资格并复用关系命令', async () => {
    api.listWorkItemRelationCandidates.mockResolvedValue(page([candidate('parent'), candidate('nested', false)]))
    const wrapper = render()
    await flushPromises()
    expect(api.listWorkItemRelationCandidates).toHaveBeenCalledWith(expect.objectContaining({
      workItemId: 'child', targetProjectId: 'project', q: 'MY-PROJ', relationType: 'PARENT_CHILD', currentRole: 'CHILD',
    }))
    expect(wrapper.findAll('.parent-picker-option')[1]!.attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('.parent-picker-option')[1]!.attributes('title')).toBe('该工作项已是子项')
    expect(wrapper.findAll('.parent-picker-option').map(row => row.text())).toEqual(['parent', 'nested'])
    expect(wrapper.text()).not.toContain('PROJ-')
    await wrapper.findAll('.parent-picker-option')[0]!.trigger('click')
    await flushPromises()
    expect(api.createWorkItemRelation).toHaveBeenCalledWith(expect.objectContaining({ xXSRFTOKEN: 'csrf', idempotencyKey: expect.any(String),
      workItemRelationCreateRequest: { relationType: 'PARENT_CHILD', currentRole: 'CHILD', targetProjectId: 'project', targetWorkItemId: 'parent' } }))
    expect(wrapper.emitted('changed')).toEqual([[['child', 'parent']]])
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('搜索防抖后丢弃旧请求的迟到结果', async () => {
    vi.useFakeTimers()
    let resolveOld!: (value: ReturnType<typeof page>) => void
    api.listWorkItemRelationCandidates.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
      .mockResolvedValueOnce(page([candidate('latest')]))
    const wrapper = render()
    await wrapper.get('input').setValue('latest')
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    resolveOld(page([candidate('old')]))
    await flushPromises()
    expect(wrapper.text()).toContain('latest')
    expect(wrapper.findAll('.parent-picker-option').map(row => row.text())).toEqual(['latest'])
    expect(api.listWorkItemRelationCandidates).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'latest', page: 0 }))
    wrapper.unmount()
  })

  it('滚动接近底部追加候选，不显示分页或取消按钮，Esc 不会转换', async () => {
    api.listWorkItemRelationCandidates.mockResolvedValueOnce(page([candidate('first')], 0, 2))
      .mockResolvedValueOnce(page([candidate('last')], 1, 2))
    const wrapper = render()
    await flushPromises()
    const list = wrapper.get('.parent-picker-results')
    Object.defineProperties(list.element, { scrollHeight: { value: 600 }, clientHeight: { value: 360 }, scrollTop: { value: 210 } })
    await list.trigger('scroll')
    await flushPromises()
    expect(api.listWorkItemRelationCandidates).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))
    expect(wrapper.findAll('.parent-picker-option').map(row => row.text())).toEqual(['first', 'last'])
    expect(wrapper.findAll('button').map(button => button.text())).toEqual(['first', 'last'])
    await wrapper.get('.parent-picker').trigger('keydown', { key: 'Escape' })
    expect(api.createWorkItemRelation).not.toHaveBeenCalled()
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('追加加载期间的新搜索丢弃旧列表响应', async () => {
    vi.useFakeTimers()
    let resolveMore!: (value: ReturnType<typeof page>) => void
    api.listWorkItemRelationCandidates.mockResolvedValueOnce(page([candidate('first')], 0, 2))
      .mockImplementationOnce(() => new Promise(resolve => { resolveMore = resolve }))
      .mockResolvedValueOnce(page([candidate('new-search')]))
    const wrapper = render()
    await flushPromises()
    await wrapper.get('.parent-picker-results').trigger('scroll')
    await wrapper.get('input').setValue('new-search')
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    resolveMore(page([candidate('old-next-page')], 1, 2))
    await flushPromises()
    expect(wrapper.findAll('.parent-picker-option').map(row => row.text())).toEqual(['new-search'])
    wrapper.unmount()
  })

  it('转换失败保留选择器与错误，不伪造已完成状态', async () => {
    api.listWorkItemRelationCandidates.mockResolvedValue(page([candidate('parent')]))
    api.createWorkItemRelation.mockRejectedValue(new Error('conflict'))
    const wrapper = render()
    await flushPromises()
    await wrapper.get('.parent-picker-option').trigger('click')
    await flushPromises()
    expect(wrapper.find('inline-problem-stub').exists()).toBe(true)
    expect(wrapper.emitted('changed')).toBeUndefined()
    expect(wrapper.emitted('close')).toBeUndefined()
    wrapper.unmount()
  })
})
