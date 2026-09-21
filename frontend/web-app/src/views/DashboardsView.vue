<script setup lang="ts">
import { ElIcon, ElPopover, ElInput, ElButton, ElTooltip, ElDropdown, ElDropdownMenu, ElDropdownItem, ElTag, ElAlert, ElSkeleton, ElDialog, ElDrawer, ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, ref, watch } from 'vue'
import { ArrowDown, Check, Close, Connection, DataAnalysis, Filter, MoreFilled, Plus, Refresh, FullScreen, Setting, List, CopyDocument, Delete, Edit, RefreshLeft, Search, User } from '@element-plus/icons-vue'
import { type DashboardFilters, type DashboardWidget, type DashboardChartSelection } from '@yumpoo/api-client'
import { problemMessage, toApiProblem } from '../api/problems'
import { useSession } from '../composables/useSession'
import { useDashboard } from '../components/dashboard/useDashboard'
import { clone, defaultConfiguration, emptyFilters, newWidget, widgetCatalog } from '../components/dashboard/dashboardModel'
import { resolveChart } from '../components/dashboard/chartModel'
import DashboardChart from '../components/dashboard/DashboardChart.vue'
import DashboardGrid from '../components/dashboard/DashboardGrid.vue'
import DashboardSettingsDialog from '../components/dashboard/DashboardSettingsDialog.vue'
import DashboardProjectsDialog from '../components/dashboard/DashboardProjectsDialog.vue'
import DashboardFiltersDialog from '../components/dashboard/DashboardFiltersDialog.vue'
import ChartTypeIcon from '../components/dashboard/ChartTypeIcon.vue'
import DashboardExploreDialog from '../components/dashboard/DashboardExploreDialog.vue'

