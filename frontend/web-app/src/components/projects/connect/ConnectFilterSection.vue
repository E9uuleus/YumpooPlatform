<script setup lang="ts">
import { ElCheckbox } from 'element-plus'
import { computed } from 'vue'
import type { ConnectColumn, ProjectWorkItemFilterOption } from '@yumpoo/api-client'

const props = withDefaults(defineProps<{
  columns: ConnectColumn[]
  incoming: ProjectWorkItemFilterOption[]
  connectedColumnIds: Set<string>
  unconnectedColumnIds: Set<string>
  incomingProjectIds: Set<string>
  columnsState?: 'idle' | 'loading' | 'ready' | 'error'
  incomingState?: 'idle' | 'loading' | 'ready' | 'error'
}>(), { columnsState: 'ready', incomingState: 'ready' })
const emit = defineEmits<{
  change: [field: 'connectedColumnIds' | 'unconnectedColumnIds' | 'incomingProjectIds', id: string, checked: boolean]
}>()
const displayedColumns = computed(() => {
  const rows = props.columns.map(column => ({ id: column.id, name: column.name, available: true }))
  const known = new Set(rows.map(column => column.id))
  for (const id of new Set([...props.connectedColumnIds, ...props.unconnectedColumnIds])) {
    if (!known.has(id)) rows.push({ id, available: false,
      name: `${props.columnsState === 'ready' ? '不可用连接列' : '待确认连接列'}（${id}）` })
  }
  return rows
})
const displayedIncoming = computed(() => {
  const rows = props.incoming.map(project => ({ ...project, count: props.incomingState === 'ready' ? project.count : null }))
  const known = new Set(rows.map(project => project.value))
  for (const id of props.incomingProjectIds) {
    if (!known.has(id)) rows.push({ value: id,
      label: `${props.incomingState === 'ready' ? '未匹配来源项目' : '待确认来源项目'}（${id}）`,
      count: props.incomingState === 'ready' ? 0 : null })
  }
  return rows.sort((left, right) => left.label.localeCompare(right.label, 'zh-CN', { numeric: true })
    || left.value.localeCompare(right.value))
})
const pending = computed(() => props.columnsState !== 'ready' || props.incomingState !== 'ready')
function cannotAdd(selected: Set<string>, id: string, available = true) {
  return !selected.has(id) && (!available || selected.size >= 20)
}
</script>

<template>
  <section
    class="connect-filters"
    aria-label="连接筛选"
  >
    <h4>连接</h4>
    <p v-if="columnsState === 'error' || incomingState === 'error'" role="status">
      连接选项暂未加载成功，已选条件仍然生效，可单独取消。
    </p>
    <p v-else-if="pending" role="status">
      连接选项信息待确认，已选条件仍然生效。
    </p>
    <p v-else-if="!displayedColumns.length && !displayedIncoming.length">
      还没有可筛选的连接。
    </p>
    <div
      v-for="column in displayedColumns"
      :key="column.id"
      class="connect-filters__column"
    >
      <span>{{ column.name }}</span>
      <el-checkbox
        :aria-label="`${column.name} 已连接`"
        :model-value="connectedColumnIds.has(column.id)"
        :disabled="cannotAdd(connectedColumnIds, column.id, column.available)"
        @change="emit('change', 'connectedColumnIds', column.id, $event === true)"
      >已连接</el-checkbox>
      <el-checkbox
        :aria-label="`${column.name} 未连接`"
        :model-value="unconnectedColumnIds.has(column.id)"
        :disabled="cannotAdd(unconnectedColumnIds, column.id, column.available)"
        @change="emit('change', 'unconnectedColumnIds', column.id, $event === true)"
      >未连接</el-checkbox>
    </div>
    <div
      v-if="displayedIncoming.length"
      class="connect-filters__incoming"
    >
      <span>双向连接来源 <small>最多选择 20 项</small></span>
      <el-checkbox
        v-for="project in displayedIncoming"
        :key="project.value"
        :aria-label="`来自 ${project.label}`"
        :model-value="incomingProjectIds.has(project.value)"
        :disabled="cannotAdd(incomingProjectIds, project.value)"
        @change="emit('change', 'incomingProjectIds', project.value, $event === true)"
      >
        <span>{{ project.label }}</span><small>{{ project.count ?? '—' }}</small>
      </el-checkbox>
    </div>
  </section>
</template>

<style scoped>
.connect-filters { display: grid; gap: var(--yp-space-3); padding: var(--yp-space-3) 0; border-top: 1px solid var(--yp-border-subtle); }
.connect-filters h4, .connect-filters p { margin: 0; }
.connect-filters p, .connect-filters small { color: var(--yp-text-muted); }
.connect-filters__column { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; gap: var(--yp-space-3); align-items: center; }
.connect-filters__column > span { overflow-wrap: anywhere; }
.connect-filters .el-checkbox { margin-right: 0; }
.connect-filters__incoming { display: grid; gap: var(--yp-space-2); }
.connect-filters__incoming :deep(.el-checkbox__label) { display: flex; flex: 1; gap: var(--yp-space-2); }
.connect-filters__incoming :deep(.el-checkbox) { min-height: 32px; height: auto; }
.connect-filters__incoming :deep(.el-checkbox__label) > span { white-space: normal; overflow-wrap: anywhere; }
.connect-filters__incoming small { margin-left: auto; }
</style>
