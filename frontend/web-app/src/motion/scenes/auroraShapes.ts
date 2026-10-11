import type { CanvasPoint, ThemedCanvasScene } from '../canvasStage'
import { createRandom } from '../noise'
import { readColorTokens, rgba, type Rgb } from '../themeTokens'

export type HeroDaypart = 'morning' | 'noon' | 'afternoon' | 'night'

type ShapeKind = 'square' | 'circle' | 'pill' | 'ring'

interface Shape {
  /** Fraction of the shape stage, which is the right part of the hero. */
  x: number
  y: number
  size: number
  kind: ShapeKind
  token: string
  /** 0–1: nearer shapes are larger, more opaque and move further with the pointer. */
  depth: number
  order: number
}

const AURORA: Record<HeroDaypart, readonly string[]> = {
  morning: ['--yp-label-peach', '--yp-label-egg-yolk', '--yp-label-chili-blue', '--yp-label-lavender'],
  noon: ['--yp-label-bright-blue', '--yp-label-aquamarine', '--yp-label-chili-blue', '--yp-label-lavender'],
  afternoon: ['--yp-label-bright-blue', '--yp-label-aquamarine', '--yp-label-peach', '--yp-label-lavender'],
  night: ['--yp-label-navy', '--yp-label-indigo', '--yp-label-royal', '--yp-label-dark-purple'],
}

const SHAPES: readonly Shape[] = ([
  { x: 0.18, y: 0.28, size: 22, kind: 'square', token: '--yp-label-bright-blue', depth: 0.9 },
  { x: 0.42, y: 0.18, size: 13, kind: 'circle', token: '--yp-label-egg-yolk', depth: 0.6 },
  { x: 0.66, y: 0.32, size: 18, kind: 'pill', token: '--yp-label-sunset', depth: 0.8 },
  { x: 0.86, y: 0.22, size: 10, kind: 'circle', token: '--yp-label-aquamarine', depth: 0.5 },
  { x: 0.28, y: 0.72, size: 15, kind: 'ring', token: '--yp-label-dark-purple', depth: 0.7 },
  { x: 0.55, y: 0.66, size: 26, kind: 'square', token: '--yp-label-aquamarine', depth: 1 },
  { x: 0.8, y: 0.7, size: 14, kind: 'pill', token: '--yp-label-bright-green', depth: 0.65 },
  { x: 0.95, y: 0.52, size: 9, kind: 'square', token: '--yp-label-bright-blue', depth: 0.45 },
  { x: 0.38, y: 0.48, size: 8, kind: 'circle', token: '--yp-label-dark-orange', depth: 0.4 },
] as const).map((shape, order) => ({ ...shape, order })).sort((a, b) => a.depth - b.depth)

const SURFACE = '--yp-bg-surface'
const HIGHLIGHT = '--yp-status-blue-foreground'
const TOKENS = [...new Set([...Object.values(AURORA).flat(), ...SHAPES.map(shape => shape.token), SURFACE, HIGHLIGHT])]
const STAGE_START = 0.44
const REFERENCE_HEIGHT = 220
const ENTER_DURATION = 900
const ENTER_STAGGER = 70
const NOISE_SIZE = 96
const TAU = Math.PI * 2

/** Settle time for the static frame under reduced motion: every shape has finished entering. */
export const AURORA_SHAPES_WARMUP = ENTER_DURATION + ENTER_STAGGER * SHAPES.length + 100

const ease = (value: number) => 1 - (1 - Math.min(1, Math.max(0, value))) ** 3

/**
 * Home hero: drifting aurora glows in the daypart palette, a surface-coloured fade on the left that keeps the greeting
 * readable, floating label-coloured shapes with depth parallax, and fixed-seed film grain.
 */
