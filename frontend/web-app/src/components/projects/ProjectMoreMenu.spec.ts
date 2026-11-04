import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { ProjectActorAccess, ProjectLifecycle, type ProjectDetail } from '@yumpoo/api-client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectMoreMenu from './ProjectMoreMenu.vue'

const api = vi.hoisted(() => ({ leaveProject: vi.fn(), getMyProjectNotificationPreference: vi.fn() }))
const push = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('../../api/client', () => ({ projectsApi: api, notificationsApi: api, workItemsApi: {} }))
vi.mock('../../composables/useTimeTracker', () => ({ useTimeTracker: () => ({ refresh: vi.fn() }) }))
vi.mock('@yumpoo/api-client', async original => ({ ...await original<typeof import('@yumpoo/api-client')>(), readCsrfToken: () => 'csrf' }))
enableAutoUnmount(afterEach)
const project = { id: 'project-1', name: '项目', actorAccess: ProjectActorAccess.Member, lifecycle: ProjectLifecycle.Active, capabilities: { canLeave: true } } as ProjectDetail
const stubs = {
  ElDropdown: { name: 'ElDropdown', emits: ['command', 'visibleChange'], template: '<div><slot /><slot name="dropdown" /></div>' },
  ElDropdownMenu: { template: '<ul><slot /></ul>' },
  ElDropdownItem: { props: ['command', 'icon'], template: '<li><slot /></li>' },
  ProjectNotificationSettingsDialog: true,
}
beforeEach(() => {
  Object.values(api).forEach(mock => mock.mockReset()); push.mockReset(); vi.restoreAllMocks()
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  api.leaveProject.mockResolvedValue({})
})
describe('项目菜单', () => {
  it('成员看到五个分区、通知提醒与退出项目，当前页高亮；只读公司管理员只看到分区与其能力内的操作', async () => {
    const wrapper = mount(ProjectMoreMenu, { props: { project, section: 'members' }, global: { stubs } })
    const items = () => wrapper.findAll('li').filter(item => !item.classes().includes('yp-menu-label')).map(item => item.text())
    expect(items()).toEqual(['工作项','统计','成员','动态','设置','通知提醒','退出项目'])
    expect(wrapper.get('li.yp-menu-item--current').text()).toBe('成员')
    await wrapper.setProps({ project: { ...project, actorAccess: ProjectActorAccess.CompanyAdmin, capabilities: { canArchive: true } } as ProjectDetail })
    expect(items()).toEqual(['工作项','统计','成员','动态','设置','归档项目'])
  })
  it('确认退出后调用接口并回到工作台', async () => {
    const wrapper = mount(ProjectMoreMenu, { props: { project, section: 'members' }, global: { stubs } })
    wrapper.findComponent({ name: 'ElDropdown' }).vm.$emit('command', { kind: 'leave' })
    await flushPromises()
    expect(api.leaveProject).toHaveBeenCalledOnce()
    expect(api.leaveProject).toHaveBeenCalledWith({ projectId: 'project-1', xXSRFTOKEN: 'csrf', idempotencyKey: expect.any(String) })
    expect(push).toHaveBeenCalledWith({ name: 'workspace-entry' })
  })
})
