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
  listAttachments: vi.fn(),
}))

vi.mock('../../api/client', () => ({
  workItemUpdatesApi: {
    listWorkItemUpdates: api.list,
    publishWorkItemUpdate: api.publish,
    getWorkItemUpdate: api.get,
    editWorkItemUpdate: api.edit,
    deleteWorkItemUpdate: api.delete,
  },
  attachmentsApi: {
    listWorkItemUpdateAttachments: api.listAttachments,
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
    api.listAttachments.mockResolvedValue({ items: [], nextCursor: null })
  })

  it('讨论附件仅在用户展开对应 Update 后加载', async () => {
    const wrapper = mount(WorkItemDiscussion, {
      props: { workItemId: '35000000-0000-4000-8000-000000000026', members: [member], canPublish: true },
    })
    await flushPromises()
    expect(api.listAttachments).not.toHaveBeenCalled()

    const button = wrapper.findAll('button').find(candidate => candidate.text() === '附件')
    expect(button).toBeDefined()
    await button!.trigger('click')
    await flushPromises()

    expect(api.listAttachments).toHaveBeenCalledWith({
      updateId: '35000000-0000-4000-8000-000000000032', size: 100,
    })
    await button!.trigger('click')
    await button!.trigger('click')
    await flushPromises()
    expect(api.listAttachments).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('首次读取最新窗口，向底部追加更早记录', async () => {
    const wrapper = mount(WorkItemDiscussion, {
      props: { workItemId: '35000000-0000-4000-8000-000000000026', members: [member], canPublish: true },
      attachTo: document.body,
    })
    await flushPromises()
    expect(api.list).toHaveBeenCalledWith({
      workItemId: '35000000-0000-4000-8000-000000000026', size: 20,
    }, { signal: expect.any(AbortSignal) })
    expect(wrapper.text()).toContain('较新讨论')

    api.list.mockResolvedValueOnce({
      items: [update('35000000-0000-4000-8000-000000000031', '更早讨论', '2026-08-24T10:01:00Z')],
      nextCursor: null,
    })
    await (wrapper.vm as unknown as { loadOlder: () => Promise<void> }).loadOlder()
    await flushPromises()
    expect(api.list).toHaveBeenLastCalledWith({
      workItemId: '35000000-0000-4000-8000-000000000026', cursor: 'older-cursor', size: 20,
    }, { signal: expect.any(AbortSignal) })
    expect(wrapper.text().indexOf('更早讨论')).toBeGreaterThan(wrapper.text().indexOf('较新讨论'))
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
    await wrapper.get('.discussion-submit').trigger('click')
    await flushPromises()
    const firstKey = api.publish.mock.calls[0]?.[0].idempotencyKey

    await wrapper.get('.discussion-submit').trigger('click')
    await flushPromises()
    expect(api.publish).toHaveBeenCalledTimes(2)
    expect(api.publish.mock.calls[1]?.[0].idempotencyKey).toBe(firstKey)
    expect(wrapper.html()).toContain('服务端净化正文')
    expect(wrapper.html()).not.toContain('onclick="evil()"')
    expect(wrapper.findAll('.discussion-update__body')[0]!.text()).toBe('服务端净化正文')
    expect(wrapper.find('.discussion-toolbar').exists()).toBe(false)
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
    api.list.mockResolvedValueOnce({ items: [existing, update('newer', '后续讨论', '2099-08-24T10:03:00Z')], nextCursor: null })
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
    expect(wrapper.findAll('.discussion-update')[0]!.text()).toContain('后续讨论')
    expect(wrapper.findAll('.discussion-update')[1]!.text()).toContain('此讨论已删除')
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
  it('三批倒序去重、同时间按 ID 排序、连续触底只发送一次请求，失败须手动重试', async () => {
    const make = (id: number) => update(String(id).padStart(3, '0'), '记录' + id, '2026-08-24T10:00:00Z')
    api.list.mockResolvedValueOnce({ items: Array.from({ length: 20 }, (_, i) => make(i + 41)), nextCursor: 'c2' })
    const wrapper = mount(WorkItemDiscussion, { props: { workItemId: 'item', members: [], canPublish: true } })
    const handle = wrapper.vm as unknown as { loadOlder: (retry?: boolean) => Promise<void> }
    await flushPromises()
    let resolve!: (value: unknown) => void
    api.list.mockImplementationOnce(() => new Promise(done => { resolve = done }))
    const pending = handle.loadOlder()
    await handle.loadOlder()
    await handle.loadOlder()
    expect(api.list).toHaveBeenCalledTimes(2)
    resolve({ items: Array.from({ length: 21 }, (_, i) => make(i + 21)), nextCursor: 'c3' })
    await pending
    api.list.mockRejectedValueOnce({ kind: 'transport', message: 'offline' })
    await handle.loadOlder()
    await handle.loadOlder()
    expect(api.list).toHaveBeenCalledTimes(3)
    expect(wrapper.text()).toContain('重试加载更早讨论')
    api.list.mockResolvedValueOnce({ items: Array.from({ length: 20 }, (_, i) => make(i + 1)), nextCursor: null })
    await handle.loadOlder(true)
    await flushPromises()
    const records = wrapper.findAll('.discussion-update__body').map(item => item.text())
    expect(records).toEqual(Array.from({ length: 60 }, (_, i) => '记录' + (60 - i)))
    expect(wrapper.text()).toContain('已加载全部讨论')
    wrapper.unmount()
  })

  it('刷新重建最新窗口且忽略旧分页与工作项切换的过期响应', async () => {
    const wrapper = mount(WorkItemDiscussion, { props: { workItemId: 'first', members: [], canPublish: true } })
    const handle = wrapper.vm as unknown as { loadOlder: () => Promise<void>, loadLatest: () => Promise<void> }
    await flushPromises()
    let older!: (value: unknown) => void
    api.list.mockImplementationOnce(() => new Promise(done => { older = done }))
    const pending = handle.loadOlder()
    const signal = api.list.mock.calls.at(-1)![1].signal as AbortSignal
    api.list.mockResolvedValueOnce({ items: [update('new', '刷新窗口', '2026-08-24T12:00:00Z')], nextCursor: 'fresh' })
    await handle.loadLatest()
    expect(signal.aborted).toBe(true)
    older({ items: [update('stale', '过期历史', '2026-08-24T09:00:00Z')], nextCursor: null })
    await pending
    expect(wrapper.text()).toContain('刷新窗口')
    expect(wrapper.text()).not.toContain('较新讨论')
    expect(wrapper.text()).not.toContain('过期历史')
    let previous!: (value: unknown) => void
    api.list.mockImplementationOnce(() => new Promise(done => { previous = done }))
    const loading = handle.loadLatest()
    api.list.mockResolvedValueOnce({ items: [update('second', '另一个工作项', '2026-08-24T12:00:00Z')], nextCursor: null })
    await wrapper.setProps({ workItemId: 'second' })
    await flushPromises()
    previous({ items: [update('wrong', '错误工作项', '2026-08-24T13:00:00Z')], nextCursor: null })
    await loading
    expect(wrapper.text()).toContain('另一个工作项')
    expect(wrapper.text()).not.toContain('错误工作项')
    wrapper.unmount()
  })

  it('使用实际抽屉根观察哨兵，不足一屏会继续补载', async () => {
    const container = document.createElement('div')
    container.className = 'el-drawer__body'
    document.body.append(container)
    Object.defineProperty(container, 'clientHeight', { value: 600 })
    const observe = vi.fn()
    let observerRoot: unknown
    vi.stubGlobal('IntersectionObserver', class {
      constructor(_callback: unknown, options: IntersectionObserverInit) { observerRoot = options.root }
      observe = observe
      disconnect = vi.fn()
    })
    api.list.mockResolvedValueOnce({ items: [update('2', '最新', '2026-08-24T12:00:00Z')], nextCursor: 'older' })
      .mockResolvedValueOnce({ items: [update('1', '最早', '2026-08-24T11:00:00Z')], nextCursor: null })
    const wrapper = mount(WorkItemDiscussion, { attachTo: container, props: { workItemId: 'first', members: [], canPublish: true } })
    await flushPromises()
    await new Promise(resolve => setTimeout(resolve, 60))
    await flushPromises()
    expect(observerRoot).toBe(container)
    expect(observe).toHaveBeenCalled()
    expect(api.list).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('最早')
    wrapper.unmount()
    container.remove()
    vi.unstubAllGlobals()
  })

  it('首次失败保留输入草稿，重试成功后展示最新窗口', async () => {
    api.list.mockRejectedValueOnce({ kind: 'transport', message: 'offline' })
    const wrapper = mount(WorkItemDiscussion, { props: { workItemId: 'item', members: [], canPublish: true } })
    await flushPromises()
    expect(wrapper.text()).toContain('重试加载讨论')
    expect(wrapper.text()).not.toContain('还没有讨论')
    const handle = wrapper.vm as unknown as { editor: { commands: { setContent: (value: string) => void }, getText: () => string }, loadLatest: () => Promise<void> }
    handle.editor.commands.setContent('<p>保留草稿</p>')
    api.list.mockResolvedValueOnce({ items: [update('new', '重试成功', '2026-08-24T12:00:00Z')], nextCursor: null })
    await handle.loadLatest()
    expect(wrapper.text()).toContain('重试成功')
    expect(handle.editor.getText()).toBe('保留草稿')
    wrapper.unmount()
  })

})
