import { describe, expect, it } from 'vitest'
import { createConstellationScene } from './constellation'
import { createOrbitHaloScene } from './orbitHalo'
import { createStatusScene, type StatusSceneVariant } from './statusScenes'

interface Recording {
  context: CanvasRenderingContext2D
  calls: string[]
  styles: string[]
}

function recordingContext(): Recording {
  const calls: string[] = []
  const styles: string[] = []
  const target: Record<string | symbol, unknown> = {}
  const context = new Proxy(target, {
    get(object, property) {
      if (!(property in object)) object[property] = (..._args: unknown[]) => { calls.push(String(property)) }
      return object[property]
    },
    set(object, property, value) {
      if (property === 'fillStyle' || property === 'strokeStyle') styles.push(String(value))
      object[property] = value
      return true
    },
  }) as unknown as CanvasRenderingContext2D
  return { context, calls, styles }
}

function run(scene: ReturnType<typeof createOrbitHaloScene>, width: number, height: number, steps = 80): Recording {
  const recording = recordingContext()
  scene.resize?.(width, height)
  for (let index = 0; index < steps; index += 1) scene.step(33)
  scene.draw(recording.context, width, height)
  return recording
}

const colorPattern = /^rgba\(\d{1,3}, \d{1,3}, \d{1,3}, (0|1)\.\d{3}\)$/u

describe('Canvas 场景', () => {
  it.each<StatusSceneVariant>(['not-found', 'forbidden', 'upgrade', 'offline'])('%s 状态场景只输出合法颜色并按画布缩放', (variant) => {
    const { calls, styles } = run(createStatusScene(variant, document.createElement('canvas')), 280, 240)
    expect(calls).toEqual(expect.arrayContaining(['save', 'translate', 'scale', 'restore']))
    expect(styles.length).toBeGreaterThan(0)
    expect(styles.every(style => colorPattern.test(style))).toBe(true)
  })

  it('星座场景按面积限制节点数量，并在指针附近绘制牵引线', () => {
    const canvas = document.createElement('canvas')
    const small = run(createConstellationScene(canvas), 200, 200, 0)
    expect(small.calls.filter(call => call === 'arc')).toHaveLength(18)
    const large = run(createConstellationScene(canvas), 1600, 1200, 0)
    expect(large.calls.filter(call => call === 'arc')).toHaveLength(70)

    const scene = createConstellationScene(canvas)
    scene.resize?.(400, 400)
    const withoutPointer = recordingContext()
    scene.draw(withoutPointer.context, 400, 400)
    scene.pointer?.({ x: 200, y: 200 })
    const withPointer = recordingContext()
    scene.draw(withPointer.context, 400, 400)
    expect(withPointer.calls.filter(call => call === 'lineTo').length)
      .toBeGreaterThan(withoutPointer.calls.filter(call => call === 'lineTo').length)
  })

  it('星座场景布局确定且缩放后保留节点', () => {
    const canvas = document.createElement('canvas')
    const first = run(createConstellationScene(canvas), 480, 640)
    const second = run(createConstellationScene(canvas), 480, 640)
    expect(first.styles).toEqual(second.styles)
    expect(first.calls).toEqual(second.calls)
  })

  it('轨道光晕输出合法颜色', () => {
    const { styles } = run(createOrbitHaloScene(document.createElement('canvas')), 132, 132)
    expect(styles.every(style => colorPattern.test(style))).toBe(true)
  })
})
