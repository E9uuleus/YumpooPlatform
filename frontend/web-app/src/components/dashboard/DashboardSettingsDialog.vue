<script setup lang="ts">
import { computed, ref, watch, type DefineComponent } from 'vue'
import { ElDialog, ElButton, ElIcon, ElAlert, ElSelect as ElSelectRaw, ElOption as ElOptionRaw, ElInput, ElColorPicker } from 'element-plus'
import { Close, Setting, ArrowUp, ArrowDown, Search, EditPen, RefreshLeft, Filter, List } from '@element-plus/icons-vue'
import type { DashboardBucket, DashboardChart, DashboardChartResult, DashboardChartSelection, DashboardConnection, DashboardFilters, DashboardWidget } from '@yumpoo/api-client'
import { clone, emptyFilters } from './dashboardModel'
import { additive, chartGroups, chartLabel, chartLabelKey, chartTypes, dateDimensions, detailColumns, dimensions, resolveChart } from './chartModel'
import { chartColor, chartPalette } from './chartColors'
import DashboardChartView from './DashboardChart.vue'
import DashboardFiltersDialog from './DashboardFiltersDialog.vue'
import ChartMeasureControl from './ChartMeasureControl.vue'
import ChartTypeIcon from './ChartTypeIcon.vue'

const ElSelect = ElSelectRaw as unknown as DefineComponent
const ElOption = ElOptionRaw as unknown as DefineComponent

