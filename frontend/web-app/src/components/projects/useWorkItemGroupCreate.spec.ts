import { type WorkItemDetail, type WorkItemCreateRequest } from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { dateGroups, EMPTY_GROUP, itemGroupKey, type GroupField, type WorkItemGroup } from './workItemGrouping'
import { useWorkItemGroupCreate } from './useWorkItemGroupCreate'

const api = vi.hoisted(() => ({ createWorkItem: vi.fn(), transitionWorkItem: vi.fn() }))
vi.mock('../../api/client', () => ({ workItemsApi: api }))
vi.mock('@yumpoo/api-client', async original => ({ ...await original<object>(), readCsrfToken: () => 'csrf' }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn() }, ElMessageBox: { prompt: vi.fn() } }))
enableAutoUnmount(afterEach)
const group = (key: string): WorkItemGroup => ({ key, label: key, color: 'blue', count: 1, rank: 0 })
const created = () => ({ id: 'new', itemNo: 'WI-1', etag: '"1"', statusCode: 'TODO',
  capabilities: { canMoveInKanban: true, availableTransitions: [{ toStatus: 'DONE', requiresResolution: false }] } } as WorkItemDetail)
function harness(initial: GroupField = 'ASSIGNEE') {
  const field = ref<GroupField | ''>(initial), project = ref('project-1'), disabled = ref('')
  const changed = vi.fn(async () => {})
  let create!: ReturnType<typeof useWorkItemGroupCreate>
  mount(defineComponent({ setup() {
    create = useWorkItemGroupCreate({ field: () => field.value, projectId: () => project.value, contentId: () => 'default',
      disabledReason: () => disabled.value, changed })
    return () => null
  } }))
  return { create, field, project, disabled, changed }
}
beforeEach(() => {
  vi.resetAllMocks()
  api.createWorkItem.mockResolvedValue(created())
  api.transitionWorkItem.mockResolvedValue({ ...created(), statusCode: 'DONE' })
})

describe('分组末尾新增', () => {
  it.each<[GroupField, string, Partial<WorkItemCreateRequest>]>([
    ['ASSIGNEE', 'user-2', { assigneeUserId: 'user-2' }], ['ASSIGNEE', EMPTY_GROUP, { assigneeUserId: null }],
    ['PRIORITY', 'HIGH', { priority: 'HIGH' }], ['PRIORITY', EMPTY_GROUP, { priority: null }],
    ['CONTENT', 'content-2', { contentId: 'content-2' }],
  ])('%s / %s 创建时写入所在分组字段', async (field, key, expected) => {
    const { create, changed } = harness(field), target = group(key)
    create.open(target); create.draft(target).title = ' 分组新工作项 '
    await create.save(target)
    expect(api.createWorkItem).toHaveBeenCalledWith(expect.objectContaining({ projectId: 'project-1',
      workItemCreateRequest: expect.objectContaining({ title: '分组新工作项', ...expected }) }))
    expect(api.transitionWorkItem).not.toHaveBeenCalled()
    expect(changed).toHaveBeenCalledOnce()
  })
  it.each(['2026-12-31', '2027-01-03'])('日期分组在 %s 新增后仍属于原分组，空值保持为空', async today => {
    const { create } = harness('DUE_DATE')
    for (const target of dateGroups(today).filter(value => !(value.from && value.to && value.from > value.to))) {
      create.open(target); create.draft(target).title = target.label
      await create.save(target)
      const body = api.createWorkItem.mock.lastCall![0].workItemCreateRequest
      expect(itemGroupKey(body, 'DUE_DATE', today)).toBe(target.key)
    }
  })
  it('状态归组失败后保留已创建记录，重试只迁移状态', async () => {
    api.transitionWorkItem.mockRejectedValueOnce(new TypeError('offline'))
    const { create, changed } = harness('STATUS'), target = group('DONE')
    create.open(target); create.draft(target).title = '已完成的新项'
    await create.save(target)
    expect(create.draft(target).error).toBeDefined()
    expect(create.draft(target).created?.id).toBe('new')
    expect(changed).not.toHaveBeenCalled()
    await create.save(target)
    expect(api.createWorkItem).toHaveBeenCalledOnce()
    expect(api.transitionWorkItem.mock.calls[0]).toEqual(api.transitionWorkItem.mock.calls[1])
    expect(api.transitionWorkItem).toHaveBeenLastCalledWith(expect.objectContaining({ workItemId: 'new', ifMatch: '"1"',
      workItemTransitionRequest: { toStatus: 'DONE', resolution: null } }))
    expect(create.draft(target).open).toBe(false)
    expect(changed).toHaveBeenCalledOnce()
  })
  it('不同组草稿、连续新增与字段切换互不干扰，项目切换清空草稿', async () => {
    const { create, field, project } = harness(), a = group('a'), b = group('b')
    create.open(a); create.draft(a).title = 'A草稿'
    create.open(b); create.draft(b).title = 'B草稿'
    field.value = 'PRIORITY'; expect(create.draft(a).title).toBe('')
    field.value = 'ASSIGNEE'; expect(create.draft(a).title).toBe('A草稿')
    await create.save(b, true)
    expect(create.draft(a).title).toBe('A草稿')
    expect(create.draft(b)).toMatchObject({ title: '', open: true })
    project.value = 'project-2'; await flushPromises()
    expect(create.draft(a).title).toBe('')
  })
  it('创建响应丢失时重试复用原请求和幂等键，禁用分组不发送请求', async () => {
    const { create, disabled } = harness(), target = group('a')
    create.open(target); create.draft(target).title = '网络重试'
    api.createWorkItem.mockRejectedValueOnce(new TypeError('offline'))
    await create.save(target)
    await create.save(target)
    expect(api.createWorkItem.mock.calls[0]).toEqual(api.createWorkItem.mock.calls[1])
    disabled.value = '已停用'; create.draft(target).title = '不可创建'
    await create.save(target)
    expect(api.createWorkItem).toHaveBeenCalledTimes(2)
  })
})
