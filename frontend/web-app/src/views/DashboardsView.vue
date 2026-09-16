<script setup lang="ts">
import { ElIcon, ElPopover, ElInput, ElButton, ElTooltip, ElDropdown, ElDropdownMenu, ElDropdownItem, ElTag, ElAlert, ElSkeleton, ElDialog, ElDrawer, ElSelect, ElOption as ElOptionRaw, ElRadioGroup, ElRadioButton, ElSwitch, ElTable, ElTableColumn, ElPagination } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, ref, watch, type DefineComponent } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowDown, Check, Close, Connection, DataAnalysis, Edit, Filter, FullScreen, Lock, MoreFilled, Plus, Refresh, Search, User } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { readCsrfToken, type DashboardFilters, type DashboardItemPage, type DashboardWidget } from '@yumpoo/api-client'
import { dashboardsApi } from '../api/client'
import { problemMessage, toApiProblem } from '../api/problems'
import { useSession } from '../composables/useSession'
import { useDashboard } from '../components/dashboard/useDashboard'
import { categories, clone, defaultConfiguration, drillFilters, duration, emptyFilters, metrics, newWidget, widgetCatalog } from '../components/dashboard/dashboardModel'
import DashboardChart from '../components/dashboard/DashboardChart.vue'
import DashboardGrid from '../components/dashboard/DashboardGrid.vue'
import DashboardProjectsDialog from '../components/dashboard/DashboardProjectsDialog.vue'
import DashboardFiltersDialog from '../components/dashboard/DashboardFiltersDialog.vue'
import YpAssignee from '../components/yp/YpAssignee.vue'
import { workItemLabelColorValue } from '../components/projects/workItemLabelColors'

