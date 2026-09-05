import { Editor } from '@tiptap/vue-3'
import { mount, flushPromises, DOMWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import type { ProjectMember } from '@yumpoo/api-client'
import DiscussionComposer from './DiscussionComposer.vue'
import { discussionExtensions, discussionFontSizes, safeDiscussionLink } from './discussionEditor'

let editor: Editor
let wrapper: ReturnType<typeof mount>
async function setup(content = '<p>测试文字</p>', members: ProjectMember[] = []) {
  editor = new Editor({ content, extensions: discussionExtensions(() => members) })
  wrapper = mount(DiscussionComposer, { props: { editor, collapsible: false }, attachTo: document.body })
  editor.commands.setTextSelection({ from: 1, to: 5 })
  await flushPromises()
}
async function click(name: string) {
  const button = Array.from(document.querySelectorAll<HTMLButtonElement>('button'))
    .find(item => (item.getAttribute('aria-label') ?? item.textContent?.trim()) === name)
  expect(button, name).toBeDefined()
  button!.click()
  await flushPromises()
}
async function input(label: string, value: string) {
  const element = document.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!
  await new DOMWrapper(element).setValue(value)
}
function html() { return editor.getHTML() }
function roundtrip() {
  const normalize = (value: unknown): unknown => {
    if (value === '') return null
    if (Array.isArray(value)) return value.filter(item => !(item.type === 'text' && !item.text.trim())).map(normalize)
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, normalize(item)]))
    return value
  }
  const before = normalize(editor.getJSON())
  const copy = new Editor({ content: html(), extensions: discussionExtensions(() => []) })
  expect(normalize(copy.getJSON())).toEqual(before)
  copy.destroy()
}
afterEach(() => { wrapper?.unmount(); editor?.destroy(); document.querySelectorAll('[data-discussion-popup]').forEach(item => item.remove()) })

