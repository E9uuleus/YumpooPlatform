import type { WorkItemDetail } from '@yumpoo/api-client'
import { ElMessage, ElMessageBox } from 'element-plus'
import { workItemsApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'

/** 新建接口固定从初始状态开始，默认状态在创建成功后迁移；失败或跳过只提示，不回滚已创建的工作项。 */
export async function applyDefaultStatus(created: WorkItemDetail, statusCode: string | null, csrf: string): Promise<WorkItemDetail> {
  if (!statusCode || created.statusCode === statusCode) return created
  const transition = created.capabilities.availableTransitions.find(option => option.toStatus === statusCode)
  if (!created.capabilities.canMoveInKanban || !transition) {
    ElMessage.warning(`已创建 ${created.itemNo}，但当前无法迁移到默认状态。`)
    return created
  }
  let resolution: string | null = null
  if (transition.requiresResolution) {
    try {
      const answer = await ElMessageBox.prompt('该状态迁移需要填写说明。', `迁移到${transition.displayName}`, {
        inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '请输入迁移说明',
        confirmButtonText: '确认迁移', cancelButtonText: '跳过',
      })
      resolution = answer.value.trim()
    } catch {
      ElMessage.info(`已创建 ${created.itemNo}，未设置默认状态。`)
      return created
    }
  }
  try {
    return await workItemsApi.transitionWorkItem({
      workItemId: created.id, xXSRFTOKEN: csrf, ifMatch: created.etag, idempotencyKey: globalThis.crypto.randomUUID(),
      workItemTransitionRequest: { toStatus: statusCode, resolution },
    })
  } catch (reason) {
    ElMessage.warning(`已创建 ${created.itemNo}，但设置默认状态失败：${problemMessage(await toApiProblem(reason))}`)
    return created
  }
}
