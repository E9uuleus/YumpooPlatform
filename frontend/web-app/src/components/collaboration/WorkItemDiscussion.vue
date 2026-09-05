<script setup lang="ts">
import { useEditor } from '@tiptap/vue-3'
import {
  ErrorCode,
  WorkItemUpdateStatus,
  readCsrfToken,
  type ProjectMember,
  type WorkItemUpdate,
} from '@yumpoo/api-client'
import { ElButton, ElDialog, ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { workItemUpdatesApi } from '../../api/client'
import {
  isProblemCode,
  isProblemStatus,
  localProblem,
  toApiProblem,
  type ApiProblem,
} from '../../api/problems'
import InlineProblem from '../InlineProblem.vue'
import DiscussionComposer from './DiscussionComposer.vue'
import { discussionExtensions, discussionHasDraft } from './discussionEditor'
import DiscussionCommentCard from './DiscussionCommentCard.vue'
import DiscussionReplyComposer from './DiscussionReplyComposer.vue'
import { Refresh } from '@element-plus/icons-vue'
import { useSession } from '../../composables/useSession'

const props = defineProps<{
  workItemId: string
  members: ProjectMember[]
  canPublish: boolean
  readOnlyReason?: string | undefined
}>()

const items = ref<WorkItemUpdate[]>([])
const nextCursor = ref<string | null>(null)
const loading = ref(false)
const publishing = ref(false)
const problem = ref<ApiProblem>()
const editProblem = ref<ApiProblem>()
const timeline = ref<HTMLElement>()
const sentinel = ref<HTMLElement>()
const composer = ref<InstanceType<typeof DiscussionComposer>>()
const editComposer = ref<InstanceType<typeof DiscussionComposer>>()
const loadingOlder = ref(false)
const loadProblem = ref<ApiProblem>()
const olderProblem = ref<ApiProblem>()
const draftHtml = ref('')
const editHtml = ref('')
const editOriginalHtml = ref('')
let generation = 0
let observer: IntersectionObserver | undefined
let resizeObserver: ResizeObserver | undefined
let scrollRoot: HTMLElement | undefined
let loadController: AbortController | undefined
let olderController: AbortController | undefined
let frame: number | undefined
let disposed = false
const draftText = ref('')
const editDraftText = ref('')
const editingItemId = ref<string>()
const editDialogVisible = ref(false)
const savingEdit = ref(false)
const mutatingId = ref<string>()
const session = useSession()
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'Asia/Shanghai')
const deletedVersions = new Map<string, number>()
const replyDrafts = reactive<Record<string, string>>({})
const replyBusy = reactive<Record<string, boolean>>({})
const replyOpen = reactive<Record<string, boolean>>({})
const repliesLoading = reactive<Record<string, boolean>>({})
const replyProblems = reactive<Record<string, ApiProblem | undefined>>({})
const replyComposers = new Map<string, InstanceType<typeof DiscussionReplyComposer>>()
const now = ref(new Date())
let publishKey = crypto.randomUUID()
let publishKeyBody = ''
let clock: ReturnType<typeof setInterval> | undefined

const editor = useEditor({
  editorProps: { attributes: { role: 'textbox', 'aria-label': '讨论正文', 'aria-multiline': 'true' } },
  content: '',
  editable: props.canPublish,
  extensions: discussionExtensions(() => props.members, () => composer.value?.closePanel()),
  onUpdate: ({ editor: current }) => {
    const html = current.getHTML()
    draftText.value = current.getText()
    draftHtml.value = html
    if (html !== publishKeyBody) {
      publishKey = crypto.randomUUID()
      publishKeyBody = html
    }
  },
})

const editEditor = useEditor({
  editorProps: { attributes: { role: 'textbox', 'aria-label': '编辑讨论正文', 'aria-multiline': 'true' } },
  content: '',
  editable: true,
  extensions: discussionExtensions(() => props.members, () => editComposer.value?.closePanel()),
  onUpdate: ({ editor: current }) => {
    editDraftText.value = current.getText()
    editHtml.value = current.getHTML()
  },
})

