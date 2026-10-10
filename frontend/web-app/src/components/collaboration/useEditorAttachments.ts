import { AttachmentOwnerType } from '@yumpoo/api-client'
import type { Editor } from '@tiptap/core'
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { attachmentContentUrl, isUploadAborted, uploadAttachment } from './attachmentUpload'
import {
  AttachmentFile, DescriptionImage, ImageUploadPlaceholder, countImageUploads,
  descriptionImageMaxBytes, descriptionImageTypes, resolveEditorUpload, type ImageUploadState,
} from './descriptionImages'

export const editorFileExtensions = ['pdf', 'docx', 'xlsx', 'pptx', 'md', 'txt', 'csv', 'zip']
export const editorFileMaxBytes = 100 * 1024 * 1024
export const editorAttachmentAccept = [...descriptionImageTypes, '.png', '.jpg', '.jpeg', '.gif',
  ...editorFileExtensions.map(extension => `.${extension}`)].join(',')
const imageExtensions: Record<string, string> = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif' }

export function useEditorAttachments(options: {
  editor: () => Editor | undefined
  ownerId: () => string
  canUpload: () => boolean
  allowFile?: boolean
}) {
  const uploads = reactive<Record<string, ImageUploadState>>({})
  const controllers = new Map<string, AbortController>()
  const revision = ref(0)
  let disposed = false
  const pendingUploads = computed(() => { void revision.value; return countImageUploads(options.editor()) })

  function onTransaction(): void { revision.value++ }

  function insertFiles(files: File[], position?: number): boolean {
    const editor = options.editor()
    if (!files.length || !editor || !options.canUpload() || !editor.isEditable) return false
    const accepted: { file: File; image: boolean; uploadId: string }[] = []
    for (const file of files) {
      const extension = file.name.split('.').pop()?.toLowerCase() ?? ''
      const image = file.type.startsWith('image/') || Boolean(imageExtensions[extension])
      if (image && !(descriptionImageTypes.includes(file.type) || (!file.type && imageExtensions[extension]))) {
        ElMessage.warning(`${file.name}：仅支持 PNG、JPG、GIF 图片。`)
        continue
      }
      if (!image && options.allowFile === false) {
        ElMessage.warning(`${file.name}：仅支持 PNG、JPG、GIF 图片。`)
        continue
      }
      if (!image && !editorFileExtensions.includes(extension)) {
        ElMessage.warning(`${file.name}：仅支持 PDF、DOCX、XLSX、PPTX、MD、TXT、CSV、ZIP 文件。`)
        continue
      }
      if (!file.size || file.size > (image ? descriptionImageMaxBytes : editorFileMaxBytes)) {
        const message = !file.size ? '不能上传空文件。'
          : image ? '单张图片不能超过 20 MiB。' : '单个文件不能超过 100 MiB。'
        ElMessage.warning(`${file.name}：${message}`)
        continue
      }
      accepted.push({ file, image, uploadId: crypto.randomUUID() })
    }
    if (!accepted.length) return true
    accepted.forEach(({ file, uploadId }) => { uploads[uploadId] = { name: file.name || '粘贴的图片', phase: 'uploading' } })
    const nodes = accepted.map(({ uploadId }) => ({ type: 'imageUpload', attrs: { uploadId } }))
    const chain = editor.chain().focus()
    const inserted = position === undefined ? chain.insertContent(nodes).run() : chain.insertContentAt(position, nodes).run()
    if (!inserted) { accepted.forEach(({ uploadId }) => { delete uploads[uploadId] }); return true }
    accepted.forEach(({ file, image, uploadId }) => void runUpload(file, image, uploadId))
    return true
  }

  async function runUpload(file: File, image: boolean, uploadId: string): Promise<void> {
    const controller = new AbortController()
    controllers.set(uploadId, controller)
    try {
      const metadata = await uploadAttachment({
        ownerType: AttachmentOwnerType.WorkItem, ownerId: options.ownerId(), file, signal: controller.signal,
        onPhase: phase => { if (uploads[uploadId]) uploads[uploadId].phase = phase },
      })
      if (disposed || controller.signal.aborted) return
      const href = attachmentContentUrl(metadata.id)
      resolveEditorUpload(options.editor(), uploadId, image
        ? { type: 'image', attrs: { src: href, alt: metadata.originalFileName } }
        : { type: 'attachmentFile', attrs: { href, name: metadata.originalFileName, size: metadata.sizeBytes ?? file.size } })
      delete uploads[uploadId]
    } catch (reason) {
      if (disposed || controller.signal.aborted || isUploadAborted(reason)) return
      if (uploads[uploadId]) uploads[uploadId] = { ...uploads[uploadId], phase: 'failed', message: (reason as Error).message }
    } finally { controllers.delete(uploadId) }
  }

  function removeUpload(uploadId: string): void {
    controllers.get(uploadId)?.abort()
    resolveEditorUpload(options.editor(), uploadId)
    delete uploads[uploadId]
  }

  function abortUploads(): void {
    controllers.forEach(controller => controller.abort())
    controllers.clear()
    Object.keys(uploads).forEach(id => { delete uploads[id] })
  }

  const editorProps = {
    handlePaste: (_view: Editor['view'], event: ClipboardEvent) => insertFiles([...(event.clipboardData?.files ?? [])]),
    handleDrop: (view: Editor['view'], event: DragEvent, _slice: unknown, moved: boolean) => {
      if (moved) return false
      const files = [...(event.dataTransfer?.files ?? [])]
      if (!files.length || !options.canUpload()) return false
      const handled = insertFiles(files, view.posAtCoords({ left: event.clientX, top: event.clientY })?.pos)
      if (handled) event.preventDefault()
      return handled
    },
  }
  const extensions = [DescriptionImage, AttachmentFile,
    ImageUploadPlaceholder.configure({ getUpload: id => uploads[id], onRemove: removeUpload })]
  watch(options.ownerId, abortUploads)
  onBeforeUnmount(() => { disposed = true; abortUploads() })
  return { extensions, editorProps, pendingUploads, insertFiles, removeUpload, abortUploads, onTransaction }
}
