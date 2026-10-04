<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElPopover } from 'element-plus'
import type { WorkItemConnection, WorkItemConnectionCell } from '@yumpoo/api-client'
import ConnectionChip from './ConnectionChip.vue'
import ConnectionList from './ConnectionList.vue'

type Side = 'source' | 'target'
const props = defineProps<{ cell?: WorkItemConnectionCell | undefined }>()
const emit = defineEmits<{ openCard: [connection: WorkItemConnection, side: Side] }>()
const expanded = ref(false)
const outgoing = computed(() => props.cell?.outgoing.flatMap(column => column.connections) ?? [])
const reverse = computed(() => props.cell?.incomingByColumn.flatMap(column => column.connections) ?? [])
const entries = computed(() => [...outgoing.value.map(connection => ({ connection, side: 'source' as Side, card: connection.target })),
  ...reverse.value.map(connection => ({ connection, side: 'target' as Side, card: connection.source }))])
function openCard(connection: WorkItemConnection, side: Side) { expanded.value = false; emit('openCard', connection, side) }
</script>

<template>
  <div
    v-if="entries.length"
    class="kanban-connections"
    aria-label="连接"
    @dragstart.stop.prevent
  >
    <connection-chip
      v-for="entry in entries.slice(0, 3)"
      :key="entry.connection.id"
      :card="entry.card"
      :show-project="true"
      :tooltip="`${entry.side === 'source' ? entry.connection.columnName : `双向连接 · ${entry.connection.columnName}`} · ${entry.card.projectName} · ${entry.card.title}`"
      @click="openCard(entry.connection, entry.side)"
    />
    <el-popover
      v-if="entries.length > 3"
      v-model:visible="expanded"
      trigger="click"
      :width="380"
      :persistent="false"
    >
      <template #reference>
        <button
          type="button"
          class="kanban-connections__more"
          :aria-label="`查看全部 ${entries.length} 个连接`"
          @click.stop
        >
          +{{ entries.length - 3 }}
        </button>
      </template>
      <connection-list
        v-if="outgoing.length"
        :connections="outgoing"
        perspective="source"
        read-only
        @open-card="openCard($event, 'source')"
      />
      <connection-list
        v-if="reverse.length"
        :connections="reverse"
        perspective="target"
        read-only
        @open-card="openCard($event, 'target')"
      />
    </el-popover>
  </div>
</template>

<style scoped>
.kanban-connections { display: flex; flex-wrap: wrap; gap: var(--yp-space-1); min-width: 0; }
.kanban-connections__more { height: 22px; padding: 0 var(--yp-space-2); border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-pill); background: var(--yp-bg-sunken); color: var(--yp-text-secondary); font: inherit; font-size: 12px; cursor: pointer; }
.kanban-connections__more:hover { background: var(--yp-bg-hover); }
.kanban-connections__more:focus-visible { outline: 2px solid var(--yp-action-primary); outline-offset: 1px; }
</style>
