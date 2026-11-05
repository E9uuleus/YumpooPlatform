import { describe, expect, it } from 'vitest'

describe('happy-dom 兼容层', () => {
  it('元素的 toString 与浏览器一致，返回类型标签而不是 HTML', () => {
    const cell = document.createElement('td')
    cell.innerHTML = '<span>内容</span>'
    expect(String(cell)).toBe('[object HTMLTableCellElement]')
    expect(String(document.body)).toBe('[object HTMLBodyElement]')
  })
})
