export interface CanvasPoint {
  x: number
  y: number
}

export interface CanvasScene {
  /** Receives the drawing size in CSS pixels whenever the canvas is laid out again. */
  resize?(width: number, height: number): void
  /** Advances the simulation by `dt` milliseconds. */
  step(dt: number): void
  draw(context: CanvasRenderingContext2D, width: number, height: number): void
  /** Pointer position relative to the canvas, or null once the pointer leaves. */
  pointer?(point: CanvasPoint | null): void
}

export interface ThemedCanvasScene extends CanvasScene {
  /** Re-reads token colors after the theme changes. */
  theme?(): void
}

export interface CanvasStageOptions {
  /** Frame cap; ambient decorations use 30. */
  fps?: number | undefined
  /** Simulated time before the single frame painted under reduced motion. */
  warmup?: number | undefined
  /** Element whose pointer movement is forwarded to the scene. */
  pointerTarget?: HTMLElement | undefined
}

export interface CanvasStage {
  readonly running: boolean
  redraw(): void
  destroy(): void
}

const MAX_STEP = 50

const inertStage: CanvasStage = {
  running: false,
  redraw: () => undefined,
  destroy: () => undefined,
}

/**
 * Drives a decorative canvas: device-pixel scaling, a capped rAF loop that pauses off screen or in hidden tabs,
 * and a static frame when the user prefers reduced motion. Without a 2D context (tests, blocked canvas) it does nothing.
 */
export function createCanvasStage(canvas: HTMLCanvasElement, scene: CanvasScene, options: CanvasStageOptions = {}): CanvasStage {
  const context = canvas.getContext('2d')
  if (!context) return inertStage

  const frameInterval = 1000 / (options.fps ?? 60)
  const warmup = options.warmup ?? 1200
  const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)')
  const cleanups: Array<() => void> = []
  let width = 0
  let height = 0
  let frame = 0
  let last = 0
  let visible = true
  let settled = false
  let destroyed = false

  const still = () => Boolean(reducedMotion?.matches)
  const active = () => visible && !document.hidden && !still() && width > 0 && height > 0

  function paint(): void {
    context!.clearRect(0, 0, width, height)
    scene.draw(context!, width, height)
  }

  function settle(): void {
    if (!settled) {
      for (let elapsed = 0; elapsed < warmup; elapsed += MAX_STEP) scene.step(MAX_STEP)
      settled = true
    }
    paint()
  }

  function tick(now: number): void {
    frame = 0
    if (!active()) return
    const elapsed = now - last
    if (elapsed >= frameInterval - 1) {
      scene.step(Math.min(elapsed, MAX_STEP))
      last = now
      paint()
    }
    frame = requestAnimationFrame(tick)
  }

  function sync(): void {
    if (destroyed) return
    if (active()) {
      if (!frame) {
        last = performance.now()
        frame = requestAnimationFrame(tick)
      }
      return
    }
    if (frame) {
      cancelAnimationFrame(frame)
      frame = 0
    }
    if (still()) {
      scene.pointer?.(null)
      settle()
    }
  }

  function resize(): void {
    width = canvas.clientWidth
    height = canvas.clientHeight
    const ratio = Math.min(window.devicePixelRatio || 1, 2)
    canvas.width = Math.round(width * ratio)
    canvas.height = Math.round(height * ratio)
    context!.setTransform(ratio, 0, 0, ratio, 0, 0)
    scene.resize?.(width, height)
    settled = false
    if (!still()) paint()
    sync()
  }

  function listen(target: EventTarget, type: string, handler: (event: Event) => void): void {
    target.addEventListener(type, handler)
    cleanups.push(() => target.removeEventListener(type, handler))
  }

  if (typeof ResizeObserver !== 'undefined') {
    const observer = new ResizeObserver(resize)
    observer.observe(canvas)
    cleanups.push(() => observer.disconnect())
  }
  if (typeof IntersectionObserver !== 'undefined') {
    const observer = new IntersectionObserver((entries) => {
      visible = entries.some(entry => entry.isIntersecting)
      sync()
    })
    observer.observe(canvas)
    cleanups.push(() => observer.disconnect())
  }
  listen(document, 'visibilitychange', sync)
  if (reducedMotion) listen(reducedMotion, 'change', sync)
  const target = options.pointerTarget
  if (target && scene.pointer) {
    listen(target, 'pointermove', (event) => {
      if (still()) return
      const bounds = canvas.getBoundingClientRect()
      const { clientX, clientY } = event as PointerEvent
      scene.pointer?.({ x: clientX - bounds.left, y: clientY - bounds.top })
    })
    listen(target, 'pointerleave', () => scene.pointer?.(null))
  }

  resize()

  return {
    get running() {
      return frame !== 0
    },
    redraw() {
      if (!destroyed) paint()
    },
    destroy() {
      destroyed = true
      if (frame) cancelAnimationFrame(frame)
      frame = 0
      cleanups.splice(0).forEach(cleanup => cleanup())
    },
  }
}
