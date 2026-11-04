import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import type { ProjectDetail } from '@yumpoo/api-client'
import ProjectWorkspaceHeader from './ProjectWorkspaceHeader.vue'
enableAutoUnmount(afterEach)
const project = { id: 'project-1', name: '项目', code: 'PRJ', lifecycle: 'ACTIVE', ownerDisplayName: '负责人' } as ProjectDetail
function render(section: 'overview' | 'statistics' | 'members' | 'activity' | 'settings' | 'catalog' = 'overview') {
  return mount(ProjectWorkspaceHeader, { props: { project, section }, global: { stubs: { ProjectMoreMenu: true } }, slots: { 'primary-action': '<button class="primary">项目操作</button>' } })
}
describe('项目头部分区切换器', () => {
  it('项目分区页在主操作插槽右侧显示项目菜单，目录页和缺少项目时不显示', async () => {
    const wrapper = render('settings')
    expect(wrapper.get('.project-workspace-header__actions').element.firstElementChild).toBe(wrapper.get('.primary').element)
    expect(wrapper.findComponent({ name: 'ProjectMoreMenu' }).props()).toMatchObject({ section: 'settings', project })
    await wrapper.setProps({ section: 'catalog' })
    expect(wrapper.findComponent({ name: 'ProjectMoreMenu' }).exists()).toBe(false)
    await wrapper.setProps({ section: 'overview', project: undefined })
    expect(wrapper.findComponent({ name: 'ProjectMoreMenu' }).exists()).toBe(false)
    expect(wrapper.find('.primary').exists()).toBe(true)
  })
  it('项目分区只显示可展开的项目标题', () => {
    const wrapper = render()
    const trigger = wrapper.get('h1 .project-workspace-header__title-trigger')
    expect(trigger.text()).toBe('项目')
    expect(trigger.attributes()).toMatchObject({ 'aria-haspopup': 'dialog', 'aria-expanded': 'false' })
    expect(wrapper.find('.project-workspace-header__code').exists()).toBe(false)
  })
  it('只有归档项目在标题旁显示状态', () => {
    const archived = mount(ProjectWorkspaceHeader, {
      props: { project: { ...project, lifecycle: 'ARCHIVED' } as ProjectDetail, section: 'overview' },
      global: { stubs: { ProjectMoreMenu: true } },
    })
    expect(archived.get('.project-workspace-header__heading').text()).toContain('已归档')
    expect(render().get('.project-workspace-header__heading').text()).toBe('项目')
  })
})
