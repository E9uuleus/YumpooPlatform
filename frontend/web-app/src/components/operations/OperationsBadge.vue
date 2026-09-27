<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { operationsApi } from '../../api/client'
import { useSession } from '../../composables/useSession'
import { useOperationsQuery } from '../../composables/useOperationsQuery'
const session = useSession()
const query = useOperationsQuery((signal) => operationsApi.getOperationsAlertSummary({ signal }), {
  interval: () => 60000,
  enabled: () => session.isPlatformAdmin.value && session.phase.value === 'authenticated',
})
const count = computed(() => (query.data.value?.critical ?? 0) + (query.data.value?.warning ?? 0))
const emit = defineEmits<{ count: [value: number] }>()
watch(count, (value) => emit('count', value), { immediate: true })
function changed() {
  void query.refresh()
}
onMounted(() => window.addEventListener('yumpoo-operations-alerts-changed', changed))
onBeforeUnmount(() => window.removeEventListener('yumpoo-operations-alerts-changed', changed))
</script>
<template>
  <span
    v-if="count"
    class="operations-badge"
    :class="{ critical: query.data.value?.critical }"
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
