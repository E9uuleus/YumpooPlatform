import { describe, expect, it } from 'vitest'

const sources = import.meta.glob([
  '../**/*.{vue,css,ts}',
  '!../**/*.spec.ts',
], {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>

// 样式声明、行内 style 对象键和 style.setProperty 都算定义；var(--x, …) 中的逗号不算。
const definitionPattern = /(?<!var\(\s*)(--yp-[\w-]+)['"]?\s*[:,]/gu
const usagePattern = /var\(\s*(--yp-[\w-]+)/gu

describe('设计 Token 引用', () => {
  it('所有 var(--yp-*) 引用都有对应定义', () => {
    const defined = new Set<string>()
    const used = new Map<string, Set<string>>()
    for (const [path, source] of Object.entries(sources)) {
      for (const [, name] of source.matchAll(definitionPattern)) defined.add(name!)
      for (const [, name] of source.matchAll(usagePattern)) {
        if (!used.has(name!)) used.set(name!, new Set())
        used.get(name!)!.add(path)
      }
    }
    const undefinedTokens = [...used]
      .filter(([name]) => !defined.has(name))
      .map(([name, paths]) => `${name} ← ${[...paths].join(', ')}`)
    expect(undefinedTokens).toEqual([])
  })
})
