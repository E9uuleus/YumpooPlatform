import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { readCsrfToken, WorkItemStatusCategory, type ProjectWorkItemListItem, type WorkItemDetail, type WorkItemLabelColorToken } from '@yumpoo/api-client'
import { workItemsApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'

interface Options {
  contextId: () => string
  items: () => ProjectWorkItemListItem[]
  canCreate: () => boolean
  content: () => { id: string; name: string; colorToken: WorkItemLabelColorToken } | undefined
  status: () => { statusCode: string; statusCategory: string } | undefined
  parentId?: () => string
  created: (item: WorkItemDetail, anchorId: string) => void
}

export function useWorkItemInlineCreate(options: Options) {
  const draft = ref<{ row: ProjectWorkItemListItem; anchorId: string; requestKey: string; parentId: string | undefined }>()
  const saving = ref(false)
  const rows = computed(() => {
    const result = [...options.items()]
    if (draft.value) {
      const anchor = result.findIndex(item => item.id === draft.value!.anchorId)
      result.splice(anchor < 0 ? result.length : anchor + 1, 0, draft.value.row)
    }
    return result
  })
  const isDraft = (item: ProjectWorkItemListItem) => item.id === draft.value?.row.id

  function start(anchor: ProjectWorkItemListItem): void {
    const content = options.content()
    if (!options.canCreate() || !content || draft.value || saving.value) return
    const status = options.status()
    draft.value = {
      anchorId: anchor.id, requestKey: crypto.randomUUID(), parentId: options.parentId?.(),
      row: {
        id: `draft-${crypto.randomUUID()}`, projectId: anchor.projectId,
        title: '', itemNo: '', contentId: content.id, contentName: content.name, contentColorToken: content.colorToken,
        statusCode: status?.statusCode ?? '', statusCategory: (status?.statusCategory as WorkItemStatusCategory | undefined) ?? WorkItemStatusCategory.Todo,
        priority: null, assigneeUserId: null, assigneeDisplayName: null, dueDate: null, dueTime: null,
        subitemCount: 0, discussionCount: 0, rowVersion: 0, etag: '', updatedAt: new Date(),
        capabilities: { canEditFields: true, canMoveInProjectOrder: false, canMoveInKanban: false,
          canDiscuss: false, canDelete: false, canRestore: false, availableTransitions: [] },
      },
    }
  }

  function cancel(): void { if (!saving.value) draft.value = undefined }
  watch(options.contextId, () => { draft.value = undefined })

  async function save(title: string): Promise<boolean> {
    const current = draft.value
    if (!current || saving.value) return false
    saving.value = true
    try {
      const token = readCsrfToken()
      if (!token) throw new Error('缺少 CSRF 凭据，请刷新后重试。')
      const body = { contentId: current.row.contentId, title, priority: null, assigneeUserId: null,
        description: null, notes: null, timelineStartDate: null, timelineEndDate: null, dueDate: null }
      const parentId = current.parentId
      const common = { xXSRFTOKEN: token, idempotencyKey: current.requestKey }
      let created = parentId
        ? await workItemsApi.createWorkItemSubitem({ ...common, parentWorkItemId: parentId, workItemSubitemCreateRequest: body })
        : await workItemsApi.createWorkItem({ ...common, projectId: current.row.projectId, workItemCreateRequest: body })
      try {
        const move = { xXSRFTOKEN: token, ifMatch: created.etag, idempotencyKey: crypto.randomUUID(),
          projectWorkItemOrderMoveRequest: { previousVisibleWorkItemId: current.anchorId, nextVisibleWorkItemId: null } }
        created = parentId
          ? await workItemsApi.moveWorkItemSubitemOrder({ ...move, parentWorkItemId: parentId, subitemId: created.id })
          : await workItemsApi.moveProjectWorkItemOrder({ ...move, projectId: current.row.projectId, workItemId: created.id })
      } catch (reason) {
        ElMessage.warning(`已创建 ${created.itemNo}，但放置到指定位置失败：${problemMessage(await toApiProblem(reason))}`)
      }
      if (draft.value === current) {
        draft.value = undefined
        options.created(created, current.anchorId)
      }
      return true
    } catch (reason) {
      ElMessage.error(problemMessage(await toApiProblem(reason)))
      return false
    } finally { saving.value = false }
  }

  return { draft, rows, isDraft, start, save, cancel }
}
