import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectConnectionsOverview from './ProjectConnectionsOverview.vue'
import { connectionCatalog } from './connectTestFixtures'

const api = vi.hoisted(() => ({ listConnectColumns: vi.fn() }))
vi.mock('../../../api/client', () => ({ workItemsApi: api }))
enableAutoUnmount(afterEach)
beforeEach(() => api.listConnectColumns.mockReset())
const global = { stubs: { RouterLink: { name: 'RouterLink', props: ['to'], template: '<a><slot /></a>' } } }
describe('设置页连接概览', () => {
  it('从列目录读取列表，管理链接定位到当前项目表格对应列', async () => {
    api.listConnectColumns.mockResolvedValue(connectionCatalog)
    const wrapper = mount(ProjectConnectionsOverview, { props: { projectId: 'source-project' }, global })
    await flushPromises()
    expect(wrapper.text()).toContain('产品缺陷')
    expect(wrapper.findComponent({ name: 'RouterLink' }).props('to')).toEqual({ path: '/projects/source-project/overview', query: { view: 'table', connectColumn: 'column-1' } })
    expect(wrapper.text()).toContain('暂无其他项目连接到本项目')
  })
  it('出错时提供重试且不伪装为空态，切换项目后重新加载', async () => {
    api.listConnectColumns.mockRejectedValueOnce(new Error('offline'))
    const wrapper = mount(ProjectConnectionsOverview, { props: { projectId: 'source-project' }, global })
    await flushPromises()
    expect(wrapper.text()).toContain('重新加载连接概览')
    expect(wrapper.find('.unified-project-settings__empty').exists()).toBe(false)
    api.listConnectColumns.mockResolvedValue({ ...connectionCatalog, items: [] })
    await wrapper.get('button').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('还没有连接。')
    await wrapper.setProps({ projectId: 'another-project' }); await flushPromises()
    expect(api.listConnectColumns).toHaveBeenLastCalledWith({ projectId: 'another-project' }, expect.anything())
  })
})
