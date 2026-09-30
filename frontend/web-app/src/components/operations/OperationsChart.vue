<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElTooltip, type TooltipInstance } from 'element-plus'
import { init, use, type ECharts } from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  ToolboxComponent,
  LegendComponent,
  MarkLineComponent,
  BrushComponent,
  AriaComponent,
} from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import { formatOperationsTimeRange, metricNames, metric } from './operationsPresentation'
use([
  LineChart,
  BarChart,
  GridComponent,
  TooltipComponent,
  ToolboxComponent,
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
  colorToken?: string
  bars?: boolean
  threshold?: number | undefined
  zoom?: boolean
  bucketSeconds?: number | undefined
  utc?: boolean
}>()
const emit = defineEmits<{ range: [from: Date, to: Date] }>()
const host = ref<HTMLElement>()
const tooltip = ref<TooltipInstance>()
const hovered = ref<{ key: string; count: number; time: string; color: string }>()
let pointer = { x: 0, y: 0 }, dragging = false
const hoverTarget = {
  getBoundingClientRect: () => new DOMRect(pointer.x, pointer.y, 0, 0),
}
let chart: ECharts | undefined,
  resize: ResizeObserver | undefined,
  theme: MutationObserver | undefined
function hideTooltip() {
  hovered.value = undefined
}
function hover(event: unknown) {
  const item = event as {
    componentType?: string
    seriesType?: string
    seriesIndex?: number
    dataIndex?: number
    color?: unknown
    event?: { offsetX: number; offsetY: number }
  }
  if (!props.bars || dragging || item.componentType !== 'series' || item.seriesType !== 'bar' ||
      item.seriesIndex == null || item.dataIndex == null || !item.event || !host.value) {
    hideTooltip()
    return
  }
  const series = props.series[item.seriesIndex], at = props.times[item.dataIndex]
  const count = series?.values[item.dataIndex]
  if (!series || !at || count == null || count <= 0) {
    hideTooltip()
    return
  }
  const seconds = props.bucketSeconds ??
    ((props.times[item.dataIndex + 1]?.getTime() ?? at.getTime() + 60000) - at.getTime()) / 1000
  const bounds = host.value.getBoundingClientRect()
  pointer = { x: bounds.left + item.event.offsetX, y: bounds.top + item.event.offsetY }
  hovered.value = {
    key: series.key,
    count,
    time: formatOperationsTimeRange(at, new Date(at.getTime() + seconds * 1000), props.utc),
    color: typeof item.color === 'string' ? item.color : 'currentColor',
  }
  void nextTick(() => tooltip.value?.updatePopper())
}
function startDrag() {
  if (props.zoom) dragging = true
  hideTooltip()
}
function endDrag() {
  dragging = false
  hideTooltip()
}
function render() {
  hideTooltip()
  if (!host.value?.clientWidth) return
  if (!chart) {
    chart = init(host.value, undefined, { renderer: 'svg' })
    chart.on('mousemove', hover)
    chart.on('mouseout', hideTooltip)
    chart.on('globalout', endDrag)
    chart.getZr().on('mousedown', startDrag)
    chart.getZr().on('mouseup', endDrag)
  }
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
      color: (props.colorToken ? [props.colorToken] : [
        '--yp-action-primary',
        '--yp-status-red',
        '--yp-status-teal',
        '--yp-status-purple',
      ]).map(color),
      aria: { enabled: true, label: { description: props.title } },
      tooltip: compact || histogram ? { show: false } :
        { trigger: 'axis', renderMode: 'richText', confine: true },
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
            toolbox: { show: false },
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
        silent: !!compact,
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
    endDrag()
    const range = (event as { areas?: { coordRange?: number[] }[] }).areas?.[0]?.coordRange
    if (range?.length === 2 && range[1]! > range[0]!) {
      emit('range', new Date(range[0]!), new Date(range[1]!))
    }
    chart?.dispatchAction({ type: 'brush', areas: [] })
  })
}
watch(() => [props.times, props.series, props.restarts, props.threshold, props.bars, props.colorToken,
  props.zoom, props.compact, props.bucketSeconds, props.utc], render, {
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
  hideTooltip()
  resize?.disconnect()
  theme?.disconnect()
  chart?.off('mousemove', hover)
  chart?.off('mouseout', hideTooltip)
  chart?.off('globalout', endDrag)
  chart?.getZr().off('mousedown', startDrag)
  chart?.getZr().off('mouseup', endDrag)
  chart?.dispose()
})
</script>
<template>
  <div
    class="operations-chart"
    :class="{ compact }"
    :aria-label="title"
  >
    <div
      ref="host"
      class="operations-chart__canvas"
    />
    <el-tooltip
      v-if="bars"
      ref="tooltip"
      :visible="!!hovered"
      :virtual-ref="hoverTarget"
      virtual-triggering
      effect="dark"
      placement="top"
      :enterable="false"
      :popper-style="{ pointerEvents: 'none' }"
      popper-class="ops-chart-tooltip"
    >
      <template #content>
        <template v-if="hovered">
          <div class="ops-chart-tooltip__time">
            {{ hovered.time }}
          </div>
          <div class="ops-chart-tooltip__value">
            <i
              :style="{ background: hovered.color }"
              aria-hidden="true"
            />
            <b>{{ hovered.key }}</b>
            <span>{{ hovered.count.toLocaleString('zh-CN') }} 条</span>
          </div>
        </template>
      </template>
    </el-tooltip>
  </div>
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
.operations-chart__canvas {
  height: 100%;
  width: 100%;
}
:global(.ops-chart-tooltip) {
  --yp-text-primary: var(--yp-bg-tooltip);
  --yp-text-inverse: var(--yp-text-tooltip);
}
.ops-chart-tooltip__time {
  margin-bottom: var(--yp-space-1);
  font-variant-numeric: tabular-nums;
}
.ops-chart-tooltip__value {
  display: flex;
  align-items: center;
  gap: var(--yp-space-2);
}
.ops-chart-tooltip__value i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.ops-chart-tooltip__value span {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}
</style>
