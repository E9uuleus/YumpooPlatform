import { onBeforeUnmount, onMounted, type Ref } from 'vue'
import { createCanvasStage, type CanvasStage, type CanvasStageOptions, type ThemedCanvasScene } from '../../motion/canvasStage'
import { watchTheme } from '../../motion/themeTokens'

interface SceneOptions extends Omit<CanvasStageOptions, 'pointerTarget'> {
  /** Forward pointer movement over the canvas' parent to the scene. */
  followParentPointer?: boolean
}

/** Mounts a decorative scene on a canvas for the component's lifetime; nothing runs where 2D canvas is unavailable. */
export function useCanvasScene(
  canvas: Ref<HTMLCanvasElement | undefined>,
  createScene: (canvas: HTMLCanvasElement) => ThemedCanvasScene,
  options: SceneOptions = {},
): void {
  let stage: CanvasStage | undefined
  let stopWatchingTheme: (() => void) | undefined

  onMounted(() => {
    const element = canvas.value
    if (!element?.getContext('2d')) return
    const scene = createScene(element)
    const { followParentPointer, ...stageOptions } = options
    stage = createCanvasStage(element, scene, {
      ...stageOptions,
      pointerTarget: followParentPointer ? element.parentElement ?? undefined : undefined,
    })
    stopWatchingTheme = watchTheme(() => {
      scene.theme?.()
      stage?.redraw()
    })
  })

  onBeforeUnmount(() => {
    stopWatchingTheme?.()
    stage?.destroy()
  })
}
