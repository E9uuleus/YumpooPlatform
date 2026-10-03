<script setup lang="ts">
import { ref } from 'vue'
import { ElPopover } from 'element-plus'
import type { WorkItemConnection } from '@yumpoo/api-client'
import ConnectionChip from './ConnectionChip.vue'
import ConnectionList from './ConnectionList.vue'

defineProps<{ connections: WorkItemConnection[] }>()
const emit = defineEmits<{ openCard: [connection: WorkItemConnection] }>()
const expanded = ref(false)
function openCard(connection: WorkItemConnection) { expanded.value = false; emit('openCard', connection) }
</script>

<template>
  <div
    v-if="connections.length"
    class="kanban-connections"
    aria-label="出站连接"
    @dragstart.stop.prevent
  >
    <connection-chip
      v-for="connection in connections.slice(0, 3)"
      :key="connection.id"
      :card="connection.target"
      :show-project="true"
      :tooltip="`${connection.columnName} · ${connection.target.projectName} · ${connection.target.title}`"
      @click="openCard(connection)"
    />
    <el-popover
      v-if="connections.length > 3"
      v-model:visible="expanded"
      trigger="click"
      :width="380"
      :persistent="false"
    >
      <template #reference>
        <button
          type="button"
          class="kanban-connections__more"
          :aria-label="`查看全部 ${connections.length} 个连接`"
          @click.stop
        >
          +{{ connections.length - 3 }}
        </button>
      </template>
      <connection-list
        :connections="connections"
        perspective="source"
        read-only
        @open-card="openCard"
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
