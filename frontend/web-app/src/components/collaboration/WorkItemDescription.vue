<script setup lang="ts">
import { useEditor } from '@tiptap/vue-3'
import { AttachmentOwnerType, ErrorCode, readCsrfToken, type WorkItemDetail } from '@yumpoo/api-client'
import { ElButton, ElImageViewer, ElMessage, ElMessageBox } from 'element-plus'
import { EditPen } from '@element-plus/icons-vue'
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { workItemsApi } from '../../api/client'
import { isProblemCode, isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../InlineProblem.vue'
import DiscussionComposer from './DiscussionComposer.vue'
import { attachmentContentUrl, isUploadAborted, uploadAttachment } from './attachmentUpload'
import {
  DescriptionImage, ImageUploadPlaceholder, countImageUploads, descriptionImageMaxBytes,
  descriptionImageTypes, resolveImageUpload, type ImageUploadState,
} from './descriptionImages'
import { discussionExtensions, discussionHasDraft } from './discussionEditor'
import './discussionRichText.css'

const props = defineProps<{
  workItemId: string
  description: string | null | undefined
  etag: string
  canEdit: boolean
}>()
const emit = defineEmits<{ updated: [detail: WorkItemDetail] }>()

const editing = ref(false)
const saving = ref(false)
const reloading = ref(false)
const problem = ref<ApiProblem>()
const conflict = ref(false)
const draftHtml = ref('')
const originalHtml = ref('')
const revision = ref(0)
const viewerIndex = ref<number>()
const body = ref<HTMLElement>()
const fileInput = ref<HTMLInputElement>()
const composer = ref<InstanceType<typeof DiscussionComposer>>()
const uploads = reactive<Record<string, ImageUploadState>>({})
const uploadControllers = new Map<string, AbortController>()
let saveKey = crypto.randomUUID()
let saveKeyBody: string | null = null
let disposed = false

const editor = useEditor({
  content: '',
  editable: true,
  extensions: [
    ...discussionExtensions(() => [], () => composer.value?.closePanel(), { mention: false }),
    DescriptionImage,
    ImageUploadPlaceholder.configure({ getUpload: id => uploads[id], onRemove: removeUpload }),
  ],
  editorProps: {
    attributes: { role: 'textbox', 'aria-label': '工作项描述', 'aria-multiline': 'true' },
    handlePaste: (_view, event) => insertFiles(imageFiles(event.clipboardData?.files)),
    handleDrop: (view, event, _slice, moved) => {
      if (moved) return false
      const files = imageFiles(event.dataTransfer?.files)
      if (!files.length) return false
      event.preventDefault()
      return insertFiles(files, view.posAtCoords({ left: event.clientX, top: event.clientY })?.pos)
    },
  },
  onUpdate: ({ editor: current }) => { draftHtml.value = current.getHTML() },
  onTransaction: () => { revision.value++ },
})

const pendingUploads = computed(() => { void revision.value; return countImageUploads(editor.value) })
const hasDraft = computed(() => editing.value && (draftHtml.value !== originalHtml.value || pendingUploads.value > 0))
const busy = computed(() => saving.value)
const imageSources = computed(() => {
  if (!props.description) return []
  const template = document.createElement('template')
  template.innerHTML = props.description
  return [...template.content.querySelectorAll('img')].map(image => image.getAttribute('src') ?? '')
})

watch(saving, value => editor.value?.setEditable(!value))
watch(() => props.workItemId, () => discardDraft())

function imageFiles(list: FileList | null | undefined): File[] {
  return [...(list ?? [])].filter(file => file.type.startsWith('image/'))
}

function insertFiles(files: File[], position?: number): boolean {
  if (!files.length || !editor.value || saving.value) return false
  const accepted = files.filter(file => {
    if (!descriptionImageTypes.includes(file.type)) { ElMessage.warning(`${file.name}：仅支持 PNG、JPG、GIF 图片。`); return false }
    if (file.size > descriptionImageMaxBytes) { ElMessage.warning(`${file.name}：单张图片不能超过 20 MiB。`); return false }
    return true
  })
  if (!accepted.length) return true
  const started = accepted.map(file => ({ file, uploadId: crypto.randomUUID() }))
  started.forEach(({ file, uploadId }) => { uploads[uploadId] = { name: file.name || '粘贴的图片', phase: 'uploading' } })
  const nodes = started.map(({ uploadId }) => ({ type: 'imageUpload', attrs: { uploadId } }))
  const chain = editor.value.chain().focus()
  if (position === undefined) chain.insertContent(nodes).run()
  else chain.insertContentAt(position, nodes).run()
  started.forEach(({ file, uploadId }) => void runUpload(file, uploadId))
  return true
}

async function runUpload(file: File, uploadId: string): Promise<void> {
  const controller = new AbortController()
  uploadControllers.set(uploadId, controller)
  try {
    const metadata = await uploadAttachment({
      ownerType: AttachmentOwnerType.WorkItem,
      ownerId: props.workItemId,
      file,
      signal: controller.signal,
      onPhase: phase => { if (uploads[uploadId]) uploads[uploadId].phase = phase },
    })
    if (disposed || controller.signal.aborted) return
    resolveImageUpload(editor.value, uploadId, { src: attachmentContentUrl(metadata.id), alt: metadata.originalFileName })
    delete uploads[uploadId]
  } catch (reason) {
    if (disposed || isUploadAborted(reason)) return
    if (uploads[uploadId]) uploads[uploadId] = { ...uploads[uploadId], phase: 'failed', message: (reason as Error).message }
  } finally {
    uploadControllers.delete(uploadId)
  }
}

function removeUpload(uploadId: string): void {
  uploadControllers.get(uploadId)?.abort()
  resolveImageUpload(editor.value, uploadId)
  delete uploads[uploadId]
}

function abortUploads(): void {
  uploadControllers.forEach(controller => controller.abort())
  uploadControllers.clear()
  Object.keys(uploads).forEach(id => { delete uploads[id] })
}

function pickImage(): void { fileInput.value?.click() }

function chooseImages(event: Event): void {
  const input = event.target as HTMLInputElement
  const files = [...(input.files ?? [])]
  input.value = ''
  insertFiles(files)
}

async function startEdit(): Promise<void> {
  if (!props.canEdit || editing.value || !editor.value) return
  problem.value = undefined
  conflict.value = false
  editor.value.commands.setContent(props.description ?? '', { emitUpdate: false })
  originalHtml.value = editor.value.getHTML()
  draftHtml.value = originalHtml.value
  editing.value = true
  await nextTick()
  editor.value.commands.focus('end')
}

function discardDraft(): void {
  abortUploads()
  editing.value = false
  problem.value = undefined
  conflict.value = false
  composer.value?.closePanel()
  editor.value?.commands.clearContent(false)
  draftHtml.value = ''
  originalHtml.value = ''
}

async function cancel(): Promise<void> {
  if (saving.value) return
  if (hasDraft.value) {
    try {
      await ElMessageBox.confirm('尚未保存的描述修改将被丢弃。', '放弃修改', {
        confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', type: 'warning',
      })
    } catch { return }
  }
  discardDraft()
}

async function save(): Promise<void> {
  if (!editor.value || saving.value || pendingUploads.value || !hasDraft.value) return
  const csrf = readCsrfToken()
  if (!csrf) { problem.value = localProblem('安全凭据已失效，请刷新页面后重试。'); return }
  const html = discussionHasDraft(editor.value) ? editor.value.getHTML() : null
  if (html !== saveKeyBody) { saveKey = crypto.randomUUID(); saveKeyBody = html }
  saving.value = true
  problem.value = undefined
  conflict.value = false
  try {
    const detail = await workItemsApi.patchWorkItemDescription({
      workItemId: props.workItemId,
      xXSRFTOKEN: csrf,
      ifMatch: props.etag,
      idempotencyKey: saveKey,
      workItemDescriptionPatchRequest: { description: html },
    })
    if (disposed) return
    saveKey = crypto.randomUUID()
    saveKeyBody = null
    discardDraft()
    emit('updated', detail)
    ElMessage.success('描述已保存')
  } catch (reason) {
    const failure = await toApiProblem(reason)
    if (disposed) return
    conflict.value = isProblemCode(failure, ErrorCode.VersionConflict) || isProblemStatus(failure, 412)
    problem.value = conflict.value
      ? localProblem('描述已被其他人或其他操作更新。你的草稿仍保留，可复制内容后载入最新版本。')
      : failure
  } finally {
    if (!disposed) saving.value = false
  }
}

async function reloadLatest(): Promise<void> {
  reloading.value = true
  try {
    const latest = await workItemsApi.getWorkItem({ workItemId: props.workItemId })
    if (disposed) return
    discardDraft()
    emit('updated', latest)
  } catch (reason) {
    if (!disposed) problem.value = await toApiProblem(reason)
  } finally {
    if (!disposed) reloading.value = false
  }
}

function onBodyClick(event: MouseEvent): void {
  const target = event.target as HTMLElement
  if (target instanceof HTMLImageElement) {
    const index = [...(body.value?.querySelectorAll('img') ?? [])].indexOf(target)
    if (index >= 0) viewerIndex.value = index
    return
  }
  if (target.closest('a') || window.getSelection()?.toString()) return
  void startEdit()
}

onBeforeUnmount(() => { disposed = true; abortUploads() })
defineExpose({ hasDraft, busy, discardDraft, editor, startEdit, save })
</script>

<template>
  <section
    class="work-item-description"
    :class="{ 'work-item-description--editing': editing }"
    aria-label="工作项描述"
  >
    <header class="work-item-description__header">
      <h3>描述</h3>
      <button
        v-if="canEdit && !editing && description"
        type="button"
        class="work-item-description__edit"
        @click="startEdit"
      >
        <edit-pen aria-hidden="true" />编辑
      </button>
    </header>
    <div
      v-show="editing"
      class="work-item-description__editor"
    >
      <discussion-composer
        ref="composer"
        :editor="editor"
        :busy="saving"
        :collapsible="false"
        :show-submit="false"
        :allow-mention="false"
        allow-image
        placeholder="描述这个工作项的背景、目标和验收标准，可粘贴或拖入截图…"
        @pick-image="pickImage"
      />
      <input
        ref="fileInput"
        class="work-item-description__file"
        type="file"
        :accept="descriptionImageTypes.join(',')"
        multiple
        tabindex="-1"
        aria-hidden="true"
        @change="chooseImages"
      >
      <inline-problem
        v-if="problem"
        :problem="problem"
      />
      <footer class="work-item-description__footer">
        <span
          class="work-item-description__hint"
          role="status"
        >{{ pendingUploads ? `${pendingUploads} 张图片处理中，完成后可保存` : '' }}</span>
        <el-button
          v-if="conflict"
          :loading="reloading"
          @click="reloadLatest"
        >
          放弃草稿并载入最新
        </el-button>
        <el-button
          :disabled="saving"
          @click="cancel"
        >
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="!hasDraft || pendingUploads > 0"
          @click="save"
        >
          保存
        </el-button>
      </footer>
    </div>
    <template v-if="!editing">
      <div
        v-if="description"
        ref="body"
        class="work-item-description__body discussion-rich-text"
        :class="{ 'work-item-description__body--editable': canEdit }"
        @click="onBodyClick"
        v-html="description"
      />
      <button
        v-else-if="canEdit"
        type="button"
        class="work-item-description__empty"
        @click="startEdit"
      >
        <edit-pen aria-hidden="true" />
        <span>添加描述</span>
        <small>记录背景、目标与验收标准，可粘贴或拖入截图</small>
      </button>
      <p
        v-else
        class="work-item-description__none"
      >
        暂无描述
      </p>
    </template>
    <el-image-viewer
      v-if="viewerIndex !== undefined"
      :url-list="imageSources"
      :initial-index="viewerIndex"
      hide-on-click-modal
      teleported
      @close="viewerIndex = undefined"
    />
  </section>
</template>

<style scoped>
.work-item-description { display: grid; gap: 12px; min-width: 0; padding-top: 4px; }
.work-item-description__header { display: flex; align-items: center; justify-content: space-between; min-height: 32px; }
.work-item-description__header h3 { margin: 0; color: var(--yp-text-primary); font-size: 15px; font-weight: 650; }
.work-item-description__edit { display: inline-flex; align-items: center; gap: 6px; padding: 5px 10px; border: 0; border-radius: 4px; color: var(--yp-text-secondary); background: transparent; cursor: pointer; font: inherit; font-size: 13px; }
.work-item-description__edit:hover { color: var(--yp-action-primary); background: var(--yp-bg-selected); }
.work-item-description__edit svg, .work-item-description__empty svg { width: 16px; height: 16px; }
.work-item-description__body { min-height: 48px; margin: 0 -12px; padding: 8px 12px; border-radius: var(--yp-radius-md); color: var(--yp-text-primary); font-size: 14px; }
.work-item-description__body--editable { cursor: text; transition: background-color .15s ease; }
.work-item-description__body--editable:hover { background: var(--yp-bg-sunken); }
.work-item-description__body :deep(img) { cursor: zoom-in; }
.work-item-description__empty { display: grid; justify-items: center; gap: 4px; width: 100%; padding: 28px 16px; border: 1px dashed var(--yp-border-strong); border-radius: var(--yp-radius-md); color: var(--yp-text-secondary); background: transparent; cursor: pointer; font: inherit; }
.work-item-description__empty span { color: var(--yp-text-primary); font-weight: 600; }
.work-item-description__empty small { color: var(--yp-text-muted); font-size: 12px; }
.work-item-description__empty:hover { border-color: var(--yp-action-primary); color: var(--yp-action-primary); background: var(--yp-bg-selected); }
.work-item-description__empty:focus-visible, .work-item-description__edit:focus-visible { outline: 2px solid var(--yp-action-primary); outline-offset: 2px; }
.work-item-description__none { margin: 0; color: var(--yp-text-muted); }
.work-item-description__editor { display: grid; gap: 10px; min-width: 0; }
.work-item-description__editor :deep(.discussion-editor .ProseMirror) { min-height: 220px; }
.work-item-description__file { display: none; }
.work-item-description__footer { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 8px; }
.work-item-description__footer .el-button + .el-button { margin-left: 0; }
.work-item-description__hint { flex: 1; min-width: 0; color: var(--yp-text-muted); font-size: 12px; }
</style>
