import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import { TimeSessionFillMode } from '@yumpoo/api-client'
import DayTimeline from './DayTimeline.vue'
import { atMinute, dayWindow, layout, PX_PER_MINUTE, shares, type DayBlock } from './dayModel'
import { draggedItem, type ShelfItem } from './shelfDrag'

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
    const captured = new Set<number>()
    element.setPointerCapture = id => { captured.add(id) }
    element.releasePointerCapture = id => { captured.delete(id) }
    element.hasPointerCapture = id => captured.has(id)
    if (element === canvas) continue
    element.getBoundingClientRect = () => {
      const parent = canvas.getBoundingClientRect()
      const place = wrapper.props('layout').get(element.dataset.sessionId!) ?? { lane: 0, lanes: 1 }
      const top = parent.top + Number.parseFloat(element.style.top), height = Number.parseFloat(element.style.height)
      const left = parent.left + place.lane / place.lanes * parent.width + 4, width = parent.width / place.lanes - 8
      return { top, left, width, height, bottom: top + height, right: left + width, x: left, y: top, toJSON: () => ({}) }
    }
  }
  return wrapper
}
const pointer = (type: string, clientY: number, altKey = false, clientX = 300) => new PointerEvent(type, { bubbles: true, button: 0, pointerId: 1, pointerType: 'mouse', clientX, clientY, altKey })
const shelfItem: ShelfItem = { workItemId: 'new', title: '新增工作项', itemNo: null, projectName: null, colorToken: null }
function shelfEvent(type: string, clientY: number, clientX = 300, relatedTarget: EventTarget | null = null) {
  const event = new MouseEvent(type, { bubbles: true, cancelable: true, clientX, clientY, relatedTarget })
  Object.defineProperty(event, 'dataTransfer', { value: { dropEffect: 'none' } })
  return event as DragEvent
}
afterEach(() => { draggedItem.value = null; vi.restoreAllMocks() })

