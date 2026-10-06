import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ProjectMemberAddDialog from './ProjectMemberAddDialog.vue'

const api = vi.hoisted(() => ({ candidates: vi.fn(), add: vi.fn() }))
vi.mock('../../api/client', () => ({
  projectsApi: { listProjectMemberCandidates: api.candidates, addProjectMember: api.add },
}))
vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf',
}))

describe('ProjectMemberAddDialog', () => {
  it('防抖搜索后多选加入，已移出成员携带 If-Match 重新加入', async () => {
    const candidate = { employmentStatus: 'ACTIVE', accountStatus: 'ENABLED', owner: false }
    api.candidates.mockResolvedValue({ items: [
      { ...candidate, userId: 'u1', displayName: '王一', membershipStatus: null, membershipRowVersion: null, membershipEtag: null },
      { ...candidate, userId: 'u2', displayName: '王二', membershipStatus: 'REMOVED', membershipRowVersion: 3, membershipEtag: '"3"' },
      { ...candidate, userId: 'u3', displayName: '王三', membershipStatus: 'ACTIVE', membershipRowVersion: 1, membershipEtag: '"1"' },
    ], page: 0, size: 20, totalElements: 3, totalPages: 1 })
    api.add.mockResolvedValue({})
    const wrapper = mount(ProjectMemberAddDialog, {
      props: { modelValue: true, projectId: 'p1', reasonRequired: false },
      global: { stubs: { teleport: true } },
    })
    await flushPromises()
    await wrapper.get('input[aria-label="搜索同企业成员"]').setValue('王')
    await new Promise(resolve => setTimeout(resolve, 300))
    await flushPromises()
    const options = wrapper.findAll('.member-add__option')
    expect(options[2]!.attributes('disabled')).toBeDefined()
    await options[0]!.trigger('click')
    await options[1]!.trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '添加')!.trigger('click')
    await flushPromises()
    expect(api.add).toHaveBeenCalledTimes(2)
    expect(api.add.mock.calls[0]![0]).not.toHaveProperty('ifMatch')
    expect(api.add.mock.calls[1]![0]).toMatchObject({ ifMatch: '"3"', projectMemberAddRequest: { userId: 'u2', reason: null } })
    expect(wrapper.emitted('added')).toEqual([[2]])
  })
})