const hasEditDraft = computed(() => editDialogVisible.value && editHtml.value !== editOriginalHtml.value)
const hasDraft = computed(() => { void draftHtml.value; return discussionHasDraft(editor.value) || hasEditDraft.value || Object.values(replyDrafts).some(Boolean) })
const busy = computed(() => publishing.value || savingEdit.value || Boolean(mutatingId.value) || Object.values(replyBusy).some(Boolean))

watch(() => [props.canPublish, publishing.value], () => editor.value?.setEditable(props.canPublish && !publishing.value))
watch(savingEdit, value => editEditor.value?.setEditable(!value))

function mergeUpdates(incoming: WorkItemUpdate[]): void {
  const merged = new Map(items.value.map(item => [item.id, item]))
  incoming.forEach(item => {
    if (item.status === WorkItemUpdateStatus.Deleted) deletedVersions.set(item.id, item.rowVersion)
    else if ((deletedVersions.get(item.id) ?? -1) >= item.rowVersion) return
    if (item.parentUpdateId) {
      const parent = merged.get(item.parentUpdateId)
      if (!parent) return
      const existing = parent.replies.find(reply => reply.id === item.id)
      if (existing && existing.rowVersion > item.rowVersion) return
      const replies = parent.replies.filter(reply => reply.id !== item.id)
      if (item.status !== WorkItemUpdateStatus.Deleted) replies.push(item)
      replies.sort((a, b) => a.createdAt.getTime() - b.createdAt.getTime() || a.id.localeCompare(b.id))
      merged.set(parent.id, { ...parent, replies,
        replyCount: Math.max(0, parent.replyCount + (item.status === WorkItemUpdateStatus.Deleted ? (existing ? -1 : 0) : (existing ? 0 : 1))) })
    } else {
      const previous = merged.get(item.id)
      if (previous && previous.rowVersion > item.rowVersion) return
      if (item.status === WorkItemUpdateStatus.Deleted) { merged.delete(item.id); delete replyDrafts[item.id]; delete replyOpen[item.id] }
      else merged.set(item.id, { ...item, replies: item.replies.filter(reply =>
        (deletedVersions.get(reply.id) ?? -1) < reply.rowVersion) })
    }
  })
  items.value = Array.from(merged.values()).sort((a, b) =>
    Number(Boolean(b.pinnedAt)) - Number(Boolean(a.pinnedAt))
    || (a.pinnedAt && b.pinnedAt ? b.pinnedAt.getTime() - a.pinnedAt.getTime() : 0)
    || b.createdAt.getTime() - a.createdAt.getTime() || b.id.localeCompare(a.id))
}

async function openReply(item: WorkItemUpdate) {
  replyOpen[item.id] = true
  await nextTick()
  replyComposers.get(item.id)?.focus()
}

async function loadReplies(item: WorkItemUpdate) {
  if (loading.value || !item.repliesNextCursor || repliesLoading[item.id]) return
  const current = generation
  const cursor = item.repliesNextCursor
  repliesLoading[item.id] = true
  replyProblems[item.id] = undefined
  try {
    const page = await workItemUpdatesApi.listWorkItemUpdateReplies({ updateId: item.id, cursor, size: 20 })
    if (current !== generation || disposed) return
    const parent = items.value.find(row => row.id === item.id)
    if (!parent) return
    const replies = new Map(parent.replies.map(reply => [reply.id, reply]))
    page.items.forEach(reply => {
      if ((deletedVersions.get(reply.id) ?? -1) >= reply.rowVersion) return
      const previous = replies.get(reply.id)
      if (!previous || previous.rowVersion <= reply.rowVersion) replies.set(reply.id, reply)
    })
    mergeUpdates([{ ...parent, replies: [...replies.values()].sort((a, b) => a.createdAt.getTime() - b.createdAt.getTime() || a.id.localeCompare(b.id)), repliesNextCursor: page.nextCursor }])
    if (page.nextCursor === cursor) replyProblems[item.id] = localProblem('回复分页未前进，请刷新后重试。')
  } catch (reason) { if (current === generation && !disposed) replyProblems[item.id] = await toApiProblem(reason) }
  finally { if (current === generation && !disposed) repliesLoading[item.id] = false }
}

