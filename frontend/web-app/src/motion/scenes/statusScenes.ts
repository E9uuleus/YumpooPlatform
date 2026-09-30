import type { ThemedCanvasScene } from '../canvasStage'
import { createRandom } from '../noise'
import { readColorTokens, rgba, type Rgb } from '../themeTokens'

export type StatusSceneVariant = 'not-found' | 'forbidden' | 'upgrade' | 'offline'

type Palette = Record<'accent' | 'muted', Rgb>

interface Drawing {
  step(dt: number): void
  draw(context: CanvasRenderingContext2D, colors: Palette): void
}

// Scenes are authored on a 260×220 board and scaled to the canvas.
const BOARD_WIDTH = 260
const BOARD_HEIGHT = 220
const CENTER_X = 130
const CENTER_Y = 112
const TAU = Math.PI * 2

function dot(context: CanvasRenderingContext2D, x: number, y: number, radius: number, color: string): void {
  context.fillStyle = color
  context.beginPath()
  context.arc(x, y, radius, 0, TAU)
  context.fill()
}

function ring(context: CanvasRenderingContext2D, x: number, y: number, radius: number, color: string, lineWidth = 1): void {
  context.strokeStyle = color
  context.lineWidth = lineWidth
  context.beginPath()
  context.arc(x, y, radius, 0, TAU)
  context.stroke()
}

/** A satellite drifts off its orbit and fades out: the page is not where it was expected. */
function notFound(): Drawing {
  const tilt = -0.35
  const orbits = [46, 74, 100]
  let time = 4100
  const onOrbit = (radiusX: number, angle: number): [number, number] => {
    const x = radiusX * Math.cos(angle)
    const y = radiusX * 0.42 * Math.sin(angle)
    return [CENTER_X + x * Math.cos(tilt) - y * Math.sin(tilt), CENTER_Y + x * Math.sin(tilt) + y * Math.cos(tilt)]
  }
  return {
    step(dt) {
      time += dt
    },
    draw(context, { accent, muted }) {
      orbits.forEach((radiusX, index) => {
        context.save()
        context.translate(CENTER_X, CENTER_Y)
        context.rotate(tilt)
        context.setLineDash(index === 2 ? [3, 5] : [])
        context.strokeStyle = rgba(muted, 0.5)
        context.lineWidth = 1
        context.beginPath()
        context.ellipse(0, 0, radiusX, radiusX * 0.42, 0, 0, TAU)
        context.stroke()
        context.restore()
      })
      dot(context, CENTER_X, CENTER_Y, 16, rgba(accent, 0.14))
      ring(context, CENTER_X, CENTER_Y, 16, rgba(accent, 0.9), 1.5)
      dot(context, ...onOrbit(46, time * 0.0011), 3.5, rgba(accent, 0.9))
      dot(context, ...onOrbit(74, 2 - time * 0.0007), 3.5, rgba(accent, 0.6))

      const drift = (time * 0.00011) % 1
      const [startX, startY] = onOrbit(100, -2.2)
      const distance = drift * 62
      const x = startX - 0.55 * distance
      const y = startY - 0.83 * distance
      const alpha = drift < 0.8 ? 1 : 1 - (drift - 0.8) / 0.2
      context.setLineDash([3, 4])
      context.strokeStyle = rgba(muted, 0.8 * alpha)
      context.lineWidth = 1
      context.beginPath()
      context.moveTo(startX, startY)
      context.lineTo(x, y)
      context.stroke()
      context.setLineDash([])
      dot(context, x, y, 3.5, rgba(accent, alpha))
      ring(context, x, y, 7, rgba(accent, 0.35 * alpha))
    },
  }
}

/** Particles glance off a locked shield. */
function forbidden(): Drawing {
  const random = createRandom(403)
  const shield = 54
  const particles = Array.from({ length: 24 }, () => {
    let x = 0
    let y = 0
    do {
      x = 10 + random() * (BOARD_WIDTH - 20)
      y = 10 + random() * (BOARD_HEIGHT - 20)
    } while (Math.hypot(x - CENTER_X, y - CENTER_Y) < shield + 12)
    const angle = random() * TAU
    const speed = 0.03 + random() * 0.02
    return { x, y, vx: Math.cos(angle) * speed, vy: Math.sin(angle) * speed, hit: 0 }
  })
  return {
    step(dt) {
      for (const particle of particles) {
        particle.x += particle.vx * dt
        particle.y += particle.vy * dt
        if (particle.x < 6 || particle.x > BOARD_WIDTH - 6) particle.vx *= -1
        if (particle.y < 6 || particle.y > BOARD_HEIGHT - 6) particle.vy *= -1
        particle.x = Math.max(6, Math.min(BOARD_WIDTH - 6, particle.x))
        particle.y = Math.max(6, Math.min(BOARD_HEIGHT - 6, particle.y))
        const dx = particle.x - CENTER_X
        const dy = particle.y - CENTER_Y
        const distance = Math.hypot(dx, dy) || 1
        if (distance < shield + 4) {
          const nx = dx / distance
          const ny = dy / distance
          const along = particle.vx * nx + particle.vy * ny
          if (along < 0) {
            particle.vx -= 2 * along * nx
            particle.vy -= 2 * along * ny
            particle.hit = 1
          }
          particle.x = CENTER_X + nx * (shield + 4)
          particle.y = CENTER_Y + ny * (shield + 4)
        }
        particle.hit = Math.max(0, particle.hit - dt / 500)
      }
    },
    draw(context, { accent, muted }) {
      dot(context, CENTER_X, CENTER_Y, shield, rgba(accent, 0.07))
      ring(context, CENTER_X, CENTER_Y, shield, rgba(accent, 0.8), 1.5)
      context.setLineDash([2, 5])
      ring(context, CENTER_X, CENTER_Y, shield - 8, rgba(accent, 0.35))
      context.setLineDash([])

      context.strokeStyle = rgba(accent, 0.95)
      context.lineWidth = 2
      context.beginPath()
      context.roundRect(CENTER_X - 11, CENTER_Y - 2, 22, 18, 3)
      context.stroke()
      context.beginPath()
      context.arc(CENTER_X, CENTER_Y - 2, 7, Math.PI, 0)
      context.stroke()
      dot(context, CENTER_X, CENTER_Y + 7, 2, rgba(accent, 0.95))

      for (const particle of particles) {
        dot(context, particle.x, particle.y, 2.4, particle.hit > 0 ? rgba(accent, 0.35 + 0.65 * particle.hit) : rgba(muted, 0.7))
      }
    },
  }
}

