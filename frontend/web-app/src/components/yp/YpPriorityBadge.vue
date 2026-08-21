<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ priority: string }>()
const presentation = computed(() => ({
  URGENT: { label: '紧急', tone: 'red' },
  HIGH: { label: '高', tone: 'orange' },
  MEDIUM: { label: '中', tone: 'yellow' },
  LOW: { label: '低', tone: 'gray' },
}[props.priority.toUpperCase()] ?? { label: `未知（${props.priority}）`, tone: 'gray' }))
</script>

<template>
  <span
    class="yp-priority"
    :class="`yp-priority--${presentation.tone}`"
  >
    <span aria-hidden="true">◆</span>
    {{ presentation.label }}
  </span>
</template>

<style scoped>
.yp-priority {
  --yp-priority-color: var(--yp-status-gray);
  display: inline-flex;
  align-items: center;
  gap: var(--yp-space-1);
  color: var(--yp-text-primary);
  font-size: var(--yp-type-caption-size);
  font-weight: 600;
}

.yp-priority span {
  color: var(--yp-priority-color);
}

.yp-priority--red { --yp-priority-color: var(--yp-status-red); }
.yp-priority--orange { --yp-priority-color: var(--yp-status-orange); }
.yp-priority--yellow { --yp-priority-color: var(--yp-status-yellow); }
.yp-priority--gray { --yp-priority-color: var(--yp-status-gray); }
</style>