const session = useSession()
const { dashboards, dashboard, snapshot, loading, refreshing, error, saveError, name, configuration, saving, changed, save, refresh, create, remove, switchTo, load, reload, reloading, recovery, resolveRecovery } = useDashboard()
const projectDialog = ref(false), filterDialog = ref(false), filterField = ref('assignees'), gallery = ref(false), galleryClosing = ref(false)
const switchSearch = ref(''), switchOpen = ref(false), grid = ref<InstanceType<typeof DashboardGrid>>()
const createOpen = ref(false), createName = ref(''), createBlank = ref(false), creating = ref(false), createError = ref('')
const settingsId = ref(''), settings = computed(() => configuration.value.widgets.find(w => w.id === settingsId.value))
const removed = ref<{ widget: DashboardWidget; index: number }>(), pendingReveal = ref('')
const detailId = ref(''), detailSelection = ref<DashboardChartSelection>()
const detailWidget = computed(() => configuration.value.widgets.find(w => w.id === detailId.value))
const connections = computed(() => snapshot.value?.projects || dashboard.value?.projects || [])
const choices = computed(() => dashboards.value.filter(d => d.name.toLowerCase().includes(switchSearch.value.toLowerCase())))
const results = computed(() => new Map(snapshot.value?.charts?.map(c => [c.id, c]) || []))
const filtersCount = computed(() => Object.entries(configuration.value.filters).reduce((count, [key, value]) => count + (Array.isArray(value) ? value.length : key === 'query' ? 0 : value ? 1 : 0), 0))
const filterChips = computed(() => {
  const f = configuration.value.filters, result: { key: string; label: string }[] = []
  for (const [key, label] of Object.entries({ projectIds: '项目', assignees: '处理人', statuses: '状态', priorities: '优先级', contentIds: '工作项类型', categories: '状态分类' })) {
    const values = f[key as keyof DashboardFilters]; if (Array.isArray(values) && values.length) result.push({ key, label: `${label} · ${values.length}` })
  }
  if (f.query) result.push({ key: 'query', label: `名称 / 编号：${f.query}` })
  if (f.dueFrom || f.dueTo) result.push({ key: 'due', label: `截止日期 ${f.dueFrom ? new Date(f.dueFrom).toLocaleDateString('zh-CN') : '不限'} — ${f.dueTo ? new Date(f.dueTo).toLocaleDateString('zh-CN') : '不限'}` })
  if (f.includeArchived) result.push({ key: 'includeArchived', label: '含已归档工作项' })
  if (f.hasTime) result.push({ key: 'hasTime', label: '有计时记录' })
  return result
})
watch(() => dashboard.value?.id, () => { removed.value = undefined; settingsId.value = ''; pendingReveal.value = ''; detailId.value = '' })
function openFilters(field: string) { filterField.value = field; filterDialog.value = true }
function applyFilters(filters: DashboardFilters) { configuration.value.filters = filters; changed(true) }
function clearChip(key: string) {
  if (key === 'due') { configuration.value.filters.dueFrom = null; configuration.value.filters.dueTo = null }
  else if (key === 'includeArchived' || key === 'hasTime') configuration.value.filters[key] = false
  else if (key === 'query') configuration.value.filters.query = ''
  else configuration.value.filters[key as 'assignees'] = []
  changed(true)
}
async function applyProjects(ids: string[]) {
  configuration.value.projectIds = ids
  configuration.value.filters.projectIds = configuration.value.filters.projectIds.filter(id => ids.includes(id))
  changed(); if (!dashboard.value) { await guarded(() => create(name.value, configuration.value)); return }
  if (await save()) await refresh()
}
async function guarded(action: () => Promise<unknown>) { try { await action() } catch (reason) { ElMessage.error(problemMessage(await toApiProblem(reason))) } }
function startCreate() { createName.value = '新仪表板'; createBlank.value = false; createError.value = ''; createOpen.value = true; switchOpen.value = false }
async function submitCreate() {
  if (creating.value) return
  creating.value = true; createError.value = ''
  try { await create(createName.value.trim(), defaultConfiguration(createBlank.value)); createOpen.value = false; projectDialog.value = true }
  catch (reason) { createError.value = problemMessage(await toApiProblem(reason)) } finally { creating.value = false }
}
async function revealPending() {
  if (!pendingReveal.value || gallery.value || galleryClosing.value || !grid.value) return
  const id = pendingReveal.value; pendingReveal.value = ''; await grid.value.reveal(id)
}
function added(widget: DashboardWidget) {
  configuration.value.widgets.push(widget); pendingReveal.value = widget.id; changed(true)
  if (gallery.value) { galleryClosing.value = true; gallery.value = false }
  else void nextTick(revealPending)
}
function addWidget(kind: `${DashboardWidget['kind']}`) {
  if (configuration.value.widgets.length >= 40) { ElMessage.warning('每个仪表板最多添加 40 个组件'); return }
  added(newWidget(kind, configuration.value.widgets))
}
function widgetAction(command: string, widget: DashboardWidget) {
  if (command === 'settings') settingsId.value = widget.id
  if (command === 'details' || command === 'fullscreen') void showDetails(widget)
  if (command === 'copy') { if (configuration.value.widgets.length >= 40) { ElMessage.warning('每个仪表板最多添加 40 个组件'); return } const position = newWidget(widget.kind, configuration.value.widgets); added({ ...clone(widget), id: position.id, title: `${widget.title} 副本`.slice(0, 80), wide: position.wide, medium: position.medium }) }
  if (command === 'remove') { const index = configuration.value.widgets.findIndex(w => w.id === widget.id); removed.value = { widget: clone(widget), index }; configuration.value.widgets.splice(index, 1); changed(true, true) }
}
function undoRemove() {
  if (!removed.value || configuration.value.widgets.length >= 40) return
  const position = newWidget(removed.value.widget.kind, configuration.value.widgets)
  configuration.value.widgets.splice(removed.value.index, 0, { ...removed.value.widget, wide: position.wide, medium: position.medium }); removed.value = undefined; changed(true)
}
function changeSettings(widget: DashboardWidget) {
  const index = configuration.value.widgets.findIndex(w => w.id === widget.id)
  if (index < 0) return
  configuration.value.widgets[index] = widget; changed(true)
}
function moveWidget(widget: DashboardWidget, direction: number) {
  const index = configuration.value.widgets.indexOf(widget), target = index + direction
  if (target < 0 || target >= configuration.value.widgets.length) return
  const next = configuration.value.widgets[target]!
  configuration.value.widgets[index] = next; configuration.value.widgets[target] = widget; changed()
}
async function dashboardAction(command: string) {
  try {
    if (command === 'rename') { const answer = await ElMessageBox.prompt('仪表板名称', '重命名仪表板', { inputValue: name.value, inputValidator: value => !!value?.trim() && value.trim().length <= 100 || '请输入 1–100 个字符', confirmButtonText: '保存', cancelButtonText: '取消' }); name.value = answer.value.trim(); changed() }
    if (command === 'copy') await create(`${name.value} 副本`.slice(0, 100), configuration.value, true)
    if (command === 'reset') { await ElMessageBox.confirm('恢复默认的 8 个组件及其布局？', '恢复默认布局', { confirmButtonText: '恢复', cancelButtonText: '取消' }); configuration.value.widgets = defaultConfiguration().widgets; changed(true) }
    if (command === 'delete') { await ElMessageBox.confirm(`删除“${name.value}”后无法恢复。`, '删除仪表板', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }); await remove() }
  } catch (reason) { if (reason !== 'cancel' && reason !== 'close') ElMessage.error(problemMessage(await toApiProblem(reason))) }
}
let detailTrigger: HTMLElement | undefined
function closeDetails() {
  detailId.value = ''
  void nextTick(() => detailTrigger?.focus({ preventScroll: true }))
}
function showDetails(widget: DashboardWidget, selection?: DashboardChartSelection) {
  detailTrigger = document.querySelector<HTMLElement>('[gs-id="' + widget.id + '"] .dashboard-card__menu') || undefined
  detailId.value = widget.id; detailSelection.value = selection
}
async function discardReload() {
  try { await ElMessageBox.confirm('放弃当前修改并载入最新版本？', '重新载入', { confirmButtonText: '放弃修改并重新载入', cancelButtonText: '继续编辑' }); await reload(true) }
  catch (reason) { if (reason !== 'cancel' && reason !== 'close') ElMessage.error(problemMessage(await toApiProblem(reason))) }
}
</script>

