<script setup lang="ts">
import Link from '@tiptap/extension-link'
import Mention from '@tiptap/extension-mention'
import StarterKit from '@tiptap/starter-kit'
import { EditorContent, useEditor } from '@tiptap/vue-3'
import {
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
const draftText = ref('')
const editDraftText = ref('')
const editingItemId = ref<string>()
const editDialogVisible = ref(false)
const savingEdit = ref(false)
const mutatingId = ref<string>()
const now = ref(Date.now())
let publishKey = crypto.randomUUID()
let publishKeyBody = ''
let clock: ReturnType<typeof setInterval> | undefined

const activeMembers = computed(() => props.members.filter(member => member.membershipStatus === 'ACTIVE'))

function mentionSuggestion() {
  return {
    char: '@',
    items: ({ query }: { query: string }) => activeMembers.value
      .filter(member => member.displayName.toLocaleLowerCase().includes(query.toLocaleLowerCase()))
      .slice(0, 8),
    render: () => {
      let popup: HTMLDivElement | undefined
      let selected = 0
      let current: { items: ProjectMember[], command: (attrs: { id: string, label: string }) => void } | undefined
      const draw = () => {
        if (!popup || !current) return
        popup.replaceChildren(...current.items.map((member, index) => {
          const button = document.createElement('button')
          button.type = 'button'
          button.className = index === selected ? 'mention-option mention-option--active' : 'mention-option'
          button.textContent = member.displayName
          button.onclick = () => current?.command({ id: member.userId, label: member.displayName })
          return button
        }))
      }
      return {
        onStart: (suggestionProps: typeof current & { clientRect?: (() => DOMRect | null) | null }) => {
          current = suggestionProps
          popup = document.createElement('div')
          popup.className = 'mention-popup'
          popup.setAttribute('role', 'listbox')
          document.body.appendChild(popup)
          const rect = suggestionProps.clientRect?.()
          if (rect) {
            popup.style.left = `${rect.left}px`
            popup.style.top = `${rect.bottom + 4}px`
          }
          draw()
        },
        onUpdate: (suggestionProps: typeof current) => {
          current = suggestionProps
          selected = 0
          draw()
        },
        onKeyDown: ({ event }: { event: KeyboardEvent }) => {
          if (!current?.items.length) return false
          if (event.key === 'ArrowDown') selected = (selected + 1) % current.items.length
          else if (event.key === 'ArrowUp') selected = (selected + current.items.length - 1) % current.items.length
          else if (event.key === 'Enter') current.command({
            id: current.items[selected]!.userId,
            label: current.items[selected]!.displayName,
          })
          else if (event.key === 'Escape') return false
          else return false
          draw()
          return true
        },
        onExit: () => {
          popup?.remove()
          popup = undefined
          current = undefined
        },
      }
    },
  }
}

function editorExtensions() {
  return [
    StarterKit.configure({ heading: false, codeBlock: false, horizontalRule: false, strike: false, link: false }),
    Link.configure({
      openOnClick: false,
      protocols: ['http', 'https', 'mailto'],
      HTMLAttributes: { target: '_blank', rel: 'nofollow noopener noreferrer' },
    }),
    Mention.configure({
      HTMLAttributes: { 'data-type': 'mention' },
      renderHTML: ({ node }) => ['span', {
        'data-type': 'mention',
        'data-mention-user-id': String(node.attrs.id),
      }, `@${String(node.attrs.label ?? node.attrs.id)}`],
      suggestion: mentionSuggestion(),
    }),
  ]
}

const editor = useEditor({
  content: '',
  editable: props.canPublish,
  extensions: editorExtensions(),
  onUpdate: ({ editor: current }) => {
    const html = current.getHTML()
    draftText.value = current.getText()
    if (html !== publishKeyBody) {
      publishKey = crypto.randomUUID()
      publishKeyBody = html
    }
  },
})

const editEditor = useEditor({
  content: '',
  editable: true,
  extensions: editorExtensions(),
  onUpdate: ({ editor: current }) => {
    editDraftText.value = current.getText()
  },
})

const hasDraft = computed(() => Boolean(draftText.value.trim()))

watch(() => props.canPublish, value => editor.value?.setEditable(value))

function mergeUpdates(incoming: WorkItemUpdate[]): void {
  const merged = new Map(items.value.map(item => [item.id, item]))
  incoming.forEach(item => merged.set(item.id, item))
  items.value = Array.from(merged.values()).sort((left, right) => {
    const time = left.createdAt.getTime() - right.createdAt.getTime()
    return time || left.id.localeCompare(right.id)
  })
}

async function loadLatest(): Promise<void> {
  loading.value = true
  problem.value = undefined
  try {
    const page = await workItemUpdatesApi.listWorkItemUpdates({ workItemId: props.workItemId, size: 20 })
    mergeUpdates(page.items)
    if (!items.value.length || nextCursor.value === null) nextCursor.value = page.nextCursor
    await nextTick()
    if (timeline.value) timeline.value.scrollTop = timeline.value.scrollHeight
  } catch (reason) {
    problem.value = await toApiProblem(reason)
  } finally {
    loading.value = false
  }
}

async function loadOlder(): Promise<void> {
  if (!nextCursor.value || !timeline.value) return
  const beforeHeight = timeline.value.scrollHeight
  const beforeTop = timeline.value.scrollTop
  loading.value = true
  problem.value = undefined
  try {
    const page = await workItemUpdatesApi.listWorkItemUpdates({
      workItemId: props.workItemId,
      cursor: nextCursor.value,
      size: 20,
    })
    mergeUpdates(page.items)
    nextCursor.value = page.nextCursor
    await nextTick()
    timeline.value.scrollTop = beforeTop + timeline.value.scrollHeight - beforeHeight
  } catch (reason) {
    problem.value = await toApiProblem(reason)
  } finally {
    loading.value = false
  }
}

function setLink(): void {
  setEditorLink(editor.value, problem)
}

function setEditLink(): void {
  setEditorLink(editEditor.value, editProblem)
}

function setEditorLink(
  target: typeof editor.value,
  targetProblem: typeof problem,
): void {
  if (!target) return
  const previous = target.getAttributes('link').href as string | undefined
  const href = window.prompt('输入绝对 http、https 或 mailto 链接', previous ?? 'https://')
  if (href === null) return
  if (!/^(https?:\/\/|mailto:)/i.test(href)) {
    targetProblem.value = localProblem('链接必须是绝对 http、https 或 mailto 地址。')
    return
  }
  target.chain().focus().extendMarkRange('link').setLink({ href }).run()
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
  if (!canEdit(item) || !item.bodyHtml) return
  editingItemId.value = item.id
  editProblem.value = undefined
  editEditor.value?.commands.setContent(item.bodyHtml)
  editDraftText.value = editEditor.value?.getText() ?? ''
  editDialogVisible.value = true
}

function currentEditingItem(): WorkItemUpdate | undefined {
  return items.value.find(item => item.id === editingItemId.value)
}

async function refreshUpdate(updateId: string): Promise<WorkItemUpdate> {
  const fresh = await workItemUpdatesApi.getWorkItemUpdate({ updateId })
  mergeUpdates([fresh])
  return fresh
}

async function handleMutationProblem(reason: unknown, updateId: string, preserveEditDraft: boolean): Promise<void> {
  const apiProblem = await toApiProblem(reason)
  const conflict = isProblemCode(apiProblem, ErrorCode.VersionConflict) || isProblemStatus(apiProblem, 412)
  const unavailable = isProblemStatus(apiProblem, 409)
  if (conflict || unavailable) {
    try {
      await refreshUpdate(updateId)
    } catch (refreshReason) {
      problem.value = await toApiProblem(refreshReason)
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
  if (!item || !editEditor.value || !editDraftText.value.trim()) return
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
    mergeUpdates([updated])
    editDialogVisible.value = false
    ElMessage.success('讨论已更新')
  } catch (reason) {
    await handleMutationProblem(reason, item.id, true)
  } finally {
    savingEdit.value = false
  }
}

async function deleteUpdate(item: WorkItemUpdate, reason?: string): Promise<void> {
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
    mergeUpdates([deleted])
    if (editingItemId.value === item.id) editDialogVisible.value = false
    ElMessage.success(reason === undefined ? '讨论已删除' : '讨论已治理删除')
  } catch (mutationReason) {
    await handleMutationProblem(mutationReason, item.id, false)
  } finally {
    mutatingId.value = undefined
  }
}

async function confirmSelfDelete(item: WorkItemUpdate): Promise<void> {
  try {
    await ElMessageBox.confirm('删除后正文不可恢复，但时间线会保留占位。确定删除吗？', '删除讨论', {
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  await deleteUpdate(item)
}

async function moderateDelete(item: WorkItemUpdate): Promise<void> {
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
  await deleteUpdate(item, reason)
}

async function publish(): Promise<void> {
  if (!editor.value || !props.canPublish || !hasDraft.value) return
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
    mergeUpdates([published])
    editor.value.commands.clearContent(true)
    publishKey = crypto.randomUUID()
    publishKeyBody = editor.value.getHTML()
    await nextTick()
    if (timeline.value) timeline.value.scrollTop = timeline.value.scrollHeight
    ElMessage.success('讨论已发布')
  } catch (reason) {
    problem.value = await toApiProblem(reason)
  } finally {
    publishing.value = false
  }
}

function discardDraft(): void {
  editor.value?.commands.clearContent(true)
  publishKey = crypto.randomUUID()
  publishKeyBody = editor.value?.getHTML() ?? ''
}

defineExpose({ hasDraft, discardDraft, editor, editEditor, saveEdit })
onMounted(() => {
  clock = setInterval(() => { now.value = Date.now() }, 1000)
  void loadLatest()
})
onBeforeUnmount(() => {
  if (clock) clearInterval(clock)
})
</script>

<template>
  <section class="discussion" aria-label="工作项讨论">
    <div class="discussion__actions">
      <el-button :disabled="!nextCursor || loading" @click="loadOlder">加载更早讨论</el-button>
      <el-button :loading="loading" @click="loadLatest">刷新</el-button>
    </div>
    <inline-problem v-if="problem" :problem="problem" />
    <div ref="timeline" v-loading="loading" class="discussion__timeline" aria-live="polite">
      <article v-for="item in items" :key="item.id" class="discussion-update">
        <header>
          <strong>{{ item.authorDisplayName }}</strong>
          <time :datetime="item.createdAt.toISOString()">{{ item.createdAt.toLocaleString('zh-CN') }}</time>
          <span v-if="item.status !== 'PUBLISHED'">{{ item.status === 'EDITED' ? '已编辑' : '已删除' }}</span>
          <span class="discussion-update__actions">
            <el-button v-if="canEdit(item)" text size="small" @click="startEdit(item)">编辑</el-button>
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
        <div v-if="item.bodyHtml" class="discussion-update__body" v-html="item.bodyHtml" />
        <div v-else class="discussion-update__deleted">
          <p>此讨论已删除</p>
          <p v-if="item.deleteReason" class="discussion-update__reason">治理理由：{{ item.deleteReason }}</p>
        </div>
      </article>
      <p v-if="!loading && !items.length" class="discussion__empty">还没有讨论，发布第一条消息吧。</p>
    </div>
    <p v-if="!canPublish" class="discussion__readonly">{{ readOnlyReason ?? '当前角色仅可查看讨论。' }}</p>
    <div v-else class="discussion-composer">
      <div class="discussion-toolbar" role="toolbar" aria-label="讨论格式">
        <el-button text @click="editor?.chain().focus().toggleBold().run()">粗体</el-button>
        <el-button text @click="editor?.chain().focus().toggleItalic().run()">斜体</el-button>
        <el-button text @click="editor?.chain().focus().toggleBulletList().run()">项目符号</el-button>
        <el-button text @click="editor?.chain().focus().toggleOrderedList().run()">编号</el-button>
        <el-button text @click="editor?.chain().focus().toggleBlockquote().run()">引用</el-button>
        <el-button text @click="editor?.chain().focus().toggleCode().run()">行内代码</el-button>
        <el-button text @click="setLink">链接</el-button>
      </div>
      <editor-content v-if="editor" :editor="editor" class="discussion-editor" />
      <div class="discussion-composer__footer">
        <span>输入 @ 提及 ACTIVE 项目成员</span>
        <el-button type="primary" :loading="publishing" :disabled="!hasDraft" @click="publish">发布讨论</el-button>
      </div>
    </div>
    <el-dialog v-model="editDialogVisible" title="编辑讨论" width="min(720px, 92vw)" destroy-on-close>
      <inline-problem v-if="editProblem" :problem="editProblem" />
      <div class="discussion-composer discussion-composer--edit">
        <div class="discussion-toolbar" role="toolbar" aria-label="编辑讨论格式">
          <el-button text @click="editEditor?.chain().focus().toggleBold().run()">粗体</el-button>
          <el-button text @click="editEditor?.chain().focus().toggleItalic().run()">斜体</el-button>
          <el-button text @click="editEditor?.chain().focus().toggleBulletList().run()">项目符号</el-button>
          <el-button text @click="editEditor?.chain().focus().toggleOrderedList().run()">编号</el-button>
          <el-button text @click="editEditor?.chain().focus().toggleBlockquote().run()">引用</el-button>
          <el-button text @click="editEditor?.chain().focus().toggleCode().run()">行内代码</el-button>
          <el-button text @click="setEditLink">链接</el-button>
        </div>
        <editor-content v-if="editEditor" :editor="editEditor" class="discussion-editor" />
      </div>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingEdit" :disabled="!editDraftText.trim()" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.discussion { display: grid; min-height: 520px; gap: var(--yp-space-3); }
.discussion__actions { display: flex; justify-content: space-between; }
.discussion__timeline { display: grid; max-height: min(52vh, 560px); align-content: start; gap: var(--yp-space-3); overflow-y: auto; padding: var(--yp-space-2); border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-md); background: var(--yp-bg-sunken); }
.discussion-update { padding: var(--yp-space-3); border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-md); background: var(--yp-bg-surface); }
.discussion-update header { display: flex; align-items: center; gap: var(--yp-space-2); color: var(--yp-text-muted); font-size: var(--yp-type-caption-size); }
.discussion-update header strong { color: var(--yp-text-primary); font-size: var(--yp-type-body-size); }
.discussion-update__actions { display: flex; gap: 2px; margin-left: auto; }
.discussion-update__body { margin-top: var(--yp-space-2); line-height: 1.65; overflow-wrap: anywhere; }
.discussion-update__body :deep(p), .discussion-update__deleted p { margin: 0 0 var(--yp-space-2); }
.discussion-update__deleted { margin-top: var(--yp-space-2); color: var(--yp-text-secondary); }
.discussion-update__reason { padding: var(--yp-space-2); border-left: 3px solid var(--yp-border-strong); background: var(--yp-bg-sunken); }
.discussion-update__body :deep(a) { color: var(--yp-link); }
.discussion-update__body :deep(span[data-type='mention']) { padding: 1px 4px; border-radius: var(--yp-radius-sm); color: var(--yp-link); background: var(--yp-bg-selected); }
.discussion__empty, .discussion__readonly { color: var(--yp-text-secondary); text-align: center; }
.discussion-composer { overflow: hidden; border: 1px solid var(--yp-border-strong); border-radius: var(--yp-radius-md); background: var(--yp-bg-surface); }
.discussion-composer--edit { margin-top: var(--yp-space-2); }
.discussion-toolbar { display: flex; flex-wrap: wrap; gap: 2px; padding: var(--yp-space-1); border-bottom: 1px solid var(--yp-border-subtle); }
.discussion-editor :deep(.ProseMirror) { min-height: 120px; padding: var(--yp-space-3); outline: none; }
.discussion-editor :deep(.ProseMirror p) { margin: 0 0 var(--yp-space-2); }
.discussion-editor :deep(span[data-type='mention']) { color: var(--yp-link); background: var(--yp-bg-selected); }
.discussion-composer__footer { display: flex; align-items: center; justify-content: space-between; gap: var(--yp-space-2); padding: var(--yp-space-2); border-top: 1px solid var(--yp-border-subtle); color: var(--yp-text-muted); font-size: var(--yp-type-caption-size); }
:global(.mention-popup) { z-index: 4000; position: fixed; display: grid; min-width: 180px; max-height: 240px; overflow-y: auto; padding: 4px; border: 1px solid var(--yp-border-strong); border-radius: var(--yp-radius-md); background: var(--yp-bg-surface); box-shadow: var(--yp-shadow-card); }
:global(.mention-option) { padding: 8px 10px; border: 0; border-radius: var(--yp-radius-sm); color: var(--yp-text-primary); background: transparent; text-align: left; cursor: pointer; }
:global(.mention-option--active), :global(.mention-option:hover) { background: var(--yp-bg-selected); }
</style>