const ElOption = ElOptionRaw as unknown as DefineComponent
const router = useRouter(), session = useSession()
const { dashboards, dashboard, snapshot, loading, refreshing, error, saveError, conflict, name, configuration, dirty, saving, saveLabel, changed, save, refresh, create, remove, switchTo, load } = useDashboard()
const editing = ref(false), projectDialog = ref(false), filterDialog = ref(false), filterField = ref('assignees'), gallery = ref(false), fullscreen = ref(false)
const switchSearch = ref(''), switchOpen = ref(false), query = ref(''), pageRoot = ref<HTMLElement>(), focusedWidget = ref<DashboardWidget>()
const createOpen = ref(false), createName = ref(''), createBlank = ref(false), creating = ref(false), createError = ref('')
const settings = ref<DashboardWidget>(), removed = ref<{ widget: DashboardWidget; index: number }>()
const detailOpen = ref(false), detailTitle = ref(''), detailFilters = ref<DashboardFilters>(emptyFilters()), detailPage = ref<DashboardItemPage>(), detailLoading = ref(false), detailError = ref(''), detailOffset = ref(0)
let detailSequence = 0, searchTimer: ReturnType<typeof setTimeout>
onBeforeUnmount(() => { clearTimeout(searchTimer); detailSequence++ })
const connections = computed(() => snapshot.value?.projects || dashboard.value?.projects || [])
const buckets = computed(() => snapshot.value?.buckets || [])
const total = computed(() => buckets.value.find(b => b.kind === 'TOTAL'))
const choices = computed(() => dashboards.value.filter(d => d.name.toLowerCase().includes(switchSearch.value.toLowerCase())))
const completion = computed(() => total.value?.count ? Math.round(total.value.done / total.value.count * 100) : 0)
const filtersCount = computed(() => Object.entries(configuration.value.filters).reduce((count, [key, value]) => count + (Array.isArray(value) ? value.length : key === 'query' ? 0 : value ? 1 : 0), 0))
const filterChips = computed(() => {
  const f = configuration.value.filters, result: { key: string; label: string }[] = []
  for (const [key, label] of Object.entries({ projectIds: '项目', assignees: '处理人', statuses: '状态', priorities: '优先级', contentIds: '工作项类型', categories: '状态分类' })) {
    const values = f[key as keyof DashboardFilters]; if (Array.isArray(values) && values.length) result.push({ key, label: `${label} · ${values.length}` })
  }
  if (f.dueFrom || f.dueTo) result.push({ key: 'due', label: `截止日期 ${f.dueFrom ? new Date(f.dueFrom).toLocaleDateString('zh-CN') : '不限'} — ${f.dueTo ? new Date(f.dueTo).toLocaleDateString('zh-CN') : '不限'}` })
  if (f.includeArchived) result.push({ key: 'includeArchived', label: '含已归档工作项' })
  if (f.hasTime) result.push({ key: 'hasTime', label: '有计时记录' })
  return result
})
watch(() => configuration.value.filters.query, value => { query.value = value })
watch(query, value => { clearTimeout(searchTimer); searchTimer = setTimeout(() => { if (configuration.value.filters.query !== value) { configuration.value.filters.query = value; changed(true) } }, 350) })
watch(() => dashboard.value?.id, () => { editing.value = false; removed.value = undefined })
function openFilters(field: string) { filterField.value = field; filterDialog.value = true }
function applyFilters(filters: DashboardFilters) { configuration.value.filters = filters; changed(true) }
function clearChip(key: string) {
  if (key === 'due') { configuration.value.filters.dueFrom = null; configuration.value.filters.dueTo = null }
  else if (key === 'includeArchived' || key === 'hasTime') configuration.value.filters[key] = false
  else (configuration.value.filters[key as 'assignees']) = []
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
function addWidget(kind: `${DashboardWidget['kind']}`) {
  if (configuration.value.widgets.length >= 40) { ElMessage.warning('每个仪表板最多添加 40 个组件'); return }
  const widget = newWidget(kind, configuration.value.widgets); configuration.value.widgets.push(widget); changed()
  void nextTick(() => { const target = pageRoot.value?.querySelector(`[gs-id="${widget.id}"]`); target?.scrollIntoView({ behavior: 'smooth', block: 'center' }); target?.animate([{ outline: '2px solid var(--yp-action-primary)' }, { outline: '2px solid transparent' }], { duration: 1500 }) })
}
function widgetAction(command: string, widget: DashboardWidget) {
  if (command === 'settings') settings.value = clone(widget)
  if (command === 'details') void showDetails(widget)
  if (command === 'fullscreen') focusedWidget.value = widget
  if (command === 'copy') { if (configuration.value.widgets.length >= 40) return; const position = newWidget(widget.kind, configuration.value.widgets); configuration.value.widgets.push({ ...clone(widget), id: position.id, title: `${widget.title} 副本`.slice(0, 80), wide: position.wide, medium: position.medium }); changed() }
  if (command === 'remove') { const index = configuration.value.widgets.findIndex(w => w.id === widget.id); removed.value = { widget: clone(widget), index }; configuration.value.widgets.splice(index, 1); changed() }
}
function undoRemove() {
  if (!removed.value) return
  const position = newWidget(removed.value.widget.kind, configuration.value.widgets)
  configuration.value.widgets.splice(removed.value.index, 0, { ...removed.value.widget, wide: position.wide, medium: position.medium }); removed.value = undefined; changed()
}
function saveSettings() { if (!settings.value) return; const index = configuration.value.widgets.findIndex(w => w.id === settings.value!.id); configuration.value.widgets[index] = settings.value; settings.value = undefined; changed() }
function moveWidget(widget: DashboardWidget, direction: number) {
  const index = configuration.value.widgets.indexOf(widget), target = index + direction
  if (target < 0 || target >= configuration.value.widgets.length) return
  const next = configuration.value.widgets[target]!
  configuration.value.widgets[index] = next; configuration.value.widgets[target] = widget; changed()
}
async function dashboardAction(command: string) {
  try {
    if (command === 'rename') { const answer = await ElMessageBox.prompt('为仪表板取一个容易识别的名称', '重命名仪表板', { inputValue: name.value, inputValidator: value => !!value?.trim() && value.trim().length <= 100 || '请输入 1–100 个字符', confirmButtonText: '保存', cancelButtonText: '取消' }); name.value = answer.value.trim(); changed() }
    if (command === 'copy') await create(`${name.value} 副本`.slice(0, 100), configuration.value)
    if (command === 'reset') { await ElMessageBox.confirm('将保留连接项目与筛选，恢复默认的 8 个组件及其布局。', '恢复默认布局', { confirmButtonText: '恢复', cancelButtonText: '取消' }); configuration.value.widgets = defaultConfiguration().widgets; changed() }
    if (command === 'delete') { await ElMessageBox.confirm(`删除“${name.value}”后无法恢复。项目及工作项不受影响。`, '删除仪表板', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }); await remove() }
  } catch (reason) { if (reason !== 'cancel' && reason !== 'close') ElMessage.error(problemMessage(await toApiProblem(reason))) }
}
function metricValue(widget: DashboardWidget) {
  const value = total.value
  return widget.metric === 'DURATION' ? duration(value?.durationMs || 0) : widget.metric === 'COMPLETION_RATE' ? `${completion.value}%` : (widget.metric === 'DONE' ? value?.done : widget.metric === 'IN_PROGRESS' ? value?.inProgress : value?.count)?.toLocaleString() || '0'
}
async function showDetails(widget: DashboardWidget, selection?: { kind: string; keys: string[] }) { detailTitle.value = widget.title; detailFilters.value = drillFilters(configuration.value.filters, widget, selection); detailPage.value = undefined; detailOffset.value = 0; detailOpen.value = true; await loadDetails() }
async function loadDetails() {
  if (!dashboard.value) return
  const token = ++detailSequence; detailLoading.value = true; detailError.value = ''
  const filters = detailFilters.value; if (filters.dueFrom) filters.dueFrom = new Date(filters.dueFrom); if (filters.dueTo) filters.dueTo = new Date(filters.dueTo)
  try { const result = await dashboardsApi.queryDashboardItems({ id: dashboard.value.id, xXSRFTOKEN: readCsrfToken() || '', dashboardItemsQuery: { filters, offset: detailOffset.value, limit: 25 } }); if (token === detailSequence) detailPage.value = result }
  catch (reason) { if (token === detailSequence) detailError.value = problemMessage(await toApiProblem(reason)) } finally { if (token === detailSequence) detailLoading.value = false }
}
</script>