/** Columns of particles rise and converge on an arrow: a newer client is needed. */
function upgrade(): Drawing {
  const random = createRandom(426)
  let particles: Array<{ column: number, progress: number, speed: number }> = []
  let untilSpawn = 0
  return {
    step(dt) {
      untilSpawn -= dt
      if (untilSpawn <= 0) {
        untilSpawn = 110
        particles.push({ column: Math.floor(random() * 7), progress: 0, speed: 0.00045 + random() * 0.0003 })
      }
      for (const particle of particles) particle.progress += particle.speed * dt
      particles = particles.filter(particle => particle.progress < 1)
    },
    draw(context, { accent, muted }) {
      context.setLineDash([2, 6])
      context.strokeStyle = rgba(muted, 0.35)
      context.lineWidth = 1
      for (let column = 0; column < 7; column += 1) {
        const x = 40 + column * 30
        context.beginPath()
        context.moveTo(x, BOARD_HEIGHT - 20)
        context.lineTo(x, BOARD_HEIGHT - 80)
        context.stroke()
      }
      context.setLineDash([])
      for (const particle of particles) {
        const startX = 40 + particle.column * 30
        const x = startX + (CENTER_X - startX) * particle.progress ** 2.2
        const y = BOARD_HEIGHT - 24 - particle.progress * (BOARD_HEIGHT - 70)
        const alpha = Math.min(1, particle.progress * 6) * (1 - Math.max(0, (particle.progress - 0.75) / 0.25))
        dot(context, x, y, 2.6, rgba(accent, alpha))
      }
      context.strokeStyle = rgba(accent, 0.9)
      context.lineWidth = 2.5
      context.lineCap = 'round'
      context.lineJoin = 'round'
      context.beginPath()
      context.moveTo(CENTER_X - 14, 52)
      context.lineTo(CENTER_X, 38)
      context.lineTo(CENTER_X + 14, 52)
      context.stroke()
      context.lineCap = 'butt'
      context.lineJoin = 'miter'
    },
  }
}

/** Signal waves flatten and slowly recover: the service is temporarily unreachable. */
function offline(): Drawing {
  let time = 800
  const waves = [
    { amplitude: 26, alpha: 0.9, width: 2, offset: 0, tone: 'accent' },
    { amplitude: 16, alpha: 0.45, width: 1.5, offset: 1.7, tone: 'accent' },
    { amplitude: 10, alpha: 0.55, width: 1, offset: 3.1, tone: 'muted' },
  ] as const
  return {
    step(dt) {
      time += dt
    },
    draw(context, colors) {
      const phase = (time % 6400) / 6400
      let envelope = phase < 0.4 ? 1 : phase < 0.5 ? 1 - (phase - 0.4) / 0.1 : phase < 0.72 ? 0 : phase < 0.9 ? (phase - 0.72) / 0.18 : 1
      envelope = envelope * envelope * (3 - 2 * envelope)
      waves.forEach((wave, index) => {
        context.strokeStyle = rgba(colors[wave.tone], wave.alpha)
        context.lineWidth = wave.width
        context.beginPath()
        for (let x = 10; x <= BOARD_WIDTH - 10; x += 3) {
          const taper = Math.sin((Math.PI * (x - 10)) / (BOARD_WIDTH - 20))
          const y = CENTER_Y + Math.sin(x * 0.045 + time * 0.0022 * (1 + index * 0.3) + wave.offset) * wave.amplitude * envelope * taper
          if (x === 10) context.moveTo(x, y)
          else context.lineTo(x, y)
        }
        context.stroke()
      })
      if (envelope < 0.05) ring(context, CENTER_X, CENTER_Y, 5, rgba(colors.muted, 0.8), 1.5)
    },
  }
}

const drawings: Record<StatusSceneVariant, () => Drawing> = {
  'not-found': notFound,
  forbidden,
  upgrade,
  offline,
}

export function createStatusScene(variant: StatusSceneVariant, canvas: HTMLCanvasElement): ThemedCanvasScene {
  const drawing = drawings[variant]()
  const palette = () => readColorTokens({ accent: '--yp-link', muted: '--yp-text-muted' }, [0, 115, 234], canvas.parentElement)
  let colors = palette()
  return {
    theme() {
      colors = palette()
    },
    step: dt => drawing.step(dt),
    draw(context, width, height) {
      const scale = Math.min(width / BOARD_WIDTH, height / BOARD_HEIGHT)
      context.save()
      context.translate((width - BOARD_WIDTH * scale) / 2, (height - BOARD_HEIGHT * scale) / 2)
      context.scale(scale, scale)
      drawing.draw(context, colors)
      context.restore()
    },
  }
}
