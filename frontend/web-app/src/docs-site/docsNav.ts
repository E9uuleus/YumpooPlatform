import { SECTIONS, type SectionId } from './content/sections'

export type DocAudience = 'member' | 'admin' | 'app-manager' | 'developer'
export interface DocHeading { depth: number; text: string; id: string }
export interface DocMeta {
  slug: string
  section: SectionId
  title: string
  description: string
  order: number
  audience: DocAudience[]
  appRoute?: string
  tour?: string
  headings: DocHeading[]
}
export interface DocSearchEntry { slug: string; title: string; sections: Array<{ heading: string; id: string; text: string }> }

const metas = import.meta.glob<DocMeta>('./content/**/*.md', { query: '?meta', import: 'default', eager: true })
const pages = import.meta.glob<{ html: string }>('./content/**/*.md', { import: 'default' })
const searches = import.meta.glob<DocSearchEntry>('./content/**/*.md', { query: '?search', import: 'default' })

const fileOf = (slug: string) => `./content/${slug}.md`

export const AUDIENCE_LABELS: Record<DocAudience, string | undefined> = {
  member: undefined, admin: '管理员', 'app-manager': '平台管理员', developer: '开发者',
}

export const docSections = SECTIONS.map(section => ({
  ...section,
  docs: Object.values(metas).filter(meta => meta.section === section.id).sort((left, right) => left.order - right.order),
}))

/** 跨分区的全局阅读顺序，也是搜索同分时的排序依据。 */
export const orderedDocs = docSections.flatMap(section => section.docs)

export function findDoc(slug: string): DocMeta | undefined {
  return metas[fileOf(slug)]
}

export async function loadDocHtml(slug: string): Promise<string> {
  const load = pages[fileOf(slug)]
  if (!load) throw new Error(`未知文档：${slug}`)
  return (await load()).html
}

let searchIndex: Promise<DocSearchEntry[]> | undefined
export function loadSearchIndex(): Promise<DocSearchEntry[]> {
  searchIndex ??= Promise.all(orderedDocs.map(doc => searches[fileOf(doc.slug)]!()))
  return searchIndex
}

export function neighbours(slug: string): { previous?: DocMeta; next?: DocMeta } {
  const index = orderedDocs.findIndex(doc => doc.slug === slug)
  if (index < 0) return {}
  return {
    ...(index > 0 ? { previous: orderedDocs[index - 1]! } : {}),
    ...(index < orderedDocs.length - 1 ? { next: orderedDocs[index + 1]! } : {}),
  }
}

export function docPath(slug: string, anchor?: string): string {
  return `/docs/${slug}${anchor ? `#${encodeURIComponent(anchor)}` : ''}`
}

export interface DocSearchHit {
  slug: string
  title: string
  heading: string
  id: string
  /** 片段按命中词切段，模板分段渲染，不使用 v-html。 */
  snippet: Array<{ text: string; hit: boolean }>
}

function occurrences(haystack: string, term: string): number {
  let count = 0
  for (let at = haystack.indexOf(term); at >= 0; at = haystack.indexOf(term, at + term.length)) count++
  return count
}

function snippetOf(text: string, terms: string[]): DocSearchHit['snippet'] {
  const lower = text.toLowerCase()
  const first = terms.map(term => lower.indexOf(term)).filter(at => at >= 0).sort((left, right) => left - right)[0]
  const start = first === undefined ? 0 : Math.max(0, first - 30)
  const end = first === undefined ? Math.min(text.length, 60) : Math.min(text.length, first + 30 + (terms.find(term => lower.startsWith(term, first)) ?? '').length)
  const slice = text.slice(start, end)
  const sliceLower = slice.toLowerCase()
  const parts: DocSearchHit['snippet'] = []
  let cursor = 0
  while (cursor < slice.length) {
    const next = terms.map(term => ({ term, at: sliceLower.indexOf(term, cursor) })).filter(match => match.at >= 0)
      .sort((left, right) => left.at - right.at || right.term.length - left.term.length)[0]
    if (!next) { parts.push({ text: slice.slice(cursor), hit: false }); break }
    if (next.at > cursor) parts.push({ text: slice.slice(cursor, next.at), hit: false })
    parts.push({ text: slice.slice(next.at, next.at + next.term.length), hit: true })
    cursor = next.at + next.term.length
  }
  if (start > 0) parts.unshift({ text: '…', hit: false })
  if (end < text.length) parts.push({ text: '…', hit: false })
  return parts
}

/** 子串匹配：每节须包含全部词；页面标题命中 +10，小节标题 +5，正文每次 +1；同分按全局文档顺序。 */
export function searchDocs(index: DocSearchEntry[], query: string, limit = 20): DocSearchHit[] {
  const terms = query.toLowerCase().split(/\s+/).filter(Boolean)
  if (!terms.length) return []
  const order = new Map(orderedDocs.map((doc, position) => [doc.slug, position]))
  const scored = index.flatMap(entry => entry.sections.map((section, position) => {
    const title = entry.title.toLowerCase()
    const heading = section.heading.toLowerCase()
    const text = section.text.toLowerCase()
    if (!terms.every(term => title.includes(term) || heading.includes(term) || text.includes(term))) return undefined
    const score = terms.reduce((sum, term) => sum + (title.includes(term) ? 10 : 0) + (heading.includes(term) ? 5 : 0) + occurrences(text, term), 0)
    const hit: DocSearchHit = { slug: entry.slug, title: entry.title, heading: section.heading, id: section.id, snippet: snippetOf(section.text, terms) }
    return { hit, score, rank: (order.get(entry.slug) ?? Number.MAX_SAFE_INTEGER) * 1000 + position }
  }).filter(value => value !== undefined))
  return scored.sort((left, right) => right.score - left.score || left.rank - right.rank).slice(0, limit).map(value => value.hit)
}
