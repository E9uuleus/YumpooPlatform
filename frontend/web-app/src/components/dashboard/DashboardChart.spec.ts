import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DashboardChart, DashboardChartResult } from '@yumpoo/api-client'
import DashboardChartView from './DashboardChart.vue'
import { newWidget } from './dashboardModel'

const renderer = vi.hoisted(() => ({ setOption: vi.fn(), on: vi.fn(), off: vi.fn(), resize: vi.fn(), dispose: vi.fn() }))
vi.mock('echarts/core', () => ({ init: () => renderer, use: vi.fn() }))
beforeEach(() => {
  vi.clearAllMocks()
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(800)
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(360)
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  document.documentElement.style.setProperty('--yp-label-green', '#00c875')
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); document.documentElement.style.removeProperty('--yp-label-green') })
describe('八种图表的展开选择', () => {
  it.each(['COLUMN', 'BAR', 'LINE', 'AREA', 'PIE', 'DONUT', 'BUBBLE'])('%s uses the source label color and respects a saved override', async type => {
    const widget = newWidget('CHART')
    widget.chart!.type = type as DashboardChart['type']
    const result = { id: widget.id, points: [{ key: 'done', label: '已完成', colorToken: 'GREEN', seriesKey: 'all', value: 1, xValue: 1, sizeValue: 1 }] } as DashboardChartResult
    const wrapper = mount(DashboardChartView, { props: { widget, result } })
    const colors = () => (renderer.setOption.mock.calls.at(-1)![0] as { series: { data: { itemStyle: { color: string } }[] }[] }).series[0]!.data[0]!.itemStyle.color
    expect(colors()).toBe('#00c875')
    await wrapper.setProps({ widget: { ...widget, chart: { ...widget.chart!, labels: [{ key: 'STATUS:done', name: '', color: '#abcdef', order: 0 }] } } })
    expect(colors()).toBe('#abcdef')
    wrapper.unmount()
  })

  it('uses the same source color for split series', () => {
    const widget = newWidget('CHART')
    widget.chart!.dimension = 'PROJECT' as DashboardChart['dimension']; widget.chart!.series = 'STATUS' as DashboardChart['series']
    const result = { id: widget.id, points: [{ key: 'p', label: '项目', colorToken: '', seriesKey: 'done', seriesLabel: '已完成', seriesColorToken: 'GREEN', value: 1 }] } as DashboardChartResult
    const wrapper = mount(DashboardChartView, { props: { widget, result } })
    expect(renderer.setOption.mock.calls.at(-1)![0].series[0].itemStyle.color).toBe('#00c875')
    wrapper.unmount()
  })
  it.each(['NUMBER', 'COLUMN', 'BAR', 'PIE', 'DONUT', 'LINE', 'AREA', 'BUBBLE'])('%s preserves complete data and emits stable selection keys', async type => {
    const widget = newWidget('CHART')
    widget.chart!.type = type as DashboardChart['type']
    const result = { id: widget.id, points: ['doing', 'done'].map((key, index) => ({ key, label: key, colorToken: 'BRIGHT_BLUE', seriesKey: 'all', seriesLabel: '', seriesColorToken: '', count: index + 1, value: index + 1, categoryValue: index + 1, xValue: index + 1, sizeValue: index + 1 })) } as DashboardChartResult
    const wrapper = mount(DashboardChartView, { props: { widget, result, selection: { key: 'done' } } })
    if (type === 'NUMBER') {
      await wrapper.get('button').trigger('click')
      expect(wrapper.emitted('select')?.[0]).toEqual([])
    } else {
      const option = renderer.setOption.mock.calls.at(-1)?.[0] as { series: { data: { itemStyle: { opacity: number } }[] }[] }
      expect(option.series[0]?.data).toHaveLength(2)
      expect(option.series[0]?.data.map(p => p.itemStyle.opacity)).toEqual([1, .22])
      renderer.on.mock.calls.at(-1)?.[1]({ seriesIndex: 0, dataIndex: 0 })
      expect(wrapper.emitted('select')?.[0]).toEqual([{ key: 'done' }])
    }
    wrapper.unmount()
    if (type !== 'NUMBER') expect(renderer.dispose).toHaveBeenCalled()
  })
})
