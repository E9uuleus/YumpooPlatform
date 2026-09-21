import { ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { readCsrfToken, type ProjectWorkItemListItem, type WorkItemDetail } from '@yumpoo/api-client'
import { workItemsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'

export type WorkItemPatchField = 'assignee' | 'priority' | 'dueDate' | 'content'
export function useWorkItemEdits(options: {
  updated: (id: string, detail: WorkItemDetail) => void
  failed: (problem: ApiProblem) => void | Promise<void>
}) {
  const busy = ref('')
  const attempts = new Map<string, string>()
  async function execute(item: ProjectWorkItemListItem, identity: unknown[], command: (common: {
    workItemId: string; xXSRFTOKEN: string; ifMatch: string; idempotencyKey: string
  }) => Promise<WorkItemDetail>): Promise<boolean> {
    if (busy.value || !item.capabilities.canEditFields) return false
    const csrf = readCsrfToken()
    if (!csrf) { await options.failed(localProblem('缺少 CSRF 凭据，请刷新后重试。')); return false }
    const signature = JSON.stringify([item.id, item.etag, ...identity])
    const key = attempts.get(signature) || crypto.randomUUID()
    attempts.set(signature, key); busy.value = item.id
    try {
      const result = await command({ workItemId: item.id, xXSRFTOKEN: csrf, ifMatch: item.etag, idempotencyKey: key })
      attempts.delete(signature); options.updated(item.id, result)
      return true
    } catch (reason) {
      const problem = await toApiProblem(reason)
      if (problem.kind === 'response' && [400, 403, 404, 412, 422].includes(problem.status)) attempts.delete(signature)
      await options.failed(problem)
      return false
    } finally { busy.value = '' }
  }
  function patch(item: ProjectWorkItemListItem, field: WorkItemPatchField, value: string | Date | null, dueTime?: string | null) {
    return execute(item, [field, value, dueTime], common => field === 'content'
      ? workItemsApi.patchWorkItemContent({ ...common, workItemContentPatchRequest: { contentId: value as string } })
      : field === 'assignee'
        ? workItemsApi.patchWorkItemAssignee({ ...common, workItemAssigneePatchRequest: { assigneeUserId: value as string | null } })
        : field === 'priority'
          ? workItemsApi.patchWorkItemPriority({ ...common, workItemPriorityPatchRequest: { priority: value as string | null } })
          : workItemsApi.patchWorkItemDueDate({ ...common, workItemDueDatePatchRequest: { dueDate: value as Date | null, ...(dueTime !== undefined ? { dueTime } : {}) } }))
  }
  async function transition(item: ProjectWorkItemListItem, statusCode: string) {
    if (busy.value || item.statusCode === statusCode) return false
    const target = item.capabilities.availableTransitions.find(t => t.toStatus === statusCode)
    if (!target) return false
    let resolution: string | null = null
    if (target.requiresResolution) {
      try {
        const answer = await ElMessageBox.prompt('该状态迁移需要填写说明。', `迁移到${target.displayName}`, {
          inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '请输入迁移说明',
          confirmButtonText: '确认迁移', cancelButtonText: '取消',
        })
        resolution = answer.value.trim()
      } catch { return false }
    }
    return execute(item, ['status', statusCode, resolution], common => workItemsApi.transitionWorkItem({
      ...common, workItemTransitionRequest: { toStatus: statusCode, resolution },
    }))
  }
  return { busy, patch, transition }
}
