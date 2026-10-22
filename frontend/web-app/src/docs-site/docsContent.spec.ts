import { describe, expect, it } from 'vitest'
import type { RouteRecordRaw } from 'vue-router'
import { routes } from '../router'
import { TOURS } from '../onboarding/tours'
import { SECTIONS } from './content/sections'
import { docSections, findDoc, loadDocHtml, loadSearchIndex, orderedDocs, searchDocs } from './docsNav'
import { ROUTE_HELP } from './routeHelp'

const sources = import.meta.glob('./content/**/*.md', { query: '?raw', import: 'default', eager: true }) as Record<string, string>

function flatten(records: RouteRecordRaw[], parent = ''): Array<{ name?: string; path: string }> {
  return records.flatMap(record => {
    const path = record.path.startsWith('/') ? record.path : `${parent}/${record.path}`
    return [{ ...(typeof record.name === 'string' ? { name: record.name } : {}), path }, ...flatten(record.children ?? [], path)]
  })
}
const namedRoutes = new Map(flatten(routes).flatMap(route => route.name ? [[route.name, route.path] as const] : []))
const pages = Promise.all(orderedDocs.map(async doc => ({ doc, html: await loadDocHtml(doc.slug) })))
const idsOf = (html: string) => new Set([...html.matchAll(/\sid="([^"]+)"/g)].map(match => match[1]!))

async function resolves(target: string): Promise<boolean> {
  const [slug = '', anchor] = target.split('#')
  if (!findDoc(slug)) return false
  if (!anchor) return true
  return idsOf(await loadDocHtml(slug)).has(decodeURIComponent(anchor))
}

describe('文档内容', () => {
  it('每个 Markdown 都编译成文档，分区齐全，分区内 order 唯一', () => {
    expect(orderedDocs).toHaveLength(Object.keys(sources).length)
    for (const section of docSections) {
      expect(section.docs.length, section.id).toBeGreaterThan(0)
      expect(new Set(section.docs.map(doc => doc.order)).size, section.id).toBe(section.docs.length)
    }
    expect(orderedDocs.every(doc => SECTIONS.some(section => section.id === doc.section))).toBe(true)
  })

  it('站内文档链接与锚点都能解析', async () => {
    for (const { doc, html } of await pages) {
      for (const [, href] of html.matchAll(/href="\/docs\/([^"]+)" data-docs-link/g))
        expect(await resolves(href!), `${doc.slug} → ${href}`).toBe(true)
      for (const [, anchor] of html.matchAll(/href="#([^"]+)"/g))
        expect(idsOf(html).has(decodeURIComponent(anchor!)), `${doc.slug} → #${anchor}`).toBe(true)
    }
  })

  it('编译后的正文不含内联样式、脚本或事件属性', async () => {
    for (const { doc, html } of await pages) expect(html, doc.slug).not.toMatch(/\sstyle=|<script|\son[a-z]+=/i)
  })

  it('appRoute 是无必填参数的路由名，tour 是已有导览', () => {
    for (const doc of orderedDocs) {
      if (doc.appRoute) {
        expect(namedRoutes.has(doc.appRoute), `${doc.slug} appRoute`).toBe(true)
        expect(namedRoutes.get(doc.appRoute), `${doc.slug} appRoute`).not.toMatch(/:[^/]+(?<![?*])(?:\/|$)/)
      }
      if (doc.tour) expect(Object.keys(TOURS), `${doc.slug} tour`).toContain(doc.tour)
    }
  })

  it('AppShell 下的具名页面都有本页帮助，错误页与只做重定向的入口除外', () => {
    const shell = routes.find(route => route.path === '/')!
    const exempt = new Set(['not-found', 'forbidden', 'workspace-entry', 'team-dashboard-entry', 'team-entry'])
    const pagesInShell = flatten(shell.children ?? []).flatMap(route => route.name && !exempt.has(route.name) ? [route.name] : [])
    expect(pagesInShell.filter(name => !ROUTE_HELP[name])).toEqual([])
  })

  it('路由帮助的键都是路由名，值都能解析', async () => {
    for (const [name, target] of Object.entries(ROUTE_HELP)) {
      expect(namedRoutes.has(name), name).toBe(true)
      expect(await resolves(target), `${name} → ${target}`).toBe(true)
    }
  })

  it('搜索“并行计时”首条结果落在计时与工时', async () => {
    expect(searchDocs(await loadSearchIndex(), '并行计时')[0]?.slug).toBe('guide/time-tracking')
  })
})
