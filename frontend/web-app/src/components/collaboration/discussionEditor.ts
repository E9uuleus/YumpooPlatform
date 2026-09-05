import { Extension, mergeAttributes, type Editor } from '@tiptap/core'
import Link from '@tiptap/extension-link'
import Mention from '@tiptap/extension-mention'
import { TaskItem, TaskList } from '@tiptap/extension-list'
import { TableKit } from '@tiptap/extension-table'
import TextAlign from '@tiptap/extension-text-align'
import { Color, BackgroundColor, FontSize, TextStyle } from '@tiptap/extension-text-style'
import StarterKit from '@tiptap/starter-kit'
import { exitSuggestion, type SuggestionProps } from '@tiptap/suggestion'
import type { ProjectMember } from '@yumpoo/api-client'

export const discussionFontSizes = [16, 18, 24, 32, 36, 48] as const
export const discussionColors = [
  '#000000', '#ffffff', '#00c875', '#4eccc6', '#00854d', '#037f4c', '#fdab3d', '#ff7575',
  '#e2445c', '#ff158a', '#bb3354', '#ff5ac4', '#a25ddc', '#784bd1', '#401694', '#225091',
  '#579bfc', '#0086c0', '#66ccff', '#9cd326', '#cab641', '#ffcb00', '#7f5347', '#808080',
]

export function safeDiscussionLink(value: string): boolean {
  try {
    const url = new URL(value.trim())
    return ['http:', 'https:'].includes(url.protocol) && Boolean(url.hostname)
      || url.protocol === 'mailto:' && Boolean(url.pathname)
  } catch { return false }
}

const Direction = Extension.create({
  name: 'discussionDirection',
  addGlobalAttributes() {
    return [{
      types: ['paragraph', 'heading', 'blockquote', 'codeBlock', 'listItem', 'taskItem'],
      attributes: {
        dir: {
          default: null,
          parseHTML: element => ['ltr', 'rtl'].includes(element.getAttribute('dir') ?? '')
            ? element.getAttribute('dir') : null,
          renderHTML: attrs => attrs.dir ? { dir: attrs.dir } : {},
        },
      },
    }]
  },
})

