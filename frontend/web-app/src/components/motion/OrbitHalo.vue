<script setup lang="ts">
import { ref } from 'vue'
import { createOrbitHaloScene } from '../../motion/scenes/orbitHalo'
import { useCanvasScene } from './useCanvasScene'

const canvas = ref<HTMLCanvasElement>()
useCanvasScene(canvas, createOrbitHaloScene, { fps: 30 })
</script>

<template>
  <canvas
    ref="canvas"
    class="orbit-halo"
    aria-hidden="true"
  />
</template>

<style scoped>
.orbit-halo {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}
</style>
