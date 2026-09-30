<script setup lang="ts">
import OperationsChart from './OperationsChart.vue'

defineProps<{
  label: string
  value: string
  unit?: string | undefined
  detail?: string | undefined
  tone: 'green' | 'yellow' | 'red'
  times: Date[]
  series: { key: string; unit: string; values: (number | null)[] }[]
}>()
</script>

<template>
  <article
    class="ops-tile"
    :class="`ops-tone-${tone}`"
  >
    <header class="ops-tile__label">
      <span>{{ label }}</span>
      <span
        v-if="tone !== 'green'"
        class="ops-dot"
        :aria-label="tone === 'red' ? '超过严重阈值' : '超过警告阈值'"
      />
    </header>
    <p class="ops-tile__value">
      {{ value }}<small v-if="unit">{{ unit }}</small>
    </p>
    <p class="ops-tile__detail">
      {{ detail ?? '最近 1 小时' }}
    </p>
    <operations-chart
      :title="label + '近一小时'"
      :times="times"
      :series="series"
      compact
    />
  </article>
</template>

<style scoped>
.ops-tile {
  display: grid;
  gap: 2px;
  min-width: 0;
  padding: var(--yp-space-4) var(--yp-space-4) var(--yp-space-3);
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-surface);
}

.ops-tile__label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-caption-size);
}

.ops-tile__value {
  margin: 0;
  overflow: hidden;
  font: 500 24px / 32px var(--yp-font-heading);
  font-variant-numeric: tabular-nums;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ops-tile__value small {
  margin-left: 3px;
  color: var(--yp-text-muted);
  font: 500 var(--yp-type-caption-size) var(--yp-font-family);
}

.ops-tone-yellow .ops-tile__value {
  color: color-mix(in srgb, var(--yp-status-yellow) 72%, var(--yp-text-primary));
}

.ops-tone-red .ops-tile__value {
  color: var(--yp-status-red);
}

.ops-tile__detail {
  margin: 0 0 var(--yp-space-1);
  overflow: hidden;
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
