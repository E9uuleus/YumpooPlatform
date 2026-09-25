<script setup lang="ts">
export type WorkItemActionIconName = 'open' | 'link' | 'duplicate' | 'top' | 'bottom' | 'add' | 'subitem' | 'convert' | 'archive' | 'delete' | 'move' | 'more' | 'chevron'
defineProps<{ name: WorkItemActionIconName }>()
const paths: Record<WorkItemActionIconName, string> = {
  open: 'M5 15 15 5M5 5h10v10',
  link: 'm8 7 2-2a4 4 0 0 1 6 6l-2 2M12 13l-2 2a4 4 0 0 1-6-6l2-2M7 13l6-6',
  duplicate: 'M7 7h10v10H7zM4 13H3V3h10v1',
  top: 'M4 3h12M10 17V6m-5 5 5-5 5 5',
  bottom: 'M4 17h12M10 3v11m-5-5 5 5 5-5',
  add: 'M10 3v14M3 10h14',
  subitem: 'M3 3h7v5H3zM10 12h7v5h-7zM6 8v6h4',
  convert: 'M4 3v10h12m-4-4 4 4-4 4',
  archive: 'M3 3h14v4H3zM4 7v10h12V7M8 10h4',
  delete: 'M3 5h14M7 5V3h6v2M5 5l1 12h8l1-12M8 8v6m4-6v6',
  move: 'M10 2v16M2 10h16M7 5l3-3 3 3M7 15l3 3 3-3M5 7l-3 3 3 3M15 7l3 3-3 3',
  more: 'M3 10h1M9.5 10h1M16 10h1',
  chevron: 'm7 4 6 6-6 6',
}
</script>

<template>
  <svg class="work-item-action-icon" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name]" /></svg>
</template>

<style scoped>
.work-item-action-icon { width: 18px; height: 18px; flex: 0 0 18px; }
</style>
