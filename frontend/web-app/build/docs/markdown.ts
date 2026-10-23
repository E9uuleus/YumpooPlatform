import MarkdownIt from 'markdown-it'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import container from 'markdown-it-container'
import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import ini from 'highlight.js/lib/languages/ini'
import java from 'highlight.js/lib/languages/java'
import json from 'highlight.js/lib/languages/json'
import powershell from 'highlight.js/lib/languages/powershell'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'
import { parse as parseYaml } from 'yaml'
import type { Plugin } from 'vite'
import type { RenderRule } from 'markdown-it/lib/renderer.mjs'
import type Token from 'markdown-it/lib/token.mjs'

const CONTENT_MARKER = '/src/docs-site/content/'
/** 内容目录在仓库中的位置，仓库内相对链接据此归一化。 */
const CONTENT_REPO_PATH = 'frontend/web-app/src/docs-site/content'
const AUDIENCES = ['member', 'admin', 'app-manager', 'developer'] as const
const UNSAFE_HTML = /\sstyle=|<script|\son[a-z]+=/i
const DIAGRAM_PLACEHOLDER = /\u0000diagram:([a-z0-9-]+)\u0000/g

const LANGUAGES = { bash, ini, java, json, powershell, sql, typescript, xml, yaml }
const LANGUAGE_LABELS: Record<string, string> = {
  bash: 'Bash', ini: 'INI', java: 'Java', json: 'JSON', powershell: 'PowerShell', sql: 'SQL',
  typescript: 'TypeScript', xml: 'XML', yaml: 'YAML', text: '文本',
}
for (const [name, language] of Object.entries(LANGUAGES)) hljs.registerLanguage(name, language)

const CALLOUTS = { tip: '提示', info: '说明', warning: '注意', danger: '警告' } as const

export type DocAudience = typeof AUDIENCES[number]
export interface CompiledDoc {
  meta: {
    slug: string
    section: string
    title: string
    description: string
    order: number
    audience: DocAudience[]
    appRoute?: string
    tour?: string
    headings: Array<{ depth: number; text: string; id: string }>
  }
  search: { slug: string; title: string; sections: Array<{ heading: string; id: string; text: string }> }
  html: string
  diagrams: string[]
  images: string[]
}

interface RenderEnv { file: string; contentRoot: string; diagrams: string[]; images: string[] }

/** 与 scripts/verify-md-links.ts 的 githubSlug 保持一致，doc-sync 用同一算法校验文档锚点。 */
export function githubSlug(heading: string): string {
  return heading.toLowerCase().replace(/[^\p{L}\p{N}_ -]/gu, '').replaceAll(' ', '-')
}

function fail(file: string, message: string): never {
  throw new Error(`[docs] ${file}: ${message}`)
}

function escapeHtml(value: string): string {
  return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;')
}

/** posix 路径归一化；越出仓库根目录时返回 undefined。 */
function normalizePath(path: string): string | undefined {
  const segments: string[] = []
  for (const segment of path.split('/')) {
    if (!segment || segment === '.') continue
    if (segment === '..') {
      if (!segments.length) return undefined
      segments.pop()
    } else segments.push(segment)
  }
  return segments.join('/')
}

function inlineText(token: Token): string {
  return (token.children ?? []).map(child => child.type === 'text' || child.type === 'code_inline' ? child.content
    : child.type === 'image' ? `${child.content} ${child.attrGet('title') ?? ''}`
    : child.type === 'softbreak' || child.type === 'hardbreak' ? ' ' : '').join('')
}

function parseFrontMatter(source: string, file: string) {
  const match = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/.exec(source)
  if (!match) fail(file, '缺少 front matter')
  const data: unknown = parseYaml(match[1] ?? '')
  if (!data || typeof data !== 'object') fail(file, 'front matter 必须是对象')
  const value = data as Record<string, unknown>
  const text = (key: string, required: boolean) => {
    const field = value[key]
    if (field === undefined && !required) return undefined
    if (typeof field !== 'string' || !field.trim()) fail(file, `front matter 字段 ${key} 必须是非空字符串`)
    return field.trim()
  }
  const order = value.order
  if (typeof order !== 'number' || !Number.isInteger(order)) fail(file, 'front matter 字段 order 必须是整数')
  const audience = value.audience
  if (!Array.isArray(audience) || !audience.length || !audience.every(item => (AUDIENCES as readonly unknown[]).includes(item)))
    fail(file, `front matter 字段 audience 必须是 ${AUDIENCES.join(' | ')} 组成的非空数组`)
  const appRoute = text('appRoute', false)
  const tour = text('tour', false)
  return {
    attributes: {
      title: text('title', true)!,
      description: text('description', true)!,
      order,
      audience: audience as DocAudience[],
      ...(appRoute ? { appRoute } : {}),
      ...(tour ? { tour } : {}),
    },
    body: source.slice(match[0].length),
  }
}

