import { ListProjectWorkItemFilterOptionsFieldEnum } from '@yumpoo/api-client'
import { expect, it, vi } from 'vitest'
import { workItemsApi } from '../../api/client'
import { myWorkTableSource } from './myWorkTableSource'

vi.mock('../../api/client', () => ({
  workItemsApi: {
    listProjectWorkItems: vi.fn().mockResolvedValue({
      items: [
        { id: 'parent', statusCode: 'BACKLOG', assignees: [{ userId: 'other' }], subitemCount: 3, matchedSubitemCount: 1 },
        { id: 'mine', statusCode: 'BACKLOG', assignees: [{ userId: 'other' }, { userId: 'me' }], matchedSubitemCount: 0 },
        { id: 'completed-parent', statusCode: 'DONE', assignees: [{ userId: 'me' }], matchedSubitemCount: 1 },
      ],
      nextCursor: null,
    }),
    listProjectWorkItemFilterOptions: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    listWorkItemSubitems: vi.fn().mockResolvedValue({ items: [] }),
  },
}))

it('三种读取都按本人筛选，只有子项命中的父项显示上下文及命中子项数', async () => {
  const source = myWorkTableSource('me')
  const assigneeUserId = new Set(['me'])
  const status = new Set(['BACKLOG'])
  await source.listProjectWorkItems({ projectId: 'project', status })
  await source.listProjectWorkItemFilterOptions({ projectId: 'project', field: ListProjectWorkItemFilterOptionsFieldEnum.Status })
  await source.listWorkItemSubitems({ parentWorkItemId: 'parent' })

  expect(workItemsApi.listProjectWorkItems).toHaveBeenCalledWith({ projectId: 'project', assigneeUserId, status }, undefined)
  expect(workItemsApi.listProjectWorkItemFilterOptions).toHaveBeenCalledWith({
    projectId: 'project', field: ListProjectWorkItemFilterOptionsFieldEnum.Status, assigneeUserId,
  }, undefined)
  expect(workItemsApi.listWorkItemSubitems).toHaveBeenCalledWith({ parentWorkItemId: 'parent', assigneeUserId }, undefined)
  expect(source.isContextRow?.('parent')).toBe(true)
  expect(source.isContextRow?.('mine')).toBe(false)
  expect(source.isContextRow?.('completed-parent')).toBe(true)
  expect(source.subitemCount?.('parent')).toBe(1)
})
