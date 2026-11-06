import { ListProjectWorkItemFilterOptionsFieldEnum } from '@yumpoo/api-client'
import { expect, it, vi } from 'vitest'
import { workItemsApi } from '../../api/client'
import { myWorkTableSource } from './myWorkTableSource'

vi.mock('../../api/client', () => ({
  workItemsApi: {
    listProjectWorkItems: vi.fn().mockResolvedValue({
      items: [
        { id: 'parent', assignees: [{ userId: 'other' }], subitemCount: 3, matchedSubitemCount: 1 },
        { id: 'mine', assignees: [{ userId: 'other' }, { userId: 'me' }], matchedSubitemCount: 0 },
      ],
      nextCursor: null,
    }),
    listProjectWorkItemFilterOptions: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    listWorkItemSubitems: vi.fn().mockResolvedValue({ items: [] }),
  },
}))

it('三种读取都按本人筛选，非本人父项显示上下文及命中子项数', async () => {
  const source = myWorkTableSource('me')
  const assigneeUserId = new Set(['me'])
  await source.listProjectWorkItems({ projectId: 'project' })
  await source.listProjectWorkItemFilterOptions({ projectId: 'project', field: ListProjectWorkItemFilterOptionsFieldEnum.Status })
  await source.listWorkItemSubitems({ parentWorkItemId: 'parent' })

  expect(workItemsApi.listProjectWorkItems).toHaveBeenCalledWith({ projectId: 'project', assigneeUserId }, undefined)
  expect(workItemsApi.listProjectWorkItemFilterOptions).toHaveBeenCalledWith({
    projectId: 'project', field: ListProjectWorkItemFilterOptionsFieldEnum.Status, assigneeUserId,
  }, undefined)
  expect(workItemsApi.listWorkItemSubitems).toHaveBeenCalledWith({ parentWorkItemId: 'parent', assigneeUserId }, undefined)
  expect(source.isContextRow?.('parent')).toBe(true)
  expect(source.isContextRow?.('mine')).toBe(false)
  expect(source.subitemCount?.('parent')).toBe(1)
})
