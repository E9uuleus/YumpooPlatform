import type { ThemedCanvasScene } from '../canvasStage'
import { readColorTokens, rgba } from '../themeTokens'

const BOARD = 132
const CENTER = BOARD / 2
const TAU = Math.PI * 2

/** Quiet orbit behind an empty-state icon: two rings and three slow satellites. */
export function createOrbitHaloScene(canvas: HTMLCanvasElement): ThemedCanvasScene {
  const palette = () => readColorTokens({ accent: '--yp-link', muted: '--yp-text-muted' }, [0, 115, 234], canvas.parentElement)
  let colors = palette()
  let time = 1200

  function satellite(context: CanvasRenderingContext2D, radius: number, angle: number, size: number, color: string): void {
    context.fillStyle = color
    context.beginPath()
    context.arc(CENTER + radius * Math.cos(angle), CENTER + radius * Math.sin(angle), size, 0, TAU)
    context.fill()
  }

  return {
    theme() {
      colors = palette()
    },
    step(dt) {
      time += dt
    },
    draw(context, width, height) {
      const scale = Math.min(width, height) / BOARD
      context.save()
      context.translate((width - BOARD * scale) / 2, (height - BOARD * scale) / 2)
      context.scale(scale, scale)

      context.fillStyle = rgba(colors.accent, 0.06)
      context.beginPath()
      context.arc(CENTER, CENTER, 58, 0, TAU)
      context.fill()
      context.lineWidth = 1
      context.strokeStyle = rgba(colors.accent, 0.22)
      context.beginPath()
      context.arc(CENTER, CENTER, 40, 0, TAU)
      context.stroke()
      context.setLineDash([2, 5])
      context.strokeStyle = rgba(colors.muted, 0.5)
      context.beginPath()
      context.arc(CENTER, CENTER, 56, 0, TAU)
      context.stroke()
      context.setLineDash([])

      satellite(context, 40, time * 0.0009, 2.6, rgba(colors.accent, 0.85))
      satellite(context, 56, -time * 0.0006, 2.4, rgba(colors.accent, 0.55))
      satellite(context, 56, -time * 0.0006 + Math.PI, 2, rgba(colors.muted, 0.8))
      context.restore()
    },
  }
}
