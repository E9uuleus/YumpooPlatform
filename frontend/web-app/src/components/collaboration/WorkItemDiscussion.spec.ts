import {
  ProjectMemberAccountStatusEnum,
  ProjectMemberEmploymentStatusEnum,
  ProjectMembershipStatus,
  WorkItemUpdateStatus,
  type ProjectMember,
  type WorkItemUpdate,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WorkItemDiscussion from './WorkItemDiscussion.vue'

const api = vi.hoisted(() => ({ list: vi.fn(), publish: vi.fn() }))

vi.mock('../../api/client', () => ({
  workItemUpdatesApi: {
    listWorkItemUpdates: api.list,
    publishWorkItemUpdate: api.publish,
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
})
