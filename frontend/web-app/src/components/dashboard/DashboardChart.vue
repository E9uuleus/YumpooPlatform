<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, use, type ECharts, type EChartsCoreOption } from 'echarts/core'
import { BarChart, PieChart, LineChart, ScatterChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, DataZoomComponent, AriaComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import type { DashboardChartResult, DashboardChartSelection, DashboardWidget } from '@yumpoo/api-client'
import { additive, chartGroups, chartLabel, formatMeasure, resolveChart } from './chartModel'
import { metrics } from './dashboardModel'
import { chartColor, chartPalette } from './chartColors'

use([BarChart, PieChart, LineChart, ScatterChart, GridComponent, TooltipComponent, LegendComponent, DataZoomComponent, AriaComponent, SVGRenderer])
const props = defineProps<{ widget: DashboardWidget; result?: DashboardChartResult | undefined; loading?: boolean | undefined; selection?: DashboardChartSelection | undefined }>()
const emit = defineEmits<{ select: [selection?: DashboardChartSelection] }>()
const config = computed(() => resolveChart(props.widget)), groups = computed(() => chartGroups(config.value, props.result))
const host = ref<HTMLElement>()
const numeric = computed(() => config.value.type === 'NUMBER')
const numberValue = computed(() => formatMeasure(props.result?.points[0]?.value || 0, config.value.measure))
const empty = computed(() => !numeric.value && !groups.value.length)
let chart: ECharts | undefined, observer: ResizeObserver | undefined, themeObserver: MutationObserver | undefined
function render() {
  if (numeric.value) { chart?.dispose(); chart = undefined; return }
  if (!host.value?.clientWidth || !host.value.clientHeight) return
  chart ||= init(host.value, undefined, { renderer: 'svg' })
  const c = config.value, rows = groups.value, small = host.value.clientWidth < 320 || host.value.clientHeight < 230
  const text = getComputedStyle(host.value).color, background = getComputedStyle(host.value).getPropertyValue('--yp-bg-surface').trim() || '#fff'
  const split = !['PIE', 'DONUT', 'BUBBLE'].includes(c.type) && c.series !== 'NONE'
  const stacked = c.stacked && additive(c) && split && c.type !== 'LINE'
  const percent = c.valueFormat === 'PERCENT' && c.measure.metric !== 'COMPLETION_RATE'
  const total = rows.reduce((sum, row) => sum + row.value, 0)
  const scaled = (value: number) => percent ? total ? value / total * 100 : 0 : c.measure.metric === 'DURATION' ? value / 3600000 : value
  const axisFormat = (value: number) => percent || c.measure.metric === 'COMPLETION_RATE' ? `${Math.round(value)}%` : c.measure.metric === 'DURATION' ? `${Math.round(value * 10) / 10}h` : String(value)
  const shown = (value: number) => percent ? `${Math.round(scaled(value) * 10) / 10}%` : formatMeasure(value, c.measure)
  const common = { animationDuration: 180, color: chartPalette, textStyle: { color: text, fontFamily: 'Figtree, Microsoft YaHei, sans-serif' },
    aria: { enabled: true, label: { description: props.widget.title } }, tooltip: { trigger: 'item', renderMode: 'richText', confine: true },
    legend: { show: c.showLegend, type: 'scroll', bottom: 0, textStyle: { color: text, fontSize: small ? 10 : 12 }, icon: 'circle', itemWidth: 8, itemHeight: 8 } }
  let option: EChartsCoreOption
  let selections: (DashboardChartSelection | undefined)[][] = []
  if (c.type === 'PIE' || c.type === 'DONUT') {
    selections = [rows.map(r => ({ key: r.key }))]
    option = { ...common, series: [{ type: 'pie', radius: c.type === 'DONUT' ? ['48%', '72%'] : ['0%', '72%'], center: ['50%', c.showLegend ? '43%' : '50%'], minAngle: 1,
      itemStyle: { borderColor: background, borderWidth: 2, borderRadius: 3 },
      label: { show: c.showValues, position: 'inside', color: '#fff', fontSize: small ? 10 : 12,
        formatter: (p: { value: number; percent: number }) => p.percent < 5 ? '' : c.valueFormat === 'PERCENT' ? `${Math.round(p.percent)}%` : formatMeasure(p.value, c.measure) },
      tooltip: { formatter: (p: { name: string; value: number; percent: number }) => `${p.name}\n${formatMeasure(p.value, c.measure)} (${Math.round(p.percent * 10) / 10}%)` },
      data: rows.map((r, i) => ({ name: r.name, value: r.value, itemStyle: { color: chartColor(c.dimension, r.color, i) } })) }] }
  } else if (c.type === 'BUBBLE') {
    const x = c.xMeasure || c.measure, size = c.sizeMeasure || c.measure
    const maxSize = Math.max(1, ...rows.map(r => r.points[0]?.sizeValue || 0))
    selections = [rows.map(r => ({ key: r.key }))]
    option = { ...common, legend: { show: false }, grid: { left: 16, right: small ? 26 : 38, top: small ? 44 : 56, bottom: 32, containLabel: true },
      xAxis: { type: 'value', name: metrics[x.metric as keyof typeof metrics], nameLocation: 'middle', nameGap: 25, nameTextStyle: { color: text }, splitLine: { lineStyle: { color: '#8882' } }, axisLabel: { color: text, formatter: (v: number) => x.metric === 'DURATION' ? `${v}h` : x.metric === 'COMPLETION_RATE' ? `${v}%` : String(v) } },
      yAxis: { type: 'value', name: metrics[c.measure.metric as keyof typeof metrics], nameTextStyle: { color: text }, axisLabel: { color: text, formatter: (v: number) => c.measure.metric === 'DURATION' ? `${v}h` : c.measure.metric === 'COMPLETION_RATE' ? `${v}%` : String(v) }, splitLine: { lineStyle: { color: '#8882' } } },
      series: [{ type: 'scatter', symbolSize: (v: number[]) => 10 + Math.sqrt(Math.max(0, v[2] || 0) / maxSize) * (small ? 24 : 42),
        label: { show: c.showValues, formatter: '{b}', position: 'top', color: text, fontSize: small ? 10 : 12, width: small ? 80 : 120, overflow: 'truncate' },
        tooltip: { formatter: (p: { dataIndex: number }) => { const r = rows[p.dataIndex]!, v = r.points[0]!; return `${r.name}\n横轴：${formatMeasure(v.xValue, x)}\n纵轴：${formatMeasure(v.value, c.measure)}\n大小：${formatMeasure(v.sizeValue, size)}` } },
        data: rows.map((r, i) => ({ name: r.name, value: [x.metric === 'DURATION' ? (r.points[0]?.xValue || 0) / 3600000 : r.points[0]?.xValue || 0, c.measure.metric === 'DURATION' ? r.value / 3600000 : r.value, r.points[0]?.sizeValue || 0], itemStyle: { color: chartColor(c.dimension, r.color, i), opacity: .8 } })) }] }
  } else {
    const horizontal = c.type === 'BAR', line = c.type === 'LINE' || c.type === 'AREA'
    const categories = { type: 'category', data: rows.map(r => r.name), inverse: horizontal, axisTick: { show: false }, axisLine: { show: false }, axisLabel: { color: text, width: small ? 60 : 110, overflow: 'truncate', fontSize: small ? 10 : 12, hideOverlap: true } }
    const values = { type: 'value', axisLabel: { color: text, formatter: axisFormat, fontSize: small ? 10 : 12 }, splitLine: { lineStyle: { color: '#8882' } }, minInterval: c.measure.metric === 'DURATION' || percent || c.measure.metric === 'COMPLETION_RATE' ? undefined : 1 }
    const seriesKeys = split ? [...new Set(rows.flatMap(r => r.points.map(p => p.seriesKey)))] : ['all']
    if (c.sort === 'CUSTOM') seriesKeys.sort((a, b) => chartLabel(c, c.series, a, '').order - chartLabel(c, c.series, b, '').order)
    selections = seriesKeys.map(key => rows.map(r => r.points.some(p => p.seriesKey === key) || !split ? { key: r.key, ...(split ? { seriesKey: key } : {}) } : undefined))
    option = { ...common, legend: { ...common.legend, show: c.showLegend && split },
      grid: { left: small ? 4 : 12, right: c.showValues ? 38 : 16, top: 16, bottom: c.showLegend && split ? 48 : rows.length > 10 && !horizontal ? 30 : 10, containLabel: true },
      xAxis: horizontal ? values : categories, yAxis: horizontal ? categories : values,
      dataZoom: rows.length > (small ? 6 : 10) ? [{ type: 'slider', ...(horizontal ? { yAxisIndex: 0, right: 0, width: 8 } : { xAxisIndex: 0, bottom: c.showLegend && split ? 28 : 0, height: 8 }), startValue: 0, endValue: (small ? 6 : 10) - 1, filterMode: 'empty', showDetail: false, borderColor: 'transparent' }] : [],
      series: seriesKeys.map((key, i) => {
        const first = rows.flatMap(r => r.points).find(p => p.seriesKey === key), label = chartLabel(c, c.series, key, first?.seriesLabel || '', first?.seriesColorToken)
        const raw = rows.map(r => split ? r.points.find(p => p.seriesKey === key)?.value ?? null : r.value)
        return { name: split ? label.name : '', type: line ? 'line' : 'bar', ...(c.type === 'AREA' ? { areaStyle: { opacity: .2 } } : {}),
          stack: stacked ? 'total' : undefined, barMaxWidth: small ? 20 : 30, symbolSize: 6,
          itemStyle: { color: chartColor(c.series, label.color, i), borderRadius: line || stacked ? 0 : 3 },
          label: { show: c.showValues, position: stacked ? 'inside' : horizontal ? 'right' : 'top', color: stacked ? '#fff' : text, fontSize: small ? 10 : 12, formatter: (p: { dataIndex: number }) => raw[p.dataIndex] == null ? '' : shown(raw[p.dataIndex]!) },
          tooltip: { formatter: (p: { dataIndex: number }) => `${rows[p.dataIndex]?.name}${split ? ` · ${label.name}` : ''}\n${raw[p.dataIndex] == null ? '—' : formatMeasure(raw[p.dataIndex]!, c.measure)}` },
          data: raw.map((value, j) => ({ value: value == null ? null : scaled(value), ...(!split ? { itemStyle: { color: chartColor(c.dimension, rows[j]!.color) } } : {}) })) }
      }) }
  }
  if (props.selection) {
    const series = option.series as { data: { itemStyle?: Record<string, unknown> }[]; lineStyle?: Record<string, unknown>; areaStyle?: Record<string, unknown> }[]
    series.forEach((entry, i) => {
      entry.data.forEach((point, j) => {
        const value = selections[i]?.[j]
        const selected = value?.key === props.selection?.key && (!props.selection?.seriesKey || value?.seriesKey === props.selection.seriesKey)
        point.itemStyle = { ...point.itemStyle, opacity: selected ? 1 : .22, ...(selected ? { borderWidth: 2, borderColor: text } : {}) }
      })
      if (entry.lineStyle || ['LINE', 'AREA'].includes(c.type)) entry.lineStyle = { ...entry.lineStyle, opacity: .35 }
    })
  }
  chart.setOption(option, true); chart.off('click'); chart.on('click', event => { const selection = selections[event.seriesIndex || 0]?.[event.dataIndex]; if (selection) emit('select', selection) }); chart.resize()
}
watch(() => [props.widget, props.result, props.selection], render, { deep: true, flush: 'post' })
onMounted(() => { render(); observer = new ResizeObserver(render); if (host.value) observer.observe(host.value); themeObserver = new MutationObserver(render); themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'data-theme'] }) })
onBeforeUnmount(() => { observer?.disconnect(); themeObserver?.disconnect(); chart?.dispose() })
</script>
<template>
  <div
    class="dashboard-chart"
    :class="{ 'dashboard-chart--number': numeric }"
    :aria-busy="loading"
  >
    <button
      v-if="numeric"
      class="chart-number"
      :aria-label="`${widget.title} ${numberValue}，查看明细`"
      @click="emit('select')"
    >
      {{ numberValue }}
    </button>
    <div
      ref="host"
      class="dashboard-chart__canvas"
      :class="{ 'is-empty': empty || numeric }"
      :aria-label="widget.title"
    />
    <div
      v-if="empty"
      class="dashboard-chart__empty"
    >
      {{ loading ? '正在加载…' : '暂无匹配的工作项' }}
    </div>
  </div>
</template>
<style scoped>
.dashboard-chart{position:relative;width:100%;height:100%;min-height:0;min-width:0;color:var(--yp-text-primary);container-type:inline-size}.dashboard-chart__canvas{width:100%;height:100%}.is-empty{visibility:hidden}.dashboard-chart__empty{position:absolute;inset:0;display:grid;place-items:center;color:var(--yp-text-secondary);font-size:13px}.chart-number{position:absolute;inset:0;width:100%;border:0;background:none;color:inherit;font:600 clamp(24px,16cqw,58px)/1.2 var(--yp-font-family);letter-spacing:-1px;cursor:pointer;text-align:center;padding:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.chart-number:focus-visible{outline:2px solid var(--yp-action-primary);outline-offset:-4px}
</style>
