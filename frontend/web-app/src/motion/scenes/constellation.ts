import type { CanvasPoint, ThemedCanvasScene } from '../canvasStage'
import { createRandom, flowAngle } from '../noise'
import { readColorTokens, rgba, type Rgb } from '../themeTokens'

interface Node {
  x: number
  y: number
  vx: number
  vy: number
  radius: number
}

interface Packet {
  from: Node
  to: Node
  progress: number
}

const LINK_DISTANCE = 84
const POINTER_REACH = 120
const PACKET_INTERVAL = 1200
const PACKET_DURATION = 900
const MAX_PACKETS = 4

/** Collaboration network for the login brand panel: drifting nodes, proximity links and messages travelling along them. */
export function createConstellationScene(canvas: HTMLCanvasElement): ThemedCanvasScene {
  const random = createRandom(20260930)
  let colors = palette()
  let width = 0
  let height = 0
  let time = 0
  let untilPacket = PACKET_INTERVAL / 2
  let pointer: CanvasPoint | null = null
  let nodes: Node[] = []
  let packets: Packet[] = []

  function palette(): Record<'node' | 'glow', Rgb> {
    return readColorTokens({ node: '--yp-status-blue-foreground', glow: '--yp-brand-glow' }, [255, 255, 255], canvas.parentElement)
  }

  function spawnNode(): Node {
    return { x: random() * width, y: random() * height, vx: 0, vy: 0, radius: 1 + random() * 1.6 }
  }

  function wrap(node: Node): void {
    if (node.x < -10) node.x = width + 10
    else if (node.x > width + 10) node.x = -10
    if (node.y < -10) node.y = height + 10
    else if (node.y > height + 10) node.y = -10
  }

  function nearestNeighbour(node: Node): Node | undefined {
    let nearest: Node | undefined
    let best = LINK_DISTANCE
    for (const other of nodes) {
      if (other === node) continue
      const distance = Math.hypot(node.x - other.x, node.y - other.y)
      if (distance < best) {
        best = distance
        nearest = other
      }
    }
    return nearest
  }

  function line(context: CanvasRenderingContext2D, from: CanvasPoint, to: CanvasPoint, color: string): void {
    context.strokeStyle = color
    context.beginPath()
    context.moveTo(from.x, from.y)
    context.lineTo(to.x, to.y)
    context.stroke()
  }

  function circle(context: CanvasRenderingContext2D, x: number, y: number, radius: number): void {
    context.beginPath()
    context.arc(x, y, radius, 0, Math.PI * 2)
  }

  return {
    theme() {
      colors = palette()
    },
    resize(nextWidth, nextHeight) {
      const scaleX = width ? nextWidth / width : 1
      const scaleY = height ? nextHeight / height : 1
      width = nextWidth
      height = nextHeight
      for (const node of nodes) {
        node.x *= scaleX
        node.y *= scaleY
      }
      const target = Math.max(18, Math.min(70, Math.round((width * height) / 2600)))
      while (nodes.length < target) nodes.push(spawnNode())
      nodes.length = target
      packets = packets.filter(packet => nodes.includes(packet.from) && nodes.includes(packet.to))
    },
    pointer(point) {
      pointer = point
    },
    step(dt) {
      time += dt
      for (const node of nodes) {
        const angle = flowAngle(node.x, node.y, time)
        node.vx += Math.cos(angle) * 0.0025 * dt
        node.vy += Math.sin(angle) * 0.0025 * dt
        if (pointer) {
          const dx = pointer.x - node.x
          const dy = pointer.y - node.y
          const distance = Math.hypot(dx, dy)
          if (distance > 1 && distance < POINTER_REACH) {
            const pull = (1 - distance / POINTER_REACH) * 0.012 * dt
            node.vx += (dx / distance) * pull
            node.vy += (dy / distance) * pull
          }
        }
        node.vx *= 0.94
        node.vy *= 0.94
        node.x += node.vx * dt * 0.05
        node.y += node.vy * dt * 0.05
        wrap(node)
      }

      untilPacket -= dt
      if (untilPacket <= 0 && packets.length < MAX_PACKETS && nodes.length > 1) {
        untilPacket = PACKET_INTERVAL
        const from = nodes[Math.floor(random() * nodes.length)]!
        const to = nearestNeighbour(from)
        if (to) packets.push({ from, to, progress: 0 })
      }
      for (const packet of packets) packet.progress += dt / PACKET_DURATION
      packets = packets.filter(packet => packet.progress < 1
        && Math.hypot(packet.from.x - packet.to.x, packet.from.y - packet.to.y) < LINK_DISTANCE * 1.2)
    },
    draw(context) {
      context.lineWidth = 0.8
      for (let index = 0; index < nodes.length; index += 1) {
        const node = nodes[index]!
        for (let next = index + 1; next < nodes.length; next += 1) {
          const other = nodes[next]!
          const distance = Math.hypot(node.x - other.x, node.y - other.y)
          if (distance < LINK_DISTANCE) line(context, node, other, rgba(colors.node, (1 - distance / LINK_DISTANCE) * 0.32))
        }
      }
      if (pointer) {
        for (const node of nodes) {
          const distance = Math.hypot(pointer.x - node.x, pointer.y - node.y)
          if (distance < POINTER_REACH) line(context, pointer, node, rgba(colors.glow, (1 - distance / POINTER_REACH) * 0.5))
        }
      }

      context.fillStyle = rgba(colors.node, 0.8)
      for (const node of nodes) {
        circle(context, node.x, node.y, node.radius)
        context.fill()
      }

      for (const packet of packets) {
        const x = packet.from.x + (packet.to.x - packet.from.x) * packet.progress
        const y = packet.from.y + (packet.to.y - packet.from.y) * packet.progress
        const alpha = Math.sin(packet.progress * Math.PI)
        context.fillStyle = rgba(colors.node, alpha)
        circle(context, x, y, 2.4)
        context.fill()
        context.strokeStyle = rgba(colors.glow, alpha * 0.6)
        circle(context, x, y, 5.5)
        context.stroke()
      }
    },
  }
}
