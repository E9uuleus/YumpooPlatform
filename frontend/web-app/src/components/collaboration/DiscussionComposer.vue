<script setup lang="ts">
import { EditorContent, type Editor } from '@tiptap/vue-3'
import { exitSuggestion } from '@tiptap/suggestion'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { discussionColors, discussionFontSizes, discussionHasDraft, safeDiscussionLink } from './discussionEditor'
import './discussionRichText.css'

const props = withDefaults(defineProps<{
  editor?: Editor | undefined
  busy?: boolean
  collapsible?: boolean
  showSubmit?: boolean
  submitDisabled?: boolean
}>(), { editor: undefined, busy: false, collapsible: true, showSubmit: true, submitDisabled: false })
const emit = defineEmits<{ submit: [] }>()
type Panel = 'format' | 'color' | 'size' | 'table' | 'link' | 'align' | 'direction' | 'emoji'
const panelNames: Record<Panel, string> = { format: '正文格式', color: '文字颜色与高亮', size: '字号', table: '表格', link: '链接', align: '对齐', direction: '文字方向', emoji: '表情' }
const root = ref<HTMLElement>()
const popup = ref<HTMLElement>()
const expanded = ref(!props.collapsible)
const panel = ref<Panel>()
const revision = ref(0)
const panelStyle = ref({ left: '0px', top: '0px' })
const colorMode = ref<'color' | 'background'>('color')
const rows = ref(3)
const columns = ref(3)
const href = ref('')
const linkText = ref('')
const linkError = ref('')
const emojiQuery = ref('')
let selection = { from: 1, to: 1 }
let selectedText = ''
let anchor: HTMLElement | undefined
let blurTimer: ReturnType<typeof setTimeout> | undefined
const emojis = [
  ['😀', '笑 开心 happy smile'], ['😂', '笑哭 joy'], ['😊', '微笑 smile'], ['🥰', '喜爱 love'],
  ['😎', '酷 cool'], ['🤔', '思考 think'], ['😅', '汗 sweat'], ['😢', '哭 sad'],
  ['😮', '惊讶 surprise'], ['😴', '睡觉 sleep'], ['🥳', '庆祝 party'], ['🤝', '握手 合作 handshake'],
  ['👍', '赞 好 同意 thumbsup'], ['👎', '反对 thumbsdown'], ['👏', '鼓掌 clap'], ['🙌', '欢呼 hooray'],
  ['🙏', '感谢 拜托 thanks'], ['👋', '你好 再见 wave'], ['💪', '加油 strong'], ['👌', '好的 ok'],
  ['❤️', '爱 红心 heart'], ['💙', '蓝心 heart'], ['✨', '闪亮 sparkles'], ['⭐', '星 star'],
  ['🎉', '庆祝 恭喜 party'], ['🎊', '庆祝 confetti'], ['🎁', '礼物 gift'], ['🏆', '奖杯 trophy'],
  ['✅', '完成 成功 done check'], ['❌', '错误 取消 cross'], ['⚠️', '注意 警告 warning'], ['❓', '问题 question'],
  ['💡', '想法 idea'], ['🔥', '火 热门 fire'], ['🚀', '发布 火箭 rocket'], ['🐛', '缺陷 bug'],
  ['🔧', '修复 工具 fix'], ['📝', '笔记 文档 note'], ['📌', '标记 pin'], ['📎', '附件 clip'],
  ['📅', '日期 日程 calendar'], ['⏰', '时间 提醒 clock'], ['⏳', '等待 hourglass'], ['👀', '查看 eyes'],
  ['💻', '电脑 开发 computer'], ['🔍', '搜索 search'], ['🔒', '安全 lock'], ['☕', '咖啡 coffee'],
].map(([value, keywords]) => ({ value: value!, keywords: keywords! }))
const filteredEmojis = computed(() => emojis.filter(item => `${item.value} ${item.keywords}`
  .toLocaleLowerCase().includes(emojiQuery.value.toLocaleLowerCase().trim())))