describe('当天时间线', () => {
  it('拖动记录按 15 分钟吸附并停在相邻记录之前，不触发打开弹层', async () => {
    const moving = block('a', at(10), at(11)), neighbour = block('b', at(12), at(13))
    const wrapper = mountTimeline([moving, neighbour])
    const element = wrapper.findAll('.day-block')[0]!.element
    element.dispatchEvent(pointer('pointerdown', y(630)))
    element.dispatchEvent(pointer('pointermove', y(700)))
    element.dispatchEvent(pointer('pointermove', y(790)))
    element.dispatchEvent(pointer('pointerup', y(790)))
    element.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 1 }))
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
    canvas.dispatchEvent(pointer('pointerdown', y(10 * 60 + 2)))
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

  it('非整刻空白的悬停预览与轻微移动后的点击结果一致', async () => {
    const wrapper = mountTimeline([block('a', at(12), at(12, 17)), block('b', at(13), at(14))])
    const canvas = wrapper.get('.day-timeline__canvas').element
    const start = y(12 * 60 + 22)
    canvas.dispatchEvent(pointer('pointermove', start))
    await wrapper.vm.$nextTick()
    expect(wrapper.get('.day-hover').text()).toContain('12:17–13:00（43m）')
    canvas.dispatchEvent(pointer('pointerdown', start))
    canvas.dispatchEvent(pointer('pointermove', start + 5))
    canvas.dispatchEvent(pointer('pointerup', start + 5))
    expect(wrapper.emitted('create')).toEqual([[{ start: at(12, 17), end: at(13) }]])
    wrapper.unmount()
  })

  it.each(['start', 'end'] as const)('卡片%s边框外的窄缝优先拉伸边缘，不提示空白补录', async edge => {
    const original = block('a', at(12, 7), at(13, 7))
    const wrapper = mountTimeline([original])
    const canvas = wrapper.get('.day-timeline__canvas').element
    const start = edge === 'start' ? y(12 * 60 + 7) + 1 : y(13 * 60 + 7) - 1
    canvas.dispatchEvent(pointer('pointermove', start))
    await wrapper.vm.$nextTick()
    expect(wrapper.get('.day-block').classes()).toContain(`is-edge-${edge}`)
    expect(wrapper.find('.day-hover').exists()).toBe(false)
    canvas.dispatchEvent(pointer('pointerdown', start))
    canvas.dispatchEvent(pointer('pointermove', start + y(15)))
    canvas.dispatchEvent(pointer('pointerup', start + y(15)))
    expect(wrapper.emitted('create')).toBeUndefined()
    expect(wrapper.emitted('change')).toEqual([[original, edge === 'start'
      ? { start: at(12, 22), end: at(13, 7) } : { start: at(12, 7), end: at(13, 22) }]])
    wrapper.unmount()
  })

  it.each([false, true])('非整刻记录保留起始偏移，小抖动不写入（Alt=%s）', async alt => {
    const original = block('a', at(10, 7) + 12_000, at(11, 7) + 12_000)
    const wrapper = mountTimeline([original])
    const element = wrapper.get('.day-block').element
    const start = y(10 * 60 + 30)
    element.dispatchEvent(pointer('pointerdown', start, alt))
    element.dispatchEvent(pointer('pointermove', start + 5, alt))
    element.dispatchEvent(pointer('pointerup', start + 5, alt))
    expect(wrapper.emitted('change')).toBeUndefined()
    const minutes = alt ? 5 : 15
    element.dispatchEvent(pointer('pointerdown', start, alt))
    element.dispatchEvent(pointer('pointermove', start + y(minutes), alt))
    element.dispatchEvent(pointer('pointerup', start + y(minutes), alt))
    expect(wrapper.emitted('change')).toEqual([[original, {
      start: original.start + minutes * 60_000, end: original.end! + minutes * 60_000,
    }]])
    wrapper.unmount()
  })

  it.each(['pointercancel', 'lostpointercapture', 'Escape', 'blur', 'date'])('%s 取消拖拽并还原卡片，不提交修改', async reason => {
    const wrapper = mountTimeline([block('a', at(10), at(11))])
    const element = wrapper.get('.day-block').element as HTMLElement
    const top = element.style.top
    element.dispatchEvent(pointer('pointerdown', y(630)))
    element.dispatchEvent(pointer('pointermove', y(645)))
    await wrapper.vm.$nextTick()
    expect(element.style.top).not.toBe(top)
    if (reason === 'Escape') window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    else if (reason === 'blur') window.dispatchEvent(new Event('blur'))
    else if (reason === 'date') await wrapper.setProps({ bounds: { start: bounds.start - 86_400_000, end: bounds.end } })
    else element.dispatchEvent(pointer(reason, y(645)))
    await wrapper.vm.$nextTick()
    expect(wrapper.get('.day-block').classes()).not.toContain('is-dragging')
    if (reason !== 'date') expect(element.style.top).toBe(top)
    element.dispatchEvent(pointer('pointerup', y(645)))
    expect(wrapper.emitted('change')).toBeUndefined()
    expect(wrapper.emitted('dragging')!.at(-1)).toEqual([false])
    wrapper.unmount()
  })

  it('滚动后按画布坐标取时段，其他指针不能结束当前拖选', async () => {
    const wrapper = mountTimeline([])
    const canvas = wrapper.get('.day-timeline__canvas').element as HTMLElement
    canvas.getBoundingClientRect = () => ({ top: -800, left: 0, right: 600, bottom: y(1440) - 800, width: 600, height: y(1440), x: 0, y: -800, toJSON: () => ({}) })
    canvas.dispatchEvent(pointer('pointerdown', y(13 * 60) - 800))
    canvas.dispatchEvent(pointer('pointermove', y(14 * 60) - 800))
    canvas.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 2 }))
    expect(wrapper.emitted('create')).toBeUndefined()
    canvas.dispatchEvent(pointer('pointerup', y(14 * 60) - 800))
    expect(wrapper.emitted('create')).toEqual([[{ start: at(13), end: at(14) }]])
    wrapper.unmount()
  })

  it('短卡片的上下边缘不交叉，主体仍能点击打开', async () => {
    const wrapper = mountTimeline([block('a', at(12), at(12, 3))])
    const card = wrapper.get('.day-block')
    const rect = card.element.getBoundingClientRect()
    card.element.dispatchEvent(pointer('pointermove', rect.top + 2))
    await wrapper.vm.$nextTick()
    expect(card.classes()).toContain('is-edge-start')
    card.element.dispatchEvent(pointer('pointermove', rect.bottom - 2))
    await wrapper.vm.$nextTick()
    expect(card.classes()).toContain('is-edge-end')
    card.element.dispatchEvent(pointer('pointermove', (rect.top + rect.bottom) / 2))
    await wrapper.vm.$nextTick()
    expect(card.classes()).not.toContain('is-edge-start')
    expect(card.classes()).not.toContain('is-edge-end')
    await card.trigger('click')
    expect(wrapper.emitted('open')).toHaveLength(1)
    wrapper.unmount()
  })

  it('取消拖动后键盘点击仍能打开记录；触屏滑动空白不补录', async () => {
    const wrapper = mountTimeline([block('a', at(10), at(11))])
    const card = wrapper.get('.day-block').element
    card.dispatchEvent(pointer('pointerdown', y(630)))
    card.dispatchEvent(pointer('pointermove', y(645)))
    card.dispatchEvent(pointer('pointercancel', y(645)))
    card.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 0 }))
    expect(wrapper.emitted('open')).toHaveLength(1)
    const canvas = wrapper.get('.day-timeline__canvas').element
    for (const [type, clientY] of [['pointerdown', y(780)], ['pointermove', y(795)], ['pointerup', y(795)]] as const) {
      canvas.dispatchEvent(new PointerEvent(type, { bubbles: true, pointerId: 1, pointerType: 'touch', button: 0, clientX: 300, clientY }))
    }
    expect(wrapper.emitted('create')).toBeUndefined()
    wrapper.unmount()
  })

  it('侧栏拖入按指向卡片的完整时段并行补录，预览不重排卡片', async () => {
    const a = block('a', at(10), at(12)), b = block('b', at(11), at(13))
    const wrapper = mountTimeline([a, b])
    const cards = wrapper.findAll('.day-block')
    const styles = cards.map(card => card.attributes('style'))
    draggedItem.value = shelfItem
    cards[1]!.element.dispatchEvent(shelfEvent('dragover', y(11 * 60 + 30), 450))
    await wrapper.vm.$nextTick()
    expect(wrapper.get('.is-drop').text()).toContain('添加并行工作项：新增工作项')
    expect(cards.map(card => card.attributes('style'))).toEqual(styles)
    expect(wrapper.get('.is-drop').attributes('style')).toContain('left: calc(50% + 4px)')
    const title = cards[1]!.get('.day-block__title').element
    cards[1]!.element.dispatchEvent(shelfEvent('dragleave', y(11 * 60 + 30), 450, title))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.is-drop').exists()).toBe(true)
    title.dispatchEvent(shelfEvent('drop', y(11 * 60 + 30), 450))
    expect(wrapper.emitted('drop')).toEqual([[shelfItem, { start: at(11), end: at(13) }, TimeSessionFillMode.Parallel]])
    expect(wrapper.emitted('change')).toBeUndefined()
    expect(draggedItem.value).toBeNull()
    wrapper.unmount()
  })

  it('侧栏拖入空白继续只填空白，移出画布或结束拖入清除预览', async () => {
    const wrapper = mountTimeline([block('a', at(10), at(11))])
    const canvas = wrapper.get('.day-timeline__canvas').element
    draggedItem.value = shelfItem
    canvas.dispatchEvent(shelfEvent('dragover', y(9 * 60 + 20)))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.is-drop').exists()).toBe(true)
    canvas.dispatchEvent(shelfEvent('dragleave', y(9 * 60 + 20), 700))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.is-drop').exists()).toBe(false)
    canvas.dispatchEvent(shelfEvent('dragover', y(9 * 60 + 20)))
    canvas.dispatchEvent(shelfEvent('drop', y(9 * 60 + 20)))
    expect(wrapper.emitted('drop')).toEqual([[shelfItem, { start: at(9), end: at(10) }, TimeSessionFillMode.Gaps]])
    wrapper.unmount()
  })

  it('运行中的目标使用预览快照，跨日目标只取当天的真实时段', async () => {
    const running = { ...block('a', at(10), at(12)), end: null, canEdit: false }
    const wrapper = mountTimeline([running], at(12))
    const card = wrapper.get('.day-block').element
    draggedItem.value = shelfItem
    card.dispatchEvent(shelfEvent('dragover', y(11 * 60)))
    await wrapper.vm.$nextTick()
    const previewHeight = (wrapper.get('.is-drop').element as HTMLElement).style.height
    await wrapper.setProps({ now: at(12, 1), limit: at(12, 1) })
    expect((wrapper.get('.is-drop').element as HTMLElement).style.height).toBe(previewHeight)
    card.dispatchEvent(shelfEvent('drop', y(11 * 60)))
    expect(wrapper.emitted('drop')).toEqual([[shelfItem, { start: at(10), end: at(12) }, TimeSessionFillMode.Parallel]])
    wrapper.unmount()

    const cross = mountTimeline([block('b', bounds.start - 60 * 60_000, at(1))])
    draggedItem.value = shelfItem
    cross.get('.day-block').element.dispatchEvent(shelfEvent('dragover', y(30)))
    cross.get('.day-block').element.dispatchEvent(shelfEvent('drop', y(30)))
    expect(cross.emitted('drop')).toEqual([[shelfItem, { start: bounds.start, end: at(1) }, TimeSessionFillMode.Parallel]])
    cross.unmount()
  })

  it('目标真实时长不足十五分钟不因视觉放大而允许补录', async () => {
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({ close: () => undefined }))
    const wrapper = mountTimeline([block('a', at(12), at(12, 3))])
    draggedItem.value = shelfItem
    const card = wrapper.get('.day-block').element
    const event = shelfEvent('dragover', y(12 * 60 + 7))
    card.dispatchEvent(event)
    await wrapper.vm.$nextTick()
    expect(event.dataTransfer!.dropEffect).toBe('none')
    expect(wrapper.get('.is-drop').text()).toContain('不足 15 分钟')
    card.dispatchEvent(shelfEvent('drop', y(12 * 60 + 7)))
    expect(wrapper.emitted('drop')).toBeUndefined()
    expect(warning).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('保存期间不开始移动、拉伸、补录或侧栏拖入', async () => {
    const wrapper = mountTimeline([block('a', at(10), at(11))])
    await wrapper.setProps({ busy: true })
    const card = wrapper.get('.day-block').element, canvas = wrapper.get('.day-timeline__canvas').element
    for (const start of [y(600) + 1, y(630), y(780)]) {
      canvas.dispatchEvent(pointer('pointerdown', start))
      canvas.dispatchEvent(pointer('pointermove', start + y(15)))
      canvas.dispatchEvent(pointer('pointerup', start + y(15)))
    }
    draggedItem.value = shelfItem
    card.dispatchEvent(shelfEvent('drop', y(630)))
    expect(wrapper.emitted('create')).toBeUndefined()
    expect(wrapper.emitted('change')).toBeUndefined()
    expect(wrapper.emitted('drop')).toBeUndefined()
    expect((wrapper.get('.day-gap').element as HTMLButtonElement).disabled).toBe(true)
    wrapper.unmount()
  })
})
