<script setup lang="ts">
import { computed } from 'vue'
import { formatDuration } from '../../composables/useTimeTracker'
const props = defineProps<{ duration: number }>()
const value = computed(() => formatDuration(props.duration))
</script>

<template>
  <output
    class="timer-digits"
    aria-label="本次计时时长"
    aria-live="off"
    :aria-valuetext="value"
  >
    <span class="digit-accessible">{{ value }}</span>
    <span
      v-for="(digit, index) in value"
      :key="index"
      class="digit-cell"
      aria-hidden="true"
      :class="{ 'is-colon': digit === ':' }"
    >
      <Transition name="digit"><span
        :key="digit"
        class="digit"
      >{{ digit }}</span></Transition>
    </span>
  </output>
</template>

<style scoped>
.timer-digits{display:inline-flex;align-items:center;font-variant-numeric:tabular-nums;white-space:nowrap;line-height:1.25;letter-spacing:-.035em}
.digit-accessible{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%);white-space:nowrap}.digit-cell{position:relative;display:inline-grid;width:.62em;overflow:hidden;text-align:center}.digit-cell.is-colon{width:.28em;opacity:.65}.digit{grid-area:1/1;display:block}
.digit-enter-active,.digit-leave-active{transition:transform .3s cubic-bezier(.2,.8,.2,1),opacity .3s}.digit-enter-from{transform:translateY(75%);opacity:0}.digit-leave-to{transform:translateY(-75%);opacity:0}
@media(prefers-reduced-motion:reduce){.digit-enter-active,.digit-leave-active{transition:none}}
</style>