describe('DiscussionComposer', () => {
  it('可写状态恢复后工具栏同步恢复可用', async () => {
    await setup()
    editor.setEditable(false)
    await flushPromises()
    expect(wrapper.get('[aria-label="粗体"]').attributes('disabled')).toBeDefined()
    editor.setEditable(true)
    await flushPromises()
    expect(wrapper.get('[aria-label="粗体"]').attributes('disabled')).toBeUndefined()
    await click('粗体')
    expect(html()).toContain('<strong>测试文字</strong>')
  })
  it.each([['粗体', 'strong'], ['斜体', 'em'], ['下划线', 'u'], ['删除线', 's'], ['行内代码', 'code']])('%s 支持选区切换和再次编辑', async (name, tag) => {
    await setup()
    await click(name!)
    expect(html()).toContain(`<${tag}>测试文字</${tag}>`)
    expect(wrapper.find(`[aria-label="${name}"]`).attributes('aria-pressed')).toBe('true')
    roundtrip()
    await click(name!)
    expect(html()).not.toContain(`<${tag}>`)
  })
  it.each([['标题', 'h2'], ['引用', 'blockquote'], ['代码块', 'pre']])('%s 可保存并恢复正文', async (name, tag) => {
    await setup()
    await click('格式'); await click(name!)
    expect(html()).toContain(`<${tag}>`)
    roundtrip()
    await click('格式'); await click(name!)
    await click('格式'); await click('正文')
    expect(html().replaceAll('<p></p>', '')).toBe('<p>测试文字</p>')
  })
  it.each([['编号', 'ol'], ['项目符号', 'ul'], ['清单', 'ul']])('%s 保存结构及勾选状态', async (name, tag) => {
    await setup()
    await click(name!)
    expect(html()).toContain(`<${tag}`)
    if (name === '清单') {
      const checkbox = wrapper.get('input[type="checkbox"]')
      await checkbox.setValue(true)
      expect(html()).toContain('data-checked="true"')
      expect(html()).not.toContain('<input')
    }
    roundtrip()
  })
  it('分隔线、所有字号预设与恢复默认、方向和对齐', async () => {
    await setup()
    for (const size of discussionFontSizes) {
      await click('字号'); await click(`${size}px`)
      expect(html()).toContain(`font-size: ${size}px`)
      roundtrip()
    }
    await click('字号'); await click('恢复默认字号')
    expect(html()).not.toContain('font-size')
    for (const [name, value] of [['左对齐', 'left'], ['居中', 'center'], ['右对齐', 'right']]) {
      await click('对齐'); await click(name!)
      expect(html()).toContain(`text-align: ${value}`)
      roundtrip()
    }
    for (const [name, value] of [['从右到左', 'rtl'], ['从左到右', 'ltr']]) {
      await click('文字方向'); await click(name!)
      expect(html()).toContain(`dir="${value}"`)
      roundtrip()
    }
    await click('分隔线')
    expect(html()).toContain('<hr>')
  })
  it('颜色与背景高亮在弹窗操作后仍作用于原选区', async () => {
    await setup()
    await click('文字颜色'); await click('选择颜色 #e2445c')
    expect(html()).toContain('color: #e2445c')
    await click('文字颜色'); await click('高亮背景'); await click('选择颜色 #ffcb00')
    expect(html()).toContain('background-color: #ffcb00')
    roundtrip()
    await click('文字颜色'); await click('清除颜色')
    expect(html()).not.toContain('background-color')
    await click('文字颜色'); await click('文字色'); await click('清除颜色')
    expect(html()).not.toContain('style')
  })
  it('链接校验、显示文字、移除和表情搜索', async () => {
    await setup()
    await click('链接'); await input('链接地址', 'javascript:alert(1)'); await click('应用链接')
    expect(document.body.textContent).toContain('请输入绝对')
    expect(html()).not.toContain('href')
    await input('链接地址', 'https://example.com'); await input('显示文字', '安全链接'); await click('应用链接')
    expect(html()).toContain('href="https://example.com"')
    expect(editor.getText()).toBe('安全链接')
    editor.commands.setTextSelection(2)
    await click('链接'); await click('移除链接')
    expect(html()).not.toContain('<a ')
    await click('表情'); await input('搜索表情', '没有匹配999')
    expect(document.body.textContent).toContain('没有匹配的表情')
    await input('搜索表情', '庆祝')
    const emoji = document.querySelector<HTMLButtonElement>('.discussion-emoji-grid button')!
    const text = emoji.textContent
    emoji.click(); await flushPromises()
    expect(editor.getText()).toContain(text)
    roundtrip()
    for (const unsafe of ['/relative', '//example.com', 'data:text/html,x', 'file:///tmp', 'mailto:']) expect(safeDiscussionLink(unsafe)).toBe(false)
  })
  it('表格行列、表头与删除操作可往返保存', async () => {
    await setup()
    editor.commands.setTextSelection(editor.state.doc.content.size - 1)
    await click('表格'); await input('表格行数', '2'); await input('表格列数', '2'); await click('插入表格')
    const count = (selector: string) => { const dom = document.createElement('div'); dom.innerHTML = html(); return dom.querySelectorAll(selector).length }
    expect(count('tr')).toBe(2); expect(count('th')).toBe(2)
    for (const name of ['上方插入行', '下方插入行', '左侧插入列', '右侧插入列']) { await click('表格'); await click(name); roundtrip() }
    expect(count('tr')).toBe(4); expect(count('td,th')).toBe(16)
    await click('表格'); await click('切换表头'); roundtrip()
    await click('表格'); await click('删除当前行'); expect(count('tr')).toBe(3)
    await click('表格'); await click('删除当前列'); expect(count('td,th')).toBe(9)
    await click('表格'); await click('删除表格'); expect(html()).not.toContain('<table')
  })
  it('按钮与键入 @ 使用同一成员弹窗，仅 ACTIVE，支持搜索与键盘选择', async () => {
    const members = [{ userId: '35000000-0000-4000-8000-000000000001', displayName: '张三', membershipStatus: 'ACTIVE' },
      { userId: '35000000-0000-4000-8000-000000000002', displayName: '李四', membershipStatus: 'REMOVED' }] as ProjectMember[]
    await setup('<p></p>', members)
    editor.commands.setTextSelection(1)
    await click('提及项目成员')
    expect(document.querySelector('.discussion-member-popup')?.textContent).toContain('张三')
    expect(document.querySelector('.discussion-member-popup')?.textContent).not.toContain('李四')
    await input('搜索项目成员', '未知')
    expect(document.body.textContent).toContain('没有匹配的项目成员')
    await input('搜索项目成员', '张')
    await new DOMWrapper(document.querySelector('input[aria-label="搜索项目成员"]')!).trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(html()).toContain('data-mention-user-id="35000000-0000-4000-8000-000000000001"')
    roundtrip()
    editor.commands.clearContent()
    editor.commands.insertContent('@张')
    await flushPromises()
    expect(document.querySelector('.discussion-member-popup')?.textContent).toContain('张三')
  })
  it('空白失焦折叠，有结构草稿时保持展开', async () => {
    await setup('<p></p>')
    await wrapper.setProps({ collapsible: true })
    document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }))
    await flushPromises()
    expect(wrapper.find('[role="toolbar"]').exists()).toBe(false)
    editor.commands.insertContent('<hr>')
    await flushPromises()
    document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }))
    await flushPromises()
    expect(wrapper.find('[role="toolbar"]').exists()).toBe(true)
  })
})