export function createAuroraShapesScene(canvas: HTMLCanvasElement, daypart: () => HeroDaypart): ThemedCanvasScene {
  let colors = palette()
  let dark = isDark()
  let time = 0
  let target: CanvasPoint = { x: 0, y: 0 }
  const tilt: CanvasPoint = { x: 0, y: 0 }
  let width = 0
  let height = 0
  const grain = createGrain()
  let grainPattern: CanvasPattern | null = null

  function palette(): Record<string, Rgb> {
    return readColorTokens(Object.fromEntries(TOKENS.map(token => [token, token])), [87, 155, 252], canvas.parentElement)
  }

  function isDark(): boolean {
    return document.documentElement.classList.contains('dark')
  }

  function color(token: string): Rgb {
    return colors[token] ?? [87, 155, 252]
  }

  function drawAurora(context: CanvasRenderingContext2D): void {
    AURORA[daypart()].forEach((token, index) => {
      const x = width * (0.62 + 0.22 * Math.sin(time * 0.00013 + index * 1.9))
      const y = height * (0.5 + 0.42 * Math.cos(time * 0.00011 + index * 2.3))
      const radius = height * (0.78 + 0.15 * Math.sin(time * 0.0002 + index))
      const glow = context.createRadialGradient(x, y, 0, x, y, radius)
      glow.addColorStop(0, rgba(color(token), dark ? 0.55 : 0.5))
      glow.addColorStop(1, rgba(color(token), 0))
      context.fillStyle = glow
      context.fillRect(0, 0, width, height)
    })
    const fade = context.createLinearGradient(0, 0, width * 0.6, 0)
    fade.addColorStop(0, rgba(color(SURFACE), 1))
    fade.addColorStop(0.55, rgba(color(SURFACE), 0.85))
    fade.addColorStop(1, rgba(color(SURFACE), 0))
    context.fillStyle = fade
    context.fillRect(0, 0, width, height)
  }

  function outline(context: CanvasRenderingContext2D, kind: ShapeKind, size: number): void {
    context.beginPath()
    if (kind === 'square') context.roundRect(-size, -size, size * 2, size * 2, size * 0.45)
    else if (kind === 'pill') context.roundRect(-size * 1.5, -size * 0.6, size * 3, size * 1.2, size * 0.6)
    else context.arc(0, 0, size, 0, TAU)
    if (kind === 'ring') context.arc(0, 0, size * 0.55, 0, TAU, true)
  }

  function shine(context: CanvasRenderingContext2D, kind: ShapeKind, size: number): void {
    context.beginPath()
    if (kind === 'square') context.roundRect(-size * 0.8, -size * 0.85, size * 1.6, size * 0.8, size * 0.35)
    else if (kind === 'pill') context.roundRect(-size * 1.3, -size * 0.5, size * 2.6, size * 0.5, size * 0.25)
    else context.ellipse(0, -size * 0.4, size * 0.7, size * 0.4, 0, 0, TAU)
  }

  function drawShape(context: CanvasRenderingContext2D, shape: Shape): void {
    const enter = ease((time - shape.order * ENTER_STAGGER) / ENTER_DURATION)
    if (enter <= 0) return
    const { depth, kind, order } = shape
    const scale = height / REFERENCE_HEIGHT
    const size = shape.size * (0.7 + 0.3 * depth) * scale
    const x = width * (STAGE_START + shape.x * (1 - STAGE_START)) + Math.sin(time * 0.0005 + order) * 6 * depth - tilt.x * 30 * depth
    const y = height * shape.y + Math.cos(time * 0.00042 + order * 1.3) * 8 * depth - tilt.y * 20 * depth + (1 - enter) * 20
    const tint = color(shape.token)

    context.save()
    context.globalAlpha = enter * (0.55 + 0.45 * depth)
    context.translate(x, y)
    if (kind === 'square' || kind === 'pill') context.rotate((time * 0.0001 * (order % 2 ? 1 : -1)) + order * 0.4)
    const body = context.createLinearGradient(-size, -size, size, size)
    body.addColorStop(0, rgba(tint, 1))
    body.addColorStop(1, rgba(tint, 0.72))
    context.fillStyle = body
    context.shadowColor = rgba(tint, dark ? 0.5 : 0.35)
    context.shadowBlur = 14 * depth * scale
    context.shadowOffsetY = 6 * depth * scale
    outline(context, kind, size)
    context.fill('evenodd')
    context.shadowColor = rgba(tint, 0)
    const light = context.createLinearGradient(0, -size, 0, 0)
    light.addColorStop(0, rgba(color(HIGHLIGHT), 0.45))
    light.addColorStop(1, rgba(color(HIGHLIGHT), 0))
    context.fillStyle = light
    shine(context, kind, size)
    context.fill()
    context.restore()
  }

  return {
    theme() {
      colors = palette()
      dark = isDark()
    },
    resize(nextWidth, nextHeight) {
      width = nextWidth
      height = nextHeight
    },
    pointer(point) {
      target = point && width && height ? { x: point.x / width - 0.5, y: point.y / height - 0.5 } : { x: 0, y: 0 }
    },
    step(dt) {
      time += dt
      const follow = Math.min(1, dt / 120)
      tilt.x += (target.x - tilt.x) * follow
      tilt.y += (target.y - tilt.y) * follow
    },
    draw(context) {
      context.fillStyle = rgba(color(SURFACE), 1)
      context.fillRect(0, 0, width, height)
      drawAurora(context)
      for (const shape of SHAPES) drawShape(context, shape)
      grainPattern ??= grain && context.createPattern(grain, 'repeat')
      if (grainPattern) {
        context.globalAlpha = dark ? 0.07 : 0.05
        context.fillStyle = grainPattern
        context.fillRect(0, 0, width, height)
        context.globalAlpha = 1
      }
    },
  }
}

function createGrain(): HTMLCanvasElement | null {
  const tile = document.createElement('canvas')
  tile.width = NOISE_SIZE
  tile.height = NOISE_SIZE
  const context = tile.getContext('2d')
  if (!context) return null
  const random = createRandom(20261010)
  const image = context.createImageData(NOISE_SIZE, NOISE_SIZE)
  for (let index = 0; index < image.data.length; index += 4) {
    const value = Math.round(random() * 255)
    image.data[index] = value
    image.data[index + 1] = value
    image.data[index + 2] = value
    image.data[index + 3] = 255
  }
  context.putImageData(image, 0, 0)
  return tile
}
