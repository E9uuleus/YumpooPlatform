<script setup lang="ts">
defineProps<{ name: 'clock' | 'search' | 'play' | 'pause' | 'pin' | 'close' | 'collapse' | 'external' | 'check' | 'loader' | 'arrow' | 'details' | 'resize' }>()
const paths = {
  clock: 'M9 2h6M12 6a8 8 0 1 0 0 16 8 8 0 0 0 0-16ZM12 10v4l3 2',
  search: 'm21 21-5-5M10.5 3a7.5 7.5 0 1 0 0 15 7.5 7.5 0 0 0 0-15Z',
  play: 'm8 5 11 7-11 7Z',
  pause: 'M8 5v14M16 5v14',
  pin: 'm8 3 8 0-1 7 4 4H5l4-4-1-7ZM12 14v7',
  close: 'm6 6 12 12M6 18 18 6',
  collapse: 'm6 9 6 6 6-6',
  external: 'M14 3h7v7M21 3 10 14M10 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-5',
  check: 'm5 12 4 4L19 6',
  loader: 'M12 3a9 9 0 1 1-9 9',
  arrow: 'M5 12h14m-6-6 6 6-6 6',
  details: 'm14 7-5 5 5 5',
  resize: 'M14 4h6v6M20 4 4 20M4 14v6h6',
}
</script>

<template>
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    :stroke-width="name === 'pause' ? 3 : 1.7"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  ><path :d="paths[name]" /></svg>
</template>

<style scoped>svg{width:18px;height:18px;flex-shrink:0}</style>
