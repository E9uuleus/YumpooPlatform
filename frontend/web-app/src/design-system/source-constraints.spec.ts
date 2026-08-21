import { describe, expect, it } from 'vitest'

const viewSources = import.meta.glob('../views/**/*.vue', {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>

describe('页面视觉语言源码约束', () => {
  it.each(Object.entries(viewSources))('%s 不引入原生表单控件', (_path, source) => {
    expect(source).not.toMatch(/<(?:input|select|textarea)(?:\s|>)/u)
  })

  it.each(Object.entries(viewSources))('%s 不包含页面级十六进制颜色', (_path, source) => {
    expect(source).not.toMatch(/#[\da-f]{3,8}\b/iu)
  })

  it.each(Object.entries(viewSources))('%s 不直接插值常见业务状态字段', (_path, source) => {
    expect(source).not.toMatch(/\{\{\s*(?:scope\.row|selected|project)\.(?:status|lifecycle|membershipStatus|accountStatus|employmentStatus)\s*\}\}/u)
  })
})
