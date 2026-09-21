import { describe, expect, it } from 'vitest'
import type { DashboardChartResult } from '@yumpoo/api-client'
import { additive, chartGroups, chartLabel, defaultChart, resolveChart } from './chartModel'
import { newWidget } from './dashboardModel'

describe('custom chart configurations', () => {
  it('adapts presets without changing saved display settings or metrics', () => {
    const widget = newWidget('PROJECT_WORKLOAD', [])
    widget.showValues = false
    expect(resolveChart(widget)).toMatchObject({ type: 'BAR', dimension: 'PROJECT', series: 'STATUS', stacked: true, showValues: false })
    expect(resolveChart(newWidget('PROJECT_TIME', []))).toMatchObject({ measure: { metric: 'DURATION', calculation: 'SUM' } })
    expect(newWidget('CHART', []).chart).toMatchObject({ type: 'COLUMN', dimension: 'STATUS', measure: { metric: 'TOTAL' } })
  })
  it('uses immutable category keys for aliases, ordering and top/bottom selection', () => {
    const c = defaultChart()
    c.labels = [{ key: 'STATUS:b', name: '自定义名称', color: '#ff0000', order: 0 }]
    const result = { id: 'chart', points: [{ key: 'a', label: 'A', colorToken: 'GRAY', value: 4 }, { key: 'b', label: 'B', colorToken: 'GRAY', value: 2 }] } as DashboardChartResult
    c.limit = 1
    expect(chartGroups(c, result)[0]?.key).toBe('a')
    c.sort = 'VALUE_ASC' as typeof c.sort
    expect(chartGroups(c, result)[0]).toMatchObject({ key: 'b', name: '自定义名称', color: '#ff0000' })
    expect(chartLabel(c, 'PROJECT', 'b', '项目').name).toBe('项目')
    expect(result.points[1]?.label).toBe('B')
  })
  it('does not stack percentages or average and median durations', () => {
    const c = defaultChart()
    expect(additive(c)).toBe(true)
    c.measure.metric = 'COMPLETION_RATE' as typeof c.measure.metric
    expect(additive(c)).toBe(false)
    c.measure = { metric: 'DURATION', calculation: 'MEDIAN' } as typeof c.measure
    expect(additive(c)).toBe(false)
    c.measure.calculation = 'SUM' as typeof c.measure.calculation
    expect(additive(c)).toBe(true)
  })
})
