<script setup lang="ts">
import { ref } from 'vue'
import { CONNECT_BOARDS_DURATION, createConnectBoardsScene } from '../../../motion/scenes/connectBoards'
import { useCanvasScene } from '../../motion/useCanvasScene'

const canvas = ref<HTMLCanvasElement>()
useCanvasScene(canvas, createConnectBoardsScene, { fps: 60, warmup: CONNECT_BOARDS_DURATION })
</script>

<template>
  <div class="connect-boards-illustration">
    <canvas
      ref="canvas"
      aria-hidden="true"
    />
  </div>
</template>

<style scoped>
.connect-boards-illustration { position: relative; height: 128px; border-radius: var(--yp-radius-lg); background: color-mix(in srgb, var(--yp-bg-selected) 55%, var(--yp-bg-sunken)); overflow: hidden; }
.connect-boards-illustration canvas { position: absolute; inset: 6px 8px; width: calc(100% - 16px); height: calc(100% - 12px); }
</style>
