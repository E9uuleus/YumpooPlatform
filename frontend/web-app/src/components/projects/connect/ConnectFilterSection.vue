<script setup lang="ts">
import { ElCheckbox } from 'element-plus'
import type { ConnectColumn, ProjectWorkItemFilterOption } from '@yumpoo/api-client'

defineProps<{
  columns: ConnectColumn[]
  incoming: ProjectWorkItemFilterOption[]
  connectedColumnIds: Set<string>
  unconnectedColumnIds: Set<string>
  incomingProjectIds: Set<string>
}>()
const emit = defineEmits<{
  change: [field: 'connectedColumnIds' | 'unconnectedColumnIds' | 'incomingProjectIds', id: string, checked: boolean]
}>()
</script>

<template>
  <section
    class="connect-filters"
    aria-label="连接筛选"
  >
    <h4>连接</h4>
    <p v-if="!columns.length && !incoming.length">
      还没有可筛选的连接。
    </p>
    <div
      v-for="column in columns"
      :key="column.id"
      class="connect-filters__column"
    >
      <span>{{ column.name }}</span>
      <el-checkbox
        :aria-label="`${column.name} 已连接`"
        :model-value="connectedColumnIds.has(column.id)"
        @change="emit('change', 'connectedColumnIds', column.id, $event === true)"
      >已连接</el-checkbox>
      <el-checkbox
        :aria-label="`${column.name} 未连接`"
        :model-value="unconnectedColumnIds.has(column.id)"
        @change="emit('change', 'unconnectedColumnIds', column.id, $event === true)"
      >未连接</el-checkbox>
    </div>
    <div
      v-if="incoming.length"
      class="connect-filters__incoming"
    >
      <span>被连接来源</span>
      <el-checkbox
        v-for="project in incoming"
        :key="project.value"
        :aria-label="`来自 ${project.label}`"
        :model-value="incomingProjectIds.has(project.value)"
        @change="emit('change', 'incomingProjectIds', project.value, $event === true)"
      >
        <span>{{ project.label }}</span><small>{{ project.count }}</small>
      </el-checkbox>
    </div>
  </section>
</template>

<style scoped>
.connect-filters { display: grid; gap: var(--yp-space-3); padding: var(--yp-space-3) 0; border-top: 1px solid var(--yp-border-subtle); }
.connect-filters h4, .connect-filters p { margin: 0; }
.connect-filters p, .connect-filters small { color: var(--yp-text-muted); }
.connect-filters__column { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; gap: var(--yp-space-3); align-items: center; }
.connect-filters .el-checkbox { margin-right: 0; }
.connect-filters__incoming { display: grid; gap: var(--yp-space-2); }
.connect-filters__incoming :deep(.el-checkbox__label) { display: flex; flex: 1; gap: var(--yp-space-2); }
.connect-filters__incoming small { margin-left: auto; }
</style>
