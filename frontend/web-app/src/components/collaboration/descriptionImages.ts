import { Node, type Editor } from '@tiptap/core'
import Image from '@tiptap/extension-image'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import ImageUploadPlaceholderView from './ImageUploadPlaceholderView.vue'
import AttachmentFileView from './AttachmentFileView.vue'

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

/** Mirrors both server rich text profiles: same-origin attachment images, src and alt only. */
export const DescriptionImage = Image.extend({
  addAttributes() {
    return { src: { default: null }, alt: { default: null } }
  },
  parseHTML() {
    return [{ tag: 'img[src]', getAttrs: element => isAttachmentImageSource(element.getAttribute('src')) ? null : false }]
  },
}).configure({ inline: false, allowBase64: false })

export function attachmentSize(value: unknown): number | null {
  if (typeof value !== 'number' && (typeof value !== 'string' || !/^[0-9]+$/.test(value))) return null
  const size = Number(value)
  return Number.isInteger(size) && size >= 1 && size <= 100 * 1024 * 1024 ? size : null
}

export function formatAttachmentSize(value: unknown): string {
  const size = attachmentSize(value)
  if (size === null) return ''
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KiB`
  return `${(size / (1024 * 1024)).toFixed(1)} MiB`
}

export function attachmentExtension(name: string): string {
  return name.includes('.') ? name.split('.').pop()!.slice(0, 5).toUpperCase() : 'FILE'
}

export const AttachmentFile = Node.create({
  name: 'attachmentFile',
  group: 'block',
  atom: true,
  selectable: true,
  draggable: true,
  priority: 1000,
  addAttributes() {
    return { href: { default: null }, name: { default: '', rendered: false }, size: { default: null, rendered: false } }
  },
  parseHTML() {
    return [{ tag: 'a[data-type="attachment"]', getAttrs: element => {
      const href = element.getAttribute('href')
      const name = (element.querySelector('.attachment-file__name')?.textContent ?? element.textContent)?.trim()
      return isAttachmentImageSource(href) && name
        ? { href, name, size: attachmentSize(element.getAttribute('data-size')) } : false
    } }]
  },
  renderHTML({ node }) {
    const size = attachmentSize(node.attrs.size)
    return ['a', { 'data-type': 'attachment', href: node.attrs.href,
      ...(size === null ? {} : { 'data-size': String(size) }) }, String(node.attrs.name)]
  },
  renderText({ node }) { return String(node.attrs.name) },
  addNodeView() { return VueNodeViewRenderer(AttachmentFileView) },
})

export function decorateAttachmentCards(body: HTMLElement | undefined): void {
  body?.querySelectorAll<HTMLAnchorElement>('a[data-type="attachment"]').forEach(anchor => {
    if (!isAttachmentImageSource(anchor.getAttribute('href')) || anchor.querySelector('.attachment-file__name')) return
    const name = anchor.textContent ?? ''
    const icon = document.createElement('span')
    icon.className = 'attachment-file__icon'
    icon.setAttribute('aria-hidden', 'true')
    icon.textContent = attachmentExtension(name)
    const text = document.createElement('span')
    text.className = 'attachment-file__text'
    const title = document.createElement('span')
    title.className = 'attachment-file__name'
    title.textContent = name
    text.append(title)
    const size = formatAttachmentSize(anchor.getAttribute('data-size'))
    if (size) {
      const caption = document.createElement('span')
      caption.className = 'attachment-file__size'
      caption.textContent = size
      text.append(caption)
    }
    anchor.replaceChildren(icon, text)
  })
}

/** Editor-only upload block; never parsed from saved HTML. */
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
  resolveEditorUpload(editor, uploadId, image ? { type: 'image', attrs: image } : undefined)
}

export function resolveEditorUpload(editor: Editor | undefined, uploadId: string,
  replacement?: { type: 'image' | 'attachmentFile'; attrs: Record<string, unknown> }): void {
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
  if (replacement) tr.replaceWith(position, position + node.nodeSize, schema.nodes[replacement.type]!.create(replacement.attrs))
  else tr.delete(position, position + node.nodeSize)
  editor.view.dispatch(tr.setMeta('addToHistory', false))
}
