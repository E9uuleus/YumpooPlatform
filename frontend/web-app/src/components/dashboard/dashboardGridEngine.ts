import { GridStackEngine } from 'gridstack/dist/gridstack-engine'
import type { GridStackMoveOpts, GridStackNode } from 'gridstack'

type Position = { x: number; y: number; w: number; h: number }
type DragNode = GridStackNode & { _moving?: boolean; _dirty?: boolean }
const position = (node: GridStackNode): Position => ({ x: node.x ?? 0, y: node.y ?? 0, w: node.w ?? 1, h: node.h ?? 1 })
const overlaps = (a: Position, b: Position) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

export class DashboardGridEngine extends GridStackEngine {
  private dragStart = new Map<GridStackNode, Position>()

  override beginUpdate(node: GridStackNode): GridStackEngine {
    this.dragStart = new Map(this.nodes.map(n => [n, position(n)]))
    return super.beginUpdate(node)
  }

  override endUpdate(): GridStackEngine {
    this.dragStart.clear()
    return super.endUpdate()
  }

  override moveNode(node: DragNode, options: GridStackMoveOpts): boolean {
    if (!node._moving || options.resizing || options.nested || this.batchMode) return super.moveNode(node, options)
    const target = { ...node, ...position(node), ...options }
    this.nodeBoundFix(target)
    const destination = position(target), original = this.dragStart.get(node) ?? position(node)
    const placed = new Map<GridStackNode, Position>([[node, destination]])
    // Rebuild from the gesture's starting layout so reversing the pointer also reverses avoidance.
    const others = this.nodes.filter(n => n !== node).map(n => ({ node: n, start: this.dragStart.get(n) ?? position(n) }))
      .sort((a, b) => a.start.y - b.start.y || a.start.x - b.start.x)
    for (const other of others) {
      const next = { ...other.start }
      if (overlaps(next, destination) && destination.y > original.y && next.y <= destination.y) {
        const above = { ...next, y: destination.y - next.h }
        let collision: Position | undefined
        while (above.y >= 0 && (collision = [...placed.values()].find(p => overlaps(above, p)))) above.y = collision.y - above.h
        if (above.y >= 0) next.y = above.y
      }
      let collision: Position | undefined
      while ((collision = [...placed.values()].find(p => overlaps(next, p)))) next.y = collision.y + collision.h
      placed.set(other.node, next)
    }
    const moved = node.x !== destination.x || node.y !== destination.y
    this.batchUpdate()
    for (const [item, next] of placed) {
      if (item.x !== next.x || item.y !== next.y) Object.assign(item, next, { _dirty: true })
    }
    // Notify GridStack once, without its default packing moving the chosen destination.
    this.batchUpdate(false, false)
    return moved
  }
}