<template>
  <section class="dashboard-page">
    <header class="dashboard-header">
      <div class="dashboard-heading">
        <el-popover
          v-model:visible="switchOpen"
          placement="bottom-start"
          :width="320"
          trigger="click"
        >
          <template #reference>
            <button
              class="dashboard-title-button"
              aria-label="切换仪表板"
            >
              <h1>{{ name }}</h1><el-icon><ArrowDown /></el-icon>
            </button>
          </template>
          <el-input
            v-model="switchSearch"
            :prefix-icon="Search"
            placeholder="查找仪表板"
            clearable
          />
          <div class="dashboard-switch-list">
            <button
              v-for="d in choices"
              :key="d.id"
              :class="{ active: d.id === dashboard?.id }"
              @click="switchTo(d.id); switchOpen = false"
            >
              <el-icon><DataAnalysis /></el-icon><span>{{ d.name }}</span><el-icon v-if="d.id === dashboard?.id">
                <Check />
              </el-icon>
            </button><div
              v-if="!choices.length"
              class="dialog-empty"
            >
              暂无仪表板
            </div>
          </div>
          <el-button
            text
            type="primary"
            :icon="Plus"
            @click="startCreate"
          >
            新建仪表板
          </el-button>
        </el-popover>
      </div>
      <div class="dashboard-header-actions">
        <el-tooltip content="刷新数据">
          <el-button
            :icon="Refresh"
            aria-label="刷新仪表板"
            :loading="refreshing || reloading"
            text
            @click="reload()"
          />
        </el-tooltip>
        <el-dropdown
          trigger="click"
          @command="dashboardAction"
        >
          <el-button
            :icon="MoreFilled"
            aria-label="仪表板更多操作"
            text
            :disabled="!dashboard || saving"
          /><template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="rename">
                <el-icon><Edit /></el-icon>
                重命名
              </el-dropdown-item><el-dropdown-item command="copy">
                <el-icon><CopyDocument /></el-icon>
                复制仪表板
              </el-dropdown-item><el-dropdown-item
                command="reset"
                divided
              >
                <el-icon><RefreshLeft /></el-icon>
                恢复默认布局
              </el-dropdown-item><el-dropdown-item
                command="delete"
                divided
              >
                <el-icon><Delete /></el-icon>
                删除仪表板
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>
    <div class="dashboard-toolbar">
      <el-button
        type="primary"
        :icon="Plus"
        :disabled="!dashboard"
        @click="gallery = true"
      >
        添加组件
      </el-button>
      <el-button
        :icon="Connection"
        text
        @click="projectDialog = true"
      >
        {{ configuration.projectIds.length ? `${configuration.projectIds.length} 个连接项目` : '连接项目' }}
      </el-button>
      <span class="toolbar-divider" />
      <el-button
        :icon="User"
        :class="{ 'filter-active': configuration.filters.assignees.length }"
        text
        @click="openFilters('assignees')"
      >
        处理人
      </el-button>
      <el-button
        :icon="Filter"
        :class="{ 'filter-active': filtersCount }"
        text
        @click="openFilters('statuses')"
      >
        筛选<span
          v-if="filtersCount"
          class="toolbar-count"
        >{{ filtersCount }}</span>
      </el-button>
    </div>
    <div
      v-if="filterChips.length"
      class="dashboard-filter-chips"
    >
      <el-tag
        v-for="chip in filterChips"
        :key="chip.key"
        closable
        effect="plain"
        @close="clearChip(chip.key)"
      >
        {{ chip.label }}
      </el-tag><el-button
        link
        @click="applyFilters(emptyFilters())"
      >
        清除全部
      </el-button>
    </div>
    <main class="dashboard-canvas">
      <el-alert
        v-if="saveError"
        class="dashboard-notice"
        type="warning"
        :closable="false"
      >
        <template #title>
          {{ `更改尚未保存：${saveError}` }}
        </template><el-button
          text
          @click="save"
        >
          重试保存
        </el-button><el-button
          text
          @click="discardReload"
        >
          放弃修改并重新载入
        </el-button>
      </el-alert>
      <el-alert
        v-if="error"
        class="dashboard-notice"
        type="error"
        :closable="false"
        :title="snapshot ? `刷新失败，当前显示上次数据。${error}` : error"
      >
        <el-button
          text
          @click="dashboard ? refresh() : load()"
        >
          重试
        </el-button>
      </el-alert>
      <el-alert
        v-if="connections.some(p => !p.available)"
        class="dashboard-notice"
        type="warning"
        :closable="false"
        title="部分连接项目已无法访问，已从统计中排除。"
      >
        <el-button
          text
          @click="projectDialog = true"
        >
          管理连接
        </el-button>
      </el-alert>
      <div
        v-if="removed"
        class="dashboard-undo"
      >
        已移除“{{ removed.widget.title }}”<el-button
          link
          type="primary"
          @click="undoRemove"
        >
          撤销
        </el-button><el-button
          :icon="Close"
          text
          aria-label="关闭撤销提示"
          @click="removed = undefined"
        />
      </div>
      <el-skeleton
        v-if="loading"
        animated
        :rows="12"
        class="dashboard-skeleton"
      />
      <div
        v-else-if="!dashboard || !configuration.projectIds.length"
        class="dashboard-welcome"
      >
        <el-icon :size="44">
          <DataAnalysis />
        </el-icon><h2>连接项目</h2><el-button
          type="primary"
          :icon="Connection"
          @click="projectDialog = true"
        >
          连接第一个项目
        </el-button><el-button
          v-if="!dashboard"
          text
          @click="startCreate"
        >
          从空白仪表板开始
        </el-button>
      </div>
      <DashboardGrid
        v-else-if="configuration.widgets.length"
        ref="grid"
        :key="dashboard?.id"
        :widgets="configuration.widgets"
        @layout="configuration.widgets = $event; changed(); save()"
        @ready="revealPending"
      >
        <template #default="{ widget, narrow }">
          <article
            class="dashboard-card"
            :class="{ 'dashboard-card--metric': resolveChart(widget).type === 'NUMBER' }"
          >
            <header class="dashboard-card__header">
              <div class="dashboard-card__handle">
                <span
                  class="drag-grip"
                  aria-hidden="true"
                >⠿</span><h2>{{ widget.title }}</h2>
              </div><el-dropdown
                trigger="click"
                @command="widgetAction($event, widget)"
              >
                <button
                  class="dashboard-card__menu"
                  :aria-label="`${widget.title}操作`"
                >
                  <el-icon><MoreFilled /></el-icon>
                </button><template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="fullscreen">
                      <el-icon><FullScreen /></el-icon>全屏
                    </el-dropdown-item><el-dropdown-item command="settings">
                      <el-icon><Setting /></el-icon>
                      组件设置
                    </el-dropdown-item><el-dropdown-item command="details">
                      <el-icon><List /></el-icon>
                      查看工作项
                    </el-dropdown-item><el-dropdown-item command="copy">
                      <el-icon><CopyDocument /></el-icon>
                      复制组件
                    </el-dropdown-item><el-dropdown-item
                      command="remove"
                      divided
                    >
                      <el-icon><Delete /></el-icon>
                      移除组件
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </header>
            <div class="dashboard-card__chart">
              <DashboardChart
                :widget="widget"
                :result="results.get(widget.id)"
                :loading="refreshing"
                @select="showDetails(widget, $event)"
              />
            </div>
            <div
              v-if="narrow"
              class="dashboard-mobile-order"
            >
              <el-button
                text
                size="small"
                @click="moveWidget(widget, -1)"
              >
                上移
              </el-button><el-button
                text
                size="small"
                @click="moveWidget(widget, 1)"
              >
                下移
              </el-button>
            </div>
          </article>
        </template>
      </DashboardGrid>
      <div
        v-else
        class="dashboard-empty-widgets"
      >
        <el-icon :size="40">
          <DataAnalysis />
        </el-icon><h2>添加第一个组件</h2><el-button
          type="primary"
          :icon="Plus"
          @click="gallery = true"
        >
          添加组件
        </el-button>
      </div>
    </main>
    <DashboardProjectsDialog
      v-model="projectDialog"
      :selected="configuration.projectIds"
      :connections="connections"
      @apply="applyProjects"
    />
    <DashboardFiltersDialog
      v-model="filterDialog"
      :filters="configuration.filters"
      :options="snapshot?.options || []"
      :projects="connections"
      :initial-field="filterField"
      :user-id="session.authentication.value?.user.id"
      @apply="applyFilters"
    />
    <el-dialog
      v-model="createOpen"
      append-to-body
      title="新建仪表板"
      width="560px"
      class="dashboard-dialog"
    >
      <label class="dashboard-form-label">仪表板名称</label><el-input
        v-model="createName"
        maxlength="100"
        placeholder="例如：产品交付概览"
        @keyup.enter="submitCreate"
      /><label class="dashboard-form-label">起始内容</label><div class="dashboard-template-options">
        <button
          :class="{ selected: !createBlank }"
          @click="createBlank = false"
        >
          <el-icon :size="26">
            <DataAnalysis />
          </el-icon><strong>项目概览</strong>
        </button><button
          :class="{ selected: createBlank }"
          @click="createBlank = true"
        >
          <el-icon :size="26">
            <Plus />
          </el-icon><strong>空白仪表板</strong>
        </button>
      </div><el-alert
        v-if="createError"
        :title="createError"
        type="error"
        :closable="false"
      /><template #footer>
        <el-button @click="createOpen = false">
          取消
        </el-button><el-button
          type="primary"
          :loading="creating"
          :disabled="!createName.trim()"
          @click="submitCreate"
        >
          创建仪表板
        </el-button>
      </template>
    </el-dialog>
    <el-drawer
      v-model="gallery"
      append-to-body
      title="添加组件"
      size="400px"
      class="dashboard-drawer"
      @closed="galleryClosing = false; revealPending()"
    >
      <div class="dashboard-gallery">
        <article
          v-for="entry in widgetCatalog"
          :key="entry.kind"
        >
          <div class="widget-preview">
            <ChartTypeIcon :type="entry.kind === 'METRIC' ? 'NUMBER' : entry.kind === 'STATUS' ? 'DONUT' : entry.kind === 'CHART' ? 'COLUMN' : 'BAR'" />
          </div><div class="gallery-copy">
            <strong>{{ entry.title }}</strong>
          </div><el-button
            :icon="Plus"
            :aria-label="`添加${entry.title}`"
            @click="addWidget(entry.kind)"
          >
            添加
          </el-button>
        </article>
      </div>
    </el-drawer>
    <DashboardSettingsDialog
      v-if="settings"
      :key="settings.id"
      :widget="settings"
      :result="results.get(settings.id)"
      :projects="connections"
      :options="snapshot?.options || []"
      :loading="refreshing"
      :error="saveError"
      :query-error="error"
      @change="changeSettings"
      @close="settingsId = ''"
      @retry="save"
      @refresh="refresh"
      @select="showDetails"
    />
    <DashboardExploreDialog
      v-if="detailWidget && dashboard"
      :key="detailWidget.id"
      :dashboard-id="dashboard.id"
      :widget="detailWidget"
      :result="results.get(detailWidget.id)"
      :filters="configuration.filters"
      :projects="connections"
      :initial-selection="detailSelection"
      :loading="refreshing"
      :hidden="!!settingsId"
      @close="closeDetails"
      @settings="settingsId = detailWidget.id"
      @refresh="refresh"
    />
    <el-dialog
      :model-value="!!recovery"
      append-to-body
      title="更改尚未保存"
      width="min(520px, calc(100vw - 32px))"
      :close-on-click-modal="false"
      :show-close="false"
      :close-on-press-escape="false"
    >
      <p>{{ saveError || '正在同步仪表板，请重试。' }}</p>
      <template #footer>
        <el-button @click="resolveRecovery('stay')">
          继续编辑
        </el-button><el-button
          :disabled="saving"
          @click="resolveRecovery('discard')"
        >
          {{ recovery === 'leave' ? '放弃并离开' : '放弃并重新载入' }}
        </el-button><el-button
          type="primary"
          :loading="saving"
          @click="resolveRecovery('retry')"
        >
          重试保存
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>
<style src="../components/dashboard/dashboard.css" />
