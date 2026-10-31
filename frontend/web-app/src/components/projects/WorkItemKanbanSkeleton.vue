<script setup lang="ts">
import './workItemSkeleton.css'

withDefaults(defineProps<{ cards?: number; label?: string }>(), { cards: 3, label: '正在加载工作项' })

const TITLE_WIDTHS = ['82%', '64%', '74%']
</script>

<template>
  <div
    class="kanban-skeleton"
    role="status"
    :aria-label="label"
  >
    <div
      v-for="card in cards"
      :key="card"
      class="kanban-skeleton__card work-item-skeleton"
      :style="{ animationDelay: `${(card - 1) * 60}ms` }"
      aria-hidden="true"
    >
      <i class="work-item-skeleton-block kanban-skeleton__meta" />
      <i
        class="work-item-skeleton-block kanban-skeleton__title"
        :style="{ width: TITLE_WIDTHS[(card - 1) % TITLE_WIDTHS.length] }"
      />
      <i class="work-item-skeleton-block kanban-skeleton__badge" />
    </div>
  </div>
</template>

<style scoped>
.kanban-skeleton__card {
  display: flex;
  flex-direction: column;
  gap: var(--yp-space-3);
  margin-top: var(--yp-space-3);
  padding: var(--yp-space-4);
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-surface);
  animation: kanban-skeleton-in 240ms var(--yp-ease-standard) backwards;
}

.kanban-skeleton__meta { width: 40%; height: 8px; }
.kanban-skeleton__title { height: 12px; }
.kanban-skeleton__badge { width: 48px; height: 18px; border-radius: var(--yp-radius-sm); }

@keyframes kanban-skeleton-in {
  from { opacity: 0; transform: translateY(4px); }
}

@media (prefers-reduced-motion: reduce) {
  .kanban-skeleton__card { animation: none; }
}
</style>
