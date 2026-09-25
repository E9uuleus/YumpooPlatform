import { Node, type Editor } from '@tiptap/core'
import Image from '@tiptap/extension-image'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import ImageUploadPlaceholderView from './ImageUploadPlaceholderView.vue'

const attachmentImageSource = /^\/api\/v1\/attachments\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\/content$/
export const descriptionImageTypes = ['image/png', 'image/jpeg', 'image/gif']
export const descriptionImageMaxBytes = 20 * 1024 * 1024

export interface ImageUploadState {
  name: string
  phase: 'uploading' | 'scanning' | 'failed'
  message?: string
}

export function isAttachmentImageSource(value: string | null | undefined): boolean {
  return Boolean(value && attachmentImageSource.test(value))
}

/** Mirrors the server description profile: only same-origin attachment images, src and alt only. */
export const DescriptionImage = Image.extend({
  addAttributes() {
    return { src: { default: null }, alt: { default: null } }
  },
  parseHTML() {
    return [{ tag: 'img[src]', getAttrs: element => isAttachmentImageSource(element.getAttribute('src')) ? null : false }]
  },
}).configure({ inline: false, allowBase64: false })

/** Editor-only block shown while an image uploads and passes the virus scan; never parsed from saved HTML. */
export const ImageUploadPlaceholder = Node.create<{
  getUpload: (uploadId: string) => ImageUploadState | undefined
  onRemove: (uploadId: string) => void
}>({
  name: 'imageUpload',
  group: 'block',
  atom: true,
  selectable: true,
  addOptions() {
    return { getUpload: () => undefined, onRemove: () => {} }
  },
  addAttributes() {
    return { uploadId: { default: null, rendered: false } }
  },
  renderHTML({ node }) {
    return ['div', { 'data-image-upload': String(node.attrs.uploadId) }]
  },
  addNodeView() {
    return VueNodeViewRenderer(ImageUploadPlaceholderView)
  },
})

export function countImageUploads(editor: Editor | undefined): number {
  let count = 0
  editor?.state.doc.descendants(node => { if (node.type.name === 'imageUpload') count++ })
  return count
}

/** Swaps (or removes) a placeholder outside undo history, so undo cannot resurrect a finished upload. */
export function resolveImageUpload(editor: Editor | undefined, uploadId: string,
  image?: { src: string; alt: string }): void {
  if (!editor || editor.isDestroyed) return
  let position: number | undefined
  editor.state.doc.descendants((node, pos) => {
    if (position !== undefined) return false
    if (node.type.name === 'imageUpload' && node.attrs.uploadId === uploadId) { position = pos; return false }
    return true
  })
  if (position === undefined) return
  const node = editor.state.doc.nodeAt(position)
  if (!node) return
  const { tr, schema } = editor.state
  if (image) tr.replaceWith(position, position + node.nodeSize, schema.nodes.image!.create(image))
  else tr.delete(position, position + node.nodeSize)
  editor.view.dispatch(tr.setMeta('addToHistory', false))
}
