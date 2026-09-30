import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import OperationsChart from './OperationsChart.vue'

const charts = vi.hoisted(() => {
  const events = new Map<string, (event?: unknown) => void>()
  const pointerEvents = new Map<string, (event?: unknown) => void>()
  const zr = {
    on: vi.fn((name: string, handler: (event?: unknown) => void) => pointerEvents.set(name, handler)),
    off: vi.fn((name: string) => pointerEvents.delete(name)),
  }
  return {
    events, pointerEvents, zr,
    chart: {
      setOption: vi.fn(), dispatchAction: vi.fn(), resize: vi.fn(), dispose: vi.fn(),
      getZr: () => zr,
      on: vi.fn((name: string, handler: (event?: unknown) => void) => events.set(name, handler)),
      off: vi.fn((name: string) => events.delete(name)),
    },
  }
})
vi.mock('echarts/core', () => ({ init: () => charts.chart, use: vi.fn() }))

const props = {
  title: '日志分布',
  times: [new Date('2026-09-30T08:50:00Z')],
  series: [
    { key: 'INFO', unit: 'COUNT', values: [5] },
    { key: 'WARN', unit: 'COUNT', values: [2] },
    { key: 'ERROR', unit: 'COUNT', values: [1] },
  ],
  bars: true, zoom: true, bucketSeconds: 60, utc: true,
}
function mountChart(overrides = {}) {
  return mount(OperationsChart, {
    props: { ...props, ...overrides },
    global: {
      stubs: {
        ElTooltip: {
          props: ['visible', 'virtualRef', 'effect', 'enterable'],
          methods: { updatePopper() {} },
          template: '<span v-if="visible" role="tooltip"><slot name="content" /></span>',
        },
      },
    },
  })
}
function hover(seriesIndex: number) {
  charts.events.get('mousemove')!({
    componentType: 'series', seriesType: 'bar', seriesIndex, dataIndex: 0,
    color: '#ffcc00', event: { offsetX: 50, offsetY: 10 },
  })
}
beforeEach(() => {
  charts.events.clear()
  charts.pointerEvents.clear()
  Object.values(charts.chart).forEach((value) => { if (vi.isMockFunction(value)) value.mockClear() })
  charts.zr.on.mockClear()
  charts.zr.off.mockClear()
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(600)
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('日志直方图气泡', () => {
  it('只展示鼠标所在色块的级别、数量和时间桶', async () => {
    const wrapper = mountChart()
    expect(charts.chart.setOption.mock.lastCall![0].tooltip.show).toBe(false)
    expect(charts.chart.setOption.mock.lastCall![0].toolbox.show).toBe(false)
    hover(1)
    await flushPromises()
    expect(wrapper.get('[role="tooltip"]').text()).toContain('08:50–08:51')
    expect(wrapper.get('[role="tooltip"]').text()).toContain('WARN2 条')
    expect(wrapper.get('[role="tooltip"]').text()).not.toMatch(/INFO|ERROR/)
    hover(0)
    await flushPromises()
    expect(wrapper.get('[role="tooltip"]').text()).toContain('INFO5 条')
    expect(wrapper.get('[role="tooltip"]').text()).not.toContain('WARN')
    charts.events.get('mouseout')!()
    await flushPromises()
    expect(wrapper.find('[role="tooltip"]').exists()).toBe(false)
    wrapper.unmount()
    expect(charts.chart.dispose).toHaveBeenCalledOnce()
    expect(charts.pointerEvents.size).toBe(0)
  })
  it('拖拽期间隐藏气泡，框选仍发出时间范围；数据更新清除旧提示', async () => {
    const wrapper = mountChart()
    hover(0)
    await flushPromises()
    charts.pointerEvents.get('mousedown')!()
    hover(1)
    await flushPromises()
    expect(wrapper.find('[role="tooltip"]').exists()).toBe(false)
    const from = new Date('2026-09-30T08:50:10Z'), to = new Date('2026-09-30T08:50:40Z')
    charts.events.get('brushEnd')!({ areas: [{ coordRange: [from.getTime(), to.getTime()] }] })
    expect(wrapper.emitted('range')).toEqual([[from, to]])
    expect(charts.chart.dispatchAction).toHaveBeenLastCalledWith({ type: 'brush', areas: [] })
    hover(2)
    await flushPromises()
    expect(wrapper.get('[role="tooltip"]').text()).toContain('ERROR1 条')
    await wrapper.setProps({ series: [{ key: 'INFO', unit: 'COUNT', values: [6] }] })
    expect(wrapper.find('[role="tooltip"]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('空白、零数量和离开图表不显示气泡，短时间桶显示秒', async () => {
    const wrapper = mountChart({
      series: [{ key: 'INFO', unit: 'COUNT', values: [0] }], bucketSeconds: 30,
    })
    hover(0)
    await flushPromises()
    expect(wrapper.find('[role="tooltip"]').exists()).toBe(false)
    await wrapper.setProps({ series: [{ key: 'WARN', unit: 'COUNT', values: [3] }] })
    hover(0)
    await flushPromises()
    expect(wrapper.get('[role="tooltip"]').text()).toContain('08:50:00–08:50:30')
    charts.events.get('mousemove')!({ componentType: 'xAxis' })
    await flushPromises()
    expect(wrapper.find('[role="tooltip"]').exists()).toBe(false)
    hover(0)
    charts.events.get('globalout')!()
    await flushPromises()
    expect(wrapper.find('[role="tooltip"]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('折线图保持原有轴向 Tooltip', () => {
    const wrapper = mountChart({ bars: false, zoom: false })
    expect(charts.chart.setOption.mock.lastCall![0].tooltip).toEqual({
      trigger: 'axis', renderMode: 'richText', confine: true,
    })
    expect(wrapper.findComponent({ name: 'ElTooltip' }).exists()).toBe(false)
    wrapper.unmount()
  })
  it('概览小图关闭悬停交互并随主题与配色变化重新取色', async () => {
    const colors: Record<string, string> = {
      '--yp-status-purple': '#943db8',
      '--yp-status-teal': '#00a5a5',
    }
    vi.spyOn(window, 'getComputedStyle').mockReturnValue({
      color: '#323338', getPropertyValue: (name: string) => colors[name] ?? '',
    } as CSSStyleDeclaration)
    const wrapper = mountChart({
      bars: false, zoom: false, compact: true, colorToken: '--yp-status-purple',
    })
    const option = () => charts.chart.setOption.mock.lastCall![0]
    expect(option().tooltip.show).toBe(false)
    expect(option().series.every((series: { silent: boolean }) => series.silent)).toBe(true)
    expect(option().color).toEqual(['#943db8'])
    expect(wrapper.find('[role="tooltip"]').exists()).toBe(false)
    colors['--yp-status-purple'] = '#b96ad8'
    document.documentElement.setAttribute('data-theme', 'dark')
    await flushPromises()
    expect(option().color).toEqual(['#b96ad8'])
    await wrapper.setProps({ colorToken: '--yp-status-teal' })
    expect(option().color).toEqual(['#00a5a5'])
    wrapper.unmount()
    document.documentElement.removeAttribute('data-theme')
  })
})
