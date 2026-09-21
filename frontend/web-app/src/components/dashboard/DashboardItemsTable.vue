<script setup lang="ts">
import { computed } from 'vue'
import type { DashboardChartSelection, DashboardConnection, DashboardFilters, DashboardWidget } from '@yumpoo/api-client'
import ProjectWorkItems from '../projects/ProjectWorkItems.vue'
import type { WorkItemTableSource } from '../projects/workItemTableSource'
import { useSession } from '../../composables/useSession'
import { resolveChart } from './chartModel'
import { dashboardTableSource } from './dashboardTableSource'

const props = defineProps<{
  dashboardId: string
  widget: DashboardWidget
  filters: DashboardFilters
  selection?: DashboardChartSelection | undefined
  projects: DashboardConnection[]
  refreshKey: number
}>()
const emit = defineEmits<{ changed: [] }>()
const session = useSession()
const tables = new Map<string, InstanceType<typeof ProjectWorkItems>>()
const sources = new Map<string, WorkItemTableSource>()
const visibleProjects = computed(() => {
  const chart = resolveChart(props.widget)
  return props.projects.filter(project => project.available
    && (!props.filters.projectIds.length || props.filters.projectIds.includes(project.id))
    && (chart.projectIds == null || chart.projectIds.includes(project.id))
    && (!chart.filters?.projectIds.length || chart.filters.projectIds.includes(project.id)))
})
function source(projectId: string) {
  if (!sources.has(projectId)) sources.set(projectId, dashboardTableSource(() => props.dashboardId,
    () => ({ projectId, widget: props.widget, filters: props.filters, ...(props.selection ? { selection: props.selection } : {}) })))
  return sources.get(projectId)!
}
function preferenceScope(projectId: string) {
  const identity = session.authentication.value
  return `yumpoo:dashboard-table:v1:${identity?.company.id ?? 'unknown'}:${identity?.user.id ?? 'unknown'}:${props.dashboardId}:${props.widget.id}:${projectId}`
}
function setTable(id: string, instance: unknown) {
  if (instance) tables.set(id, instance as InstanceType<typeof ProjectWorkItems>)
  else tables.delete(id)
}
async function canClose() {
  for (const table of tables.values()) if (!await table.canClose()) return false
  return true
}
defineExpose({ canClose })
</script>

<template>
  <div class="dashboard-project-tables">
    <section v-for="project in visibleProjects" :key="project.id" class="dashboard-project-table" :aria-label="`${project.name}工作项`">
      <header class="dashboard-project-table-heading">
        <strong>{{ project.name }}</strong><span>{{ project.code }}</span>
      </header>
      <ProjectWorkItems
        :ref="instance => setTable(project.id, instance)"
        :embedded-project-id="project.id"
        :preference-scope="preferenceScope(project.id)"
        :source="source(project.id)"
        :refresh-key="refreshKey"
        @changed="emit('changed')"
      />
    </section>
    <p v-if="!visibleProjects.length" class="dashboard-project-table-empty">当前范围内没有可用项目</p>
  </div>
</template>

<style scoped>
.dashboard-project-tables { min-width: 0; min-height: 0; flex: 1; overflow-x: hidden; overflow-y: auto; overscroll-behavior: contain; }
.dashboard-project-table { display: flex; flex-direction: column; height: 100%; min-height: 0; padding-left: 32px; }
.dashboard-project-table + .dashboard-project-table { margin-top: 28px; padding-top: 24px; border-top: 1px solid var(--yp-border-default); }
.dashboard-project-table-heading { display: flex; flex: none; align-items: center; gap: 12px; margin-bottom: 14px; font-size: 16px; }
.dashboard-project-table-heading span, .dashboard-project-table-empty { color: var(--yp-text-secondary); font-size: 12px; }
</style>
