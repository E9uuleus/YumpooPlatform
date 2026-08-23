import { ContentSortDirection, ContentSortField, type WorkItemPriority } from '@yumpoo/api-client'
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ContentTableQueryEditor from './ContentTableQueryEditor.vue'
import type { ContentTableQuery } from './contentTableQuery'

const modelValue: ContentTableQuery = {
  filters: { query: null, statusCodes: new Set<string>(), priorities: new Set<WorkItemPriority>(),
    assigneeUserIds: new Set<string>(), dueFrom: null, dueTo: null, updatedAfter: null },
  sort: [{ field: ContentSortField.ItemNo, direction: ContentSortDirection.Desc }],
}

describe('M2-13 Table 查询编辑器', () => {
  afterEach(() => vi.useRealTimers())

  it('连续搜索防抖 300ms 且只提交最后输入', async () => {
    vi.useFakeTimers()
    const wrapper = mount(ContentTableQueryEditor, {
      props: { modelValue, statuses: [], members: [] },
    })
    const input = wrapper.find('input[aria-label="工作项标题关键字"]')
    await input.setValue('第一个')
    await vi.advanceTimersByTimeAsync(200)
    await input.setValue('最终条件')
    await vi.advanceTimersByTimeAsync(299)
    expect(wrapper.emitted('search')).toBeUndefined()
    await vi.advanceTimersByTimeAsync(1)
    expect(wrapper.emitted('search')).toHaveLength(1)
    expect(wrapper.emitted('search')?.[0]?.[0]).toMatchObject({
      filters: { query: '最终条件' },
    })
    wrapper.unmount()
  })
})
