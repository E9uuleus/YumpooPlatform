import { describe, expect, it } from 'vitest'

const pageSources = import.meta.glob([
  '../views/**/*.vue',
  '../components/projects/**/*.vue',
  '../components/operations/**/*.vue',
], {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>

describe('页面视觉语言源码约束', () => {
  it.each(Object.entries(pageSources))('%s 不引入原生可见表单控件', (_path, source) => {
    const visibleControls = source.replace(/<input\b(?=[^>]*\btype="file")(?=[^>]*\bhidden\b)[^>]*>/gu, '')
    expect(visibleControls).not.toMatch(/<(?:input|select|textarea)(?:\s|>)/u)
  })

  it.each(Object.entries(pageSources))('%s 不包含页面级十六进制颜色', (_path, source) => {
    expect(source).not.toMatch(/#[\da-f]{3,8}\b/iu)
  })

  it.each(Object.entries(pageSources))('%s 不直接插值常见业务状态字段', (_path, source) => {
    expect(source).not.toMatch(/\{\{\s*(?:scope\.row|selected|project)\.(?:status|lifecycle|membershipStatus|accountStatus|employmentStatus)\s*\}\}/u)
  })
})

const editorSources = import.meta.glob('../**/*.vue', { eager: true, import: 'default', query: '?raw' }) as Record<string, string>

describe('富文本编辑器 CSP 约束', () => {
  it('每个 useEditor 都关闭运行时样式注入，基础样式由 prosemirror.css 提供', () => {
    const editors = Object.entries(editorSources).filter(([, source]) => source.includes('useEditor({'))
    expect(editors.length).toBeGreaterThan(0)
    for (const [path, source] of editors) {
      expect(source.split('useEditor({').length - 1, path).toBe(source.split('injectCSS: false').length - 1)
    }
  })
})

describe('提醒条约束', () => {
  it('提醒条统一使用 YpNotice，不直接使用 el-alert', () => {
    const offenders = Object.entries(editorSources).filter(([, source]) => /<el-alert\b|\bElAlert\b/u.test(source)).map(([path]) => path)
    expect(offenders).toEqual([])
  })
})