<template>
  <section
    ref="pageRoot"
    class="dashboard-page"
    :class="{ 'dashboard-page--fullscreen': fullscreen }"
    @keydown.esc="fullscreen = false"
  >
    <header class="dashboard-header">
      <div class="dashboard-heading">
        <div class="dashboard-eyebrow">
          <el-icon><DataAnalysis /></el-icon> 工作台 / 仪表板
        </div>
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
              <el-icon><DataAnalysis /></el-icon><span>{{ d.name }}<small>{{ d.projectCount }} 个项目 · {{ d.widgetCount }} 个组件</small></span><el-icon v-if="d.id === dashboard?.id">
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
        <div class="dashboard-subtitle">
          <span><el-icon><Lock /></el-icon> 仅自己可见</span><i /><span :class="{ 'save-warning': saveError || dirty }">{{ saveLabel }}</span><span
            v-if="snapshot"
            class="dashboard-update-time"
          >更新于 {{ snapshot.asOf.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}</span>
        </div>
      </div>
      <div class="dashboard-header-actions">
        <el-tooltip content="刷新数据">
          <el-button
            :icon="Refresh"
            aria-label="刷新仪表板"
            :loading="refreshing"
            text
            @click="refresh"
          />
        </el-tooltip><el-tooltip :content="fullscreen ? '退出全屏' : '全屏查看'">
          <el-button
            :icon="fullscreen ? Close : FullScreen"
            :aria-label="fullscreen ? '退出全屏' : '全屏查看'"
            text
            @click="fullscreen = !fullscreen"
          />
        </el-tooltip><el-dropdown
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
                重命名
              </el-dropdown-item><el-dropdown-item command="copy">
                复制仪表板
              </el-dropdown-item><el-dropdown-item
                command="reset"
                divided
              >
                恢复默认布局
              </el-dropdown-item><el-dropdown-item
                command="delete"
                divided
              >
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
        @click="projectDialog = true"
      >
        连接项目<span class="toolbar-count">{{ configuration.projectIds.length }}</span>
      </el-button>
      <span class="toolbar-divider" />
      <el-input
        v-model="query"
        class="dashboard-search"
        :prefix-icon="Search"
        clearable
        placeholder="搜索工作项"
        aria-label="搜索工作项名称或编号"
        maxlength="200"
      />
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
      <el-button
        class="layout-button"
        :icon="editing ? Check : Edit"
        :type="editing ? 'primary' : 'default'"
        :plain="editing"
        :disabled="!dashboard"
        @click="editing = !editing"
      >
        {{ editing ? '完成布局' : '编辑布局' }}
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
          {{ conflict ? '此仪表板已在其他窗口更新，当前修改已保留。' : `更改尚未保存：${saveError}` }}
        </template><el-button
          v-if="!conflict"
          text
          @click="save"
        >
          重试保存
        </el-button><template v-else>
          <el-button
            text
            @click="guarded(() => create(`${name} 副本`.slice(0, 100), configuration))"
          >
            将当前修改另存为副本
          </el-button><el-button
            text
            @click="guarded(async () => { await ElMessageBox.confirm('放弃当前修改并载入最新版本？', '重新载入', { confirmButtonText: '重新载入', cancelButtonText: '取消' }); await load(dashboard?.id) })"
          >
            重新载入
          </el-button>
        </template>
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
      <div
        v-if="editing"
        class="dashboard-edit-hint"
      >
        <el-icon><Edit /></el-icon><span>拖动卡片标题调整位置，拖动右下角调整大小。布局会自动保存。</span>
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
        <div class="dashboard-welcome-art">
          <span class="welcome-donut" /><span class="welcome-bars"><i /><i /><i /></span><span class="welcome-number">128<small>工作项</small></span>
        </div>
        <span class="dashboard-eyebrow">所有进展，尽在一处</span><h2>把项目连接到你的仪表板</h2><p>汇总工作量、团队分配与实际耗时，<br>用你喜欢的方式了解项目全貌。</p><el-button
          type="primary"
          :icon="Connection"
          size="large"
          @click="projectDialog = true"
        >
          连接第一个项目
        </el-button><el-button
          v-if="!dashboard"
          text
          @click="startCreate"
        >
          从空白仪表板开始
        </el-button><small><el-icon><Lock /></el-icon> 私人空间，仅你可以查看和编辑</small>
      </div>
      <template v-else>
        <DashboardGrid
          v-if="configuration.widgets.length"
          :widgets="configuration.widgets"
          :editing="editing"
          @layout="configuration.widgets = $event; changed()"
        >
          <template #default="{ widget, narrow }">
            <article
              class="dashboard-card"
              :class="{ 'dashboard-card--metric': widget.kind === 'METRIC' }"
            >
              <header class="dashboard-card__header">
                <div class="dashboard-card__handle">
                  <span
                    v-if="editing"
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
                      <el-dropdown-item command="settings">
                        组件设置
                      </el-dropdown-item><el-dropdown-item command="details">
                        查看工作项
                      </el-dropdown-item><el-dropdown-item command="fullscreen">
                        全屏查看
                      </el-dropdown-item><el-dropdown-item command="copy">
                        复制组件
                      </el-dropdown-item><el-dropdown-item
                        command="remove"
                        divided
                      >
                        移除组件
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </header>
              <button
                v-if="widget.kind === 'METRIC'"
                class="dashboard-metric"
                :class="`dashboard-metric--${widget.metric.toLowerCase()}`"
                :aria-label="`${widget.title} ${metricValue(widget)}，查看明细`"
                @click="showDetails(widget)"
              >
                <strong>{{ metricValue(widget) }}</strong><span v-if="widget.metric === 'DONE'"><i class="metric-progress"><i :style="{ width: `${completion}%` }" /></i>{{ completion }}% 完成率</span><span v-else>{{ widget.metric === 'DURATION' ? '实际计时 · 含进行中的计时' : widget.metric === 'IN_PROGRESS' ? '正在推进的工作项' : widget.metric === 'COMPLETION_RATE' ? '已完成 / 全部工作项' : '当前筛选下的全部工作项' }}</span>
              </button>
              <div
                v-else
                class="dashboard-card__chart"
              >
                <DashboardChart
                  :widget="widget"
                  :buckets="buckets"
                  :projects="connections"
                  @select="showDetails(widget, $event)"
                />
              </div>
              <div
                v-if="editing && narrow"
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
          </el-icon><h2>从一个组件开始</h2><p>挑选你关心的指标，搭建自己的数据视图。</p><el-button
            type="primary"
            :icon="Plus"
            @click="gallery = true"
          >
            添加组件
          </el-button>
        </div>
        <footer class="dashboard-footnote">
          父项与子项分别计数 · 耗时汇总各工作项的原始记录 · 每 30 秒自动更新
        </footer>
      </template>
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
      <p class="dialog-intro">
        为不同项目或关注重点建立独立视图。
      </p><label class="dashboard-form-label">仪表板名称</label><el-input
        v-model="createName"
        maxlength="100"
        show-word-limit
        placeholder="例如：产品交付概览"
        @keyup.enter="submitCreate"
      /><label class="dashboard-form-label">起始内容</label><div class="dashboard-template-options">
        <button
          :class="{ selected: !createBlank }"
          @click="createBlank = false"
        >
          <el-icon :size="26">
            <DataAnalysis />
          </el-icon><strong>项目概览</strong><small>4 个指标 + 4 张基础图表</small>
        </button><button
          :class="{ selected: createBlank }"
          @click="createBlank = true"
        >
          <el-icon :size="26">
            <Plus />
          </el-icon><strong>空白仪表板</strong><small>自由添加需要的组件</small>
        </button>
      </div><p class="dialog-intro">
        <el-icon><Lock /></el-icon> 仅自己可见，创建后选择连接项目。
      </p><el-alert
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
      size="440px"
      class="dashboard-drawer"
    >
      <p class="dialog-intro">
        选择数据的表达方式。所有组件共享仪表板的项目与筛选条件。
      </p><div class="dashboard-gallery">
        <article
          v-for="entry in widgetCatalog"
          :key="entry.kind"
        >
          <div
            class="widget-preview"
            :class="`widget-preview--${entry.icon}`"
          >
            <template v-if="entry.kind === 'METRIC'">
              <strong>128</strong><small>工作项总数</small>
            </template><span
              v-else-if="entry.kind === 'STATUS'"
              class="preview-donut"
            /><template v-else>
              <i /><i /><i />
            </template>
          </div><div class="gallery-copy">
            <strong>{{ entry.title }}</strong><p>{{ entry.description }}</p>
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
    <el-drawer
      append-to-body
      :model-value="!!settings"
      title="组件设置"
      size="480px"
      class="dashboard-drawer"
      @close="settings = undefined"
    >
      <template v-if="settings">
        <div class="settings-preview">
          <template v-if="settings.kind === 'METRIC'">
            <small>{{ settings.title }}</small><strong>{{ metricValue(settings) }}</strong>
          </template><DashboardChart
            v-else
            :widget="settings"
            :buckets="buckets"
            :projects="connections"
          />
        </div><label class="dashboard-form-label">组件名称</label><el-input
          v-model="settings.title"
          maxlength="80"
          show-word-limit
        /><template v-if="settings.kind === 'METRIC'">
          <label class="dashboard-form-label">统计指标</label><el-select
            v-model="settings.metric"
            style="width:100%"
          >
            <el-option
              v-for="(label, key) in metrics"
              :key="key"
              :value="key"
              :label="label"
            />
          </el-select>
        </template><template v-else>
          <template v-if="settings.kind === 'STATUS' || settings.kind === 'PROJECT_WORKLOAD'">
            <label class="dashboard-form-label">状态分组</label><el-radio-group v-model="settings.grouping">
              <el-radio-button value="STATUS">
                项目状态
              </el-radio-button><el-radio-button value="CATEGORY">
                四类状态
              </el-radio-button>
            </el-radio-group><label class="dashboard-settings-toggle">显示图例<el-switch v-model="settings.showLegend" /></label>
          </template><label class="dashboard-form-label">排序</label><el-radio-group v-model="settings.sort">
            <el-radio-button value="DESC">
              从多到少
            </el-radio-button><el-radio-button value="ASC">
              从少到多
            </el-radio-button>
          </el-radio-group><label class="dashboard-settings-toggle">显示数值<el-switch v-model="settings.showValues" /></label>
        </template><p class="dialog-intro">
          数据范围由仪表板顶部的连接项目与筛选控制。
        </p>
      </template><template #footer>
        <el-button @click="settings = undefined">
          取消
        </el-button><el-button
          type="primary"
          :disabled="!settings?.title.trim()"
          @click="saveSettings"
        >
          应用设置
        </el-button>
      </template>
    </el-drawer>
    <el-drawer
      v-model="detailOpen"
      append-to-body
      :title="`${detailTitle} · 工作项明细`"
      size="min(960px, 100vw)"
      class="dashboard-drawer"
    >
      <p class="dialog-intro">
        共 {{ detailPage?.totalElements || 0 }} 个工作项。点击名称查看详情。
      </p><el-alert
        v-if="detailError"
        :title="detailError"
        type="error"
        :closable="false"
      >
        <el-button
          text
          @click="loadDetails"
        >
          重试
        </el-button>
      </el-alert><el-table
        v-loading="detailLoading"
        :data="detailPage?.items || []"
        empty-text="暂无匹配的工作项"
      >
        <el-table-column
          label="工作项"
          min-width="240"
        >
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              class="detail-item-link"
              @click="router.push({ name: 'project-overview', params: { projectId: row.projectId }, query: { workItemId: row.id } })"
            >
              {{ row.itemNo }} · {{ row.title }}
            </el-button>
          </template>
        </el-table-column><el-table-column
          label="项目"
          min-width="130"
        >
          <template #default="{ row }">
            {{ connections.find(p => p.id === row.projectId)?.name }}
          </template>
        </el-table-column><el-table-column
          label="处理人"
          min-width="130"
        >
          <template #default="{ row }">
            <YpAssignee
              :user-id="row.assigneeUserId"
              :display-name="row.assigneeName"
              size="table"
            />
          </template>
        </el-table-column><el-table-column
          label="状态"
          min-width="110"
        >
          <template #default="{ row }">
            <span class="detail-status"><i :style="{ background: workItemLabelColorValue(row.colorToken) }" />{{ row.statusName || categories[row.statusCategory]?.name }}</span>
          </template>
        </el-table-column><el-table-column
          label="实际耗时"
          width="110"
        >
          <template #default="{ row }">
            {{ duration(row.durationMs) }}
          </template>
        </el-table-column>
      </el-table><template #footer>
        <el-pagination
          :current-page="detailOffset / 25 + 1"
          :page-size="25"
          :total="detailPage?.totalElements || 0"
          layout="prev, pager, next"
          @current-change="detailOffset = ($event - 1) * 25; loadDetails()"
        />
      </template>
    </el-drawer>
    <el-dialog
      append-to-body
      :model-value="!!focusedWidget"
      :title="focusedWidget?.title || ''"
      fullscreen
      class="dashboard-widget-fullscreen"
      @close="focusedWidget = undefined"
    >
      <div
        v-if="focusedWidget"
        class="focused-widget"
      >
        <strong v-if="focusedWidget.kind === 'METRIC'">{{ metricValue(focusedWidget) }}</strong><DashboardChart
          v-else
          :widget="focusedWidget"
          :buckets="buckets"
          :projects="connections"
          @select="showDetails(focusedWidget, $event)"
        />
      </div>
    </el-dialog>
  </section>
</template>
<style src="../components/dashboard/dashboard.css" />
