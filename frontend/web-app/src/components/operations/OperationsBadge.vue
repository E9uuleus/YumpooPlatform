<script setup lang="ts">
import { computed, inject } from 'vue'
import { operationsAlertSummaryContext } from './operationsContext'
const summary = inject(operationsAlertSummaryContext)!
const count = computed(() => (summary.value?.critical ?? 0) + (summary.value?.warning ?? 0))
</script>
<template>
  <span
    v-if="count"
    class="operations-badge"
    :class="{ critical: summary?.critical }"
    :aria-label="`${count} 条未确认告警`"
  >{{ count > 99 ? '99+' : count }}</span>
</template>
<style scoped>
.operations-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 16px;
  padding: 0 4px;
  border-radius: var(--yp-radius-pill);
  font-size: 10px;
  line-height: 16px;
  background: var(--yp-status-yellow);
  color: var(--yp-status-yellow-foreground);
}
.critical {
  background: var(--yp-status-red);
  color: var(--yp-status-red-foreground);
}
</style>
