import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createCanvasStage, type CanvasScene } from './canvasStage'
import { createRandom } from './noise'
import { parseColor, readColorTokens, rgba } from './themeTokens'

function fakeContext(): CanvasRenderingContext2D {
  return { clearRect: vi.fn(), setTransform: vi.fn() } as unknown as CanvasRenderingContext2D
}

function sizedCanvas(context: CanvasRenderingContext2D | null = fakeContext()): HTMLCanvasElement {
  const canvas = document.createElement('canvas')
  Object.defineProperty(canvas, 'clientWidth', { value: 200 })
  Object.defineProperty(canvas, 'clientHeight', { value: 100 })
  canvas.getContext = vi.fn(() => context) as unknown as HTMLCanvasElement['getContext']
  return canvas
}

function scene() {
  return {
    step: vi.fn<CanvasScene['step']>(),
    draw: vi.fn<CanvasScene['draw']>(),
    resize: vi.fn<NonNullable<CanvasScene['resize']>>(),
  }
}

function stubReducedMotion(matches: boolean): void {
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches, addEventListener: vi.fn(), removeEventListener: vi.fn() })))
}

describe('Canvas 动效舞台', () => {
  let frames: FrameRequestCallback[]

  beforeEach(() => {
    frames = []
    vi.stubGlobal('ResizeObserver', undefined)
    vi.stubGlobal('IntersectionObserver', undefined)
    vi.stubGlobal('requestAnimationFrame', vi.fn((callback: FrameRequestCallback) => frames.push(callback)))
    vi.stubGlobal('cancelAnimationFrame', vi.fn())
    stubReducedMotion(false)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    Reflect.deleteProperty(document, 'hidden')
  })

  it('没有 2D 上下文时完全空转', () => {
    const current = scene()
    const stage = createCanvasStage(sizedCanvas(null), current)
    expect(stage.running).toBe(false)
    expect(current.draw).not.toHaveBeenCalled()
    expect(requestAnimationFrame).not.toHaveBeenCalled()
  })

  it('按设备像素缩放并以受限步长推进动画', () => {
    const current = scene()
    const context = fakeContext()
    const canvas = sizedCanvas(context)
    const stage = createCanvasStage(canvas, current)
    expect(canvas.width).toBe(200 * Math.min(window.devicePixelRatio || 1, 2))
    expect(current.resize).toHaveBeenCalledWith(200, 100)
    expect(stage.running).toBe(true)

    frames.shift()!(performance.now() + 500)
    expect(current.step).toHaveBeenCalledWith(50)
    expect(current.draw).toHaveBeenLastCalledWith(context, 200, 100)
    expect(frames).toHaveLength(1)
    stage.destroy()
  })

  it('减少动态效果时只绘制预热后的静态帧', () => {
    stubReducedMotion(true)
    const current = scene()
    const stage = createCanvasStage(sizedCanvas(), current, { warmup: 500 })
    expect(requestAnimationFrame).not.toHaveBeenCalled()
    expect(stage.running).toBe(false)
    expect(current.step).toHaveBeenCalledTimes(10)
    expect(current.draw).toHaveBeenCalledTimes(1)
  })

  it('页面隐藏时暂停，销毁后不再恢复', () => {
    const current = scene()
    const stage = createCanvasStage(sizedCanvas(), current)
    Object.defineProperty(document, 'hidden', { configurable: true, value: true })
    document.dispatchEvent(new Event('visibilitychange'))
    expect(cancelAnimationFrame).toHaveBeenCalled()
    expect(stage.running).toBe(false)

    stage.destroy()
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    document.dispatchEvent(new Event('visibilitychange'))
    expect(requestAnimationFrame).toHaveBeenCalledTimes(1)
  })

  it('指针事件转换为画布坐标，离开时清除', () => {
    const target = document.createElement('div')
    const pointer = vi.fn()
    const canvas = sizedCanvas()
    canvas.getBoundingClientRect = () => ({ left: 10, top: 20 }) as DOMRect
    const stage = createCanvasStage(canvas, { ...scene(), pointer }, { pointerTarget: target })
    target.dispatchEvent(Object.assign(new Event('pointermove'), { clientX: 60, clientY: 50 }))
    target.dispatchEvent(new Event('pointerleave'))
    expect(pointer.mock.calls).toEqual([[{ x: 50, y: 30 }], [null]])
    stage.destroy()
  })
})

describe('动效颜色与随机源', () => {
  it('解析浏览器返回的颜色格式', () => {
    expect(parseColor('#0073ea')).toEqual([0, 115, 234])
    expect(parseColor('#fff')).toEqual([255, 255, 255])
    expect(parseColor('#70b7ffcc')).toEqual([112, 183, 255])
    expect(parseColor('rgb(1, 2, 3)')).toEqual([1, 2, 3])
    expect(parseColor('rgba(10 20 30 / 50%)')).toEqual([10, 20, 30])
    expect(parseColor('color(srgb 1 0.5 0)')).toEqual([255, 128, 0])
    expect(parseColor('var(--missing)')).toBeNull()
    expect(rgba([1, 2, 3], 1.5)).toBe('rgba(1, 2, 3, 1.000)')
  })

  it('无法解析的 Token 回退到默认色并清理探针', () => {
    const colors = readColorTokens({ glow: '--yp-missing-token' }, [9, 9, 9])
    expect(colors.glow).toEqual([9, 9, 9])
    expect(document.documentElement.querySelector('span')).toBeNull()
  })

  it('相同种子生成相同序列', () => {
    const first = createRandom(42)
    const second = createRandom(42)
    const values = [first(), first(), first()]
    expect([second(), second(), second()]).toEqual(values)
    expect(values.every(value => value >= 0 && value < 1)).toBe(true)
  })
})
