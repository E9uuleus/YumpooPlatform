<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, use, type ECharts } from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, MarkLineComponent, DataZoomComponent, AriaComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import { metricNames, metric } from './operationsPresentation'
use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, MarkLineComponent, DataZoomComponent, AriaComponent, SVGRenderer])
const props = defineProps<{ title: string; times: Date[]; series: { key: string; unit: string; values: (number | null)[] }[]; restarts?: Date[] | undefined; compact?: boolean; bars?: boolean; threshold?: number | undefined; zoom?: boolean }>()
const emit = defineEmits<{ range: [from: Date, to: Date] }>()
const host = ref<HTMLElement>()
let chart: ECharts | undefined, resize: ResizeObserver | undefined, theme: MutationObserver | undefined
function render() {
  if (!host.value?.clientWidth) return
  chart ||= init(host.value, undefined, { renderer: 'svg' })
  const style = getComputedStyle(host.value), color = (name: string) => style.getPropertyValue(name).trim()
  const compact = props.compact
  chart.setOption({
    animation: false, color: ['--yp-action-primary', '--yp-status-red', '--yp-status-teal', '--yp-status-purple'].map(color),
    aria: { enabled: true, label: { description: props.title } },
    tooltip: { trigger: 'axis', renderMode: 'richText', confine: true },
    grid: { left: compact ? 0 : 58, right: compact ? 0 : 20, top: compact ? 4 : 20, bottom: compact ? 0 : props.zoom ? 72 : 48 },
    legend: { show: !compact, bottom: 0, textStyle: { color: style.color }, type: 'scroll' },
    xAxis: { type: 'time', show: !compact, axisLabel: { color: style.color } },
    yAxis: { type: 'value', show: !compact, axisLabel: { color: style.color, formatter: (value: number) => metric(value, props.series[0]?.unit) }, splitLine: { lineStyle: { color: color('--yp-border-subtle') } } },
    dataZoom: props.zoom ? [{ type: 'slider', bottom: 26, height: 18, realtime: false }] : [],
    series: props.series.map((series, index) => ({
      name: metricNames[series.key] ?? series.key, type: props.bars ? 'bar' : 'line', showSymbol: false, connectNulls: false,
      ...(props.bars ? { stack: 'levels', itemStyle: { color: color(({ ERROR: '--yp-status-red', WARN: '--yp-status-yellow', INFO: '--yp-action-primary', DEBUG: '--yp-status-teal', TRACE: '--yp-text-muted' } as Record<string,string>)[series.key] ?? '--yp-action-primary') } } : {}),
      lineStyle: { width: compact ? 1.5 : 2, type: series.key.endsWith('.max') ? 'dashed' : 'solid' },
      ...(compact ? { areaStyle: { opacity: .1 } } : {}),
      data: props.times.map((at, i) => [at.getTime(), series.values[i] ?? null]),
      markLine: index ? undefined : { silent: true, symbol: 'none', label: { color: style.color }, data: [
        ...(props.restarts ?? []).map(at => ({ xAxis: at.getTime(), label: { formatter: '重启' } })),
        ...(props.threshold == null ? [] : [{ yAxis: props.threshold, label: { formatter: '警告阈值' } }]),
      ] },
    })),
  }, true)
  chart.off('datazoom')
  chart.on('datazoom', (event: unknown) => {
    const zoom = event as { start?: number; end?: number }
    const first = props.times[0]?.getTime(), last = props.times.at(-1)?.getTime()
    if (first != null && last != null && zoom.start != null && zoom.end != null)
      emit('range', new Date(first + (last - first) * zoom.start / 100), new Date(first + (last - first) * zoom.end / 100))
  })
}
watch(() => [props.times, props.series, props.restarts, props.threshold], render, { deep: true, flush: 'post' })
onMounted(() => {
  render(); resize = new ResizeObserver(() => { chart?.resize(); render() }); if (host.value) resize.observe(host.value)
  theme = new MutationObserver(render); theme.observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'data-theme'] })
})
onBeforeUnmount(() => { resize?.disconnect(); theme?.disconnect(); chart?.dispose() })
</script>
<template>
  <div
    ref="host"
    class="operations-chart"
    :class="{ compact }"
    :aria-label="title"
  />
</template>
<style scoped>
.operations-chart{height:220px;min-width:0;width:100%;color:var(--yp-text-secondary)}.compact{height:44px}
</style>