const empty = computed(() => { void revision.value; return !discussionHasDraft(props.editor) })
const unavailable = computed(() => props.busy || !props.editor?.isEditable)
const simpleActions = [
  { name: '粗体', symbol: 'B', mark: 'bold', run: (e: Editor) => e.chain().focus().toggleBold().run() },
  { name: '斜体', symbol: 'I', mark: 'italic', run: (e: Editor) => e.chain().focus().toggleItalic().run() },
  { name: '下划线', symbol: 'U', mark: 'underline', run: (e: Editor) => e.chain().focus().toggleUnderline().run() },
  { name: '删除线', symbol: 'S', mark: 'strike', run: (e: Editor) => e.chain().focus().toggleStrike().run() },
]
const listActions = [
  { name: '编号', symbol: '1.', mark: 'orderedList', run: (e: Editor) => e.chain().focus().toggleOrderedList().run() },
  { name: '项目符号', symbol: '≡', mark: 'bulletList', run: (e: Editor) => e.chain().focus().toggleBulletList().run() },
]
const tableActions = [
  { name: '上方插入行', run: (e: Editor) => e.chain().focus().addRowBefore().run() },
  { name: '下方插入行', run: (e: Editor) => e.chain().focus().addRowAfter().run() },
  { name: '左侧插入列', run: (e: Editor) => e.chain().focus().addColumnBefore().run() },
  { name: '右侧插入列', run: (e: Editor) => e.chain().focus().addColumnAfter().run() },
  { name: '切换表头', run: (e: Editor) => e.chain().focus().toggleHeaderRow().run() },
  { name: '删除当前行', run: (e: Editor) => e.chain().focus().deleteRow().run() },
  { name: '删除当前列', run: (e: Editor) => e.chain().focus().deleteColumn().run() },
  { name: '删除表格', run: (e: Editor) => e.chain().focus().deleteTable().run() },
]

function changed() { revision.value++; if (!empty.value) expanded.value = true }
watch(() => props.editor, (editor, old) => {
  old?.off('transaction', changed)
  editor?.on('transaction', changed)
}, { immediate: true })

function closePanel() { panel.value = undefined }
function reset() { closePanel(); expanded.value = !props.collapsible }
function run(action: (editor: Editor) => unknown) {
  if (!props.editor || unavailable.value) return
  closePanel()
  exitSuggestion(props.editor.view)
  action(props.editor)
}
function position() {
  if (!anchor || !popup.value) return
  const rect = anchor.getBoundingClientRect()
  const height = popup.value.offsetHeight
  panelStyle.value = {
    left: `${Math.max(8, Math.min(rect.left, window.innerWidth - Math.min(304, window.innerWidth - 16) - 8))}px`,
    top: `${Math.max(8, Math.min(rect.bottom + 6, window.innerHeight - height - 8))}px`,
  }
}
function openPanel(name: Panel, event: MouseEvent) {
  if (!props.editor || unavailable.value) return
  if (panel.value === name) { closePanel(); return }
  exitSuggestion(props.editor.view)
  if (name === 'link') props.editor.chain().extendMarkRange('link').run()
  selection = { from: props.editor.state.selection.from, to: props.editor.state.selection.to }
  selectedText = props.editor.state.doc.textBetween(selection.from, selection.to, ' ')
  href.value = String(props.editor.getAttributes('link').href ?? '')
  linkText.value = selectedText
  linkError.value = ''
  panel.value = name
  expanded.value = true
  anchor = event.currentTarget as HTMLElement
  void nextTick(position)
}
function restore(editor: Editor) { return editor.chain().focus().setTextSelection(selection) }
function mention() {
  expanded.value = true
  run(editor => editor.chain().focus().insertContent('@').run())
}
function applyLink() {
  const url = href.value.trim()
  if (!safeDiscussionLink(url)) { linkError.value = '请输入绝对 http、https 或 mailto 地址。'; return }
  run(editor => {
    const chain = restore(editor)
    if (selectedText && linkText.value === selectedText) chain.setLink({ href: url }).run()
    else chain.insertContent({ type: 'text', text: linkText.value.trim() || url,
      marks: [{ type: 'link', attrs: { href: url } }] }).run()
  })
}
function applyColor(color?: string) {
  run(editor => {
    const chain = restore(editor)
    if (colorMode.value === 'color') { if (color) chain.setColor(color).run(); else chain.unsetColor().run() }
    else { if (color) chain.setBackgroundColor(color).run(); else chain.unsetBackgroundColor().run() }
  })
}
function insertTable() {
  if (![rows.value, columns.value].every(value => Number.isInteger(value) && value >= 1 && value <= 20)) return
  run(editor => restore(editor).insertTable({ rows: rows.value, cols: columns.value, withHeaderRow: true }).run())
}
function direction(dir: string) {
  run(editor => restore(editor).updateAttributes('paragraph', { dir }).updateAttributes('heading', { dir })
    .updateAttributes('codeBlock', { dir }).updateAttributes('blockquote', { dir }).run())
}
function collapseIfEmpty() {
  if (!props.collapsible || !empty.value || panel.value) return
  const active = document.activeElement
  if (active && (root.value?.contains(active) || active.closest('[data-discussion-popup]'))) return
  expanded.value = false
}
function blur() { if (blurTimer) clearTimeout(blurTimer); blurTimer = setTimeout(collapseIfEmpty, 0) }
function outside(event: PointerEvent) {
  const target = event.target as HTMLElement
  if (root.value?.contains(target) || popup.value?.contains(target)
    || target.closest?.('.discussion-member-popup')) return
  closePanel()
  if (props.collapsible && empty.value) expanded.value = false
}
function escape(event: KeyboardEvent) {
  if (event.key === 'Escape' && panel.value) {
    event.stopPropagation()
    closePanel()
    props.editor?.commands.focus()
  }
}
onMounted(() => {
  document.addEventListener('pointerdown', outside)
  window.addEventListener('resize', position)
  window.addEventListener('scroll', position, true)
})
onBeforeUnmount(() => {
  props.editor?.off('transaction', changed)
  document.removeEventListener('pointerdown', outside)
  window.removeEventListener('resize', position)
  window.removeEventListener('scroll', position, true)
  if (blurTimer) clearTimeout(blurTimer)
})
defineExpose({ closePanel, reset })
</script>

