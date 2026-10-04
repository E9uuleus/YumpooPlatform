import type { ThemedCanvasScene } from '../canvasStage'
import { readColorTokens, rgba, type Rgb } from '../themeTokens'

const BOARD_WIDTH = 300
const BOARD_HEIGHT = 116
const ROWS = 5
/** The whole one-shot sequence; the stage stops repainting once it has played through. */
export const CONNECT_BOARDS_DURATION = 1500

const easeOut = (value: number) => 1 - (1 - Math.min(1, Math.max(0, value))) ** 3
const progress = (time: number, start: number, length: number) => easeOut((time - start) / length)

interface Card { x: number; bar: 'barLeft' | 'barRight'; pills: Array<'green' | 'purple' | 'orange' | 'red' | 'teal' | 'pink'> }
const cards: Card[] = [
  { x: 10, bar: 'barLeft', pills: ['green', 'purple', 'green', 'orange', 'red'] },
  { x: 170, bar: 'barRight', pills: ['orange', 'green', 'pink', 'purple', 'teal'] },
]

/**
 * Two mini tables slide in, a link draws from a row on one to a row on the other through the two-way badge, a signal
 * travels the link once and the badge settles. It never loops, so it stays within the workspace motion budget.
 */
export function createConnectBoardsScene(canvas: HTMLCanvasElement): ThemedCanvasScene & { finished(): boolean } {
  const palette = () => readColorTokens({
    surface: '--yp-bg-surface', sunken: '--yp-bg-sunken', border: '--yp-border-subtle', line: '--yp-border-default',
    accent: '--yp-link', barLeft: '--yp-status-purple', barRight: '--yp-status-pink',
    green: '--yp-status-green', purple: '--yp-status-purple', orange: '--yp-status-orange', red: '--yp-status-red',
    teal: '--yp-status-teal', pink: '--yp-status-pink',
  }, [0, 115, 234], canvas.parentElement)
  let colors = palette()
  let time = 0

  function roundRect(context: CanvasRenderingContext2D, x: number, y: number, width: number, height: number, radius: number): void {
    context.beginPath()
    context.moveTo(x + radius, y)
    context.arcTo(x + width, y, x + width, y + height, radius)
    context.arcTo(x + width, y + height, x, y + height, radius)
    context.arcTo(x, y + height, x, y, radius)
    context.arcTo(x, y, x + width, y, radius)
    context.closePath()
  }

  function card(context: CanvasRenderingContext2D, value: Card, index: number): void {
    const width = 120, top = 10, height = 96
    context.fillStyle = rgba(colors.surface, 1)
    context.strokeStyle = rgba(colors.border, 1)
    context.lineWidth = 1
    roundRect(context, value.x, top, width, height, 8)
    context.fill()
    context.stroke()
    for (let row = 0; row < ROWS; row++) {
      const appear = progress(time, index * 90 + row * 70, 320)
      if (appear <= 0) continue
      const y = top + 12 + row * 16
      const shift = (1 - appear) * -10
      context.globalAlpha = appear
      context.fillStyle = rgba(colors[value.bar], 0.9)
      roundRect(context, value.x + 8 + shift, y, 4, 11, 2)
      context.fill()
      context.fillStyle = rgba(colors.sunken, 1)
      roundRect(context, value.x + 15 + shift, y, 60, 11, 2)
      context.fill()
      context.fillStyle = rgba(colors.line, 0.9)
      roundRect(context, value.x + 19 + shift, y + 4, 24 + ((row * 13) % 18), 3, 1.5)
      context.fill()
      context.fillStyle = rgba(colors[value.pills[row]!], 0.9)
      roundRect(context, value.x + 80 + shift, y, 30, 11, 2)
      context.fill()
      context.globalAlpha = 1
    }
  }

  function linkPoint(t: number): [number, number] {
    const start: [number, number] = [120, 44], end: [number, number] = [178, 76]
    const c1: [number, number] = [150, 44], c2: [number, number] = [150, 76]
    const u = 1 - t
    return [u ** 3 * start[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t ** 3 * end[0],
      u ** 3 * start[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t ** 3 * end[1]]
  }

  function link(context: CanvasRenderingContext2D): void {
    const drawn = progress(time, 620, 420)
    if (drawn <= 0) return
    context.strokeStyle = rgba(colors.accent, 0.7)
    context.lineWidth = 1.5
    context.setLineDash([3, 3])
    context.beginPath()
    const steps = 24
    for (let index = 0; index <= Math.round(steps * drawn); index++) {
      const [x, y] = linkPoint(index / steps)
      if (index === 0) context.moveTo(x, y); else context.lineTo(x, y)
    }
    context.stroke()
    context.setLineDash([])
    const travel = (time - 900) / 420
    if (travel > 0 && travel < 1) {
      const [x, y] = linkPoint(easeOut(travel))
      context.fillStyle = rgba(colors.accent, 0.95)
      context.beginPath()
      context.arc(x, y, 3, 0, Math.PI * 2)
      context.fill()
    }
  }

  function badge(context: CanvasRenderingContext2D, accent: Rgb): void {
    const appear = progress(time, 360, 300)
    if (appear <= 0) return
    const pulse = time > 1180 ? Math.sin(Math.min(1, (time - 1180) / 300) * Math.PI) * 0.12 : 0
    const radius = 15 * appear * (1 + pulse)
    context.fillStyle = rgba(colors.surface, 1)
    context.strokeStyle = rgba(accent, 0.35 * appear)
    context.lineWidth = 1
    context.beginPath()
    context.arc(150, 58, radius, 0, Math.PI * 2)
    context.fill()
    context.stroke()
    context.strokeStyle = rgba(accent, appear)
    context.lineWidth = 1.5
    context.lineCap = 'round'
    context.beginPath()
    context.moveTo(143, 58); context.lineTo(157, 58)
    context.moveTo(146, 55); context.lineTo(143, 58); context.lineTo(146, 61)
    context.moveTo(154, 55); context.lineTo(157, 58); context.lineTo(154, 61)
    context.stroke()
  }

  return {
    theme() { colors = palette() },
    step(dt) { time = Math.min(CONNECT_BOARDS_DURATION, time + dt) },
    finished() { return time >= CONNECT_BOARDS_DURATION },
    draw(context, width, height) {
      const scale = Math.min(width / BOARD_WIDTH, height / BOARD_HEIGHT)
      context.save()
      context.translate((width - BOARD_WIDTH * scale) / 2, (height - BOARD_HEIGHT * scale) / 2)
      context.scale(scale, scale)
      cards.forEach((value, index) => card(context, value, index))
      link(context)
      badge(context, colors.accent)
      context.restore()
    },
  }
}
