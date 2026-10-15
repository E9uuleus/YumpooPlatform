import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import DayTimeline from './DayTimeline.vue'
import { atMinute, dayWindow, layout, PX_PER_MINUTE, shares, type DayBlock } from './dayModel'

const zone = 'Asia/Shanghai', date = '2026-10-08'
const at = (hour: number, minute = 0) => atMinute(date, hour * 60 + minute, zone)
const bounds = dayWindow(date, zone)
const y = (minutes: number) => minutes * PX_PER_MINUTE
const block = (id: string, start: number, end: number): DayBlock => ({ id, workItemId: id, projectId: 'p', title: id, itemNo: null,
  projectName: null, colorToken: null, start, end, canEdit: true, etag: '"0"' })

function mountTimeline(blocks: DayBlock[], limit = at(18)) {
  const wrapper = mount(DayTimeline, { attachTo: document.body, props: {
    blocks, layout: layout(blocks, limit, bounds), shares: shares(blocks, limit, bounds), bounds, workday: { start: at(9), end: at(18) },
    now: limit, limit, timezone: zone } })
  // happy-dom has no layout; place the canvas at 1.6px per minute from local midnight at y=0.
  const canvas = wrapper.get('.day-timeline__canvas').element as HTMLElement
  canvas.getBoundingClientRect = () => ({ top: 0, left: 0, right: 600, bottom: y(1440), width: 600, height: y(1440), x: 0, y: 0, toJSON: () => ({}) })
  for (const element of (wrapper.element as HTMLElement).querySelectorAll<HTMLElement>('.day-block, .day-timeline__canvas')) {
    element.setPointerCapture = () => undefined
    element.releasePointerCapture = () => undefined
    element.hasPointerCapture = () => true
  }
  return wrapper
}
const pointer = (type: string, clientY: number, altKey = false) => new PointerEvent(type, { bubbles: true, button: 0, pointerId: 1, pointerType: 'mouse', clientY, altKey })
afterEach(() => vi.restoreAllMocks())