<template>
  <div
    ref="root"
    class="discussion-composer"
    :class="{ 'discussion-composer--expanded': expanded }"
    :data-revision="revision"
    @focusin="expanded = true"
    @focusout="blur"
    @keydown="escape"
  >
    <div
      v-if="expanded"
      class="discussion-toolbar"
      role="toolbar"
      aria-label="讨论格式"
    >
      <button
        type="button"
        aria-label="格式"
        title="格式"
        :disabled="unavailable"
        :aria-expanded="panel === 'format'"
        @mousedown.prevent
        @click="openPanel('format', $event)"
      >
        ¶
      </button>
      <button
        v-for="action in simpleActions"
        :key="action.name"
        type="button"
        :aria-label="action.name"
        :title="action.name"
        :class="action.mark"
        :aria-pressed="editor?.isActive(action.mark)"
        :disabled="unavailable"
        @mousedown.prevent
        @click="run(action.run)"
      >
        {{ action.symbol }}
      </button>
      <button
        type="button"
        aria-label="文字颜色"
        title="文字颜色与高亮"
        :disabled="unavailable"
        :aria-expanded="panel === 'color'"
        @mousedown.prevent
        @click="openPanel('color', $event)"
      >
        A̲
      </button>
      <button
        type="button"
        aria-label="字号"
        title="字号"
        :disabled="unavailable"
        :aria-expanded="panel === 'size'"
        @mousedown.prevent
        @click="openPanel('size', $event)"
      >
        A↕
      </button>
      <button
        v-for="action in listActions"
        :key="action.name"
        type="button"
        :aria-label="action.name"
        :title="action.name"
        :aria-pressed="editor?.isActive(action.mark)"
        :disabled="unavailable"
        @mousedown.prevent
        @click="run(action.run)"
      >
        {{ action.symbol }}
      </button>
      <button
        type="button"
        aria-label="表格"
        title="表格"
        :disabled="unavailable"
        :aria-expanded="panel === 'table'"
        @mousedown.prevent
        @click="openPanel('table', $event)"
      >
        ▦
      </button>
      <button
        type="button"
        aria-label="链接"
        title="链接"
        :disabled="unavailable"
        :aria-pressed="editor?.isActive('link')"
        @mousedown.prevent
        @click="openPanel('link', $event)"
      >
        ↗
      </button>
      <button
        type="button"
        aria-label="对齐"
        title="对齐"
        :disabled="unavailable"
        :aria-expanded="panel === 'align'"
        @mousedown.prevent
        @click="openPanel('align', $event)"
      >
        ☰
      </button>
      <button
        type="button"
        aria-label="分隔线"
        title="分隔线"
        :disabled="unavailable"
        @mousedown.prevent
        @click="run(e => e.chain().focus().setHorizontalRule().run())"
      >
        ―
      </button>
      <button
        type="button"
        aria-label="文字方向"
        title="文字方向"
        :disabled="unavailable"
        :aria-expanded="panel === 'direction'"
        @mousedown.prevent
        @click="openPanel('direction', $event)"
      >
        ⇄
      </button>
      <button
        type="button"
        aria-label="清单"
        title="清单"
        :disabled="unavailable"
        :aria-pressed="editor?.isActive('taskList')"
        @mousedown.prevent
        @click="run(e => e.chain().focus().toggleTaskList().run())"
      >
        ☑
      </button>
      <button
        type="button"
        aria-label="行内代码"
        title="行内代码"
        :disabled="unavailable"
        :aria-pressed="editor?.isActive('code')"
        @mousedown.prevent
        @click="run(e => e.chain().focus().toggleCode().run())"
      >
        &lt;/&gt;
      </button>
    </div>
    <div
      class="discussion-editor"
      @click="expanded = true"
    >
      <span
        v-if="empty"
        class="discussion-editor__placeholder"
      >写下讨论，输入 @ 提及项目成员…</span>
      <editor-content
        v-if="editor"
        :editor="editor"
        class="discussion-rich-text"
      />
    </div>
    <div class="discussion-composer__footer">
      <div class="discussion-composer__insert">
        <button
          type="button"
          aria-label="提及项目成员"
          title="提及项目成员"
          :disabled="unavailable"
          @mousedown.prevent
          @click="mention"
        >
          @
        </button>
        <button
          v-if="expanded"
          type="button"
          aria-label="表情"
          title="表情"
          :disabled="unavailable"
          :aria-expanded="panel === 'emoji'"
          @mousedown.prevent
          @click="openPanel('emoji', $event)"
        >
          ☺
        </button>
      </div>
      <button
        v-if="expanded && showSubmit"
        type="button"
        class="discussion-submit"
        :disabled="unavailable || submitDisabled"
        :aria-busy="busy"
        @click="emit('submit')"
      >
        {{ busy ? '发布中…' : '发布讨论' }}
      </button>
    </div>
    <teleport to="body">
      <div
        v-if="panel"
        ref="popup"
        class="discussion-editor-popup"
        :style="panelStyle"
        role="dialog"
        :aria-label="panelNames[panel]"
        data-discussion-popup
        @keydown="escape"
      >
        <template v-if="panel === 'format'">
          <button
            type="button"
            :aria-pressed="editor?.isActive('paragraph')"
            @click="run(e => restore(e).setParagraph().run())"
          >
            正文
          </button>
          <button
            type="button"
            :aria-pressed="editor?.isActive('heading', { level: 2 })"
            @click="run(e => restore(e).toggleHeading({ level: 2 }).run())"
          >
            标题
          </button>
          <button
            type="button"
            :aria-pressed="editor?.isActive('blockquote')"
            @click="run(e => restore(e).toggleBlockquote().run())"
          >
            引用
          </button>
          <button
            type="button"
            :aria-pressed="editor?.isActive('codeBlock')"
            @click="run(e => restore(e).toggleCodeBlock().run())"
          >
            代码块
          </button>
        </template>
        <template v-else-if="panel === 'color'">
          <div class="discussion-color-tabs">
            <button
              type="button"
              :aria-pressed="colorMode === 'color'"
              @click="colorMode = 'color'"
            >
              文字色
            </button><button
              type="button"
              :aria-pressed="colorMode === 'background'"
              @click="colorMode = 'background'"
            >
              高亮背景
            </button>
          </div>
          <div class="discussion-color-grid">
            <button
              v-for="color in discussionColors"
              :key="color"
              type="button"
              :aria-label="`选择颜色 ${color}`"
              :title="color"
              :style="{ backgroundColor: color }"
              @click="applyColor(color)"
            />
          </div>
          <button
            type="button"
            @click="applyColor()"
          >
            清除颜色
          </button>
        </template>
        <template v-else-if="panel === 'size'">
          <button
            v-for="size in discussionFontSizes"
            :key="size"
            :aria-pressed="editor?.getAttributes('textStyle').fontSize === `${size}px`"
            type="button"
            @click="run(e => restore(e).setFontSize(`${size}px`).run())"
          >
            {{ size }}px
          </button>
          <button
            type="button"
            @click="run(e => restore(e).unsetFontSize().run())"
          >
            恢复默认字号
          </button>
        </template>
        <template v-else-if="panel === 'table'">
          <form
            class="discussion-table-form"
            @submit.prevent="insertTable"
          >
            <label>行数<input
              v-model.number="rows"
              aria-label="表格行数"
              type="number"
              min="1"
              max="20"
              required
            ></label><label>列数<input
              v-model.number="columns"
              aria-label="表格列数"
              type="number"
              min="1"
              max="20"
              required
            ></label><button type="submit">
              插入表格
            </button>
          </form>
          <button
            v-for="action in tableActions"
            :key="action.name"
            type="button"
            :disabled="!editor?.isActive('table')"
            @click="run(action.run)"
          >
            {{ action.name }}
          </button>
        </template>
        <form
          v-else-if="panel === 'link'"
          class="discussion-link-form"
          @submit.prevent="applyLink"
        >
          <label>链接地址<input
            v-model="href"
            aria-label="链接地址"
            placeholder="https://example.com"
            autofocus
          ></label>
          <label>显示文字<input
            v-model="linkText"
            aria-label="显示文字"
          ></label>
          <p
            v-if="linkError"
            role="alert"
          >
            {{ linkError }}
          </p>
          <button type="submit">
            应用链接
          </button><button
            type="button"
            :disabled="!editor?.isActive('link')"
            @click="run(e => restore(e).unsetLink().run())"
          >
            移除链接
          </button>
        </form>
        <template v-else-if="panel === 'align'">
          <button
            type="button"
            :aria-pressed="editor?.isActive({ textAlign: 'left' })"
            @click="run(e => restore(e).setTextAlign('left').run())"
          >
            左对齐
          </button>
          <button
            type="button"
            :aria-pressed="editor?.isActive({ textAlign: 'center' })"
            @click="run(e => restore(e).setTextAlign('center').run())"
          >
            居中
          </button>
          <button
            type="button"
            :aria-pressed="editor?.isActive({ textAlign: 'right' })"
            @click="run(e => restore(e).setTextAlign('right').run())"
          >
            右对齐
          </button>
        </template>
        <template v-else-if="panel === 'direction'">
          <button
            type="button"
            :aria-pressed="editor?.isActive({ dir: 'ltr' })"
            @click="direction('ltr')"
          >
            从左到右
          </button><button
            type="button"
            :aria-pressed="editor?.isActive({ dir: 'rtl' })"
            @click="direction('rtl')"
          >
            从右到左
          </button>
        </template>
        <template v-else-if="panel === 'emoji'">
          <input
            v-model="emojiQuery"
            aria-label="搜索表情"
            placeholder="搜索表情，如：完成、庆祝"
          >
          <div class="discussion-emoji-grid">
            <button
              v-for="item in filteredEmojis"
              :key="item.value"
              type="button"
              :aria-label="`${item.value} ${item.keywords}`"
              :title="item.keywords"
              @click="run(e => restore(e).insertContent({ type: 'text', text: item.value }).run())"
            >
              {{ item.value }}
            </button>
          </div>
          <p v-if="!filteredEmojis.length">
            没有匹配的表情
          </p>
        </template>
        <button
          type="button"
          class="discussion-popup-close"
          @click="closePanel(); editor?.commands.focus()"
        >
          关闭
        </button>
      </div>
    </teleport>
  </div>
