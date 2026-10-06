import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ProjectDetail } from '@yumpoo/api-client'
import ProjectWorkspaceHeader from './ProjectWorkspaceHeader.vue'
const push = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
enableAutoUnmount(afterEach)
const project = { id: 'project-1', name: '项目', code: 'PRJ', lifecycle: 'ACTIVE', ownerDisplayName: '负责人' } as ProjectDetail
const sections = ['overview', 'members', 'activity', 'settings'] as const
function render(section: typeof sections[number] | 'catalog' = 'overview') {
  return mount(ProjectWorkspaceHeader, { props: { project, section }, slots: { 'primary-action': '<button class="primary">项目操作</button>' } })
}
beforeEach(() => push.mockReset())
describe('项目头部分区切换器', () => {
  it('四个按钮按固定顺序提供可访问名称和延迟 tooltip', () => {
    const wrapper = render()
    expect(wrapper.get('nav[aria-label="项目分区"]').findAll('button').map(button => button.attributes('aria-label')))
      .toEqual(['工作项', '成员', '动态', '设置'])
    expect(wrapper.findAll('.project-workspace-header__section-group').map(group => group.findAll('button').length)).toEqual([3, 1])
    const tooltips = wrapper.findAllComponents({ name: 'ElTooltip' })
      .filter(tooltip => ['工作项', '成员', '动态', '设置'].includes(tooltip.props('content')))
    expect(tooltips).toHaveLength(4)
    for (const tooltip of tooltips) {
      expect(tooltip.props()).toMatchObject({ placement: 'bottom', showAfter: 300 })
    }
  })
  it.each(sections)('%s 分区仍显示全部入口，当前按钮标记 page 并高亮', section => {
    const wrapper = render(section)
    const buttons = wrapper.findAll('nav button')
    expect(buttons).toHaveLength(4)
    expect(buttons.filter(button => button.attributes('aria-current') === 'page')).toHaveLength(1)
    expect(buttons[sections.indexOf(section)]!.classes()).toContain('is-current')
    expect(wrapper.get('nav .is-current').attributes('aria-current')).toBe('page')
  })
  it('各入口使用原 route name 和 projectId 导航，主操作插槽位于图标组左侧', async () => {
    const wrapper = render('settings')
    for (const [index, button] of wrapper.findAll('nav button').entries()) {
      await button.trigger('click')
      expect(push).toHaveBeenLastCalledWith({ name: `project-${sections[index]}`, params: { projectId: 'project-1' } })
    }
    const actions = wrapper.get('.project-workspace-header__actions')
    expect(actions.element.firstElementChild).toBe(wrapper.get('.primary').element)
    expect(wrapper.get('.primary').text()).toBe('项目操作')
  })
  it('目录页和缺少项目时不渲染分区，主操作插槽仍可用', async () => {
    const wrapper = render('catalog')
    expect(wrapper.find('nav').exists()).toBe(false)
    await wrapper.setProps({ section: 'overview', project: undefined })
    expect(wrapper.find('nav').exists()).toBe(false)
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
    })
    expect(archived.get('.project-workspace-header__heading').text()).toContain('已归档')
    expect(render().get('.project-workspace-header__heading').text()).toBe('项目')
  })
})