function useContainer(md: MarkdownIt, name: string, render: RenderRule, validate?: (params: string) => boolean): void {
  md.use(container, name, validate ? { render, validate } : { render })
}

function createRenderer(): MarkdownIt {
  const md = new MarkdownIt({ html: true, linkify: false, typographer: false })

  for (const [name, label] of Object.entries(CALLOUTS)) {
    useContainer(md, name, (tokens, index) => {
      const token = tokens[index]!
      if (token.nesting !== 1) return '</div>\n'
      const title = token.info.trim().slice(name.length).trim() || label
      return `<div class="docs-callout docs-callout--${name}"><p class="docs-callout__title">${escapeHtml(title)}</p>\n`
    })
  }
  useContainer(md, 'details', (tokens, index) => {
    const token = tokens[index]!
    if (token.nesting !== 1) return '</details>\n'
    return `<details class="docs-details"><summary>${escapeHtml(token.info.trim().slice('details'.length).trim())}</summary>\n`
  }, params => /^details\s+\S/.test(params.trim()))
  useContainer(md, 'steps', (tokens, index) => tokens[index]!.nesting === 1 ? '<div class="docs-steps">\n' : '</div>\n')
  useContainer(md, 'diagram', (tokens, index, _options, env: RenderEnv) => {
    const token = tokens[index]!
    if (token.nesting !== 1) return ''
    const name = token.info.trim().slice('diagram'.length).trim()
    env.diagrams.push(name)
    return `<figure class="docs-diagram">\u0000diagram:${name}\u0000</figure>\n`
  }, params => /^diagram\s+[a-z0-9-]+$/.test(params.trim()))

  md.renderer.rules.fence = (tokens, index) => {
    const token = tokens[index]!
    const lang = token.info.trim().split(/\s+/)[0]?.toLowerCase() || 'text'
    const code = hljs.getLanguage(lang) && lang !== 'text'
      ? hljs.highlight(token.content, { language: lang, ignoreIllegals: true }).value
      : escapeHtml(token.content)
    return `<div class="docs-code" data-lang="${escapeHtml(lang)}"><span class="docs-code__lang">${escapeHtml(LANGUAGE_LABELS[lang] ?? lang)}</span>`
      + `<button type="button" class="docs-code__copy" data-docs-copy>复制</button><pre><code class="hljs">${code}</code></pre></div>\n`
  }
  md.renderer.rules.table_open = () => '<div class="docs-table-wrap"><table>\n'
  md.renderer.rules.table_close = () => '</table></div>\n'
  md.renderer.rules.image = (tokens, index, _options, env: RenderEnv) => {
    const token = tokens[index]!
    const source = token.attrGet('src') ?? ''
    const directory = env.file.split('/').slice(0, -1).join('/')
    const path = normalizePath(`${directory}/${source}`)
    if (!/^(?:\.\.\/)*screenshots\/[a-z0-9-]+\.png$/.test(source) || !path?.startsWith('screenshots/'))
      fail(env.file, '截图必须引用 content/screenshots/ 下的本地 PNG')
    const alt = token.content.trim()
    const caption = token.attrGet('title')?.trim()
    if (!alt || !caption) fail(env.file, '截图需要替代文本和 title 图注')
    let bytes: Buffer
    try { bytes = readFileSync(resolve(env.contentRoot, path)) }
    catch { fail(env.file, `截图不存在：${path}`) }
    if (bytes.length < 24 || bytes.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a' || bytes.toString('ascii', 12, 16) !== 'IHDR')
      fail(env.file, `截图不是有效 PNG：${path}`)
    const width = bytes.readUInt32BE(16), height = bytes.readUInt32BE(20)
    if (!width || !height) fail(env.file, `截图尺寸无效：${path}`)
    const image = env.images.push(path) - 1
    return `<img src="\u0000image:${image}\u0000" alt="${escapeHtml(alt)}" width="${width}" height="${height}" loading="lazy" decoding="async">`
      + `<figcaption>${escapeHtml(caption)}</figcaption>`
  }

  md.core.ruler.push('docs_images', state => {
    const env = state.env as RenderEnv
    state.tokens.forEach((token, index) => {
      if ([token, ...(token.children ?? [])].some(child => /^html_/.test(child.type) && /<img\b/i.test(child.content)))
        fail(env.file, '截图请使用 Markdown 图片语法')
      const images = token.children?.filter(child => child.type === 'image') ?? []
      if (!images.length) return
      const open = state.tokens[index - 1], close = state.tokens[index + 1]
      if (token.children?.length !== 1 || open?.type !== 'paragraph_open' || close?.type !== 'paragraph_close')
        fail(env.file, '每张截图必须独占一个段落')
      open.tag = close.tag = 'figure'
      open.hidden = close.hidden = false
      open.attrSet('class', 'docs-screenshot')
    })
  })

  md.core.ruler.push('docs_headings', state => {
    const env = state.env as RenderEnv
    const used = new Set<string>()
    const occurrences = new Map<string, number>()
    state.tokens.forEach((token, index) => {
      if (token.type !== 'heading_open') return
      if (token.tag === 'h1') fail(env.file, '正文不允许 h1，标题来自 front matter')
      const base = githubSlug(inlineText(state.tokens[index + 1]!))
      let id = base
      let suffix = occurrences.get(base) ?? 0
      while (used.has(id)) {
        suffix += 1
        id = `${base}-${suffix}`
      }
      occurrences.set(base, suffix)
      used.add(id)
      token.attrSet('id', id)
      const anchor = new state.Token('html_inline', '', 0)
      anchor.content = `<a class="docs-anchor" href="#${escapeHtml(id)}" aria-label="链接到本节">#</a>`
      state.tokens[index + 1]!.children?.unshift(anchor)
    })
  })

  md.core.ruler.push('docs_links', state => {
    const env = state.env as RenderEnv
    const directory = env.file.split('/').slice(0, -1).join('/')
    for (const block of state.tokens) {
      if (block.type !== 'inline' || !block.children) continue
      const children: Token[] = []
      for (let index = 0; index < block.children.length; index++) {
        const token = block.children[index]!
        if (token.type !== 'link_open') { children.push(token); continue }
        const href = token.attrGet('href') ?? ''
        if (/^https?:\/\//.test(href)) {
          token.attrSet('target', '_blank')
          token.attrSet('rel', 'noopener noreferrer')
        } else if (href.startsWith('/')) token.attrSet('data-docs-link', '')
        else if (!href.startsWith('#')) {
          const [path = '', hash] = href.split('#')
          const resolved = normalizePath(`${CONTENT_REPO_PATH}/${directory ? `${directory}/` : ''}${decodeURI(path)}`)
          if (resolved === undefined) fail(env.file, `链接越出仓库：${href}`)
          if (resolved.startsWith(`${CONTENT_REPO_PATH}/`) && resolved.endsWith('.md')) {
            const slug = resolved.slice(CONTENT_REPO_PATH.length + 1, -'.md'.length)
            token.attrSet('href', `/docs/${slug}${hash ? `#${hash}` : ''}`)
            token.attrSet('data-docs-link', '')
          } else {
            const code = new state.Token('html_inline', '', 0)
            code.content = `<code class="docs-repo-path" title="仓库文件">${escapeHtml(resolved)}</code>`
            children.push(code)
            while (index < block.children.length && block.children[index]!.type !== 'link_close') index++
            continue
          }
        }
        children.push(token)
      }
      block.children = children
    }
  })
  return md
}

const renderer = createRenderer()

function searchSections(tokens: Token[], title: string) {
  const sections: CompiledDoc['search']['sections'] = [{ heading: title, id: '', text: '' }]
  tokens.forEach((token, index) => {
    if (token.type === 'heading_open' && (token.tag === 'h2' || token.tag === 'h3')) {
      sections.push({ heading: inlineText(tokens[index + 1]!), id: token.attrGet('id') ?? '', text: '' })
      return
    }
    if (tokens[index - 1]?.type === 'heading_open') return
    const text = token.type === 'inline' ? inlineText(token) : token.type === 'fence' || token.type === 'code_block' ? token.content : ''
    if (!text.trim()) return
    const section = sections[sections.length - 1]!
    section.text = section.text ? `${section.text} ${text.trim()}` : text.trim()
  })
  return sections.filter(section => section.id || section.text)
}

/** relativePath 为 content/ 下的 posix 相对路径，例如 guide/time-tracking.md。 */
export function compileDoc(source: string, relativePath: string, contentRoot = resolve('src/docs-site/content')): CompiledDoc {
  const segments = relativePath.split('/')
  if (segments.length < 2 || !relativePath.endsWith('.md')) fail(relativePath, '文档必须位于分区目录下')
  const { attributes, body } = parseFrontMatter(source, relativePath)
  const env: RenderEnv = { file: relativePath, contentRoot, diagrams: [], images: [] }
  const tokens = renderer.parse(body, env)
  const html = renderer.renderer.render(tokens, renderer.options, env)
  if (UNSAFE_HTML.test(html.replace(DIAGRAM_PLACEHOLDER, ''))) fail(relativePath, '输出包含 style=、<script 或 on*= 事件属性，违反 CSP')
  const slug = relativePath.slice(0, -'.md'.length)
  const headings = tokens.flatMap((token, index) => token.type === 'heading_open' && (token.tag === 'h2' || token.tag === 'h3')
    ? [{ depth: Number(token.tag.slice(1)), text: inlineText(tokens[index + 1]!), id: token.attrGet('id') ?? '' }] : [])
  return {
    meta: { slug, section: segments[0]!, ...attributes, headings },
    search: { slug, title: attributes.title, sections: searchSections(tokens, attributes.title) },
    html,
    diagrams: env.diagrams,
    images: env.images,
  }
}

/** 架构图内联进正文，必须同样满足 CSP：颜色走 dg-* 类，不带内联样式、脚本或外链。 */
export function checkDiagram(svg: string, file: string): string {
  const content = svg.replace(/<\?xml[\s\S]*?\?>/, '').trim()
  if (!content.startsWith('<svg')) fail(file, '架构图必须是 <svg> 根元素')
  if (/style/i.test(content) || /<script/i.test(content) || /\son[a-z]+=/i.test(content) || /xlink:href="http/i.test(content))
    fail(file, '架构图不得包含 style、<script、on*= 或外链')
  return content
}

function moduleCode(doc: CompiledDoc, query: string): string {
  if (query === 'meta') return `export default ${JSON.stringify(doc.meta)}`
  if (query === 'search') return `export default ${JSON.stringify(doc.search)}`
  const imports = doc.diagrams.map((name, index) => `import d${index} from '${CONTENT_MARKER}diagrams/${name}.svg?raw'`)
  imports.push(...doc.images.map((path, index) => `import i${index} from '${CONTENT_MARKER}${path}?no-inline'`))
  let diagram = 0
  const html = doc.html.split(/(\u0000(?:diagram:[a-z0-9-]+|image:\d+)\u0000)/).map(part => {
    if (part.startsWith('\u0000diagram:')) return `d${diagram++}`
    if (part.startsWith('\u0000image:')) return `i${Number(part.slice(7, -1))}`
    return JSON.stringify(part)
  }).join(' + ')
  return `${imports.join('\n')}\nexport default { html: ${html} }`
}

export function docsMarkdown(): Plugin {
  return {
    name: 'yumpoo-docs-markdown',
    enforce: 'pre',
    transform(code, id) {
      const [path = '', query = ''] = id.replaceAll('\\', '/').split('?')
      const at = path.indexOf(CONTENT_MARKER)
      if (at < 0) return undefined
      const relativePath = path.slice(at + CONTENT_MARKER.length)
      // Vite 的资源插件先于 transform 加载 .svg，只有内置 ?raw 能拿到原文：此时 code 为 export default "<svg…>"。
      if (path.endsWith('.svg') && query === 'raw') {
        const raw = /^export default (".*")\s*;?\s*$/s.exec(code)?.[1]
        if (raw === undefined) fail(relativePath, '无法读取架构图原文')
        return { code: `export default ${JSON.stringify(checkDiagram(JSON.parse(raw) as string, relativePath))}`, map: null }
      }
      if (!path.endsWith('.md') || !['', 'meta', 'search'].includes(query)) return undefined
      return { code: moduleCode(compileDoc(code, relativePath, path.slice(0, at + CONTENT_MARKER.length)), query), map: null }
    },
  }
}
