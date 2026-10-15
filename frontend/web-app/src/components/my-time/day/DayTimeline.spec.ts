import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import DayTimeline from './DayTimeline.vue'
import { atMinute, dayWindow, layout, PX_PER_MINUTE, shares, type DayBlock } from './dayModel'

const zone = 'Asia/Shanghai', date = '2026-10-08'
const at = (hour: number, minute = 0) => atMinute(date, hour * 60 + minute, zone)
const bounds = dayWindow(date, zone)
const y = (minutes: number) => minutes * PX_PER_MINUTE
const block = (id: string, start: number, end: number): DayBlock => ({ id, workItemId: id, projectId: 'p', title: id, itemNo: null,
  projectName: null, colorToken: null, start, end, canEdit: true, etag: '"0"' })

function mountTimeline(blocks: DayBlock[]) {
  const wrapper = mount(DayTimeline, { attachTo: document.body, props: {
    blocks, layout: layout(blocks, at(18)), shares: shares(blocks, at(18), bounds), bounds, workday: { start: at(9), end: at(18) },
    now: at(18), limit: at(18), timezone: zone } })
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
const pointer = (type: string, clientY: number) => new PointerEvent(type, { bubbles: true, button: 0, pointerId: 1, pointerType: 'mouse', clientY })

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
})
