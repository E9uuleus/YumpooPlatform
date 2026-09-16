<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, use, type ECharts, type EChartsCoreOption } from 'echarts/core'
import { BarChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, DataZoomComponent, GraphicComponent, AriaComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import type { DashboardBucket, DashboardConnection, DashboardWidget } from '@yumpoo/api-client'
import { duration, statusGroups } from './dashboardModel'
import { workItemLabelColorValue } from '../projects/workItemLabelColors'
use([BarChart, PieChart, GridComponent, TooltipComponent, LegendComponent, DataZoomComponent, GraphicComponent, AriaComponent, SVGRenderer])
const props = defineProps<{ widget: DashboardWidget; buckets: DashboardBucket[]; projects: DashboardConnection[] }>()
const emit = defineEmits<{ select: [value: { kind: string; keys: string[] }] }>()
const host = ref<HTMLElement>(); let chart: ECharts | undefined, observer: ResizeObserver | undefined, themeObserver: MutationObserver | undefined
const groups = computed(() => statusGroups(props.buckets, props.projects, props.widget.grouping))
const total = computed(() => props.buckets.find(b => b.kind === 'TOTAL')?.count || 0)
const empty = computed(() => !total.value || props.widget.kind === 'PROJECT_TIME' && !props.buckets.some(b => b.durationMs > 0))
function color(token: string) {
  const value = workItemLabelColorValue(token), match = value.match(/var\(([^)]+)\)/)
  return match ? getComputedStyle(document.documentElement).getPropertyValue(match[1]!).trim() || '#c4c4c4' : value
}
function render() {
  if (!host.value?.clientWidth || !host.value.clientHeight) return
  chart ||= init(host.value, undefined, { renderer: 'svg' })
  const textColor = getComputedStyle(host.value).color
  const common = { animationDuration: 300, textStyle: { fontFamily: 'Figtree, Microsoft YaHei, sans-serif', color: textColor }, tooltip: { trigger: 'item', renderMode: 'richText' }, aria: { enabled: true, label: { description: `${props.widget.title}，共 ${total.value} 个工作项。可点击图表查看明细。` } }, legend: { show: props.widget.showLegend, type: 'scroll', bottom: 0, textStyle: { color: textColor }, icon: 'circle', itemWidth: 9, itemHeight: 9 } }
  let option: EChartsCoreOption
  let selection: { kind: string; keys: string[] }[] = []
  if (props.widget.kind === 'STATUS') {
    const sorted = [...groups.value].sort((a, b) => props.widget.sort === 'ASC' ? a.count - b.count : b.count - a.count)
    selection = sorted.map(g => ({ kind: 'STATUS', keys: g.keys }))
    option = { ...common, graphic: [{ type: 'text', left: 'center', top: '34%', style: { text: String(total.value), fontSize: 32, fontWeight: 600, fill: textColor } }, { type: 'text', left: 'center', top: '55%', style: { text: '工作项', fontSize: 12, fill: textColor } }], series: [{ type: 'pie', radius: ['53%', '72%'], center: ['50%', '44%'], avoidLabelOverlap: true, itemStyle: { borderRadius: 3, borderWidth: 2, borderColor: getComputedStyle(host.value).getPropertyValue('--yp-bg-surface').trim() || '#fff' }, label: { show: props.widget.showValues, position: 'inside', formatter: (p: { percent: number }) => p.percent >= 5 ? `${Math.round(p.percent)}%` : '', color: '#fff', fontSize: 11 }, data: sorted.map(g => ({ name: g.name, value: g.count, itemStyle: { color: color(g.color) } })) }] }
  } else {
    const member = props.widget.kind === 'MEMBER_WORKLOAD', time = props.widget.kind === 'PROJECT_TIME'
    const rows = props.buckets.filter(b => b.kind === (member ? 'MEMBER' : 'PROJECT')).sort((a, b) => (props.widget.sort === 'ASC' ? 1 : -1) * ((time ? a.durationMs : a.count) - (time ? b.durationMs : b.count)))
    selection = rows.map(b => ({ kind: member ? 'MEMBER' : 'PROJECT', keys: [b.key] }))
    const names = rows.map(b => member ? b.label || '未分配' : props.projects.find(p => p.id === b.projectId)?.name || '项目')
    option = { ...common, legend: { ...common.legend, show: props.widget.showLegend && !member && !time }, grid: { left: 12, right: 36, top: 16, bottom: !member && !time && props.widget.showLegend ? 44 : 16, containLabel: true }, xAxis: { type: 'value', minInterval: time ? undefined : 1, axisLabel: { color: textColor, formatter: time ? (value: number) => `${value}h` : undefined }, splitLine: { lineStyle: { color: 'rgba(128,128,128,.12)' } } }, yAxis: { type: 'category', inverse: true, data: names, axisLabel: { width: 100, overflow: 'truncate', color: textColor }, axisLine: { show: false }, axisTick: { show: false } }, dataZoom: rows.length > 8 ? [{ type: 'slider', yAxisIndex: 0, right: 0, width: 10, startValue: 0, endValue: 7, filterMode: 'empty' }] : [], series: member || time ? [{ type: 'bar', barMaxWidth: 24, itemStyle: { color: time ? color('PURPLE') : color('BRIGHT_BLUE'), borderRadius: [0, 4, 4, 0] }, label: { show: props.widget.showValues, position: 'right', color: textColor, formatter: (p: { dataIndex: number }) => time ? duration(rows[p.dataIndex]!.durationMs) : String(rows[p.dataIndex]!.count) }, data: rows.map(b => time ? b.durationMs / 3600000 : b.count) }] : groups.value.map(g => ({ name: g.name, type: 'bar', stack: 'total', barMaxWidth: 26, itemStyle: { color: color(g.color) }, label: { show: props.widget.showValues, position: 'inside', color: '#fff', formatter: (p: { value: number }) => p.value || '' }, data: rows.map(row => props.buckets.filter(b => b.kind === 'STATUS' && b.projectId === row.projectId && g.keys.includes(b.key)).reduce((sum, b) => sum + b.count, 0)) })) }
  }
  chart.setOption(option, true); chart.off('click'); chart.on('click', params => {
    const index = params.dataIndex
    if (typeof index === 'number' && selection[index]) emit('select', selection[index]!)
  }); chart.resize()
}
watch(() => [props.widget, props.buckets, props.projects], render, { deep: true, flush: 'post' })
onMounted(() => { render(); observer = new ResizeObserver(() => { if (chart) chart.resize(); else render() }); if (host.value) observer.observe(host.value); themeObserver = new MutationObserver(render); themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'data-theme'] }) })
onBeforeUnmount(() => { observer?.disconnect(); themeObserver?.disconnect(); chart?.dispose() })
</script>
<template>
  <div class="dashboard-chart">
    <div
      ref="host"
      class="dashboard-chart__canvas"
      :class="{ 'is-empty': empty }"
      :aria-label="`${widget.title}，共 ${total} 个工作项`"
    />
    <div
      v-if="empty"
      class="dashboard-chart__empty"
    >
      <span class="empty-ring" /><strong>{{ widget.kind === 'PROJECT_TIME' && total ? '还没有计时记录' : '暂无匹配的工作项' }}</strong><span>{{ total ? '开始计时后，这里会展示实际投入' : '调整筛选或连接项目以查看数据' }}</span>
    </div>
  </div>
</template>
<style scoped>
.dashboard-chart{position:relative;width:100%;height:100%;min-height:180px;color:var(--yp-text-primary)}.dashboard-chart__canvas{width:100%;height:100%}.is-empty{visibility:hidden}.dashboard-chart__empty{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:10px;color:var(--yp-text-secondary);font-size:12px}.dashboard-chart__empty strong{font-weight:500;font-size:14px}.empty-ring{width:48px;height:48px;border:10px solid var(--yp-bg-sunken);border-radius:50%;margin-bottom:4px}
</style>
