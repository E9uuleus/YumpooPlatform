import { ProjectActorAccess, ProjectLifecycle, type ProjectSummary } from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { projectsApi } from '../../api/client'
import ProjectDeletionActions from './ProjectDeletionActions.vue'

vi.mock('../../api/client', () => ({ projectsApi: { scheduleProjectDeletion: vi.fn(), cancelProjectDeletion: vi.fn() } }))
vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(), readCsrfToken: () => 'csrf',
}))
const project: ProjectSummary = {
  id: 'project-1', workspaceId: 'workspace-1', workspaceCode: 'MAIN', workspaceName: '工作空间',
  code: 'P001', name: '归档项目', lifecycle: ProjectLifecycle.Archived, ownerUserId: 'owner-1',
  ownerDisplayName: '负责人', actorAccess: ProjectActorAccess.Owner, rowVersion: 3, etag: '"3"',
  createdAt: new Date(), updatedAt: new Date(),
  capabilities: { canUpdateSettings: false, canManageMembers: false, canReassignOwner: false,
    canArchive: false, canRestore: true, canMoveWorkspace: false, canOverrideArchive: false,
    canScheduleDeletion: true, canCancelDeletion: false },
}
describe('ProjectDeletionActions', () => {
  beforeEach(() => { vi.restoreAllMocks(); vi.clearAllMocks() })
  it('要求精确项目编号并携带版本、XSRF和幂等键', async () => {
    const prompt = vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: 'P001', action: 'confirm' } as never)
    vi.mocked(projectsApi.scheduleProjectDeletion).mockResolvedValue({ id: project.id, lifecycle: ProjectLifecycle.Archived, rowVersion: 4, etag: '"4"' })
    const wrapper = mount(ProjectDeletionActions, { props: { project } })
    await wrapper.get('button').trigger('click')
    await flushPromises()
    const options = prompt.mock.calls[0]?.[2]
    const validate = options?.inputValidator as (value: string) => boolean | string
    expect(validate('p001')).toBe('请输入准确的项目编号')
    expect(validate(' P001')).toBe('请输入准确的项目编号')
    expect(validate('P001')).toBe(true)
    expect(projectsApi.scheduleProjectDeletion).toHaveBeenCalledWith(expect.objectContaining({
      projectId: project.id, ifMatch: '"3"', xXSRFTOKEN: 'csrf', idempotencyKey: expect.any(String),
      projectDeletionRequest: { confirmationCode: 'P001' },
    }))
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })
  it('取消确认不发送删除请求', async () => {
    vi.spyOn(ElMessageBox, 'prompt').mockRejectedValue('cancel')
    const wrapper = mount(ProjectDeletionActions, { props: { project } })
    await wrapper.get('button').trigger('click'); await flushPromises()
    expect(projectsApi.scheduleProjectDeletion).not.toHaveBeenCalled()
  })
  it('确认期间列表刷新仍只删除已确认项目且不重复打开确认框', async () => {
    let resolvePrompt!: (value: { value: string; action: string }) => void
    const prompt = vi.spyOn(ElMessageBox, 'prompt').mockImplementation(() => new Promise(resolve => {
      resolvePrompt = value => resolve(value as never)
    }))
    const wrapper = mount(ProjectDeletionActions, { props: { project } })
    await wrapper.get('button').trigger('click')
    await wrapper.get('button').trigger('click')
    expect(prompt).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ project: { ...project, id: 'project-2', code: 'P002', etag: '"8"' } })
    const validate = prompt.mock.calls[0]?.[2]?.inputValidator as (value: string) => boolean | string
    expect(validate('P001')).toBe(true)
    expect(validate('P002')).toBe('请输入准确的项目编号')
    resolvePrompt({ value: 'P001', action: 'confirm' })
    await flushPromises()
    expect(projectsApi.scheduleProjectDeletion).toHaveBeenCalledWith(expect.objectContaining({
      projectId: project.id, ifMatch: '"3"', projectDeletionRequest: { confirmationCode: 'P001' },
    }))
  })
  it('计划删除后仅显示撤销操作，撤销使用当前版本', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mount(ProjectDeletionActions, { props: { project: {
      ...project, etag: '"4"', capabilities: { ...project.capabilities, canScheduleDeletion: false, canCancelDeletion: true, canRestore: false },
    } } })
    expect(wrapper.text()).toBe('撤销删除')
    await wrapper.get('button').trigger('click'); await flushPromises()
    expect(projectsApi.cancelProjectDeletion).toHaveBeenCalledWith(expect.objectContaining({ ifMatch: '"4"' }))
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })
  it('冲突或传输结果未知时刷新真源再允许重试', async () => {
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: 'P001', action: 'confirm' } as never)
    vi.mocked(projectsApi.scheduleProjectDeletion).mockRejectedValue(new Response(JSON.stringify({
      status: 412, code: 'VERSION_CONFLICT', title: '项目已更新', detail: '版本冲突',
    }), { status: 412, headers: { 'Content-Type': 'application/problem+json' } }))
    const wrapper = mount(ProjectDeletionActions, { props: { project } })
    await wrapper.get('button').trigger('click'); await flushPromises()
    expect(wrapper.emitted('changed')).toHaveLength(1)
    expect(wrapper.emitted('problem')).toHaveLength(1)
  })
  it('旧服务端或没有删除能力时隐藏操作', () => {
    const { canScheduleDeletion: _schedule, canCancelDeletion: _cancel, ...capabilities } = project.capabilities
    const wrapper = mount(ProjectDeletionActions, { props: { project: { ...project, capabilities } } })
    expect(wrapper.find('button').exists()).toBe(false)
  })
})
