<script setup lang="ts">
import { useEditor } from '@tiptap/vue-3'
import {
  AttachmentOwnerType,
  ErrorCode,
  WorkItemUpdateStatus,
  readCsrfToken,
  type ProjectMember,
  type WorkItemUpdate,
} from '@yumpoo/api-client'
import { ElButton, ElDialog, ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
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
import AttachmentPanel from './AttachmentPanel.vue'

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
const expandedAttachmentIds = ref(new Set<string>())
const now = ref(Date.now())
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
const hasDraft = computed(() => { void draftHtml.value; return discussionHasDraft(editor.value) || hasEditDraft.value })
const busy = computed(() => publishing.value || savingEdit.value || Boolean(mutatingId.value))

watch(() => [props.canPublish, publishing.value], () => editor.value?.setEditable(props.canPublish && !publishing.value))
watch(savingEdit, value => editEditor.value?.setEditable(!value))

function toggleAttachments(updateId: string): void {
  const next = new Set(expandedAttachmentIds.value)
  if (next.has(updateId)) next.delete(updateId)
  else next.add(updateId)
  expandedAttachmentIds.value = next
}

function mergeUpdates(incoming: WorkItemUpdate[]): void {
  const merged = new Map(items.value.map(item => [item.id, item]))
  incoming.forEach(item => {
    const previous = merged.get(item.id)
    if (!previous || item.rowVersion >= previous.rowVersion) merged.set(item.id, item)
  })
  items.value = Array.from(merged.values()).sort((left, right) =>
    right.createdAt.getTime() - left.createdAt.getTime() || right.id.localeCompare(left.id))
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

async function loadLatest(): Promise<void> {
  if (busy.value || editDialogVisible.value || disposed) return
  const current = ++generation
  loadController?.abort()
  olderController?.abort()
  loadController = new AbortController()
  loading.value = true
  loadingOlder.value = false
  loadProblem.value = undefined
  olderProblem.value = undefined
  try {
    const page = await workItemUpdatesApi.listWorkItemUpdates({ workItemId: props.workItemId, size: 20 }, { signal: loadController.signal })
    if (current !== generation || disposed) return
    items.value = []
    mergeUpdates(page.items)
    nextCursor.value = page.nextCursor
    await nextTick()
    scrollToTop()
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

function authorWindowOpen(item: WorkItemUpdate): boolean {
  return item.status !== WorkItemUpdateStatus.Deleted && now.value < item.editDeadlineAt.getTime()
}

function canEdit(item: WorkItemUpdate): boolean {
  return item.capabilities.canEdit && authorWindowOpen(item)
}

function canSelfDelete(item: WorkItemUpdate): boolean {
  return item.capabilities.canSelfDelete && authorWindowOpen(item)
}

function canModerateDelete(item: WorkItemUpdate): boolean {
  return item.capabilities.canModerateDelete && item.status !== WorkItemUpdateStatus.Deleted
}

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
  return items.value.find(item => item.id === editingItemId.value)
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
      : '讨论已超出可操作窗口或状态已变化；未提交的编辑草稿仍保留，可复制后再处理。'
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

async function deleteUpdate(item: WorkItemUpdate, reason?: string): Promise<void> {
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
      workItemUpdateDeleteRequest: reason === undefined ? {} : { reason },
    })
    if (current !== generation || disposed) return
    mergeUpdates([deleted])
    const expanded = new Set(expandedAttachmentIds.value)
    expanded.delete(item.id)
    expandedAttachmentIds.value = expanded
    if (editingItemId.value === item.id) editDialogVisible.value = false
    ElMessage.success(reason === undefined ? '讨论已删除' : '讨论已治理删除')
  } catch (mutationReason) {
    await handleMutationProblem(mutationReason, item.id, false, current)
  } finally {
    if (current === generation && !disposed) mutatingId.value = undefined
  }
}

async function confirmSelfDelete(item: WorkItemUpdate): Promise<void> {
  const current = generation
  try {
    await ElMessageBox.confirm('删除后正文不可恢复，但时间线会保留占位。确定删除吗？', '删除讨论', {
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  if (current === generation && !disposed) await deleteUpdate(item)
}

async function moderateDelete(item: WorkItemUpdate): Promise<void> {
  const current = generation
  let reason: string
  try {
    const result = await ElMessageBox.prompt('请输入治理删除理由（1–500 字）', '治理删除讨论', {
      confirmButtonText: '确认治理删除',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputValidator: value => {
        const normalized = value.trim()
        return normalized.length > 0 && normalized.length <= 500 ? true : '理由须为 1–500 字'
      },
    })
    reason = result.value.trim()
  } catch {
    return
  }
  if (current === generation && !disposed) await deleteUpdate(item, reason)
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
  expandedAttachmentIds.value = new Set()
  problem.value = undefined
  editProblem.value = undefined
  discardDraft()
  void loadLatest()
})
watch(busy, value => { if (!value) queueFill() })

defineExpose({ hasDraft, busy, discardDraft, editor, editEditor, saveEdit, loadLatest, loadOlder })
onMounted(() => {
  clock = setInterval(() => { now.value = Date.now() }, 1000)
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
        @click="loadLatest"
      >
        刷新
      </el-button>
    </div>
    <inline-problem
      v-if="loadProblem"
      :problem="loadProblem"
    />
    <el-button
      v-if="loadProblem"
      @click="loadLatest"
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
      <article
        v-for="item in items"
        :key="item.id"
        class="discussion-update"
      >
        <header>
          <strong>{{ item.authorDisplayName }}</strong>
          <time :datetime="item.createdAt.toISOString()">{{ item.createdAt.toLocaleString('zh-CN') }}</time>
          <span v-if="item.status !== 'PUBLISHED'">{{ item.status === 'EDITED' ? '已编辑' : '已删除' }}</span>
          <span class="discussion-update__actions">
            <el-button
              v-if="item.status !== WorkItemUpdateStatus.Deleted"
              text
              size="small"
              @click="toggleAttachments(item.id)"
            >{{ expandedAttachmentIds.has(item.id) ? '收起附件' : '附件' }}</el-button>
            <el-button
              v-if="canEdit(item)"
              text
              size="small"
              @click="startEdit(item)"
            >编辑</el-button>
            <el-button
              v-if="canSelfDelete(item)"
              text
              size="small"
              :loading="mutatingId === item.id"
              @click="confirmSelfDelete(item)"
            >删除</el-button>
            <el-button
              v-if="canModerateDelete(item)"
              text
              size="small"
              :loading="mutatingId === item.id"
              @click="moderateDelete(item)"
            >治理删除</el-button>
          </span>
        </header>
        <div
          v-if="item.bodyHtml"
          class="discussion-update__body discussion-rich-text"
          v-html="item.bodyHtml"
        />
        <div
          v-else
          class="discussion-update__deleted"
        >
          <p>此讨论已删除</p>
          <p
            v-if="item.deleteReason"
            class="discussion-update__reason"
          >
            治理理由：{{ item.deleteReason }}
          </p>
        </div>
        <keep-alive>
          <attachment-panel
            v-if="item.status !== WorkItemUpdateStatus.Deleted && expandedAttachmentIds.has(item.id)"
            class="discussion-update__attachments"
            :owner-type="AttachmentOwnerType.WorkItemUpdate"
            :owner-id="item.id"
            :can-upload="canPublish"
          />
        </keep-alive>
      </article>
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
.discussion { display: grid; min-width: 0; gap: var(--yp-space-3); }
.discussion__actions { display: flex; justify-content: flex-end; }
.discussion__timeline { display: grid; min-width: 0; align-content: start; gap: var(--yp-space-3); }
.discussion__state { min-height: 24px; text-align: center; color: var(--yp-text-muted); font-size: var(--yp-type-caption-size); }
.discussion-update { padding: var(--yp-space-3); border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-md); background: var(--yp-bg-surface); }
.discussion-update header { display: flex; align-items: center; gap: var(--yp-space-2); flex-wrap: wrap; color: var(--yp-text-muted); font-size: var(--yp-type-caption-size); }
.discussion-update header strong { color: var(--yp-text-primary); font-size: var(--yp-type-body-size); }
.discussion-update__actions { display: flex; gap: 2px; margin-left: auto; }
.discussion-update__body { margin-top: var(--yp-space-2); line-height: 1.65; overflow-wrap: anywhere; }
.discussion-update__body :deep(p), .discussion-update__deleted p { margin: 0 0 var(--yp-space-2); }
.discussion-update__deleted { margin-top: var(--yp-space-2); color: var(--yp-text-secondary); }
.discussion-update__reason { padding: var(--yp-space-2); border-left: 3px solid var(--yp-border-strong); background: var(--yp-bg-sunken); }
.discussion-update__attachments { margin-top: var(--yp-space-3); }
.discussion-update__body :deep(a) { color: var(--yp-link); }
.discussion-update__body :deep(span[data-type='mention']) { padding: 1px 4px; border-radius: var(--yp-radius-sm); color: var(--yp-link); background: var(--yp-bg-selected); }
.discussion__empty, .discussion__readonly { color: var(--yp-text-secondary); text-align: center; }
</style>
