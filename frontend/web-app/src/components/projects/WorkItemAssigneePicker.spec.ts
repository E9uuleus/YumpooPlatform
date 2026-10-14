import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { projectsApi } from '../../api/client'
import WorkItemAssigneePicker from './WorkItemAssigneePicker.vue'

vi.mock('../../api/client', () => ({ projectsApi: { listProjectMembers: vi.fn() } }))
enableAutoUnmount(afterEach)

const members = [{ userId: 'u1', displayName: '张三' }, { userId: 'u2', displayName: '李四' }]
beforeEach(() => {
  vi.useFakeTimers()
  vi.mocked(projectsApi.listProjectMembers).mockReset().mockResolvedValue({ items: members } as never)
})
afterEach(() => { vi.useRealTimers() })

function mountPicker(selected: Array<{ userId: string; displayName: string }> = []) {
  return mount(WorkItemAssigneePicker, { props: { projectId: 'p', selected }, attachTo: document.body })
}

describe('处理人选择器', () => {
  it('添加后保持打开并清空搜索，完成、Esc 与空搜索 Enter 结束选择', async () => {
    const wrapper = mountPicker([{ userId: 'u1', displayName: '张三' }])
    await flushPromises()
    const input = wrapper.get('input')
    await input.setValue('李')
    await wrapper.get('.assignee-picker__option').trigger('click')
    expect(wrapper.emitted('change')).toEqual([[['u1', 'u2']]])
    expect((input.element as HTMLInputElement).value).toBe('')
    expect(wrapper.emitted('done')).toBeUndefined()

    await input.trigger('keydown', { key: 'Enter' })
    await wrapper.get('.assignee-picker').trigger('keydown', { key: 'Escape' })
    await wrapper.get('.assignee-picker__primary').trigger('click')
    expect(wrapper.emitted('done')).toHaveLength(3)
  })

  it('批量模式只修改草稿，按所选方式一次应用；替换为空时清空', async () => {
    const wrapper = mount(WorkItemAssigneePicker, { props: { projectId: 'p', selected: [{ userId: 'u1', displayName: '张三' }], batchCount: 3 },
      attachTo: document.body })
    await flushPromises()
    expect(wrapper.text()).toContain('将所选成员追加到每一项')
    const apply = () => wrapper.get('.assignee-picker__primary')
    expect(apply().text()).toBe('应用到 3 项')
    expect(apply().attributes('disabled')).toBeDefined()

    await wrapper.findAll('.assignee-picker__option')[1]!.trigger('click')
    expect(wrapper.emitted('change')).toBeUndefined()
    await apply().trigger('click')
    expect(wrapper.emitted('batchApply')).toEqual([['add', ['u2']]])

    await wrapper.get('[aria-label="移除处理人李四"]').trigger('click')
    await wrapper.findAll('[role="radio"]')[1]!.trigger('click')
    expect(apply().text()).toBe('清空 3 项的处理人')
    await wrapper.get('input').trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('batchApply')?.[1]).toEqual(['replace', []])
    expect(wrapper.emitted('done')).toBeUndefined()
  })

  it('有搜索词时 Enter 添加第一个候选人', async () => {
    const wrapper = mountPicker()
    await flushPromises()
    await wrapper.get('input').setValue('张')
    await vi.advanceTimersByTimeAsync(250)
    await wrapper.get('input').trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('change')).toEqual([[['u1']]])
    expect(wrapper.emitted('done')).toBeUndefined()
  })
})
