import {
  ContentSortDirection,
  ContentSortField,
  WorkItemPriority,
} from '@yumpoo/api-client'
import { describe, expect, it } from 'vitest'
import {
  encodeTableQuery,
  hasCustomTableQuery,
  parseTableQuery,
  sharedTableQuery,
  withoutTableQuery,
} from './contentTableQuery'

describe('M2-13 Content Table 查询 URL 契约', () => {
  it('完整编码临时查询并可从前进后退路由状态恢复', () => {
    const state = {
      filters: {
        query: '发布计划', statusCodes: new Set(['BACKLOG', 'READY']),
        priorities: new Set([WorkItemPriority.High]), assigneeUserIds: new Set(['user-1']),
        dueFrom: new Date('2026-08-20T00:00:00.000Z'),
        dueTo: new Date('2026-08-31T00:00:00.000Z'),
        updatedAfter: new Date('2026-08-22T01:02:03.000Z'),
      },
      sort: [
        { field: ContentSortField.Status, direction: ContentSortDirection.Asc },
        { field: ContentSortField.Priority, direction: ContentSortDirection.Desc },
      ],
    }
    const encoded = encodeTableQuery(state, { tab: 'items' })
    expect(encoded).toEqual({
      tab: 'items', custom: '1', q: '发布计划', status: ['BACKLOG', 'READY'],
      priority: ['HIGH'], assigneeUserId: ['user-1'], dueFrom: '2026-08-20',
      dueTo: '2026-08-31', updatedAfter: '2026-08-22T01:02:03.000Z',
      sort: ['STATUS,ASC', 'PRIORITY,DESC'],
    })
    expect(hasCustomTableQuery(encoded as never)).toBe(true)
    expect(parseTableQuery(encoded as never)).toEqual(state)
    expect(withoutTableQuery(encoded as never)).toEqual({ tab: 'items' })
  })

  it('共享默认克隆 Set、Date 与排序，临时修改不污染 Content 配置', () => {
    const filters = {
      query: null, statusCodes: new Set(['BACKLOG']), priorities: new Set<WorkItemPriority>(),
      assigneeUserIds: new Set<string>(), dueFrom: new Date('2026-08-20T00:00:00.000Z'),
      dueTo: null, updatedAfter: null,
    }
    const sort = [{ field: ContentSortField.ItemNo, direction: ContentSortDirection.Desc }]
    const cloned = sharedTableQuery(filters, sort)
    cloned.filters.statusCodes.add('DONE')
    cloned.filters.dueFrom?.setUTCDate(21)
    cloned.sort[0]!.direction = ContentSortDirection.Asc
    expect(Array.from(filters.statusCodes)).toEqual(['BACKLOG'])
    expect(filters.dueFrom.toISOString()).toContain('2026-08-20')
    expect(sort[0]!.direction).toBe(ContentSortDirection.Desc)
  })
})
