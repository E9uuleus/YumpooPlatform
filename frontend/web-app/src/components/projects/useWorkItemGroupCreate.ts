import { readCsrfToken, type WorkItemCreateRequest, type WorkItemDetail } from '@yumpoo/api-client'
import { ElMessage, ElMessageBox } from 'element-plus'
import { nextTick, reactive, watch } from 'vue'
import { workItemsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import { EMPTY_GROUP, type GroupField, type WorkItemGroup } from './workItemGrouping'

interface GroupCreateDraft {
  open: boolean
  title: string
  saving: boolean
  error?: ApiProblem | undefined
  created?: WorkItemDetail | undefined
  request?: { body: WorkItemCreateRequest; key: string } | undefined
  transition?: { key: string; resolution: string | null } | undefined
}

export function groupCreateFields(field: GroupField, group: WorkItemGroup): Partial<WorkItemCreateRequest> {
  const value = group.key === EMPTY_GROUP ? null : group.key
  switch (field) {
    case 'ASSIGNEE': return { assigneeUserId: value }
    case 'PRIORITY': return { priority: value }
    case 'CONTENT': return { contentId: group.key }
    case 'DUE_DATE': return { dueDate: value ? new Date(`${group.from ?? group.to}T00:00:00Z`) : null }
    case 'STATUS': return {}
  }
}

export function useWorkItemGroupCreate(options: {
  projectId: () => string
  field: () => GroupField | ''
  contentId: () => string | undefined
  disabledReason: (group: WorkItemGroup) => string
  changed: () => Promise<void>
}) {
  const drafts = reactive<Record<string, GroupCreateDraft>>({})
  const inputs = new Map<string, { focus: () => void }>()
  const keyOf = (group: WorkItemGroup) => `${options.field()}:${group.key}`
  const draft = (group: WorkItemGroup): GroupCreateDraft => drafts[keyOf(group)] ??= { open: false, title: '', saving: false }
  const focus = async (key: string) => { await nextTick(); inputs.get(key)?.focus() }
  function setInput(group: WorkItemGroup, input: unknown): void {
    if (input && typeof (input as { focus?: unknown }).focus === 'function') inputs.set(keyOf(group), input as { focus: () => void })
    else inputs.delete(keyOf(group))
  }
  function open(group: WorkItemGroup): void {
    if (options.disabledReason(group)) return
    draft(group).open = true
    void focus(keyOf(group))
  }
  function cancel(group: WorkItemGroup): void {
    const state = draft(group)
    if (state.saving || state.created || state.request) return
    state.open = false
    state.title = ''
    state.error = undefined
  }
  async function save(group: WorkItemGroup, continueAdding = false): Promise<void> {
    const state = draft(group), field = options.field(), projectId = options.projectId(), key = keyOf(group)
    if (state.saving || !state.title.trim() || !field || options.disabledReason(group)) return
    const csrf = readCsrfToken()
    if (!csrf) { state.error = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
    state.saving = true
    state.error = undefined
    try {
      state.request ??= { key: globalThis.crypto.randomUUID(), body: {
        contentId: options.contentId()!, title: state.title.trim(), priority: null, assigneeUserId: null,
        description: null, notes: null, timelineStartDate: null, timelineEndDate: null, dueDate: null,
        ...groupCreateFields(field, group),
      } }
      state.created ??= await workItemsApi.createWorkItem({ projectId, xXSRFTOKEN: csrf,
        idempotencyKey: state.request.key, workItemCreateRequest: state.request.body })
      if (field === 'STATUS' && state.created.statusCode !== group.key) {
        const transition = state.created.capabilities.availableTransitions.find(option => option.toStatus === group.key)
        if (!state.created.capabilities.canMoveInKanban || !transition) throw localProblem('工作项已创建，但当前无法移入此状态。请重试归组。')
        let resolution = state.transition?.resolution ?? null
        if (!state.transition && transition.requiresResolution) {
          const answer = await ElMessageBox.prompt('该状态迁移需要填写说明。', `迁移到${transition.displayName}`, {
            inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '请输入迁移说明',
            confirmButtonText: '确认迁移', cancelButtonText: '取消',
          })
          resolution = answer.value.trim()
        }
        state.transition ??= { key: globalThis.crypto.randomUUID(), resolution }
        state.created = await workItemsApi.transitionWorkItem({ workItemId: state.created.id, xXSRFTOKEN: csrf,
          ifMatch: state.created.etag, idempotencyKey: state.transition.key,
          workItemTransitionRequest: { toStatus: group.key, resolution: state.transition.resolution } })
      }
      ElMessage.success(`已创建 ${state.created.itemNo}`)
      state.created = undefined
      state.request = undefined
      state.transition = undefined
      state.title = ''
      state.open = continueAdding
      if (options.projectId() === projectId) await options.changed()
    } catch (reason) {
      state.error = state.created ? localProblem('工作项已创建，归组未完成。点击重试继续归组，不会重复创建。') : await toApiProblem(reason)
      if (!state.created && state.error.kind === 'response' && state.error.status >= 400 && state.error.status < 500 && state.error.status !== 408)
        state.request = undefined
    } finally {
      state.saving = false
      if (options.projectId() === projectId && state.open) void focus(key)
    }
  }
  function keydown(group: WorkItemGroup, rawEvent: Event | KeyboardEvent): void {
    const event = rawEvent as KeyboardEvent
    if (event.isComposing || event.keyCode === 229) return
    if (event.key === 'Escape') cancel(group)
    if (event.key === 'Enter') { event.preventDefault(); void save(group, event.shiftKey) }
  }
  watch(options.projectId, () => { Object.keys(drafts).forEach(key => delete drafts[key]); inputs.clear() })
  return { draft, setInput, open, cancel, save, keydown }
}
