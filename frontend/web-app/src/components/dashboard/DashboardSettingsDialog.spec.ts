import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { ElSelect, ElColorPicker } from 'element-plus'
import type { DashboardChart, DashboardChartResult } from '@yumpoo/api-client'
import DashboardSettingsDialog from './DashboardSettingsDialog.vue'
import { newWidget } from './dashboardModel'

vi.mock('./DashboardChart.vue', () => ({ default: { template: '<div data-test="preview" />' } }))
beforeEach(() => { document.documentElement.style.setProperty('--yp-label-green', '#00c875'); document.documentElement.style.setProperty('--yp-label-red', '#e2445c') })
afterEach(() => { document.documentElement.style.removeProperty('--yp-label-green'); document.documentElement.style.removeProperty('--yp-label-red') })
function editor(extra = {}) {
  return mount(DashboardSettingsDialog, {
    props: { widget: newWidget('CHART'), projects: [{ id: 'a', name: '项目 A', code: 'A', lifecycle: 'ACTIVE', available: true }, { id: 'b', name: '项目 B', code: 'B', lifecycle: 'ACTIVE', available: true }], options: [], loading: false, error: '', queryError: '', ...extra },
    global: { stubs: { ElDialog: { template: '<div><slot name="header"/><slot/></div>' }, ElTooltip: { template: '<span><slot/></span>' }, DashboardFiltersDialog: true } },
  })
}
describe('chart editor', () => {
  it('retains intermediate names locally and emits only valid drafts', async () => {
    const wrapper = editor()
    const name = wrapper.get('input[aria-label="组件名称"]')
    await name.setValue('')
    expect(wrapper.emitted('change')).toBeUndefined()
    await name.setValue('  有效名称  ')
    expect(wrapper.emitted('change')?.at(-1)?.[0]).toMatchObject({ title: '有效名称' })
    wrapper.unmount()
  })
  it('keeps compatible axes and explicit empty project scopes when changing type', async () => {
    const wrapper = editor()
    wrapper.findAllComponents(ElSelect).find(s => s.props('ariaLabel') === '分类字段')!.vm.$emit('update:modelValue', 'DUE'); await nextTick()
    const bubble = wrapper.findAll('button').find(b => b.text() === '气泡图')!
    await bubble.trigger('click')
    expect(wrapper.emitted('change')?.at(-1)?.[0]).toMatchObject({ chart: { type: 'BUBBLE', dimension: 'DUE', xMeasure: { metric: 'TOTAL' }, sizeMeasure: { metric: 'TOTAL' } } })
    expect(wrapper.findAllComponents(ElSelect).some(s => s.props('ariaLabel') === '拆分系列')).toBe(false)
    const allProjects = wrapper.findAll('label').find(l => l.text() === '所有连接项目')!
    await allProjects.get('input').setValue(false)
    expect(wrapper.emitted('change')?.at(-1)?.[0]).toMatchObject({ chart: { projectIds: [] } })
    wrapper.unmount()
  })
  it('exposes recovery without discarding the editor draft', async () => {
    const wrapper = editor({ error: '网络不可用' })
    await wrapper.get('input[aria-label="组件名称"]').setValue('保留草稿')
    await wrapper.findAll('button').find(b => b.text() === '重试保存')!.trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
    expect((wrapper.get('input[aria-label="组件名称"]').element as HTMLInputElement).value).toBe('保留草稿')
    wrapper.unmount()
  })
  it('keeps the count input available while an invalid intermediate value is being typed', async () => {
    const wrapper = editor()
    await wrapper.findAll('label').find(l => l.text() === '仅显示排序前 N 项')!.get('input').setValue(true)
    const before = wrapper.emitted('change')?.length
    await wrapper.get('input[aria-label="显示数量"]').setValue('')
    expect(wrapper.find('input[aria-label="显示数量"]').exists()).toBe(true)
    expect(wrapper.emitted('change')?.length).toBe(before)
    await wrapper.get('input[aria-label="显示数量"]').setValue('5')
    expect(wrapper.emitted('change')?.at(-1)?.[0]).toMatchObject({ chart: { limit: 5 } })
    wrapper.unmount()
  })

  it.each(['STATUS', 'CATEGORY', 'PRIORITY', 'CONTENT'])('%s defaults to the label color without persisting an override', async dimension => {
    const widget = newWidget('CHART')
    widget.chart!.dimension = dimension as DashboardChart['dimension']
    widget.chart!.labels = [{ key: `${dimension}:done`, name: '已处理', color: '', order: 0 }]
    const wrapper = editor({ widget, result: { id: widget.id, points: [{ key: 'done', label: '已完成', colorToken: 'GREEN', value: 2, seriesKey: 'all' }] } })
    await wrapper.findAll('button').find(b => b.text() === '编辑颜色、名称与顺序')!.trigger('click')
    expect(wrapper.getComponent(ElColorPicker).props('modelValue')).toBe('#00c875')
    expect(wrapper.emitted('change')).toBeUndefined()
    wrapper.unmount()
  })

  it('inherits series colors, preserves overrides and restores current source colors without clearing the alias', async () => {
    const widget = newWidget('CHART')
    widget.chart!.dimension = 'PROJECT' as DashboardChart['dimension']; widget.chart!.series = 'STATUS' as DashboardChart['series']
    widget.chart!.labels = [{ key: 'STATUS:done', name: '交付完成', color: '#123456', order: 0 }]
    const result = { id: widget.id, points: [{ key: 'p', label: '项目', colorToken: '', value: 2, seriesKey: 'done', seriesLabel: '已完成', seriesColorToken: 'GREEN' }] } as DashboardChartResult
    const wrapper = editor({ widget, result })
    await wrapper.findAll('button').find(b => b.text() === '编辑颜色、名称与顺序')!.trigger('click')
    const picker = () => wrapper.findAllComponents(ElColorPicker).find(p => p.props('ariaLabel') === '交付完成颜色')!
    expect(picker().props('modelValue')).toBe('#123456')
    await wrapper.setProps({ result: { ...result, points: result.points.map(p => ({ ...p, seriesColorToken: 'RED' })) } })
    expect(picker().props('modelValue')).toBe('#123456')
    await wrapper.get('button[aria-label="交付完成恢复默认颜色"]').trigger('click')
    expect(picker().props('modelValue')).toBe('#e2445c')
    expect(wrapper.emitted('change')?.at(-1)?.[0]).toMatchObject({ chart: { labels: [{ key: 'STATUS:done', name: '交付完成', color: '', order: 0 }] } })
    picker().vm.$emit('change', '#abcdef'); await nextTick()
    expect(wrapper.emitted('change')?.at(-1)?.[0]).toMatchObject({ chart: { labels: [{ color: '#abcdef' }] } })
    wrapper.unmount()
  })

  it('keeps label search available with no matches and uses selected IDs for content filters', async () => {
    const wrapper = editor({ result: { id: 'chart', points: [{ key: 'done', label: '已完成', colorToken: 'GREEN', value: 2, seriesKey: 'all' }] } })
    await wrapper.findAll('button').find(b => b.text() === '编辑颜色、名称与顺序')!.trigger('click')
    await wrapper.get('input[aria-label="查找图表分类"]').setValue('不存在')
    expect(wrapper.text()).toContain('没有匹配的分类')
    await wrapper.get('input[aria-label="查找图表分类"]').setValue('')
    expect(wrapper.findAllComponents(ElColorPicker)).toHaveLength(1)
    const select = wrapper.findAllComponents(ElSelect).find(s => s.props('ariaLabel') === '工作项类型')!
    select.vm.$emit('update:modelValue', ['content-a']); await nextTick()
    expect(wrapper.emitted('change')?.at(-1)?.[0]).toMatchObject({ chart: { filters: { contentIds: ['content-a'] } } })
    select.vm.$emit('update:modelValue', []); await nextTick()
    expect(wrapper.emitted('change')?.at(-1)?.[0]).toMatchObject({ chart: { filters: { contentIds: [] } } })
    wrapper.unmount()
  })
})