</template>

<style scoped>
.discussion-composer { min-width: 0; border: 1px solid var(--yp-border-strong); border-radius: var(--yp-radius-md); background: var(--yp-bg-surface); }
.discussion-composer:focus-within { border-color: var(--yp-action-primary); }
.discussion-toolbar { display: flex; flex-wrap: wrap; gap: 2px; padding: 6px; border-bottom: 1px solid var(--yp-border-subtle); }
.discussion-toolbar button, .discussion-composer__insert button { display: inline-grid; place-items: center; width: 30px; height: 30px; padding: 0; border: 0; border-radius: 4px; color: var(--yp-text-secondary); background: transparent; font-size: 15px; cursor: pointer; }
.discussion-toolbar .bold { font-weight: 750; }.discussion-toolbar .italic { font-style: italic; }.discussion-toolbar .underline { text-decoration: underline; }.discussion-toolbar .strike { text-decoration: line-through; }
.discussion-toolbar button:hover, .discussion-composer__insert button:hover, .discussion-toolbar button[aria-pressed='true'], .discussion-toolbar button[aria-expanded='true'] { color: var(--yp-action-primary); background: var(--yp-bg-selected); }
button:focus-visible { outline: 2px solid var(--yp-action-primary); outline-offset: 2px; } button:disabled { cursor: not-allowed; opacity: .45; }
.discussion-editor { position: relative; cursor: text; }
.discussion-editor__placeholder { position: absolute; top: 14px; left: 14px; right: 12px; color: var(--yp-text-muted); pointer-events: none; font-size: 14px; }
.discussion-editor :deep(.ProseMirror) { min-height: 48px; padding: 14px; outline: none; }
.discussion-composer--expanded .discussion-editor :deep(.ProseMirror) { min-height: 128px; }
.discussion-composer__footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 38px; padding: 4px 8px 8px; }
.discussion-composer__insert { display: flex; gap: 2px; }.discussion-composer__insert button { font-size: 21px; }
.discussion-submit { padding: 7px 14px; border: 0; border-radius: 4px; background: var(--yp-action-primary); color: white; cursor: pointer; font-size: 14px; }
</style>
