import { describe, expect, it } from 'vitest'
import type { ProjectWorkItemListItem } from '@yumpoo/api-client'
import { workItemOrderBlock } from './workItemOrder'
const rows = (ids: string[]) => ids.map(id => ({ id }) as ProjectWorkItemListItem)
describe('整块移动顺序', () => {
  it.each(['top', 'bottom'] as const)('%s 保持原来的相对顺序，所有已选项都排除于锚点', edge => {
    const block = workItemOrderBlock(rows(['a', 'anchor', 'b', 'c']), new Set(['c', 'b', 'a']), edge)
    const requests = block.items.map(item => { const request = block.position(); block.moved(item.id); return request })
    expect(block.items.map(item => item.id)).toEqual(['a', 'b', 'c'])
    expect(requests).toEqual(edge === 'top' ? [
      { previousVisibleWorkItemId: null, nextVisibleWorkItemId: 'anchor' },
      { previousVisibleWorkItemId: 'a', nextVisibleWorkItemId: 'anchor' },
      { previousVisibleWorkItemId: 'b', nextVisibleWorkItemId: 'anchor' },
    ] : [
      { previousVisibleWorkItemId: 'anchor', nextVisibleWorkItemId: null },
      { previousVisibleWorkItemId: 'a', nextVisibleWorkItemId: null },
      { previousVisibleWorkItemId: 'b', nextVisibleWorkItemId: null },
    ])
  })
  it('失败不推进锚点，全部选中时不发送无锚点移动', () => {
    const block = workItemOrderBlock(rows(['anchor', 'a', 'b']), new Set(['a', 'b']), 'top')
    expect(block.position()).toEqual(block.position())
    block.moved('b')
    expect(block.position().previousVisibleWorkItemId).toBe('b')
    expect(workItemOrderBlock(rows(['a', 'b']), new Set(['a', 'b']), 'bottom').hasAnchor).toBe(false)
  })
})
