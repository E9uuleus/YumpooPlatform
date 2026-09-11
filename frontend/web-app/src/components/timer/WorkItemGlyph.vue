<script setup lang="ts">
import { computed } from 'vue'
import { workItemLabelColorValue } from '../projects/workItemLabelColors'

const props = defineProps<{ code?: string | undefined; name: string; colorToken?: string | undefined }>()
const kind = computed(() => {
  const code = props.code?.toUpperCase()
  if (code === 'TASKS' || code === 'TASK') return 'task'
  if (code === 'DEFECTS' || code === 'DEFECT' || code === 'BUG') return 'defect'
  if (code === 'REQUIREMENTS' || code === 'REQUIREMENT') return 'requirement'
  return 'custom'
})
const paths = {
  task: 'M8 4H6a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2h-2M9 2h6v5H9ZM8 13l3 3 5-6',
  defect: 'M8 10a4 4 0 0 1 8 0v6a4 4 0 0 1-8 0ZM9 4l2 2m4-2-2 2M4 9l4 2m8 0 4-2M3 15h5m8 0h5M5 21l3-3m8 0 3 3M12 11v8',
  requirement: 'm12 2 9 5-9 5-9-5ZM3 12l9 5 9-5M3 17l9 5 9-5',
  custom: 'M9 3h6l6 9-6 9H9l-6-9ZM9 12h6M12 9v6',
}
</script>

<template>
  <span
    class="work-glyph"
    :style="{ '--category-color': workItemLabelColorValue(colorToken) }"
    role="img"
    :aria-label="name"
    :data-kind="kind"
  >
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.65"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    ><path :d="paths[kind]" /></svg>
  </span>
</template>

<style scoped>
.work-glyph{display:grid;place-items:center;width:36px;height:40px;flex-shrink:0;color:var(--category-color);background:radial-gradient(ellipse,color-mix(in srgb,var(--category-color) 12%,transparent),transparent 72%)}
svg{width:23px;height:23px;filter:drop-shadow(0 2px 3px color-mix(in srgb,var(--category-color) 16%,transparent))}
</style>
