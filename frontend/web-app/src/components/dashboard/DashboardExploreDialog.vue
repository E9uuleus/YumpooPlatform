<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElIcon, ElAlert, ElButton, ElDialog, ElDropdown, ElDropdownItem, ElDropdownMenu, ElTag, ElTooltip } from 'element-plus'
import { Close, DataAnalysis, Grid, Refresh, Setting } from '@element-plus/icons-vue'
import { readCsrfToken, type DashboardChartResult, type DashboardChartSelection, type DashboardConnection, type DashboardFilters, type DashboardItemPage, type DashboardWidget } from '@yumpoo/api-client'
import { dashboardsApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'
import { clone } from './dashboardModel'
import { chartGroups, resolveChart } from './chartModel'
import DashboardChart from './DashboardChart.vue'
import DashboardItemsTable from './DashboardItemsTable.vue'


const props = defineProps<{ dashboardId: string; widget: DashboardWidget; result?: DashboardChartResult | undefined; filters: DashboardFilters; projects: DashboardConnection[]; initialSelection?: DashboardChartSelection | undefined; loading?: boolean; hidden?: boolean }>()
const emit = defineEmits<{ close: []; settings: []; refresh: [] }>()
const selection = ref(props.initialSelection), page = ref<DashboardItemPage>(), loadingItems = ref(false), error = ref(''), mode = ref('split')
const table = ref<InstanceType<typeof DashboardItemsTable>>()
const refreshKey = ref(0)
let refreshTimer: ReturnType<typeof setTimeout> | undefined
const config = computed(() => resolveChart(props.widget))
const selectionLabel = computed(() => chartGroups(config.value, props.result).find(g => g.key === selection.value?.key)?.name || '已选数据')
let sequence = 0
const opener = document.activeElement instanceof HTMLElement ? document.activeElement : undefined
async function load() {
  const token = ++sequence; loadingItems.value = true; error.value = ''; refreshKey.value++
  const filters = clone(props.filters), widget = clone(props.widget)
  for (const f of [filters, widget.chart?.filters]) { if (f?.dueFrom) f.dueFrom = new Date(f.dueFrom); if (f?.dueTo) f.dueTo = new Date(f.dueTo) }
  try {
    const result = await dashboardsApi.queryDashboardItems({ id: props.dashboardId, xXSRFTOKEN: readCsrfToken() || '', dashboardItemsQuery: { filters, widget, offset: 0, limit: 1, ...(selection.value ? { selection: selection.value } : {}) } })
    if (token !== sequence) return
    page.value = result
  } catch (reason) { const p = await toApiProblem(reason); if (token === sequence) error.value = problemMessage(p) }
  finally { if (token === sequence) loadingItems.value = false }
}
async function select(value?: DashboardChartSelection) {
  if (table.value && !await table.value.canClose()) return
  selection.value = selection.value?.key === value?.key && selection.value?.seriesKey === value?.seriesKey ? undefined : value
  void load()
}
async function close(done?: () => void) { if (!table.value || await table.value.canClose()) { done?.(); emit('close'); opener?.focus({ preventScroll: true }) } }
async function settings() { if (!table.value || await table.value.canClose()) emit('settings') }
async function refresh() { if (table.value && !await table.value.canClose()) return; emit('refresh'); void load() }
function changed() {
  if (refreshTimer) clearTimeout(refreshTimer)
  refreshTimer = setTimeout(() => { emit('refresh'); void load() }, 80)
}

watch(() => [props.dashboardId, props.widget, props.filters], () => { void load() }, { immediate: true, deep: true })
watch(() => props.hidden, hidden => { if (!hidden) void load() })
onBeforeUnmount(() => { ++sequence; if (refreshTimer) clearTimeout(refreshTimer) })
</script>
<template>
  <el-dialog
    :model-value="!hidden"
    append-to-body
    class="dashboard-explore"
    :show-close="false"
    :close-on-click-modal="false"
    :before-close="close"
    :destroy-on-close="false"
    aria-label="组件展开视图"
  >
    <template #header>
      <div class="dashboard-explore-heading">
        <h2>{{ widget.title }}</h2><div class="dashboard-explore-actions">
          <el-dropdown
            trigger="click"
            @command="mode = $event"
          >
            <el-button
              :icon="mode === 'split' ? Grid : DataAnalysis"
              text
              aria-label="显示模式"
            >
              {{ mode === 'split' ? '图表与工作项' : '仅图表' }}
            </el-button><template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="split">
                  <el-icon><Grid /></el-icon>图表与工作项
                </el-dropdown-item><el-dropdown-item command="chart">
                  <el-icon><DataAnalysis /></el-icon>仅图表
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-tooltip content="组件设置">
            <el-button
              :icon="Setting"
              text
              aria-label="组件设置"
              @click="settings"
            />
          </el-tooltip>
          <el-tooltip content="刷新">
            <el-button
              :icon="Refresh"
              text
              aria-label="刷新组件"
              :loading="loading || loadingItems"
              @click="refresh"
            />
          </el-tooltip>
          <el-button
            :icon="Close"
            text
            aria-label="关闭展开视图"
            @click="close()"
          />
        </div>
      </div>
    </template>
    <div
      class="dashboard-explore-body"
      :class="{ 'chart-only': mode === 'chart' }"
    >
      <div class="dashboard-explore-chart">
        <DashboardChart
          :widget="widget"
          :result="result"
          :loading="loading"
          :selection="selection"
          @select="select"
        />
      </div>
      <section
        v-show="mode === 'split'"
        class="dashboard-explore-items"
        aria-label="匹配的工作项"
      >
        <div class="dashboard-explore-table-heading">
          <strong>工作项 <span>{{ page?.totalElements || 0 }}</span></strong><el-tag
            v-if="selection"
            closable
            effect="plain"
            @close="select()"
          >
            {{ selectionLabel }}
          </el-tag><span v-else>该组件全部匹配事项</span><span
            v-if="loadingItems"
            role="status"
          >正在加载…</span>
        </div>
        <el-alert
          v-if="error"
          :title="error"
          type="error"
          :closable="false"
        >
          <el-button
            text
            @click="load"
          >
            重试
          </el-button>
        </el-alert>
        <DashboardItemsTable
          ref="table"
          :dashboard-id="dashboardId"
          :widget="widget"
          :filters="filters"
          :selection="selection"
          :refresh-key="refreshKey"
          :projects="projects"
          @changed="changed"
        />
      </section>
    </div>
  </el-dialog>
</template>
<style>
.el-dialog.dashboard-explore {
  width: calc(100vw - 64px);
  max-width: 1600px;
  height: calc(100vh - 64px);
  margin: 32px auto;
  border-radius: 14px;
  padding: 24px 32px;
  display: flex;
  flex-direction: column;
  background: var(--yp-bg-surface);
}
.dashboard-explore .el-dialog__header { padding: 0 0 16px; margin: 0; }
.dashboard-explore .el-dialog__body {
  flex: 1;
  min-height: 0;
  margin-left: -32px;
  padding: 0 0 0 32px;
  overflow: hidden;
}
.dashboard-explore-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.dashboard-explore-heading h2 { font-size: 22px; font-weight: 600; margin: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dashboard-explore-actions { display: flex; align-items: center; flex-shrink: 0; gap: 4px; }
.dashboard-explore-actions .el-button + .el-button { margin: 0; }
.dashboard-explore-body { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.dashboard-explore-chart { flex: 0 0 40%; min-height: 0; padding: 8px 16px 20px; }
.dashboard-explore-body.chart-only .dashboard-explore-chart { flex: 1; box-sizing: border-box; }
.dashboard-explore-items {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  border-top: 1px solid var(--yp-border-color, var(--el-border-color));
  padding-top: 18px;
  margin-left: -32px;
}
.dashboard-explore-table-heading {
  display: flex;
  flex: none;
  align-items: center;
  gap: 16px;
  margin-bottom: 14px;
  padding-left: 32px;
  color: var(--yp-text-secondary);
  font-size: 13px;
}
.dashboard-explore-table-heading strong { font-size: 16px; color: var(--yp-text-primary); }
.dashboard-explore-table-heading strong span { font-weight: 400; padding-left: 6px; color: var(--yp-text-secondary); }
@media (max-width: 720px) {
  .el-dialog.dashboard-explore { width: 100vw; height: 100dvh; margin: 0; border-radius: 0; padding: 16px 32px; }
  .dashboard-explore-heading { flex-wrap: wrap; gap: 8px; }
  .dashboard-explore-heading h2 { font-size: 18px; }
  .dashboard-explore-actions { margin-left: auto; }
  .dashboard-explore-chart { padding: 8px 0 16px; }
}
</style>
