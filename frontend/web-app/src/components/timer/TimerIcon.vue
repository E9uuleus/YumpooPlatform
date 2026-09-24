<script setup lang="ts">
defineProps<{ name: 'clock' | 'search' | 'play' | 'pause' | 'pin' | 'close' | 'collapse' | 'external' | 'check' | 'loader' | 'arrow' | 'details' | 'resize' | 'settings' | 'window' | 'power' | 'swap' | 'back' | 'orb' | 'dock' | 'hidden' }>()
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
  settings: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z',
  window: 'M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2ZM3 9h18M7 6.5h.01M10 6.5h.01',
  power: 'M12 3v9M18.36 6.64a9 9 0 1 1-12.73 0',
  swap: 'm16 3 4 4-4 4M20 7H4M8 21l-4-4 4-4M4 17h16',
  back: 'm15 18-6-6 6-6',
  orb: 'M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16ZM12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7Z',
  dock: 'M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1ZM17 9v6',
  hidden: 'm3 3 18 18M10.6 10.6a2 2 0 0 0 2.8 2.8M9.9 5.1A9.9 9.9 0 0 1 12 5c5 0 9 4.5 10 7a12.6 12.6 0 0 1-3 4.1M6.6 6.6A12.6 12.6 0 0 0 2 12c1 2.5 5 7 10 7a9.7 9.7 0 0 0 5.4-1.6',
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
