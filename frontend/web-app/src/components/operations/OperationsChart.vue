<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, use, type ECharts } from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  MarkLineComponent,
  BrushComponent,
  AriaComponent,
} from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import { metricNames, metric } from './operationsPresentation'
use([
  LineChart,
  BarChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  MarkLineComponent,
  BrushComponent,
  AriaComponent,
  SVGRenderer,
])
const props = defineProps<{
  title: string
  times: Date[]
  series: { key: string; unit: string; values: (number | null)[] }[]
  restarts?: Date[] | undefined
  compact?: boolean
  bars?: boolean
  threshold?: number | undefined
  zoom?: boolean
}>()
const emit = defineEmits<{ range: [from: Date, to: Date] }>()
const host = ref<HTMLElement>()
let chart: ECharts | undefined,
  resize: ResizeObserver | undefined,
  theme: MutationObserver | undefined
function render() {
  if (!host.value?.clientWidth) return
  chart ||= init(host.value, undefined, { renderer: 'svg' })
  const style = getComputedStyle(host.value),
    color = (name: string) => style.getPropertyValue(name).trim()
  const compact = props.compact
  // 直方图与原型一致：只保留底部时间轴，数值读 tooltip 与级别计数，不画纵轴与辅助线。
  const histogram = props.bars
  const primary = color('--yp-action-primary')
  const translucent = (value: string, alpha: string) =>
    /^#[0-9a-f]{6}$/i.test(value) ? value + alpha : value
  chart.setOption(
    {
      animation: false,
      color: [
        '--yp-action-primary',
        '--yp-status-red',
        '--yp-status-teal',
        '--yp-status-purple',
      ].map(color),
      aria: { enabled: true, label: { description: props.title } },
      tooltip: { trigger: 'axis', renderMode: 'richText', confine: true },
      grid: histogram
        ? { left: 2, right: 2, top: 6, bottom: 22 }
        : {
            left: compact ? 0 : 58,
            right: compact ? 0 : 20,
            top: compact ? 4 : 20,
            bottom: compact ? 0 : 48,
          },
      legend: {
        show: !compact && !histogram,
        bottom: 0,
        textStyle: { color: style.color },
        type: 'scroll',
      },
      xAxis: {
        type: 'time',
        show: !compact,
        axisLabel: { color: style.color, hideOverlap: true, fontSize: histogram ? 11 : 12 },
        axisTick: { show: !histogram },
        axisLine: { lineStyle: { color: color('--yp-border-default') } },
        splitLine: { show: false },
      },
      yAxis: {
        type: 'value',
        show: !compact && !histogram,
        axisLabel: {
          color: style.color,
          formatter: (value: number) => metric(value, props.series[0]?.unit),
        },
        splitLine: {
          show: !histogram,
          lineStyle: { color: color('--yp-border-subtle') },
        },
      },
      ...(props.zoom
        ? {
            brush: {
              xAxisIndex: 0,
              brushType: 'lineX',
              brushMode: 'single',
              transformable: false,
              brushStyle: {
                color: translucent(primary, '29'),
                borderColor: primary,
                borderWidth: 1,
              },
            },
          }
        : {}),
      series: props.series.map((series, index) => ({
        name: metricNames[series.key] ?? series.key,
        type: props.bars ? 'bar' : 'line',
        showSymbol: false,
        connectNulls: false,
        ...(props.bars
          ? {
              stack: 'levels',
              itemStyle: {
                color: color(
                  (
                    {
                      ERROR: '--yp-status-red',
                      WARN: '--yp-status-yellow',
                      INFO: '--yp-action-primary',
                      DEBUG: '--yp-status-gray',
                      TRACE: '--yp-text-disabled',
                    } as Record<string, string>
                  )[series.key] ?? '--yp-action-primary',
                ),
              },
            }
          : {}),
        lineStyle: {
          width: compact ? 1.5 : 2,
          type: series.key.endsWith('.max') ? 'dashed' : 'solid',
        },
        ...(compact ? { areaStyle: { opacity: 0.1 } } : {}),
        data: props.times.map((at, i) => [at.getTime(), series.values[i] ?? null]),
        markLine: index
          ? undefined
          : {
              silent: true,
              symbol: 'none',
              label: { color: style.color },
              data: [
                ...(props.restarts ?? []).map((at) => ({
                  xAxis: at.getTime(),
                  label: { formatter: '重启' },
                })),
                ...(props.threshold == null
                  ? []
                  : [{ yAxis: props.threshold, label: { formatter: '警告阈值' } }]),
              ],
            },
      })),
    },
    true,
  )
  chart.off('brushEnd')
  if (!props.zoom) return
  // 在柱状图上直接拖拽框选时间范围（原型交互），选中后清掉框选区域。
  chart.dispatchAction({
    type: 'takeGlobalCursor',
    key: 'brush',
    brushOption: { brushType: 'lineX', brushMode: 'single' },
  })
  chart.on('brushEnd', (event: unknown) => {
    const range = (event as { areas?: { coordRange?: number[] }[] }).areas?.[0]?.coordRange
    if (range?.length === 2 && range[1]! > range[0]!) {
      emit('range', new Date(range[0]!), new Date(range[1]!))
    }
    chart?.dispatchAction({ type: 'brush', areas: [] })
  })
}
watch(() => [props.times, props.series, props.restarts, props.threshold], render, {
  deep: true,
  flush: 'post',
})
onMounted(() => {
  render()
  resize = new ResizeObserver(() => {
    chart?.resize()
    render()
  })
  if (host.value) resize.observe(host.value)
  theme = new MutationObserver(render)
  theme.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ['class', 'data-theme'],
  })
})
onBeforeUnmount(() => {
  resize?.disconnect()
  theme?.disconnect()
  chart?.dispose()
})
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
.operations-chart {
  height: 220px;
  min-width: 0;
  width: 100%;
  color: var(--yp-text-secondary);
}
.compact {
  height: 44px;
}
</style>