const props = defineProps<{ widget: DashboardWidget; result?: DashboardChartResult | undefined; projects: DashboardConnection[]; options: DashboardBucket[]; loading: boolean; error: string; queryError: string }>()
const emit = defineEmits<{ change: [widget: DashboardWidget]; close: []; retry: []; refresh: []; select: [widget: DashboardWidget, selection?: DashboardChartSelection] }>()
const draft = ref({ ...clone(props.widget), chart: clone(resolveChart(props.widget)) })
const c = computed(() => draft.value.chart), filterOpen = ref(false), labelSearch = ref(''), labelsOpen = ref(false)
const limitEnabled = ref(c.value.limit > 0)
const validLimit = computed(() => Number.isInteger(c.value.limit) && c.value.limit >= (limitEnabled.value ? 1 : 0) && c.value.limit <= 5000)
const cartesian = computed(() => ['COLUMN', 'BAR', 'LINE', 'AREA'].includes(c.value.type))
const dated = computed(() => dateDimensions.has(c.value.dimension) || dateDimensions.has(c.value.series))
const filters = computed(() => c.value.filters || { ...emptyFilters(), includeArchived: true })
const contentOptions = computed(() => props.options.filter(o => o.kind === 'CONTENT' && (c.value.projectIds == null || c.value.projectIds.includes(o.projectId || ''))))
const allLabels = computed(() => {
  const rows = new Map<string, { key: string; name: string; color: string; order: number; customColor: boolean; group: string }>()
  const groups = chartGroups({ ...c.value, limit: 0 }, props.result)
  const seriesKeys = [...new Set(groups.flatMap(r => r.points.map(p => p.seriesKey)))]
  if (c.value.sort === 'CUSTOM') seriesKeys.sort((a, b) => chartLabel(c.value, c.value.series, a, '').order - chartLabel(c.value, c.value.series, b, '').order)
  function add(dimension: string, id: string, name: string, token: string, index: number) {
    const key = chartLabelKey(dimension, id), label = chartLabel(c.value, dimension, id, name, token)
    rows.set(key, { key, ...label, color: chartColor(dimension, label.color, index), customColor: !!c.value.labels.find(l => l.key === key)?.color, group: dimensions[dimension as keyof typeof dimensions] || '' })
  }
  groups.forEach((group, index) => {
    const p = group.points[0]!
    add(c.value.dimension, p.key, p.label, p.colorToken, cartesian.value ? 0 : index)
    if (cartesian.value && c.value.series !== 'NONE') for (const point of group.points) add(c.value.series, point.seriesKey, point.seriesLabel, point.seriesColorToken, seriesKeys.indexOf(point.seriesKey))
  })
  return [...rows.values()].sort((a, b) => a.order - b.order)
})
const labels = computed(() => allLabels.value.filter(r => r.name.toLowerCase().includes(labelSearch.value.toLowerCase())).slice(0, 100))
const validTimezone = computed(() => { try { new Intl.DateTimeFormat('zh-CN', { timeZone: c.value.timezone }); return !!c.value.timezone } catch { return false } })
watch(draft, value => { if (value.title.trim() && validTimezone.value && validLimit.value) emit('change', { ...clone(value), title: value.title.trim() }) }, { deep: true })
function setType(type: typeof chartTypes[number]['value']) {
  c.value.type = type as DashboardChart['type']
  if (c.value.dimension === 'NONE') c.value.dimension = 'STATUS' as DashboardChart['dimension']
  c.value.xMeasure ||= { ...c.value.measure }; c.value.sizeMeasure ||= { ...c.value.measure }
}
function applyFilters(value: DashboardFilters) { c.value.filters = value }
function toggleProject(id: string, event: Event) {
  const ids = c.value.projectIds == null ? props.projects.filter(p => p.available).map(p => p.id) : [...c.value.projectIds]
  c.value.projectIds = (event.target as HTMLInputElement).checked ? [...new Set([...ids, id])] : ids.filter(v => v !== id)
}
function setLabel(key: string, field: 'name' | 'color', value: string) {
  let label = c.value.labels.find(l => l.key === key)
  if (!label) { if (c.value.labels.length >= 500) return; label = { key, name: '', color: '', order: 5000 }; c.value.labels.push(label) }
  label[field] = value
}
function moveLabel(index: number, delta: number) {
  const rows = [...labels.value], other = index + delta
  if (other < 0 || other >= rows.length) return
  if (c.value.labels.length + rows.filter(r => !c.value.labels.some(l => l.key === r.key)).length > 500) return
  const row = rows[index]!; rows[index] = rows[other]!; rows[other] = row
  rows.forEach((r, i) => { let saved = c.value.labels.find(l => l.key === r.key); if (!saved) { saved = { key: r.key, name: '', color: '', order: i }; c.value.labels.push(saved) } saved.order = i })
  c.value.sort = 'CUSTOM' as DashboardChart['sort']
}
function changeContents(ids: string[]) { c.value.filters = { ...clone(filters.value), contentIds: ids } }
</script>
<template>
  <el-dialog
    :model-value="true"
    append-to-body
    :show-close="false"
    :close-on-click-modal="false"
    width="calc(100vw - 64px)"
    top="32px"
    class="chart-editor"
    aria-label="组件设置"
    @close="emit('close')"
  >
    <template #header>
      <div class="chart-editor-heading">
        <span class="chart-editor-heading-icon"><ChartTypeIcon :type="c.type" /></span>
        <input
          v-model="draft.title"
          aria-label="组件名称"
          maxlength="80"
          :class="{ invalid: !draft.title.trim() }"
          @keydown.enter.prevent="($event.target as HTMLInputElement).blur()"
        >
        <el-button
          :icon="Close"
          class="chart-editor-close"
          text
          aria-label="关闭组件设置"
          @click="emit('close')"
        />
      </div>
    </template>
    <div class="chart-editor-body">
      <section class="chart-editor-preview">
        <el-alert
          v-if="queryError"
          :title="queryError"
          type="error"
          :closable="false"
        >
          <el-button
            text
            @click="emit('refresh')"
          >
            重试预览
          </el-button>
        </el-alert>
        <el-alert
          v-if="error"
          :title="error"
          type="warning"
          :closable="false"
        >
          <el-button
            text
            @click="emit('retry')"
          >
            重试保存
          </el-button>
        </el-alert>
        <div class="chart-preview-actions">
          <span class="chart-preview-label">图表预览</span>
          <el-button
            text
            :icon="List"
            @click="emit('select', draft)"
          >
            查看工作项
          </el-button>
        </div>
        <div class="chart-preview-canvas">
          <DashboardChartView
            :widget="draft"
            :result="result"
            :loading="loading"
            @select="emit('select', draft, $event)"
          />
        </div>
      </section>
      <aside class="chart-editor-settings">
        <div class="chart-settings-heading">
          <h2><el-icon><Setting /></el-icon>组件设置</h2>
        </div>
        <details
          open
          class="chart-setting-section"
        >
          <summary><span>图表类型</span><span class="chart-section-value">{{ chartTypes.find(t => t.value === c.type)?.label }}</span></summary>
          <div class="chart-type-grid">
            <button
              v-for="type in chartTypes"
              :key="type.value"
              :class="{ selected: c.type === type.value }"
              :aria-pressed="c.type === type.value"
              @click="setType(type.value)"
            >
              <ChartTypeIcon :type="type.value" /><span>{{ type.label }}</span>
            </button>
          </div>
        </details>
        <details
          v-if="c.type !== 'NUMBER'"
          open
          class="chart-setting-section"
        >
          <summary>{{ c.type === 'PIE' || c.type === 'DONUT' || c.type === 'BUBBLE' ? '分类' : '横轴 / 分类' }}</summary>
          <div class="chart-setting-content">
            <div class="chart-field">
              <span>字段</span><el-select
                v-model="c.dimension"
                filterable
                popper-class="chart-settings-select-menu"
                aria-label="分类字段"
              >
                <el-option
                  v-for="(label, key) in dimensions"
                  :key="key"
                  :value="key"
                  :label="label"
                />
              </el-select>
            </div>
            <div
              v-if="dated"
              class="chart-field"
            >
              <span>日期分组</span><el-select
                v-model="c.dateInterval"
                popper-class="chart-settings-select-menu"
                aria-label="日期分组"
              >
                <el-option
                  value="DAY"
                  label="日"
                /><el-option
                  value="WEEK"
                  label="周"
                /><el-option
                  value="MONTH"
                  label="月"
                />
              </el-select>
            </div>
            <div
              v-if="dated"
              class="chart-field"
            >
              <span>时区</span><el-select
                v-model="c.timezone"
                popper-class="chart-settings-select-menu"
                aria-label="时区"
              >
                <el-option
                  :value="c.timezone"
                  :label="c.timezone"
                /><el-option
                  v-if="c.timezone !== 'UTC'"
                  value="UTC"
                  label="UTC"
                /><el-option
                  v-if="c.timezone !== 'Asia/Shanghai'"
                  value="Asia/Shanghai"
                  label="Asia/Shanghai"
                />
              </el-select>
            </div>
            <div
              v-if="cartesian"
              class="chart-field"
            >
              <span>拆分系列</span><el-select
                v-model="c.series"
                filterable
                popper-class="chart-settings-select-menu"
                aria-label="拆分系列"
              >
                <el-option
                  value="NONE"
                  label="不拆分"
                /><el-option
                  v-for="(label, key) in dimensions"
                  :key="key"
                  :value="key"
                  :label="label"
                />
              </el-select>
            </div>
            <label
              v-if="cartesian && c.series !== 'NONE' && ['COLUMN', 'BAR', 'AREA'].includes(c.type) && additive(c)"
              class="chart-check"
            ><input
              v-model="c.stacked"
              type="checkbox"
            >堆叠系列</label>
          </div>
        </details>
        <details
          open
          class="chart-setting-section"
        >
          <summary>{{ c.type === 'BUBBLE' ? '坐标轴与大小' : cartesian ? '纵轴 / 数值' : '数值' }}</summary>
          <div class="chart-setting-content">
            <ChartMeasureControl
              v-if="c.type === 'BUBBLE' && c.xMeasure"
              v-model="c.xMeasure"
              label="横轴"
            />
            <ChartMeasureControl
              v-model="c.measure"
              :label="c.type === 'BUBBLE' ? '纵轴' : '统计指标'"
            />
            <ChartMeasureControl
              v-if="c.type === 'BUBBLE' && c.sizeMeasure"
              v-model="c.sizeMeasure"
              label="气泡大小"
            />
          </div>
        </details>
        <details
          v-if="c.type !== 'NUMBER'"
          class="chart-setting-section"
        >
          <summary>自定义外观</summary>
          <div class="chart-setting-content">
            <label
              v-if="c.type !== 'BUBBLE'"
              class="chart-check"
            ><input
              v-model="c.showLegend"
              type="checkbox"
            >显示图例</label>
            <label class="chart-check"><input
              v-model="c.showValues"
              type="checkbox"
            >显示数值 / 标签</label>
            <div
              v-if="c.type !== 'BUBBLE'"
              class="chart-field"
            >
              <span>数值显示</span><el-select
                v-model="c.valueFormat"
                popper-class="chart-settings-select-menu"
                aria-label="数值显示"
              >
                <el-option
                  value="VALUE"
                  label="数值"
                /><el-option
                  value="PERCENT"
                  label="百分比"
                />
              </el-select>
            </div>
            <div class="chart-field">
              <span>排序</span><el-select
                v-model="c.sort"
                popper-class="chart-settings-select-menu"
                aria-label="图表排序"
              >
                <el-option
                  value="VALUE_DESC"
                  label="数值从大到小"
                /><el-option
                  value="VALUE_ASC"
                  label="数值从小到大"
                /><el-option
                  value="NAME_ASC"
                  label="名称正序"
                /><el-option
                  value="NAME_DESC"
                  label="名称倒序"
                /><el-option
                  value="CUSTOM"
                  label="自定义顺序"
                />
              </el-select>
            </div>
            <label class="chart-check"><input
              v-model="limitEnabled"
              type="checkbox"
              @change="c.limit = ($event.target as HTMLInputElement).checked ? 10 : 0"
            >仅显示排序前 N 项</label>
            <label
              v-if="limitEnabled"
              class="chart-field"
            ><span>数量</span><input
              v-model.number="c.limit"
              type="number"
              min="1"
              max="5000"
              aria-label="显示数量"
              :aria-invalid="!validLimit"
            ></label>
            <span
              v-if="limitEnabled && !validLimit"
              role="alert"
            >请输入 1–5000 的整数</span>
            <label class="chart-check"><input
              v-model="c.showEmpty"
              type="checkbox"
            >显示空值分类</label>
            <div
              v-if="allLabels.length"
              class="chart-label-customization"
            >
              <div class="chart-field">
                <span>分类标签</span><el-button
                  :icon="EditPen"
                  :aria-expanded="labelsOpen"
                  @click="labelsOpen = !labelsOpen"
                >
                  编辑颜色、名称与顺序
                </el-button>
              </div>
              <div
                v-if="labelsOpen"
                class="chart-label-editor"
              >
                <el-input
                  v-model="labelSearch"
                  :prefix-icon="Search"
                  clearable
                  placeholder="查找分类"
                  aria-label="查找图表分类"
                />
                <div class="chart-label-list">
                  <div
                    v-for="(row, index) in labels"
                    :key="row.key"
                    class="chart-label-row"
                  >
                    <el-color-picker
                      :model-value="row.color"
                      color-format="hex"
                      :predefine="chartPalette"
                      popper-class="chart-settings-color-menu"
                      :aria-label="`${row.name}颜色`"
                      @change="setLabel(row.key, 'color', $event || '')"
                    />
                    <el-input
                      :model-value="row.name"
                      maxlength="80"
                      :aria-label="`${row.name}显示名称`"
                      @update:model-value="setLabel(row.key, 'name', $event)"
                    />
                    <el-button
                      :disabled="!row.customColor"
                      :icon="RefreshLeft"
                      text
                      :aria-label="`${row.name}恢复默认颜色`"
                      title="恢复默认颜色"
                      @click="setLabel(row.key, 'color', '')"
                    />
                    <el-button
                      :disabled="index === 0 || !!labelSearch"
                      :icon="ArrowUp"
                      text
                      :aria-label="`${row.name}上移`"
                      @click="moveLabel(index, -1)"
                    />
                    <el-button
                      :disabled="index === labels.length - 1 || !!labelSearch"
                      :icon="ArrowDown"
                      text
                      :aria-label="`${row.name}下移`"
                      @click="moveLabel(index, 1)"
                    />
                    <span class="chart-label-group">{{ row.group }}</span>
                  </div>
                  <p
                    v-if="!labels.length"
                    class="chart-settings-empty"
                  >
                    没有匹配的分类
                  </p>
                </div>
                <p class="chart-field-hint">
                  默认沿用标签颜色，可单独调整。
                </p>
              </div>
            </div>
          </div>
        </details>
        <details class="chart-setting-section">
          <summary>项目</summary>
          <div class="chart-setting-content chart-projects">
            <label class="chart-check"><input
              type="checkbox"
              :checked="c.projectIds == null"
              @change="c.projectIds = ($event.target as HTMLInputElement).checked ? null : []"
            >所有连接项目</label>
            <label
              v-for="project in projects"
              :key="project.id"
              class="chart-check"
            ><input
              type="checkbox"
              :disabled="!project.available"
              :checked="c.projectIds == null || c.projectIds.includes(project.id)"
              @change="toggleProject(project.id, $event)"
            >{{ project.name || '不可访问项目' }}</label>
          </div>
        </details>
        <details class="chart-setting-section">
          <summary>工作项类型与筛选</summary>
          <div class="chart-setting-content">
            <div class="chart-field">
              <span>工作项类型</span><el-select
                multiple
                filterable
                clearable
                collapse-tags
                collapse-tags-tooltip
                :max-collapse-tags="2"
                :model-value="filters.contentIds"
                popper-class="chart-settings-select-menu"
                placeholder="全部工作项类型"
                aria-label="工作项类型"
                @update:model-value="changeContents"
              >
                <el-option
                  v-for="option in contentOptions"
                  :key="option.key"
                  :value="option.key"
                  :label="`${option.label} · ${projects.find(p => p.id === option.projectId)?.name || ''}`"
                />
              </el-select>
            </div>
            <el-button
              :icon="Filter"
              @click="filterOpen = true"
            >
              设置组件筛选
            </el-button>
            <el-button
              v-if="c.filters"
              text
              @click="c.filters = null"
            >
              清除组件筛选
            </el-button>
          </div>
        </details>
        <details class="chart-setting-section">
          <summary>明细显示列</summary>
          <div class="chart-setting-content">
            <label class="chart-check"><input
              type="checkbox"
              checked
              disabled
            >工作项名称</label><label
              v-for="(label, key) in detailColumns"
              :key="key"
              class="chart-check"
            ><input
              v-model="c.detailColumns"
              type="checkbox"
              :value="key"
            >{{ label }}</label>
          </div>
        </details>
      </aside>
    </div>
    <DashboardFiltersDialog
      v-model="filterOpen"
      title="组件筛选"
      :filters="filters"
      :options="options"
      :projects="projects"
      @apply="applyFilters"
    />
  </el-dialog>
</template>