describe('当天时间线', () => {
  it('拖动记录按 15 分钟吸附并停在相邻记录之前，不触发打开弹层', async () => {
    const moving = block('a', at(10), at(11)), neighbour = block('b', at(12), at(13))
    const wrapper = mountTimeline([moving, neighbour])
    const element = wrapper.findAll('.day-block')[0]!.element
    element.dispatchEvent(pointer('pointerdown', y(630)))
    element.dispatchEvent(pointer('pointermove', y(700)))
    element.dispatchEvent(pointer('pointermove', y(790)))
    element.dispatchEvent(pointer('pointerup', y(790)))
    element.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    expect(wrapper.emitted('change')![0]).toEqual([moving, { start: at(11), end: at(12) }])
    expect(wrapper.emitted('open')).toBeUndefined()
    wrapper.unmount()
  })

  it('拖选空白生成时段，单击空白生成 1 小时并裁剪到所在空白', async () => {
    const wrapper = mountTimeline([block('a', at(10), at(11))])
    const canvas = wrapper.get('.day-timeline__canvas').element
    canvas.dispatchEvent(pointer('pointerdown', y(13 * 60 + 7)))
    canvas.dispatchEvent(pointer('pointermove', y(14 * 60 + 52)))
    canvas.dispatchEvent(pointer('pointerup', y(14 * 60 + 52)))
    expect(wrapper.emitted('create')![0]).toEqual([{ start: at(13), end: at(14, 45) }])
    canvas.dispatchEvent(pointer('pointerdown', y(9 * 60 + 20)))
    canvas.dispatchEvent(pointer('pointerup', y(9 * 60 + 20)))
    expect(wrapper.emitted('create')![1]).toEqual([{ start: at(9), end: at(10) }])
    wrapper.unmount()
  })

  it('短拖选在相邻记录前向上补足十五分钟，不制造重叠', async () => {
    const wrapper = mountTimeline([block('a', at(10, 10), at(11))])
    const canvas = wrapper.get('.day-timeline__canvas').element
    canvas.dispatchEvent(pointer('pointerdown', y(10 * 60)))
    canvas.dispatchEvent(pointer('pointermove', y(10 * 60 + 5), true))
    canvas.dispatchEvent(pointer('pointerup', y(10 * 60 + 5), true))
    expect(wrapper.emitted('create')![0]).toEqual([{ start: at(9, 55), end: at(10, 10) }])
    wrapper.unmount()
  })

  it.each([7, 12])('临近现在线的短拖选向上补足十五分钟（10:%i）', async minute => {
    const wrapper = mountTimeline([], at(10, minute))
    const canvas = wrapper.get('.day-timeline__canvas').element
    canvas.dispatchEvent(pointer('pointerdown', y(10 * 60 + minute - 4)))
    canvas.dispatchEvent(pointer('pointermove', y(10 * 60 + minute)))
    canvas.dispatchEvent(pointer('pointerup', y(10 * 60 + minute)))
    expect(wrapper.emitted('create')![0]).toEqual([{ start: at(9, 45 + minute), end: at(10, minute) }])
    wrapper.unmount()
  })

  it.each([7, 12])('从现在线或未来区域拖动不发起补录，也不提示空白不足（起点 10:%i）', async minute => {
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({ close: () => undefined }))
    const wrapper = mountTimeline([], at(10, 7))
    const canvas = wrapper.get('.day-timeline__canvas').element
    for (const endMinute of [minute + 5, minute - 20]) {
      canvas.dispatchEvent(pointer('pointerdown', y(10 * 60 + minute)))
      canvas.dispatchEvent(pointer('pointermove', y(10 * 60 + endMinute)))
      await wrapper.vm.$nextTick()
      expect(wrapper.find('.day-selection').exists()).toBe(false)
      canvas.dispatchEvent(pointer('pointerup', y(10 * 60 + endMinute)))
    }
    expect(wrapper.emitted('create')).toBeUndefined()
    expect(warning).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it.each([7, 10])('拒绝只有 %i 分钟的空白并说明原因', async minutes => {
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({ close: () => undefined }))
    const wrapper = mountTimeline([block('a', at(9), at(10)), block('b', at(10, minutes), at(11))], at(10, minutes))
    const canvas = wrapper.get('.day-timeline__canvas').element
    canvas.dispatchEvent(pointer('pointerdown', y(10 * 60)))
    canvas.dispatchEvent(pointer('pointermove', y(10 * 60 + minutes), true))
    canvas.dispatchEvent(pointer('pointerup', y(10 * 60 + minutes), true))
    expect(wrapper.emitted('create')).toBeUndefined()
    expect(warning).toHaveBeenCalledWith(expect.stringContaining('至少记录 15 分钟'))
    wrapper.unmount()
  })

  it('短记录之后的补录入口移至可见部分，补录仍使用真实空白', async () => {
    const wrapper = mountTimeline([block('a', at(10), at(10, 3)), block('b', at(10, 20), at(11))])
    const shortcut = wrapper.get('[aria-label="补录 10:03 到 10:20 的空白"]')
    const card = wrapper.findAll('.day-block')[0]!.element as HTMLElement
    expect(Number.parseFloat((shortcut.element as HTMLElement).style.top)).toBeGreaterThan(Number.parseFloat(card.style.top) + Number.parseFloat(card.style.height))
    await shortcut.trigger('click')
    expect(wrapper.emitted('create')![0]).toEqual([{ start: at(10, 3), end: at(10, 20) }])
    wrapper.unmount()
  })

  it.each([29, 30, 31])('空间不足时不放入第二行内容（%i 分钟）', minutes => {
    const wrapper = mountTimeline([block('a', at(10), at(10, minutes))])
    expect(wrapper.find('.day-block__meta').exists()).toBe(minutes === 31)
    wrapper.unmount()
  })
})
