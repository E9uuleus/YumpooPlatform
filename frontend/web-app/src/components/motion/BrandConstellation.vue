<script setup lang="ts">
import { ref } from 'vue'
import { createConstellationScene } from '../../motion/scenes/constellation'
import { useCanvasScene } from './useCanvasScene'

const canvas = ref<HTMLCanvasElement>()
useCanvasScene(canvas, createConstellationScene, { followParentPointer: true })
</script>

<template>
  <canvas
    ref="canvas"
    class="brand-constellation"
    aria-hidden="true"
  />
</template>

<style scoped>
.brand-constellation {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}
</style>
