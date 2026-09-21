import type { DashboardChart, DashboardChartMeasure, DashboardChartPoint, DashboardChartResult, DashboardWidget } from '@yumpoo/api-client'
import { duration } from './dashboardModel'

export const chartTypes = [
  { value: 'NUMBER', label: '指标' }, { value: 'COLUMN', label: '柱形图' }, { value: 'BAR', label: '条形图' },
  { value: 'LINE', label: '折线图' }, { value: 'AREA', label: '面积图' }, { value: 'PIE', label: '饼图' },
  { value: 'DONUT', label: '环图' }, { value: 'BUBBLE', label: '气泡图' },
] as const
export const dimensions = { PROJECT: '项目', CONTENT: '工作项类型', STATUS: '状态', CATEGORY: '状态分类', PRIORITY: '优先级', ASSIGNEE: '处理人', REPORTER: '报告人', CREATED: '创建日期', UPDATED: '更新日期', COMPLETED: '完成日期', DUE: '截止日期', TIMELINE_START: '计划开始日期', TIMELINE_END: '计划结束日期' }
export const calculations = { SUM: '合计', AVG: '平均值', MEDIAN: '中位数', MIN: '最小值', MAX: '最大值' }
export const detailColumns = { project: '项目', content: '工作项类型', assignee: '处理人', reporter: '报告人', status: '状态', priority: '优先级', duration: '实际耗时', due: '截止日期', timeline: '计划时间', created: '创建时间', updated: '更新时间', completed: '完成时间' }
export const dateDimensions = new Set(['CREATED', 'UPDATED', 'COMPLETED', 'DUE', 'TIMELINE_START', 'TIMELINE_END'])
export function defaultChart(): DashboardChart {
  return { type: 'COLUMN', dimension: 'STATUS', series: 'NONE', dateInterval: 'MONTH', timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
    measure: { metric: 'TOTAL', calculation: 'SUM' }, xMeasure: { metric: 'TOTAL', calculation: 'SUM' }, sizeMeasure: { metric: 'TOTAL', calculation: 'SUM' },
    stacked: false, showLegend: true, showValues: true, valueFormat: 'VALUE', sort: 'VALUE_DESC', limit: 0, showEmpty: true,
    projectIds: null, filters: null, labels: [], detailColumns: ['project', 'content', 'assignee', 'status', 'duration'] } as DashboardChart
}
export function resolveChart(widget: DashboardWidget): DashboardChart {
  if (widget.chart) return widget.chart
  const c = defaultChart()
  c.type = (widget.kind === 'METRIC' ? 'NUMBER' : widget.kind === 'STATUS' ? 'DONUT' : 'BAR') as DashboardChart['type']
  c.dimension = (widget.kind === 'METRIC' ? 'STATUS' : widget.kind === 'STATUS' ? widget.grouping : widget.kind === 'MEMBER_WORKLOAD' ? 'ASSIGNEE' : 'PROJECT') as DashboardChart['dimension']
  c.measure.metric = (widget.kind === 'METRIC' ? widget.metric : widget.kind === 'PROJECT_TIME' ? 'DURATION' : 'TOTAL') as DashboardChartMeasure['metric']
  c.series = (widget.kind === 'PROJECT_WORKLOAD' ? widget.grouping : 'NONE') as DashboardChart['series']
  c.stacked = widget.kind === 'PROJECT_WORKLOAD'; c.showLegend = widget.showLegend; c.showValues = widget.showValues
  c.sort = (widget.sort === 'ASC' ? 'VALUE_ASC' : 'VALUE_DESC') as DashboardChart['sort']
  c.valueFormat = (widget.kind === 'STATUS' ? 'PERCENT' : 'VALUE') as DashboardChart['valueFormat']
  return c
}
export function chartLabelKey(dimension: string, key: string) { return `${dimension}:${key}` }
export function chartLabel(c: DashboardChart, dimension: string, key: string, fallback: string, colorToken = '') {
  const custom = c.labels.find(l => l.key === chartLabelKey(dimension, key))
  return { name: custom?.name || fallback, color: custom?.color || colorToken, order: custom?.order ?? 5000 }
}
export function formatMeasure(value: number, measure: DashboardChartMeasure) {
  return measure.metric === 'DURATION' ? duration(value) : measure.metric === 'COMPLETION_RATE' ? `${Math.round(value * 10) / 10}%` : value.toLocaleString('zh-CN', { maximumFractionDigits: 1 })
}
export function additive(c: DashboardChart) { return c.measure.metric !== 'COMPLETION_RATE' && (c.measure.metric !== 'DURATION' || c.measure.calculation === 'SUM') }
export interface ChartGroup { key: string; name: string; color: string; order: number; value: number; points: DashboardChartPoint[] }
export function chartGroups(c: DashboardChart, result?: DashboardChartResult): ChartGroup[] {
  const map = new Map<string, ChartGroup>()
  for (const point of result?.points || []) {
    let group = map.get(point.key)
    if (!group) { const label = chartLabel(c, c.dimension, point.key, point.label); group = { key: point.key, ...label, color: label.color || point.colorToken, value: 0, points: [] }; map.set(point.key, group) }
    group.value = point.categoryValue ?? group.value + point.value; group.points.push(point)
  }
  const groups = [...map.values()]
  groups.sort((a, b) => c.sort === 'VALUE_ASC' ? a.value - b.value || a.name.localeCompare(b.name, 'zh-CN')
    : c.sort === 'VALUE_DESC' ? b.value - a.value || a.name.localeCompare(b.name, 'zh-CN')
      : c.sort === 'NAME_DESC' ? b.name.localeCompare(a.name, 'zh-CN') : c.sort === 'CUSTOM' ? a.order - b.order
        : a.name.localeCompare(b.name, 'zh-CN'))
  return c.limit ? groups.slice(0, c.limit) : groups
}
