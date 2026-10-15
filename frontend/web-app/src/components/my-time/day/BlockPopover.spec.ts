import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ listTimerCandidates: vi.fn() }))
vi.mock('../../../api/client', () => ({ timeTrackingApi: api }))
import BlockPopover from './BlockPopover.vue'
import { atMinute, dayWindow, type DayBlock } from './dayModel'

const settle = async () => { await new Promise(resolve => setTimeout(resolve)); await flushPromises() }
const zone = 'Asia/Shanghai', date = '2026-10-08'
const at = (hour: number) => atMinute(date, hour * 60, zone)
const block: DayBlock = { id: 'b1', workItemId: 'item', projectId: 'p', title: '写周报', itemNo: 'T-1', projectName: '项目', colorToken: null,
  start: at(9), end: at(10), canEdit: true, etag: '"3"' }
api.listTimerCandidates.mockResolvedValue({ items: [{ workItemId: 'next', projectId: 'p', projectName: '项目', itemNo: 'T-2', title: '评审',
  contentName: '任务', contentCode: 'TASKS', statusCategory: 'TODO', assignedToMe: true, lastTrackedAt: null, ownDurationMs: 0 }], nextOffset: null })

function mountPopover(value: DayBlock, maxParallel = 1) {
  return mount(BlockPopover, {
    props: { block: value, share: { allocatedMs: 1_800_000, maxParallel }, bounds: dayWindow(date, zone), limit: at(18), date, timezone: zone,
      timerEnabled: true, busy: false },
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
}
const button = (wrapper: ReturnType<typeof mountPopover>, text: string) => wrapper.findAll('button').find(item => item.text() === text)

describe('记录弹层', () => {
  it('运行中的记录只读，可停止计时', async () => {
    const wrapper = mountPopover({ ...block, end: null, canEdit: false })
    expect(wrapper.text()).toContain('正在计时')
    expect(wrapper.find('input').exists()).toBe(false)
    expect(button(wrapper, '删除')).toBeUndefined()
    await button(wrapper, '停止计时')!.trigger('click')
    expect(wrapper.emitted('stop')).toHaveLength(1)
  })

  it('删除需要二次确认', async () => {
    const wrapper = mountPopover(block)
    await button(wrapper, '删除')!.trigger('click')
    expect(wrapper.emitted('remove')).toBeUndefined()
    expect(wrapper.get('.is-confirm').text()).toContain('删除这条 1h 的记录')
    await wrapper.get('.is-confirm .el-button--danger').trigger('click')
    expect(wrapper.emitted('remove')).toHaveLength(1)
  })

  it('修改时间后才出现保存，并行时可独占，更换工作项发出目标', async () => {
    const wrapper = mountPopover(block, 2)
    expect(button(wrapper, '保存时段')).toBeUndefined()
    expect(wrapper.text()).toContain('与其他 1 项并行 · 当天计入 30m')
    const end = wrapper.get('input[aria-label="结束时间"]')
    await end.setValue('1030')
    await end.trigger('blur')
    await button(wrapper, '保存时段')!.trigger('click')
    expect(wrapper.emitted('save')![0]).toEqual([{ start: at(9), end: at(10) + 1_800_000 }])
    await button(wrapper, '独占此时段')!.trigger('click')
    expect(wrapper.emitted('exclusive')).toHaveLength(1)
    await button(wrapper, '更换工作项')!.trigger('click')
    await settle()
    await wrapper.get('[role="option"]').trigger('click')
    expect(wrapper.emitted('reassign')![0]).toEqual(['next'])
  })
})
