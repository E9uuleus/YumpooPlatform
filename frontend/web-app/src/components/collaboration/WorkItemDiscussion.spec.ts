import {
  ErrorCode,
  ProjectMemberAccountStatusEnum,
  ProjectMemberEmploymentStatusEnum,
  ProjectMembershipStatus,
  WorkItemUpdateStatus,
  type ProjectMember,
  type WorkItemUpdate,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WorkItemDiscussion from './WorkItemDiscussion.vue'

const api = vi.hoisted(() => ({
  list: vi.fn(),
  publish: vi.fn(),
  get: vi.fn(),
  edit: vi.fn(),
  delete: vi.fn(),
}))

vi.mock('../../api/client', () => ({
  workItemUpdatesApi: {
    listWorkItemUpdates: api.list,
    publishWorkItemUpdate: api.publish,
    getWorkItemUpdate: api.get,
    editWorkItemUpdate: api.edit,
    deleteWorkItemUpdate: api.delete,
  },
}))
vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))
vi.mock('../../api/problems', async importOriginal => ({
  ...await importOriginal<typeof import('../../api/problems')>(),
  toApiProblem: async (reason: unknown) => reason,
}))

const member: ProjectMember = {
  membershipId: '35000000-0000-4000-8000-000000000021',
  projectId: '35000000-0000-4000-8000-000000000022',
  userId: '35000000-0000-4000-8000-000000000023',
  displayName: '项目成员',
  employmentStatus: ProjectMemberEmploymentStatusEnum.Active,
  accountStatus: ProjectMemberAccountStatusEnum.Enabled,
  membershipStatus: ProjectMembershipStatus.Active,
  owner: false,
  joinedAt: new Date('2026-08-24T09:00:00Z'),
  joinedByUserId: '35000000-0000-4000-8000-000000000024',
  removedAt: null,
  removedByUserId: null,
  rowVersion: 0,
  etag: '"0"',
}

function update(id: string, bodyText: string, createdAt: string): WorkItemUpdate {
  return {
    id,
    projectId: member.projectId,
    contentId: '35000000-0000-4000-8000-000000000025',
    workItemId: '35000000-0000-4000-8000-000000000026',
    authorUserId: member.userId,
    authorDisplayName: member.displayName,
    bodyHtml: `<p>${bodyText}</p>`,
    bodyText,
    status: WorkItemUpdateStatus.Published,
    editDeadlineAt: new Date(new Date(createdAt).getTime() + 15 * 60_000),
    rowVersion: 0,
    etag: '"0"',
    createdAt: new Date(createdAt),
    editedAt: null,
    editedByUserId: null,
    deletedAt: null,
    deletedByUserId: null,
    deleteReason: null,
    capabilities: {
      canEdit: false,
      canSelfDelete: false,
      canModerateDelete: false,
    },
  }
}

function actionableUpdate(capabilities: WorkItemUpdate['capabilities']): WorkItemUpdate {
  return {
    ...update('35000000-0000-4000-8000-000000000034', '可操作讨论', '2099-08-24T10:02:00Z'),
    capabilities,
  }
}

