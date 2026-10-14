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
