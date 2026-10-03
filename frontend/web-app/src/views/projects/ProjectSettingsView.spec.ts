import { ProjectActorAccess, ProjectLifecycle, ResponseError, type ProjectDetail } from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { ElInput, ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectSettingsView from './ProjectSettingsView.vue'

const api = vi.hoisted(() => ({ getProject: vi.fn(), updateProject: vi.fn(), listConnectColumns: vi.fn() }))
const guard = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { projectId: 'project-1' } }), useRouter: () => ({ push: vi.fn() }), onBeforeRouteLeave: guard }))
vi.mock('../../api/client', () => ({ projectsApi: api, workItemsApi: api }))
vi.mock('../../composables/useSession', () => ({ useSession: () => ({ authentication: { value: { company: { timezone: 'Asia/Shanghai' } } } }) }))
vi.mock('@yumpoo/api-client', async original => ({ ...await original<typeof import('@yumpoo/api-client')>(), readCsrfToken: () => 'csrf' }))
enableAutoUnmount(afterEach)
const project: ProjectDetail = {
  id: 'project-1', workspaceId: 'main', workspaceCode: 'MAIN', workspaceName: '主工作空间',
  code: 'P001', name: '项目', description: null, lifecycle: ProjectLifecycle.Active,
  ownerUserId: 'user-1', ownerDisplayName: '张三', actorAccess: ProjectActorAccess.Owner,
  capabilities: { canUpdateSettings: true, canManageMembers: true, canReassignOwner: false, canArchive: true, canRestore: false, canMoveWorkspace: false, canOverrideArchive: false },
  rowVersion: 0, etag: '"0"', createdAt: new Date('2026-10-01T00:00:00Z'), updatedAt: new Date('2026-10-01T00:00:00Z'), archivedAt: null,
}
beforeEach(() => {
  Object.values(api).forEach(mock => mock.mockReset()); guard.mockReset(); vi.restoreAllMocks(); api.getProject.mockResolvedValue(project)
  api.listConnectColumns.mockResolvedValue({ items: [], incomingColumns: [], incomingAvailable: false, canManage: true, canDelete: true })
})

describe('项目设置', () => {
  it('展示只读编码与项目信息，连接目录为空时显示空态，归档时间按实际状态出现', async () => {
    const wrapper = mount(ProjectSettingsView)
    await flushPromises()
    expect(wrapper.get('.unified-project-settings__code').text()).toContain('P001')
    expect(wrapper.get('.unified-project-settings__empty').text()).toBe('还没有连接。在工作项表格右上角「+」中添加连接列。')
    expect(wrapper.get('.unified-project-settings__facts').text()).toContain('张三')
    expect(wrapper.text()).not.toContain('归档时间')
    expect(wrapper.findAllComponents(ElInput)).toHaveLength(2)
    expect(api.getProject).toHaveBeenCalledOnce()
    expect(api.listConnectColumns).toHaveBeenCalledWith({ projectId: 'project-1' }, expect.objectContaining({ signal: expect.any(AbortSignal) }))
  })
  it('连接概览同时展示本项目的列与连接到本项目的来源列', async () => {
    api.listConnectColumns.mockResolvedValue({ items: [{ id: 'column-1', name: '产品缺陷', targets: [{ projectId: 'target', name: 'Yumpoo 门户' }] }],
      incomingColumns: [{ columnId: 'incoming-1', projectName: '现场实施', projectCode: 'P012', columnName: '实施问题' }], incomingAvailable: true })
    const wrapper = mount(ProjectSettingsView, { global: { stubs: { RouterLink: { props: ['to'], template: '<a><slot /></a>' } } } })
    await flushPromises()
    expect(wrapper.find('.unified-project-settings__empty').exists()).toBe(false)
    expect(wrapper.get('.project-connections-overview').text()).toContain('产品缺陷')
    expect(wrapper.get('.project-connections-overview').text()).toContain('Yumpoo 门户')
    expect(wrapper.get('.project-connections-overview').text()).toContain('现场实施')
    expect(wrapper.get('.project-connections-overview').text()).toContain('实施问题')
  })
  it('发送名称和描述完整快照，并使用当前 ETag', async () => {
    const wrapper = mount(ProjectSettingsView)
    await flushPromises()
    api.updateProject.mockResolvedValue({ ...project, name: '新名称', rowVersion: 1, etag: '"1"' })
    await wrapper.findComponent(ElInput).setValue(' 新名称 ')
    await (wrapper.vm as unknown as { save: () => Promise<void> }).save()
    expect(api.updateProject).toHaveBeenCalledWith({ projectId: project.id, xXSRFTOKEN: 'csrf', ifMatch: '"0"', projectUpdateRequest: { name: '新名称', description: null } })
    expect((wrapper.findComponent(ElInput).props('modelValue'))).toBe('新名称')
  })
  it('412 刷新版本而保留输入，再提交使用最新 ETag', async () => {
    const wrapper = mount(ProjectSettingsView)
    await flushPromises()
    await wrapper.findComponent(ElInput).setValue('我的更改')
    api.getProject.mockResolvedValue({ ...project, name: '他人的更改', rowVersion: 2, etag: '"2"' })
    api.updateProject.mockRejectedValueOnce(new ResponseError(new Response(JSON.stringify({ code: 'VERSION_CONFLICT', message: '版本冲突', requestId: 'up2-412', retryable: false, fieldErrors: [], details: {} }), { status: 412 })))
    const vm = wrapper.vm as unknown as { save: () => Promise<void> }
    await vm.save()
    expect(wrapper.findComponent(ElInput).props('modelValue')).toBe('我的更改')
    api.updateProject.mockResolvedValue({ ...project, name: '我的更改' })
    await vm.save()
    expect(api.updateProject).toHaveBeenLastCalledWith(expect.objectContaining({ ifMatch: '"2"' }))
  })
  it('管理员只读与归档只读禁用设置；脏内容离开需确认', async () => {
    api.getProject.mockResolvedValue({ ...project, lifecycle: ProjectLifecycle.Archived, archivedAt: new Date(), capabilities: { ...project.capabilities, canUpdateSettings: false } })
    const archived = mount(ProjectSettingsView)
    await flushPromises()
    expect((archived.get('input').element as HTMLInputElement).disabled).toBe(true)
    expect(archived.text()).toContain('归档时间')
    api.getProject.mockResolvedValue(project)
    const wrapper = mount(ProjectSettingsView)
    await flushPromises()
    await wrapper.findComponent(ElInput).setValue('未保存')
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    expect(await guard.mock.calls.at(-1)![0]()).toBe(false)
  })
})