describe('WorkItemDiscussion', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.list.mockResolvedValue({
      items: [update('35000000-0000-4000-8000-000000000032', '较新讨论', '2026-08-24T10:02:00Z')],
      nextCursor: 'older-cursor',
    })
  })

  it('首次挂载才读取最新窗口，并向顶部加载更早记录', async () => {
    const wrapper = mount(WorkItemDiscussion, {
      props: { workItemId: '35000000-0000-4000-8000-000000000026', members: [member], canPublish: true },
      attachTo: document.body,
    })
    await flushPromises()
    expect(api.list).toHaveBeenCalledWith({
      workItemId: '35000000-0000-4000-8000-000000000026', size: 20,
    })
    expect(wrapper.text()).toContain('较新讨论')

    api.list.mockResolvedValueOnce({
      items: [update('35000000-0000-4000-8000-000000000031', '更早讨论', '2026-08-24T10:01:00Z')],
      nextCursor: null,
    })
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(api.list).toHaveBeenLastCalledWith({
      workItemId: '35000000-0000-4000-8000-000000000026', cursor: 'older-cursor', size: 20,
    })
    expect(wrapper.text().indexOf('更早讨论')).toBeLessThan(wrapper.text().indexOf('较新讨论'))
    wrapper.unmount()
  })

  it('只读状态仍显示服务端净化正文，但不创建编辑器', async () => {
    api.list.mockResolvedValueOnce({
      items: [{ ...update('35000000-0000-4000-8000-000000000032', '安全正文', '2026-08-24T10:02:00Z'),
        bodyHtml: '<p><strong>安全正文</strong></p>' }],
      nextCursor: null,
    })
    const wrapper = mount(WorkItemDiscussion, {
      props: {
        workItemId: '35000000-0000-4000-8000-000000000026',
        members: [member],
        canPublish: false,
        readOnlyReason: 'Project 已归档，工作项仅可查看。',
      },
    })
    await flushPromises()
    expect(wrapper.html()).toContain('<strong>安全正文</strong>')
    expect(wrapper.text()).toContain('Project 已归档')
    expect(wrapper.find('.discussion-composer').exists()).toBe(false)
    wrapper.unmount()
  })

  it('传输失败重试复用原幂等键，成功后采用服务端净化响应', async () => {
    api.publish.mockRejectedValueOnce(new TypeError('network unavailable'))
      .mockResolvedValueOnce(update(
        '35000000-0000-4000-8000-000000000033',
        '服务端净化正文',
        '2026-08-24T10:03:00Z',
      ))
    const wrapper = mount(WorkItemDiscussion, {
      props: { workItemId: '35000000-0000-4000-8000-000000000026', members: [member], canPublish: true },
      attachTo: document.body,
    })
    await flushPromises()
    const exposed = wrapper.vm as unknown as { editor: { commands: { setContent: (html: string) => void } } }
    exposed.editor.commands.setContent('<p onclick="evil()">本地草稿</p>')
    await flushPromises()
    await wrapper.get('.discussion-composer__footer button').trigger('click')
    await flushPromises()
    const firstKey = api.publish.mock.calls[0]?.[0].idempotencyKey

    await wrapper.get('.discussion-composer__footer button').trigger('click')
    await flushPromises()
    expect(api.publish).toHaveBeenCalledTimes(2)
    expect(api.publish.mock.calls[1]?.[0].idempotencyKey).toBe(firstKey)
    expect(wrapper.html()).toContain('服务端净化正文')
    expect(wrapper.html()).not.toContain('onclick="evil()"')
    wrapper.unmount()
  })

  it('按服务端能力展示操作，编辑回填并以强 ETag 原位更新', async () => {
    const existing = actionableUpdate({ canEdit: true, canSelfDelete: true, canModerateDelete: false })
    api.list.mockResolvedValueOnce({ items: [existing], nextCursor: null })
    api.edit.mockResolvedValueOnce({
      ...existing,
      bodyHtml: '<p>服务端净化后的编辑</p>',
      bodyText: '服务端净化后的编辑',
      status: WorkItemUpdateStatus.Edited,
      rowVersion: 1,
      etag: '"1"',
    })
    const wrapper = mount(WorkItemDiscussion, {
      props: { workItemId: existing.workItemId, members: [member], canPublish: true },
      attachTo: document.body,
    })
    await flushPromises()
    expect(wrapper.text()).toContain('编辑')
    expect(wrapper.text()).toContain('删除')
    expect(wrapper.text()).not.toContain('治理删除')

    const editButton = wrapper.findAll('button').find(button => button.text() === '编辑')!
    await editButton.trigger('click')
    const exposed = wrapper.vm as unknown as {
      editEditor: { commands: { setContent: (html: string) => void }, getHTML: () => string }
      saveEdit: () => Promise<void>
    }
    expect(exposed.editEditor.getHTML()).toContain('可操作讨论')
    exposed.editEditor.commands.setContent('<p>本地编辑 <span data-type="mention" data-mention-user-id="35000000-0000-4000-8000-000000000023">@项目成员</span></p>')
    await exposed.saveEdit()
    await flushPromises()
    expect(api.edit).toHaveBeenCalledWith({
      updateId: existing.id,
      xXSRFTOKEN: 'csrf-token',
      ifMatch: '"0"',
      workItemUpdateEditRequest: { bodyHtml: expect.stringContaining('data-mention-user-id') },
    })
    expect(wrapper.html()).toContain('服务端净化后的编辑')
    wrapper.unmount()
  })

  it('作者删除二次确认后渲染不可恢复占位', async () => {
    const existing = actionableUpdate({ canEdit: false, canSelfDelete: true, canModerateDelete: false })
    const tombstone: WorkItemUpdate = {
      ...existing,
      bodyHtml: null,
      bodyText: null,
      status: WorkItemUpdateStatus.Deleted,
      rowVersion: 1,
      etag: '"1"',
      deletedAt: new Date('2099-08-24T10:03:00Z'),
      deletedByUserId: member.userId,
      capabilities: { canEdit: false, canSelfDelete: false, canModerateDelete: false },
    }
    api.list.mockResolvedValueOnce({ items: [existing], nextCursor: null })
    api.delete.mockResolvedValueOnce(tombstone)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValueOnce('confirm' as never)
    const wrapper = mount(WorkItemDiscussion, {
      props: { workItemId: existing.workItemId, members: [member], canPublish: true },
      attachTo: document.body,
    })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '删除')!.trigger('click')
    await flushPromises()
    expect(api.delete).toHaveBeenCalledWith({
      updateId: existing.id,
      xXSRFTOKEN: 'csrf-token',
      ifMatch: '"0"',
      workItemUpdateDeleteRequest: {},
    })
    expect(wrapper.text()).toContain('此讨论已删除')
    expect(wrapper.html()).not.toContain('<p>可操作讨论</p>')
    wrapper.unmount()
  })

  it('治理删除要求理由并仅在墓碑展示治理理由', async () => {
    const existing = actionableUpdate({ canEdit: false, canSelfDelete: false, canModerateDelete: true })
    const tombstone: WorkItemUpdate = {
      ...existing,
      bodyHtml: null,
      bodyText: null,
      status: WorkItemUpdateStatus.Deleted,
      rowVersion: 1,
      etag: '"1"',
      deletedAt: new Date('2099-08-24T10:03:00Z'),
      deletedByUserId: '35000000-0000-4000-8000-000000000099',
      deleteReason: '  违反项目讨论规范  '.trim(),
      capabilities: { canEdit: false, canSelfDelete: false, canModerateDelete: false },
    }
    api.list.mockResolvedValueOnce({ items: [existing], nextCursor: null })
    api.delete.mockResolvedValueOnce(tombstone)
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValueOnce({
      value: '  违反项目讨论规范  ',
      action: 'confirm',
    } as never)
    const wrapper = mount(WorkItemDiscussion, {
      props: { workItemId: existing.workItemId, members: [member], canPublish: true },
      attachTo: document.body,
    })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '治理删除')!.trigger('click')
    await flushPromises()
    expect(api.delete.mock.calls[0]?.[0].workItemUpdateDeleteRequest).toEqual({ reason: '违反项目讨论规范' })
    expect(wrapper.text()).toContain('治理理由：违反项目讨论规范')
    expect(wrapper.html()).not.toContain('<p>可操作讨论</p>')
    wrapper.unmount()
  })

  it('412 后单条刷新且保留未提交编辑草稿', async () => {
    const existing = actionableUpdate({ canEdit: true, canSelfDelete: false, canModerateDelete: false })
    const fresh = { ...existing, bodyHtml: '<p>其他人已更新</p>', bodyText: '其他人已更新', rowVersion: 1, etag: '"1"' }
    api.list.mockResolvedValueOnce({ items: [existing], nextCursor: null })
    api.edit.mockRejectedValueOnce({
      kind: 'response',
      status: 412,
      error: { code: ErrorCode.VersionConflict, message: '版本冲突', requestId: 'req-1', retryable: false, fieldErrors: [] },
    })
    api.get.mockResolvedValueOnce(fresh)
    const wrapper = mount(WorkItemDiscussion, {
      props: { workItemId: existing.workItemId, members: [member], canPublish: true },
      attachTo: document.body,
    })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '编辑')!.trigger('click')
    const exposed = wrapper.vm as unknown as {
      editEditor: { commands: { setContent: (html: string) => void }, getHTML: () => string }
      saveEdit: () => Promise<void>
    }
    exposed.editEditor.commands.setContent('<p>我的未提交草稿</p>')
    await exposed.saveEdit()
    await flushPromises()
    expect(api.get).toHaveBeenCalledWith({ updateId: existing.id })
    expect(exposed.editEditor.getHTML()).toContain('我的未提交草稿')
    expect(document.body.textContent).toContain('未提交的编辑草稿仍保留')
    wrapper.unmount()
  })

  it('到达服务端截止时刻后主动隐藏作者操作', async () => {
    const existing = {
      ...actionableUpdate({ canEdit: true, canSelfDelete: true, canModerateDelete: false }),
      editDeadlineAt: new Date(Date.now()),
    }
    api.list.mockResolvedValueOnce({ items: [existing], nextCursor: null })
    const wrapper = mount(WorkItemDiscussion, {
      props: { workItemId: existing.workItemId, members: [member], canPublish: true },
    })
    await flushPromises()
    expect(wrapper.findAll('button').some(button => button.text() === '编辑')).toBe(false)
    expect(wrapper.findAll('button').some(button => button.text() === '删除')).toBe(false)
    wrapper.unmount()
  })
})