async function pinUpdate(item: WorkItemUpdate) {
  if (busy.value || loading.value || !item.capabilities.canPin) return
  const csrf = readCsrfToken()
  if (!csrf) { problem.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  const current = generation
  mutatingId.value = item.id
  problem.value = undefined
  let success = false
  try {
    const updated = await workItemUpdatesApi.pinWorkItemUpdate({ updateId: item.id, xXSRFTOKEN: csrf,
      ifMatch: item.etag, workItemUpdatePinRequest: { pinned: !item.pinnedAt } })
    if (current !== generation || disposed) return
    mergeUpdates([updated]); success = true
  } catch (reason) { await handleMutationProblem(reason, item.id, false, current) }
  finally { if (current === generation && !disposed) mutatingId.value = undefined }
  if (success && current === generation && !disposed) await loadLatest(false)
}

function scrollToTop(): void { if (scrollRoot) scrollRoot.scrollTop = 0 }

function queueFill(): void {
  if (disposed || frame !== undefined || !scrollRoot) return
  frame = requestAnimationFrame(() => {
    frame = undefined
    if (!sentinel.value || !scrollRoot || scrollRoot.clientHeight <= 0) return
    const rect = sentinel.value.getBoundingClientRect()
    const rootRect = scrollRoot.getBoundingClientRect()
    if (rect.top <= rootRect.bottom && rect.bottom >= rootRect.top) void loadOlder()
  })
}

async function loadLatest(resetScroll = true): Promise<void> {
  if (busy.value || editDialogVisible.value || disposed) return
  const current = ++generation
  loadController?.abort()
  olderController?.abort()
  loadController = new AbortController()
  loading.value = true
  loadingOlder.value = false
  Object.keys(repliesLoading).forEach(id => { delete repliesLoading[id] })
  Object.keys(replyProblems).forEach(id => { delete replyProblems[id] })
  loadProblem.value = undefined
  olderProblem.value = undefined
  try {
    const page = await workItemUpdatesApi.listWorkItemUpdates({ workItemId: props.workItemId, size: 20 }, { signal: loadController.signal })
    if (current !== generation || disposed) return
    items.value = items.value.filter(item => Boolean(replyDrafts[item.id]))
    mergeUpdates([...page.items, ...(page.pinnedItems ?? [])])
    nextCursor.value = page.nextCursor
    await nextTick()
    if (resetScroll) scrollToTop()
  } catch (reason) {
    const failure = await toApiProblem(reason)
    if (current === generation && !disposed) loadProblem.value = failure
  } finally {
    if (current === generation && !disposed) { loading.value = false; queueFill() }
  }
}

async function loadOlder(retry = false): Promise<void> {
  if (disposed || !nextCursor.value || loading.value || loadingOlder.value || loadProblem.value || busy.value
    || (olderProblem.value && !retry)) return
  const current = generation
  const cursor = nextCursor.value
  olderController = new AbortController()
  loadingOlder.value = true
  olderProblem.value = undefined
  try {
    const page = await workItemUpdatesApi.listWorkItemUpdates({ workItemId: props.workItemId, cursor, size: 20 }, { signal: olderController.signal })
    if (current !== generation || disposed) return
    mergeUpdates(page.items)
    nextCursor.value = page.nextCursor
    if (page.nextCursor === cursor) olderProblem.value = localProblem('历史分页未前进，请刷新讨论后重试。')
    await nextTick()
  } catch (reason) {
    const failure = await toApiProblem(reason)
    if (current === generation && !disposed) olderProblem.value = failure
  } finally {
    if (current === generation && !disposed) { loadingOlder.value = false; queueFill() }
  }
}

function canEdit(item: WorkItemUpdate): boolean { return item.capabilities.canEdit && item.status !== WorkItemUpdateStatus.Deleted }

function startEdit(item: WorkItemUpdate): void {
  if (busy.value || loading.value || !canEdit(item) || !item.bodyHtml) return
  editingItemId.value = item.id
  editProblem.value = undefined
  editEditor.value?.commands.setContent(item.bodyHtml)
  editDraftText.value = editEditor.value?.getText() ?? ''
  editOriginalHtml.value = editEditor.value?.getHTML() ?? ''
  editHtml.value = editOriginalHtml.value
  editDialogVisible.value = true
}

function currentEditingItem(): WorkItemUpdate | undefined {
  return items.value.flatMap(item => [item, ...item.replies]).find(item => item.id === editingItemId.value)
}

async function refreshUpdate(updateId: string, current: number): Promise<WorkItemUpdate> {
  const fresh = await workItemUpdatesApi.getWorkItemUpdate({ updateId })
  if (current === generation && !disposed) mergeUpdates([fresh])
  return fresh
}

async function handleMutationProblem(reason: unknown, updateId: string, preserveEditDraft: boolean, current: number): Promise<void> {
  const apiProblem = await toApiProblem(reason)
  if (current !== generation || disposed) return
  const conflict = isProblemCode(apiProblem, ErrorCode.VersionConflict) || isProblemStatus(apiProblem, 412)
  const unavailable = isProblemStatus(apiProblem, 409)
  if (conflict || unavailable) {
    try {
      await refreshUpdate(updateId, current)
      if (current !== generation || disposed) return
    } catch (refreshReason) {
      const failure = await toApiProblem(refreshReason)
      if (current === generation && !disposed) problem.value = failure
      return
    }
    const message = conflict
      ? '讨论已被其他操作更新，已刷新当前版本；未提交的编辑草稿仍保留。'
      : '评论状态或权限已变化；未提交的编辑草稿仍保留，可复制后再处理。'
    if (preserveEditDraft) editProblem.value = localProblem(message)
    else problem.value = localProblem(message.replace('；未提交的编辑草稿仍保留', ''))
    return
  }
  if (preserveEditDraft) editProblem.value = apiProblem
  else problem.value = apiProblem
}

async function saveEdit(): Promise<void> {
  const item = currentEditingItem()
  if (!item || !editEditor.value || !editDraftText.value.trim() || savingEdit.value) return
  const current = generation
  const csrf = readCsrfToken()
  if (!csrf) {
    editProblem.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  savingEdit.value = true
  editProblem.value = undefined
  try {
    const updated = await workItemUpdatesApi.editWorkItemUpdate({
      updateId: item.id,
      xXSRFTOKEN: csrf,
      ifMatch: item.etag,
      workItemUpdateEditRequest: { bodyHtml: editEditor.value.getHTML() },
    })
    if (current !== generation || disposed) return
    mergeUpdates([updated])
    editDialogVisible.value = false
    ElMessage.success('讨论已更新')
  } catch (reason) {
    await handleMutationProblem(reason, item.id, true, current)
  } finally {
    if (current === generation && !disposed) savingEdit.value = false
  }
}

async function deleteUpdate(item: WorkItemUpdate): Promise<void> {
  const current = generation
  if (busy.value || loading.value) return
  const csrf = readCsrfToken()
  if (!csrf) {
    problem.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  mutatingId.value = item.id
  problem.value = undefined
  try {
    const deleted = await workItemUpdatesApi.deleteWorkItemUpdate({
      updateId: item.id,
      xXSRFTOKEN: csrf,
      ifMatch: item.etag,
      body: {},
    })
    if (current !== generation || disposed) return
    mergeUpdates([deleted])
    if (editingItemId.value === item.id) editDialogVisible.value = false
    ElMessage.success(item.parentUpdateId ? '回复已删除' : '讨论串已删除')
  } catch (mutationReason) {
    await handleMutationProblem(mutationReason, item.id, false, current)
  } finally {
    if (current === generation && !disposed) mutatingId.value = undefined
  }
}

async function confirmDelete(item: WorkItemUpdate): Promise<void> {
  const current = generation
  try {
    await ElMessageBox.confirm(item.parentUpdateId ? '删除这条回复后不可恢复。确定删除吗？' : '将删除这条评论及全部回复（包括其他人的回复），删除后不可恢复。确定删除吗？', '删除评论', {
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  if (current === generation && !disposed) await deleteUpdate(item)
}

async function publish(): Promise<void> {
  if (!editor.value || !props.canPublish || !draftText.value.trim() || publishing.value || loading.value) return
  const current = generation
  const csrf = readCsrfToken()
  if (!csrf) {
    problem.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  const bodyHtml = editor.value.getHTML()
  if (publishKeyBody !== bodyHtml) {
    publishKey = crypto.randomUUID()
    publishKeyBody = bodyHtml
  }
  publishing.value = true
  problem.value = undefined
  try {
    const published = await workItemUpdatesApi.publishWorkItemUpdate({
      workItemId: props.workItemId,
      xXSRFTOKEN: csrf,
      idempotencyKey: publishKey,
      workItemUpdateCreateRequest: { bodyHtml },
    })
    if (current !== generation || disposed) return
    mergeUpdates([published])
    editor.value.commands.clearContent(true)
    publishKey = crypto.randomUUID()
    publishKeyBody = editor.value.getHTML()
    await nextTick()
    composer.value?.reset()
    scrollToTop()
    ElMessage.success('讨论已发布')
  } catch (reason) {
    const failure = await toApiProblem(reason)
    if (current === generation && !disposed) problem.value = failure
  } finally {
    if (current === generation && !disposed) publishing.value = false
  }
}

function discardDraft(): void {
  editDialogVisible.value = false
  replyComposers.forEach(composer => composer.discard())
  Object.keys(replyDrafts).forEach(id => { delete replyDrafts[id] })
  editEditor.value?.commands.clearContent(true)
  editor.value?.commands.clearContent(true)
  composer.value?.reset()
  publishKey = crypto.randomUUID()
  publishKeyBody = editor.value?.getHTML() ?? ''
}

async function closeEdit(done: () => void): Promise<void> {
  if (savingEdit.value) return
  if (hasEditDraft.value) {
    try { await ElMessageBox.confirm('离开将丢弃未保存的编辑内容。', '放弃编辑', { confirmButtonText: '放弃编辑', cancelButtonText: '继续编辑' }) }
    catch { return }
  }
  done()
}

watch(() => props.workItemId, () => {
  generation++
  publishing.value = false
  savingEdit.value = false
  mutatingId.value = undefined
  items.value = []
  nextCursor.value = null
  replyComposers.clear()
  deletedVersions.clear()
  for (const state of [replyBusy, replyOpen, repliesLoading, replyProblems]) Object.keys(state).forEach(id => { delete state[id] })
  problem.value = undefined
  editProblem.value = undefined
  discardDraft()
  void loadLatest()
})
watch(busy, value => { if (!value) queueFill() })

defineExpose({ hasDraft, busy, discardDraft, editor, editEditor, saveEdit, loadLatest, loadOlder })
onMounted(() => {
  clock = setInterval(() => { now.value = new Date() }, 60_000)
  scrollRoot = timeline.value?.closest<HTMLElement>('.el-drawer__body') ?? undefined
  if (scrollRoot && typeof IntersectionObserver !== 'undefined' && sentinel.value) {
    observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) void loadOlder()
    }, { root: scrollRoot })
    observer.observe(sentinel.value)
  }
  if (scrollRoot && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(queueFill)
    resizeObserver.observe(scrollRoot)
    if (timeline.value) resizeObserver.observe(timeline.value)
  }
  scrollRoot?.addEventListener('scroll', queueFill, { passive: true })
  void loadLatest()
})
onBeforeUnmount(() => {
  disposed = true
  generation++
  loadController?.abort()
  olderController?.abort()
  observer?.disconnect()
  resizeObserver?.disconnect()
  scrollRoot?.removeEventListener('scroll', queueFill)
  if (frame !== undefined) cancelAnimationFrame(frame)
  if (clock) clearInterval(clock)
})
</script>

<template>
  <section
    class="discussion"
    aria-label="工作项讨论"
  >
    <p
      v-if="!canPublish"
      class="discussion__readonly"
    >
      {{ readOnlyReason ?? '当前角色仅可查看讨论。' }}
    </p>
    <discussion-composer
      v-else
      ref="composer"
      :editor="editor"
      :busy="publishing"
      :submit-disabled="!draftText.trim() || loading"
      @submit="publish"
    />
    <inline-problem
      v-if="problem"
      :problem="problem"
    />
    <div class="discussion__actions">
      <el-button
        text
        size="small"
        :loading="loading"
        :disabled="busy"
        aria-label="刷新评论"
        title="刷新评论"
        @click="loadLatest()"
      >
        <refresh class="discussion__refresh-icon" />
      </el-button>
    </div>
    <inline-problem
      v-if="loadProblem"
      :problem="loadProblem"
    />
    <el-button
      v-if="loadProblem"
      @click="loadLatest()"
    >
      重试加载讨论
    </el-button>
    <p
      v-if="loading && !items.length"
      class="discussion__state"
      role="status"
    >
      正在加载讨论…
    </p>
    <div
      ref="timeline"
      class="discussion__timeline"
      aria-live="polite"
    >
      <discussion-comment-card
        v-for="item in items"
        :key="item.id"
        :item="item"
        :now="now"
        :timezone="timezone"
        :busy="busy || loading"
        @edit="startEdit(item)"
        @delete="confirmDelete(item)"
        @pin="pinUpdate(item)"
        @reply="openReply(item)"
      >
        <div
          v-if="item.replyCount || replyOpen[item.id] || replyDrafts[item.id]"
          class="discussion-thread"
        >
          <discussion-comment-card
            v-for="reply in item.replies"
            :key="reply.id"
            :item="reply"
            :now="now"
            :timezone="timezone"
            :busy="busy || loading"
            reply
            @edit="startEdit(reply)"
            @delete="confirmDelete(reply)"
          />
          <inline-problem
            v-if="replyProblems[item.id]"
            :problem="replyProblems[item.id]!"
          />
          <el-button
            v-if="item.repliesNextCursor"
            text
            :loading="Boolean(repliesLoading[item.id])"
            :disabled="busy"
            @click="loadReplies(item)"
          >
            加载更多回复
          </el-button>
          <discussion-reply-composer
            v-if="item.capabilities.canReply || replyDrafts[item.id]"
            :ref="value => { if (value) replyComposers.set(item.id, value as InstanceType<typeof DiscussionReplyComposer>); else replyComposers.delete(item.id) }"
            :work-item-id="workItemId"
            :parent-update-id="item.id"
            :members="members"
            :can-publish="item.capabilities.canReply && !loading && !mutatingId && !savingEdit"
            :draft="replyDrafts[item.id] ?? ''"
            @draft="replyDrafts[item.id] = $event"
            @busy="replyBusy[item.id] = $event"
            @published="mergeUpdates([$event])"
          />
        </div>
      </discussion-comment-card>
      <p
        v-if="!loading && !loadProblem && !items.length"
        class="discussion__empty"
      >
        还没有讨论，发布第一条消息吧。
      </p>
    </div>
    <div
      ref="sentinel"
      class="discussion__state"
      aria-live="polite"
    >
      <template v-if="loadingOlder">
        正在加载更早讨论…
      </template>
      <template v-else-if="olderProblem">
        <inline-problem :problem="olderProblem" /><el-button @click="loadOlder(true)">
          重试加载更早讨论
        </el-button>
      </template>
      <template v-else-if="!loading && !loadProblem && items.length && !nextCursor">
        已加载全部讨论
      </template>
    </div>
    <el-dialog
      v-model="editDialogVisible"
      title="编辑讨论"
      width="min(720px, 92vw)"
      destroy-on-close
      :before-close="closeEdit"
    >
      <inline-problem
        v-if="editProblem"
        :problem="editProblem"
      />
      <discussion-composer
        ref="editComposer"
        :editor="editEditor"
        :busy="savingEdit"
        :collapsible="false"
        :show-submit="false"
      />
      <template #footer>
        <el-button @click="closeEdit(() => { editDialogVisible = false })">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="savingEdit"
          :disabled="!editDraftText.trim()"
          @click="saveEdit"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.discussion { display: grid; min-width: 0; gap: 8px; }
.discussion__actions { display: flex; justify-content: flex-end; height: 24px; }
.discussion__refresh-icon { width: 16px; height: 16px; }
.discussion__timeline { display: grid; min-width: 0; align-content: start; gap: 12px; }
.discussion__state { min-height: 24px; text-align: center; color: var(--yp-text-muted); font-size: var(--yp-type-caption-size); }
.discussion__empty, .discussion__readonly { color: var(--yp-text-secondary); text-align: center; }
.discussion-thread { display: grid; min-width: 0; gap: 16px; padding: 16px; border-top: 1px solid var(--yp-border-subtle); }
</style>
