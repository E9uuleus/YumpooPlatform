<script setup lang="ts">
import { ref } from 'vue'
import { createStatusScene, type StatusSceneVariant } from '../../motion/scenes/statusScenes'
import { useCanvasScene } from './useCanvasScene'

// The scene is built once; parents key this component by variant when it can change.
const props = defineProps<{ variant: StatusSceneVariant }>()

const canvas = ref<HTMLCanvasElement>()
useCanvasScene(canvas, element => createStatusScene(props.variant, element), { fps: 30 })
</script>

<template>
  <canvas
    ref="canvas"
    class="status-scene"
    :data-variant="variant"
    aria-hidden="true"
  />
</template>

<style scoped>
.status-scene {
  display: block;
  width: 100%;
  height: 100%;
}
</style>
