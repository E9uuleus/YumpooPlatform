import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { TimeSessionFillMode } from '@yumpoo/api-client'
const api = vi.hoisted(() => ({ listTimerCandidates: vi.fn() }))
vi.mock('../../../api/client', () => ({ timeTrackingApi: api }))
import EntryComposer from './EntryComposer.vue'
import { atMinute, dayWindow, type DayBlock } from './dayModel'

const settle = async () => { await new Promise(resolve => setTimeout(resolve)); await flushPromises() }
const zone = 'Asia/Shanghai', date = '2026-10-08'
const at = (hour: number, minute = 0) => atMinute(date, hour * 60 + minute, zone)
const candidate = (id: string) => ({ workItemId: id, projectId: 'p', projectName: '项目', itemNo: id.toUpperCase(), title: `工作项 ${id}`,
  contentName: '任务', contentCode: 'TASKS', statusCategory: 'TODO', assignedToMe: true, lastTrackedAt: null, ownDurationMs: 0 })
const existing: DayBlock = { id: 'b1', workItemId: 'other', projectId: 'p', title: '已有', itemNo: null, projectName: null, colorToken: null,
  start: at(9), end: at(10), canEdit: true, etag: '"0"' }
api.listTimerCandidates.mockResolvedValue({ items: [candidate('a'), candidate('b')], nextOffset: null })

async function mountComposer(range: { start: number; end: number }, blocks: DayBlock[] = []) {
  const wrapper = mount(EntryComposer, { props: { range, blocks, bounds: dayWindow(date, zone), limit: at(18), date, timezone: zone, busy: false } })
  await settle()
  return wrapper
}

describe('补录弹层', () => {
  it('没有重叠时不显示处理方式，单击工作项按只填空白直接保存', async () => {
    const wrapper = await mountComposer({ start: at(11), end: at(12) }, [existing])
    expect(wrapper.find('.overlap-mode').exists()).toBe(false)
    await wrapper.findAll('[role="option"]')[0]!.trigger('click')
    const [mode, items, range] = wrapper.emitted('submit')![0]!
    expect(mode).toBe(TimeSessionFillMode.Gaps)
    expect((items as { workItemId: string }[]).map(item => item.workItemId)).toEqual(['a'])
    expect(range).toEqual({ start: at(11), end: at(12) })
  })

  it('部分重叠默认只填空白，完全覆盖时改为并行均摊且禁用只填空白', async () => {
    const partial = await mountComposer({ start: at(9, 30), end: at(10, 30) }, [existing])
    expect(partial.get('.overlap-mode [aria-checked="true"]').text()).toBe('只填空白')
    expect(partial.get('.overlap-mode__effect').text()).toContain('补录 30m')
    const covered = await mountComposer({ start: at(9), end: at(10) }, [existing])
    expect(covered.get('.overlap-mode [aria-checked="true"]').text()).toBe('并行均摊')
    expect(covered.findAll('.overlap-mode button')[0]!.attributes('disabled')).toBeDefined()
    await covered.findAll('[role="option"]')[0]!.trigger('click')
    expect(covered.emitted('submit')![0]![0]).toBe(TimeSessionFillMode.Parallel)
  })

  it('Ctrl+单击多选后一次添加多项并行记录，Esc 关闭', async () => {
    const wrapper = await mountComposer({ start: at(11), end: at(12) })
    const options = wrapper.findAll('[role="option"]')
    await options[0]!.trigger('click', { ctrlKey: true })
    await options[1]!.trigger('click')
    expect(wrapper.emitted('submit')).toBeUndefined()
    await wrapper.get('.entry-composer__foot .el-button--primary').trigger('click')
    expect((wrapper.emitted('submit')![0]![1] as { workItemId: string }[]).map(item => item.workItemId)).toEqual(['a', 'b'])
    await wrapper.get('.item-picker input').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('结束时间晚于现在时提示且不保存', async () => {
    const wrapper = await mountComposer({ start: at(17), end: at(19) })
    expect(wrapper.get('.entry-composer__problem').text()).toBe('不能记录未来的时间')
    await wrapper.findAll('[role="option"]')[0]!.trigger('click')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })
})
