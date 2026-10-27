import { reactive } from 'vue'
import { describe, expect, it } from 'vitest'
import { plainRailProjects, sameRailProjects } from './railProjects'

describe('侧边栏项目 IPC 数据', () => {
  it('将响应式数组元素转成可结构化克隆的纯对象', () => {
    const items = reactive([{ id: 'a', name: '项目 A' }])
    expect(() => structuredClone([...items])).toThrow()
    expect(structuredClone(plainRailProjects(items))).toEqual([{ id: 'a', name: '项目 A' }])
  })
  it('比较项目标识、名称和顺序', () => {
    const items = [{ id: 'a', name: 'A' }, { id: 'b', name: 'B' }]
    expect(sameRailProjects(items, plainRailProjects(items))).toBe(true)
    expect(sameRailProjects(items, items.slice(1))).toBe(false)
    expect(sameRailProjects(items, [...items].reverse())).toBe(false)
    expect(sameRailProjects(items, [{ id: 'a', name: '更名' }, items[1]!])).toBe(false)
    expect(sameRailProjects(items, [{ ...items[0]!, label: '研发' }, items[1]!])).toBe(false)
  })
})
