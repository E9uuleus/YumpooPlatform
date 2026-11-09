<script setup lang="ts">
export type WorkItemActionIconName = 'open' | 'link' | 'duplicate' | 'top' | 'bottom' | 'add' | 'subitem' | 'convert' | 'archive' | 'delete' | 'move' | 'more' | 'chevron'
  | 'settings' | 'filter' | 'sort' | 'collapse' | 'group' | 'expand' | 'status' | 'calendar' | 'priority' | 'category'
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
  settings: 'M10 7.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5ZM10 2.5v2M10 15.5v2M2.5 10h2M15.5 10h2M4.7 4.7l1.4 1.4M13.9 13.9l1.4 1.4M4.7 15.3l1.4-1.4M13.9 6.1l1.4-1.4',
  filter: 'M3 4h14l-5.5 6.5V16l-3 1.5v-7L3 4Z',
  sort: 'M6 3v14M3 14l3 3 3-3M14 17V3M11 6l3-3 3 3',
  collapse: 'M3 6l4 4-4 4M17 6l-4 4 4 4',
  group: 'M3 4h14v12H3zM6 7h3v6H6z',
  expand: 'M3 10h14M6 7l-3 3 3 3M14 7l3 3-3 3',
  status: 'M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14ZM7 10.2l2.1 2.1 4-4.3',
  calendar: 'M4.5 4.5h11A1.5 1.5 0 0 1 17 6v9.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 3 15.5V6a1.5 1.5 0 0 1 1.5-1.5ZM3 8.5h14M7 2.5v4M13 2.5v4',
  priority: 'M5 17.5V3M5 3.8c3-1.6 5.2 1.4 10 0v7.4c-4.8 1.4-7-1.6-10 0',
  category: 'M3.5 3.5h5v5h-5zM11.5 3.5h5v5h-5zM3.5 11.5h5v5h-5zM11.5 11.5h5v5h-5z',
}
</script>

<template>
  <svg class="work-item-action-icon" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name]" /></svg>
</template>

<style scoped>
.work-item-action-icon { width: 18px; height: 18px; flex: 0 0 18px; }
</style>
