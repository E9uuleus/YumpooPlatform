import { ref } from 'vue'
import type { ProjectWorkItemListItem } from '@yumpoo/api-client'
import { workItemsApi } from '../../api/client'
import { workItemAssignees } from '../projects/workItemAssignees'
import type { WorkItemTableSource } from '../projects/workItemTableSource'

/**
 * 我的工作：主表、分组选项与子项都只读取分配给本人的工作项；未完成条件由表格的 myWork 模式追加。
 * 只因命中子项而返回的父项标记为上下文；子项入口显示范围内数量，真实 subitemCount 保留给结构操作。
 */
export function myWorkTableSource(userId: string): WorkItemTableSource {
  const assignee = new Set([userId])
  const contextIds = ref(new Set<string>())
  const matchedCounts = ref(new Map<string, number>())
  function remember(items: ProjectWorkItemListItem[]): void {
    const context = new Set(contextIds.value)
    const counts = new Map(matchedCounts.value)
    for (const item of items) {
      if (workItemAssignees(item).some(person => person.userId === userId)) context.delete(item.id)
      else context.add(item.id)
      counts.set(item.id, item.matchedSubitemCount ?? 0)
    }
    contextIds.value = context
    matchedCounts.value = counts
  }
  return {
    listProjectWorkItems: async (request, init) => {
      const page = await workItemsApi.listProjectWorkItems({ ...request, assigneeUserId: assignee }, init)
      remember(page.items)
      return page
    },
    listProjectWorkItemFilterOptions: (request, init) =>
      workItemsApi.listProjectWorkItemFilterOptions({ ...request, assigneeUserId: assignee }, init),
    listWorkItemSubitems: (request, init) => workItemsApi.listWorkItemSubitems({ ...request, assigneeUserId: assignee }, init),
    isContextRow: id => contextIds.value.has(id),
    subitemCount: id => matchedCounts.value.get(id) ?? 0,
  }
}
