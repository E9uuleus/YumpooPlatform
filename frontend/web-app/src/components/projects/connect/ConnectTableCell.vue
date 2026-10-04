<script setup lang="ts">
import { computed } from 'vue'
import type { WorkItemConnection, WorkItemConnectionCell } from '@yumpoo/api-client'
import '../workItemAccentBar.css'
import type { ConnectSourceItem, ConnectTableColumn } from './connectColumnKeys'
import ConnectCell from './ConnectCell.vue'

const props = defineProps<{ column: ConnectTableColumn; item: ConnectSourceItem; cell?: WorkItemConnectionCell | undefined; readOnly: boolean
  /** Position used to stagger the entrance of new columns; capped so long tables do not wait. */
  rowIndex?: number | undefined; celebrate?: boolean | undefined }>()
const emit = defineEmits<{ openCard: [connection: WorkItemConnection, side: 'source' | 'target'] }>()
const outgoing = computed(() => props.cell?.outgoing.find(value => value.columnId === props.column.column?.id)?.connections ?? [])
const reverse = computed(() => props.cell?.incomingByColumn.find(value => value.columnId === props.column.reverse?.columnId))
const stagger = computed(() => ({ '--connect-row-index': Math.min(props.rowIndex ?? 0, 12) }))
</script>

<template>
  <div
    class="connect-table-cell"
    :class="{ 'connect-table-cell--celebrate': celebrate, 'connect-table-cell--draft': column.kind === 'draft' }"
    :style="stagger"
  >
    <connect-cell
      v-if="column.kind === 'connect' && column.column"
      :item="item"
      :column="column.column"
      :connections="outgoing"
      :read-only="readOnly"
      @open-card="emit('openCard', $event, 'source')"
    />
    <connect-cell
      v-else-if="column.kind === 'reverse' && column.reverse"
      :item="item"
      :reverse="column.reverse"
      :connections="reverse?.connections ?? []"
      :total="reverse?.total ?? 0"
      :read-only="readOnly"
      @open-card="emit('openCard', $event, 'target')"
    />
    <div
      v-else-if="column.kind === 'draft'"
      class="connect-table-cell__placeholder work-item-accent-bar"
      aria-hidden="true"
    >
      –
    </div>
  </div>
</template>

<style scoped>
.connect-table-cell { width: 100%; min-width: 0; }
.connect-table-cell__placeholder { display: flex; height: var(--work-item-quick-control-height, 26px); align-items: center; justify-content: center; margin: 4px 2px; background: var(--yp-bg-sunken); color: var(--yp-text-muted); }
.connect-table-cell--draft { animation: connect-cell-in var(--yp-motion-overlay) var(--yp-ease-standard) both; animation-delay: calc(var(--connect-row-index, 0) * 18ms); }
.connect-table-cell--celebrate :deep(.connect-cell__box)::before { transform-origin: top; animation: connect-bar-fill 520ms var(--yp-ease-standard) both; animation-delay: calc(var(--connect-row-index, 0) * 24ms); }
@keyframes connect-cell-in { from { opacity: 0; transform: translateY(-3px); } }
@keyframes connect-bar-fill { from { transform: scaleY(0); } }
@media (prefers-reduced-motion: reduce) {
  .connect-table-cell--draft, .connect-table-cell--celebrate :deep(.connect-cell__box)::before { animation: none; }
}
</style>
