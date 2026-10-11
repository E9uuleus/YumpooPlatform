<script setup lang="ts">
import { ref } from 'vue'
import { AURORA_SHAPES_WARMUP, createAuroraShapesScene, type HeroDaypart } from '../../motion/scenes/auroraShapes'
import { useCanvasScene } from './useCanvasScene'

const props = defineProps<{ daypart: HeroDaypart }>()
const canvas = ref<HTMLCanvasElement>()
useCanvasScene(canvas, element => createAuroraShapesScene(element, () => props.daypart), {
  fps: 30,
  warmup: AURORA_SHAPES_WARMUP,
  followParentPointer: true,
})
</script>

<template>
  <canvas
    ref="canvas"
    class="aurora-shapes"
    aria-hidden="true"
  />
</template>

<style scoped>
.aurora-shapes {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}
</style>
