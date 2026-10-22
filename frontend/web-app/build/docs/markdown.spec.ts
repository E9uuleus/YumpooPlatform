import { describe, expect, it } from 'vitest'
import { checkDiagram, compileDoc } from './markdown'

const FRONT = '---\ntitle: 示例\ndescription: 一句话。\norder: 10\naudience: [member]\n---\n'
const compile = (body: string, path = 'guide/sample.md') => compileDoc(`${FRONT}\n${body}`, path)

describe('文档构建期编译', () => {
  it('标题锚点与 doc-sync 的 githubSlug 一致，重复标题加后缀', () => {
    const doc = compile('## 计时与工时（Time）\n\n### Shift+Enter 快速添加\n\n## 常见问题\n\n## 常见问题\n')
    expect(doc.meta.headings).toEqual([
      { depth: 2, text: '计时与工时（Time）', id: '计时与工时time' },
      { depth: 3, text: 'Shift+Enter 快速添加', id: 'shiftenter-快速添加' },
      { depth: 2, text: '常见问题', id: '常见问题' },
      { depth: 2, text: '常见问题', id: '常见问题-1' },
    ])
    expect(doc.html).toContain('<h2 id="常见问题-1"><a class="docs-anchor" href="#常见问题-1" aria-label="链接到本节">#</a>常见问题</h2>')
  })

  it('改写站内文档、仓库文件、外部与应用路径四类链接', () => {
    const doc = compile([
      '[计时](time-tracking.md#并行计时)',
      '[规则](../../../../../../backend/pom.xml)',
      '[官网](https://example.com/a)',
      '[下载](/download)',
    ].join(' '))
    expect(doc.html).toContain('href="/docs/guide/time-tracking#%E5%B9%B6%E8%A1%8C%E8%AE%A1%E6%97%B6" data-docs-link=""')
    expect(doc.html).toContain('<code class="docs-repo-path" title="仓库文件">backend/pom.xml</code>')
    expect(doc.html).not.toContain('>规则<')
    expect(doc.html).toContain('href="https://example.com/a" target="_blank" rel="noopener noreferrer"')
    expect(doc.html).toContain('href="/download" data-docs-link=""')
  })

  it('渲染提示、折叠、步骤与架构图容器', () => {
    const doc = compile(['::: tip', '内容', ':::', '::: danger 不要这样做', '内容', ':::',
      '::: details 展开看看', '细节', ':::', '::: steps', '1. 第一步', '2. 第二步', ':::', '::: diagram system-context', ':::'].join('\n'))
    expect(doc.html).toContain('<div class="docs-callout docs-callout--tip"><p class="docs-callout__title">提示</p>')
    expect(doc.html).toContain('<p class="docs-callout__title">不要这样做</p>')
    expect(doc.html).toContain('<details class="docs-details"><summary>展开看看</summary>')
    expect(doc.html).toContain('<div class="docs-steps">\n<ol>')
    expect(doc.diagrams).toEqual(['system-context'])
    expect(doc.html).toContain('<figure class="docs-diagram">\u0000diagram:system-context\u0000</figure>')
  })

  it('代码块只带 class，未注册语言只转义', () => {
    const doc = compile('```powershell\nGet-Item $env:PATH\n```\n\n```rust\nlet a = "<b>";\n```\n')
    expect(doc.html).toContain('<div class="docs-code" data-lang="powershell"><span class="docs-code__lang">PowerShell</span><button type="button" class="docs-code__copy" data-docs-copy>复制</button>')
    expect(doc.html).toContain('<span class="hljs-')
    expect(doc.html).toContain('let a = &quot;&lt;b&gt;&quot;;')
    expect(doc.html).not.toMatch(/\sstyle=/)
  })

  it('表格外包横向滚动容器，搜索索引按小节切分', () => {
    const doc = compile('开头说明。\n\n## 小节\n\n| 列 | 值 |\n|---|---|\n| a | `b` |\n')
    expect(doc.html).toContain('<div class="docs-table-wrap"><table>')
    expect(doc.search.sections).toEqual([
      { heading: '示例', id: '', text: '开头说明。' },
      { heading: '小节', id: '小节', text: '列 值 a b' },
    ])
  })

  it.each([
    ['内联样式', '<p style="color:red">x</p>'],
    ['脚本', '<script>alert(1)</script>'],
    ['事件属性', '<a onclick="x()">x</a>'],
    ['图片', '![截图](a.png)'],
    ['正文 h1', '# 标题'],
  ])('%s 会让构建失败', (_name, body) => {
    expect(() => compile(body)).toThrow(/\[docs\] guide\/sample\.md/)
  })

  it('front matter 缺字段或 audience 非法时报错', () => {
    expect(() => compileDoc('---\ntitle: 示例\norder: 1\naudience: [member]\n---\n', 'guide/a.md')).toThrow(/description/)
    expect(() => compileDoc('---\ntitle: 示例\ndescription: x\norder: 1\naudience: [boss]\n---\n', 'guide/a.md')).toThrow(/audience/)
  })

  it('架构图拒绝内联样式、脚本、事件与外链', () => {
    expect(checkDiagram('<?xml version="1.0"?>\n<svg viewBox="0 0 1 1"><rect class="dg-node"/></svg>', 'diagrams/a.svg'))
      .toBe('<svg viewBox="0 0 1 1"><rect class="dg-node"/></svg>')
    for (const svg of ['<svg style="x"></svg>', '<svg><script/></svg>', '<svg onload="x"></svg>', '<svg><use xlink:href="http://a"/></svg>'])
      expect(() => checkDiagram(svg, 'diagrams/a.svg')).toThrow(/架构图/)
  })
})
