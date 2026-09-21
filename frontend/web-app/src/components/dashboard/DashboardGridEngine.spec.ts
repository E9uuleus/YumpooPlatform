import { describe, expect, it } from 'vitest'
import { GridStackEngine } from 'gridstack/dist/gridstack-engine'
import { DashboardGridEngine } from './dashboardGridEngine'
import type { GridStackNode } from 'gridstack'

function assertNoOverlap(engine: GridStackEngine) {
  for (const node of engine.nodes) {
    expect(engine.collide(node)).toBeUndefined()
    expect(node.y).toBeGreaterThanOrEqual(0)
    expect(node.x! + node.w!).toBeLessThanOrEqual(engine.column)
  }
}

describe.each([6, 12])('仪表板目标位置优先（%i 列）', columns => {
  function setup() {
    const engine = new DashboardGridEngine({ column: columns, float: true })
    engine.batchUpdate()
    const moving = engine.addNode({ id: 'wide', x: 1, y: 8, w: columns - 1, h: 5 })
    engine.addNode({ id: 'small', x: 1, y: 2, w: 2, h: 3 })
    engine.addNode({ id: 'lower', x: 1, y: 5, w: 3, h: 3 })
    engine.addNode({ id: 'side', x: 0, y: 0, w: 1, h: 12 })
    engine.batchUpdate(false)
    engine.beginUpdate(moving); Object.assign(moving, { _moving: true })
    return { engine, moving }
  }

  it('大卡片轻微覆盖其它卡片即可落在目标行，其它卡片向下级联避让', () => {
    const { engine, moving } = setup()
    expect(engine.moveNodeCheck(moving, { x: 1, y: 1, rect: { x: 100, y: 40, w: 100, h: 20 } })).toBe(true)
    expect(moving).toMatchObject({ x: 1, y: 1 })
    expect(engine.nodes.find(n => n.id === 'small')).toMatchObject({ x: 1, y: 6 })
    expect(engine.nodes.find(n => n.id === 'lower')).toMatchObject({ x: 1, y: 9 })
    expect(engine.nodes.find(n => n.id === 'side')).toMatchObject({ x: 0, y: 0 })
    assertNoOverlap(engine)
  })

  it('向下插入不同尺寸卡片的位置时，上方有空间则向上避让', () => {
    const engine = new DashboardGridEngine({ column: columns, float: true })
    const moving = engine.addNode({ id: 'wide', x: 0, y: 0, w: columns, h: 4 })
    engine.addNode({ id: 'small', x: 1, y: 4, w: 2, h: 3 })
    engine.beginUpdate(moving); Object.assign(moving, { _moving: true })
    engine.moveNodeCheck(moving, { x: 0, y: 4 })
    expect(moving.y).toBe(4)
    expect(engine.nodes.find(n => n.id === 'small')?.y).toBe(1)
    assertNoOverlap(engine)
  })

  it('同一次拖动回到起点会恢复被避让的卡片，重复经过不会不断下推', () => {
    const { engine, moving } = setup()
    const before = engine.save(false, undefined, columns)
    for (let attempt = 0; attempt < 3; attempt++) {
      engine.moveNodeCheck(moving, { x: 1, y: 1 })
      engine.moveNodeCheck(moving, { x: 1, y: 8 })
      expect(engine.save(false, undefined, columns)).toEqual(before)
      assertNoOverlap(engine)
    }
  })

  it('所有合法横向位置与连续目标行都可到达，松手与重载不吸回顶部', () => {
    const { engine, moving } = setup()
    for (let x = 0; x <= 1; x++) for (let y = 0; y < 20; y++) {
      engine.moveNodeCheck(moving, { x, y })
      expect(moving).toMatchObject({ x, y })
      assertNoOverlap(engine)
    }
    delete (moving as GridStackNode & { _moving?: boolean })._moving
    engine.endUpdate()
    const saved = engine.save(false, undefined, columns)
    const restored = new DashboardGridEngine({ column: columns, float: true })
    restored.batchUpdate(); saved.forEach(n => restored.addNode({ ...n })); restored.batchUpdate(false)
    expect(restored.save(false, undefined, columns)).toEqual(saved)
  })
})

describe('真实 GridStack 布局序列化', () => {
  it('6 列保存忽略 12 列缓存，碰撞后的坐标可重新载入', () => {
    const engine = new GridStackEngine({ column: 12 })
    const first = engine.addNode({ id: 'a', x: 7, y: 0, w: 5, h: 5 })
    engine.addNode({ id: 'b', x: 0, y: 5, w: 3, h: 4 })
    engine.cacheLayout(engine.nodes, 12)
    engine.column = 6
    Object.assign(first, { x: 0, y: 0, w: 2, h: 4 })
    engine.moveNode(first, { x: 0, y: 5, w: 2, h: 4 })
    expect(engine.save(false).find(n => n.id === 'a')?.x).toBe(7)
    const saved = engine.save(false, undefined, 6)
    expect(saved.find(n => n.id === 'a')).toMatchObject({ x: 0, w: 2 })
    for (const node of saved) expect((node.x || 0) + node.w!).toBeLessThanOrEqual(6)
    const restored = new GridStackEngine({ column: 6 })
    saved.forEach(n => restored.addNode({ ...n }))
    expect(restored.save(false, undefined, 6)).toEqual(saved)
  })
  it('宽画布允许单列最小卡片，不改变另一档布局缓存', () => {
    const engine = new GridStackEngine({ column: 12 })
    engine.addNode({ id: 'small', x: 0, y: 0, w: 1, h: 4, minW: 1, minH: 4 })
    const saved = engine.save(false, undefined, 12)
    expect(saved[0]?.w ?? 1).toBe(1)
    expect(saved[0]?.h ?? saved[0]?.minH).toBe(4)
    const restored = new GridStackEngine({ column: 12 })
    restored.addNode({ ...saved[0] })
    expect(restored.save(false, undefined, 12)).toEqual(saved)
  })
})