function memberSuggestion(getMembers: () => ProjectMember[], onOpen: () => void) {
  const members = (query: string) => getMembers().filter(member => member.membershipStatus === 'ACTIVE'
    && member.displayName.toLocaleLowerCase().includes(query.toLocaleLowerCase()))
  return {
    char: '@',
    allowedPrefixes: null,
    items: ({ query }: { query: string }) => members(query),
    render: () => {
      let popup: HTMLDivElement | undefined
      let input: HTMLInputElement | undefined
      let list: HTMLDivElement | undefined
      let current: SuggestionProps<ProjectMember> | undefined
      let candidates: ProjectMember[] = []
      let selected = 0
      const choose = () => {
        const member = candidates[selected]
        if (member) current?.command({ id: member.userId, label: member.displayName })
      }
      const draw = () => {
        if (!list) return
        candidates = members(input?.value ?? current?.query ?? '')
        selected = Math.min(selected, Math.max(0, candidates.length - 1))
        list.replaceChildren(...candidates.map((member, index) => {
          const option = document.createElement('button')
          option.type = 'button'
          option.setAttribute('role', 'option')
          option.setAttribute('aria-selected', String(index === selected))
          option.textContent = member.displayName
          option.onmousedown = event => event.preventDefault()
          option.onclick = () => { selected = index; choose() }
          return option
        }))
        if (!candidates.length) list.textContent = '没有匹配的项目成员'
        list.querySelector('[aria-selected="true"]')?.scrollIntoView?.({ block: 'nearest' })
      }
      const position = () => {
        if (!popup || !current) return
        const rect = current.clientRect?.() ?? current.editor.view.dom.getBoundingClientRect()
        popup.style.left = `${Math.max(8, Math.min(rect.left, window.innerWidth - 288))}px`
        popup.style.top = `${Math.max(8, Math.min(rect.bottom + 6, window.innerHeight - popup.offsetHeight - 8))}px`
      }
      const keydown = (event: KeyboardEvent) => {
        if (event.key === 'Escape') {
          const editor = current?.editor
          if (editor) exitSuggestion(editor.view)
          editor?.commands.focus()
          return true
        }
        if (!candidates.length) return event.key === 'Enter'
        if (event.key === 'ArrowDown') selected = (selected + 1) % candidates.length
        else if (event.key === 'ArrowUp') selected = (selected + candidates.length - 1) % candidates.length
        else if (event.key === 'Enter') { choose(); return true }
        else return false
        draw()
        return true
      }
      const outside = (event: PointerEvent) => {
        if (popup?.contains(event.target as Node) || current?.editor.view.dom.contains(event.target as Node)) return
        if (current) exitSuggestion(current.editor.view)
      }
      return {
        onStart: (props: SuggestionProps<ProjectMember>) => {
          onOpen()
          current = props
          popup = document.createElement('div')
          popup.className = 'discussion-member-popup'
          popup.dataset.discussionPopup = 'true'
          popup.setAttribute('role', 'dialog')
          popup.setAttribute('aria-label', '提及项目成员')
          input = document.createElement('input')
          input.setAttribute('aria-label', '搜索项目成员')
          input.placeholder = '搜索项目成员'
          input.value = props.query
          input.oninput = () => { selected = 0; draw(); position() }
          input.onkeydown = event => { if (keydown(event)) { event.preventDefault(); event.stopPropagation() } }
          list = document.createElement('div')
          list.setAttribute('role', 'listbox')
          list.setAttribute('aria-label', '项目成员')
          popup.append(input, list)
          document.body.append(popup)
          draw()
          position()
          window.addEventListener('scroll', position, true)
          window.addEventListener('resize', position)
          document.addEventListener('pointerdown', outside)
        },
        onUpdate: (props: SuggestionProps<ProjectMember>) => {
          current = props
          if (input) input.value = props.query
          selected = 0
          draw()
          position()
        },
        onKeyDown: ({ event }: { event: KeyboardEvent }) => keydown(event),
        onExit: () => {
          popup?.remove()
          popup = undefined
          current = undefined
          window.removeEventListener('scroll', position, true)
          window.removeEventListener('resize', position)
          document.removeEventListener('pointerdown', outside)
        },
      }
    },
  }
}

export function discussionExtensions(getMembers: () => ProjectMember[], onMentionOpen: () => void = () => {}) {
  return [
    StarterKit.configure({ heading: { levels: [2] }, link: false }),
    Link.configure({
      openOnClick: false,
      protocols: ['http', 'https', 'mailto'],
      isAllowedUri: safeDiscussionLink,
      HTMLAttributes: { target: '_blank', rel: 'nofollow noopener noreferrer' },
    }),
    TextStyle, Color, BackgroundColor, FontSize,
    TextAlign.configure({ types: ['heading', 'paragraph'], alignments: ['left', 'center', 'right'] }),
    Direction,
    TableKit.configure({ table: { resizable: false } }),
    TaskList,
    TaskItem.extend({
      renderHTML({ HTMLAttributes }) {
        return ['li', mergeAttributes(HTMLAttributes, { 'data-type': 'taskItem' }), 0]
      },
    }).configure({ nested: true, a11y: { checkboxLabel: node => `完成：${node.textContent || '清单项'}` } }),
    Mention.extend({
      addAttributes() {
        return {
          id: { default: null, parseHTML: element => element.getAttribute('data-mention-user-id'), rendered: false },
          label: { default: null, parseHTML: element => element.textContent?.replace(/^@/, ''), rendered: false },
        }
      },
    }).configure({
      renderHTML: ({ node }) => ['span', {
        'data-type': 'mention', 'data-mention-user-id': String(node.attrs.id),
      }, `@${String(node.attrs.label ?? node.attrs.id)}`],
      suggestion: memberSuggestion(getMembers, onMentionOpen),
    }),
  ]
}

export function discussionHasDraft(editor: Editor | undefined): boolean {
  if (!editor) return false
  return Boolean(editor.getText().trim()) || /<(?:table|hr|pre|ul|ol|h2|blockquote)\b/.test(editor.getHTML())
}
