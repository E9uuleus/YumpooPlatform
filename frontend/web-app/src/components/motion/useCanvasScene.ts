import { onBeforeUnmount, onMounted, type Ref } from 'vue'
import { createCanvasStage, type CanvasScene, type CanvasStage, type CanvasStageOptions } from '../../motion/canvasStage'
import { watchTheme } from '../../motion/themeTokens'

export interface ThemedCanvasScene extends CanvasScene {
  /** Re-reads token colors after the theme changes. */
  theme?(): void
}

interface SceneOptions extends Omit<CanvasStageOptions, 'pointerTarget'> {
  pointerTarget?: Ref<HTMLElement | undefined>
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
    stage = createCanvasStage(element, scene, { ...options, pointerTarget: options.pointerTarget?.value })
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
