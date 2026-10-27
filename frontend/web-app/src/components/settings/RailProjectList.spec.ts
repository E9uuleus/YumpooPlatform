import { mount, flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import RailProjectList from './RailProjectList.vue'

vi.mock('../../api/client', () => ({ projectsApi: { listProjects: vi.fn().mockResolvedValue({ items: [] }) } }))

describe('恢复默认侧边栏项目', () => {
  const defaults = [{ id: 'a', name: '项目 A' }]
  it('已经是默认列表时禁用按钮', async () => {
    const wrapper = mount(RailProjectList, { props: { items: [...defaults], defaults } })
    await flushPromises()
    const button = wrapper.get('button.el-button')
    expect(button.attributes('disabled')).toBeDefined()
    expect(button.attributes('title')).toBe('已是默认项目')
    wrapper.unmount()
  })
  it('列表不同时发出默认列表', async () => {
    const wrapper = mount(RailProjectList, { props: { items: [], defaults } })
    await flushPromises()
    await wrapper.get('button.el-button').trigger('click')
    expect(wrapper.emitted('update')).toEqual([[defaults]])
    wrapper.unmount()
  })
})
