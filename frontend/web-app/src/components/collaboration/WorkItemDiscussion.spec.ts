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
import DiscussionReplyComposer from './DiscussionReplyComposer.vue'
import DiscussionCommentCard from './DiscussionCommentCard.vue'

const api = vi.hoisted(() => ({
  list: vi.fn(),
  publish: vi.fn(),
  get: vi.fn(),
  edit: vi.fn(),
  delete: vi.fn(),
  listAttachments: vi.fn(),
  pin: vi.fn(),
  replies: vi.fn(),
}))

vi.mock('../../api/client', () => ({
  workItemUpdatesApi: {
    listWorkItemUpdates: api.list,
    pinWorkItemUpdate: api.pin,
    listWorkItemUpdateReplies: api.replies,
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
    rowVersion: 0,
    etag: '"0"',
    createdAt: new Date(createdAt),
    editedAt: null,
    editedByUserId: null,
    deletedAt: null,
    deletedByUserId: null,
    parentUpdateId: null, pinnedAt: null, pinnedByUserId: null, replyCount: 0, replies: [], repliesNextCursor: null,
    capabilities: {
      canEdit: false,
      canDelete: false, canReply: false, canPin: false,
    },
  }
}

function actionableUpdate(capabilities: WorkItemUpdate['capabilities']): WorkItemUpdate {
  return {
    ...update('35000000-0000-4000-8000-000000000034', '可操作讨论', '2099-08-24T10:02:00Z'),
    capabilities,
  }
}

async function clickMenu(wrapper: ReturnType<typeof mount>, label: string) {
  await wrapper.get('[aria-label="评论操作"]').trigger('click')
  await flushPromises()
  const button = [...document.querySelectorAll<HTMLButtonElement>('.discussion-menu button')].find(candidate => candidate.textContent?.trim() === label)
  expect(button, label).toBeDefined()
  button!.click()
  await flushPromises()
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

  it('评论页面不再提供附件入口或请求', async () => {
    const wrapper = mount(WorkItemDiscussion, { props: { workItemId: 'item', members: [member], canPublish: true } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('附件')
    expect(api.listAttachments).not.toHaveBeenCalled()
    expect(wrapper.get('[aria-label="刷新评论"]').find('svg').exists()).toBe(true)
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
    const existing = actionableUpdate({ canEdit: true, canDelete: true, canReply: false, canPin: false })
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
    expect(wrapper.find('[aria-label="评论操作"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('治理删除')

    await clickMenu(wrapper, '编辑')
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

  it('作者删除二次确认后移除讨论串', async () => {
    const existing = actionableUpdate({ canEdit: false, canDelete: true, canReply: false, canPin: false })
    const tombstone: WorkItemUpdate = {
      ...existing,
      bodyHtml: null,
      bodyText: null,
      status: WorkItemUpdateStatus.Deleted,
      rowVersion: 1,
      etag: '"1"',
      deletedAt: new Date('2099-08-24T10:03:00Z'),
      deletedByUserId: member.userId,
      capabilities: { canEdit: false, canDelete: false, canReply: false, canPin: false },
    }
    api.list.mockResolvedValueOnce({ items: [existing, update('newer', '后续讨论', '2099-08-24T10:03:00Z')], nextCursor: null })
    api.delete.mockResolvedValueOnce(tombstone)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValueOnce('confirm' as never)
    const wrapper = mount(WorkItemDiscussion, {
      props: { workItemId: existing.workItemId, members: [member], canPublish: true },
      attachTo: document.body,
    })
    await flushPromises()
    await clickMenu(wrapper, '删除')
    await flushPromises()
    expect(api.delete).toHaveBeenCalledWith({
      updateId: existing.id,
      xXSRFTOKEN: 'csrf-token',
      ifMatch: '"0"',
      body: {},
    })
    expect(wrapper.text()).not.toContain('此讨论已删除')
    expect(wrapper.html()).not.toContain('<p>可操作讨论</p>')
    expect(wrapper.findAll('.discussion-update')[0]!.text()).toContain('后续讨论')
    expect(wrapper.findAll('.discussion-update')).toHaveLength(1)
    wrapper.unmount()
  })

  it('置顶多条主评论并与普通窗口去重', async () => {
    const pinned = { ...update('old', '旧置顶', '2020-01-01T00:00:00Z'), pinnedAt: new Date(), pinnedByUserId: member.userId }
    const recent = actionableUpdate({ canEdit: true, canDelete: true, canReply: true, canPin: true })
    api.list.mockResolvedValue({ items: [recent, pinned], pinnedItems: [pinned], nextCursor: null })
    const wrapper = mount(WorkItemDiscussion, { props: { workItemId: recent.workItemId, members: [member], canPublish: true }, attachTo: document.body })
    await flushPromises()
    expect(wrapper.findAll('.discussion-update')).toHaveLength(2)
    expect(wrapper.findAll('.discussion-update')[0]!.text()).toContain('旧置顶')
    api.pin.mockResolvedValue({ ...recent, pinnedAt: new Date(), pinnedByUserId: member.userId, rowVersion: 1, etag: '"1"' })
    await clickMenu(wrapper, '置顶')
    expect(api.pin).toHaveBeenCalledWith(expect.objectContaining({ updateId: recent.id, ifMatch: '"0"', workItemUpdatePinRequest: { pinned: true } }))
    expect(api.list).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('回复只挂在主评论下，已有回复默认展示输入框，刷新保留草稿', async () => {
    const parent = actionableUpdate({ canEdit: true, canDelete: true, canReply: true, canPin: true })
    const child = { ...update('reply1', '第一条回复', '2026-08-24T10:03:00Z'), parentUpdateId: parent.id }
    api.list.mockResolvedValue({ items: [{ ...parent, replies: [child], replyCount: 1 }], pinnedItems: [], nextCursor: null })
    const wrapper = mount(WorkItemDiscussion, { props: { workItemId: parent.workItemId, members: [member], canPublish: true }, attachTo: document.body })
    await flushPromises()
    expect(wrapper.findAll('.discussion-update--reply')).toHaveLength(1)
    expect(wrapper.find('.discussion-update--reply .discussion-update__reply').exists()).toBe(false)
    const reply = wrapper.getComponent(DiscussionReplyComposer)
    const editor = (reply.vm as unknown as { editor: { commands: { setContent: (html: string) => void } } }).editor
    editor.commands.setContent('<p>回复草稿</p>')
    await flushPromises()
    expect((wrapper.vm as unknown as { hasDraft: boolean }).hasDraft).toBe(true)
    await (wrapper.vm as unknown as { loadLatest: () => Promise<void> }).loadLatest()
    expect(wrapper.getComponent(DiscussionReplyComposer).text()).toContain('回复草稿')
    api.publish.mockResolvedValue({ ...child, id: 'reply2', bodyHtml: '<p>服务端回复</p>' })
    await reply.get('.discussion-submit').trigger('click')
    await flushPromises()
    expect(api.publish).toHaveBeenCalledWith(expect.objectContaining({ workItemUpdateCreateRequest: { bodyHtml: '<p>回复草稿</p>', parentUpdateId: parent.id } }))
    expect(wrapper.findAll('.discussion-update--reply')).toHaveLength(2)
    expect((wrapper.vm as unknown as { hasDraft: boolean }).hasDraft).toBe(false)
    wrapper.unmount()
  })

  it('回复失败保留独立草稿和幂等键，继续分页不重复已有回复', async () => {
    const parent = actionableUpdate({ canEdit: true, canDelete: true, canReply: true, canPin: true })
    const child = { ...update('reply1', '首条回复', '2026-08-24T10:03:00Z'), parentUpdateId: parent.id }
    api.list.mockResolvedValue({ items: [{ ...parent, replies: [child], replyCount: 3, repliesNextCursor: 'next' }], pinnedItems: [], nextCursor: null })
    api.replies.mockRejectedValueOnce({ kind: 'network', message: '网络错误' }).mockResolvedValueOnce({ items: [child, { ...child, id: 'reply2' }], nextCursor: null })
    const wrapper = mount(WorkItemDiscussion, { props: { workItemId: parent.workItemId, members: [member], canPublish: true }, attachTo: document.body })
    await flushPromises()
    const more = () => wrapper.findAll('button').find(button => button.text() === '加载更多回复')!
    await more().trigger('click'); await flushPromises()
    expect(api.replies).toHaveBeenCalledTimes(1)
    await more().trigger('click'); await flushPromises()
    expect(wrapper.findAll('.discussion-update--reply')).toHaveLength(2)
    const reply = wrapper.getComponent(DiscussionReplyComposer)
    const exposed = reply.vm as unknown as { editor: { commands: { setContent: (html: string) => void } }; publish: () => Promise<void> }
    exposed.editor.commands.setContent('<p>等待重试</p>')
    api.publish.mockRejectedValueOnce({ kind: 'network', message: '网络错误' }).mockResolvedValueOnce({ ...child, id: 'reply3' })
    await exposed.publish(); await flushPromises()
    const key = api.publish.mock.calls[0]![0].idempotencyKey
    expect(reply.text()).toContain('等待重试')
    await exposed.publish(); await flushPromises()
    expect(api.publish.mock.calls[1]![0].idempotencyKey).toBe(key)
    expect(wrapper.findAll('.discussion-update--reply')).toHaveLength(3)
    wrapper.unmount()
  })

  it('评论菜单支持方向键选择和 Escape 返回触发按钮', async () => {
    const item = actionableUpdate({ canEdit: true, canDelete: true, canReply: true, canPin: true })
    const wrapper = mount(DiscussionCommentCard, { props: { item, now: new Date(), timezone: 'Asia/Shanghai', busy: false }, attachTo: document.body })
    const trigger = wrapper.get('[aria-label="评论操作"]')
    ;(trigger.element as HTMLButtonElement).focus()
    await trigger.trigger('keydown', { key: 'ArrowDown', code: 'ArrowDown' }); await flushPromises()
    expect(document.activeElement?.textContent?.trim()).toBe('置顶')
    document.activeElement!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    expect(document.activeElement?.textContent?.trim()).toBe('编辑')
    document.activeElement!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(document.activeElement).toBe(trigger.element)
    expect(trigger.attributes('aria-expanded')).toBe('false')
    wrapper.unmount()
  })

  it('刷新时忽略过期回复分页，解除加载状态并允许重新分页', async () => {
    const child = { ...update('child', '首条回复', '2026-08-24T10:01:00Z'), parentUpdateId: 'root' }
    const root = { ...update('root', '主评论', '2026-08-24T10:00:00Z'), replies: [child], replyCount: 22, repliesNextCursor: 'reply-page' }
    api.list.mockResolvedValue({ items: [root], pinnedItems: [], nextCursor: null })
    let resolve!: (page: unknown) => void
    api.replies.mockImplementationOnce(() => new Promise(done => { resolve = done }))
    const wrapper = mount(WorkItemDiscussion, { props: { workItemId: root.workItemId, members: [], canPublish: true } })
    await flushPromises()
    const more = () => wrapper.findAll('button').find(button => button.text() === '加载更多回复')!
    await more().trigger('click')
    await (wrapper.vm as unknown as { loadLatest: () => Promise<void> }).loadLatest()
    resolve({ items: [{ ...child, id: 'stale', bodyHtml: '<p>过期回复</p>' }], nextCursor: null })
    await flushPromises()
    expect(wrapper.text()).not.toContain('过期回复')
    expect(more().classes()).not.toContain('is-loading')
    api.replies.mockResolvedValueOnce({ items: [{ ...child, id: 'new', bodyHtml: '<p>新回复</p>' }], nextCursor: null })
    await more().trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('新回复')
    expect(api.replies).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('412 后单条刷新且保留未提交编辑草稿', async () => {
    const existing = actionableUpdate({ canEdit: true, canDelete: false, canReply: false, canPin: false })
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
    await clickMenu(wrapper, '编辑')
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

  it('旧评论仍按服务端权限允许编辑和删除', async () => {
    const existing = { ...update('old', '很早的评论', '2020-01-01T00:00:00Z'), capabilities: { canEdit: true, canDelete: true, canReply: false, canPin: false } }
    api.list.mockResolvedValueOnce({ items: [existing], pinnedItems: [], nextCursor: null })
    const wrapper = mount(WorkItemDiscussion, { props: { workItemId: existing.workItemId, members: [member], canPublish: true }, attachTo: document.body })
    await flushPromises()
    await clickMenu(wrapper, '编辑')
    expect((wrapper.vm as unknown as { editEditor: { getHTML: () => string } }).editEditor.getHTML()).toContain('很早的评论')
    expect(wrapper.find('time').text()).toMatch(/天前$/)
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
