<script setup lang="ts">
defineProps<{ name: 'tour' | 'help' | 'bell' | 'settings' | 'appearance' }>()
const paths = {
  tour: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM15.6 8.4l-2 5.2-5.2 2 2-5.2 5.2-2Z',
  help: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM9.4 9.3a2.7 2.7 0 1 1 3.7 2.5c-.7.3-1.1.9-1.1 1.6v.4M12 16.8h.01',
  bell: 'M6 9.5a6 6 0 0 1 12 0c0 4.2 1.4 6.2 2.4 7H3.6c1-.8 2.4-2.8 2.4-7ZM10 19.5a2.2 2.2 0 0 0 4 0',
  appearance: 'M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z',
  settings: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z',
}
</script>

<template>
  <svg
    class="shell-icon"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.6"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  ><path :d="paths[name]" /></svg>
</template>

<style scoped>.shell-icon{width:18px;height:18px;flex-shrink:0}</style>
