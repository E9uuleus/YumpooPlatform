<script setup lang="ts">
import { pageProgress } from './page-progress'
const { visible, value } = pageProgress
</script>

<template>
  <Teleport to="body">
    <div
      v-if="visible"
      class="yp-page-progress"
      :class="{ 'is-finishing': value === 1 }"
      role="progressbar"
      aria-label="正在加载页面"
    >
      <div
        class="yp-page-progress__bar"
        :style="{ transform: `scaleX(${value})` }"
      />
    </div>
  </Teleport>
</template>

<style>
.yp-page-progress { position: fixed; inset: 0 0 auto; height: 3px; z-index: 9998; pointer-events: none; }
.yp-page-progress.is-finishing { opacity: 0; transition: opacity 180ms ease-out; }
.yp-page-progress__bar { width: 100%; height: 100%; background: var(--yp-action-primary); transform-origin: left; transition: transform 180ms ease-out; }
@media (prefers-reduced-motion: reduce) {
  .yp-page-progress__bar { transform: scaleX(.35) !important; transition: none; }
}
</style>
