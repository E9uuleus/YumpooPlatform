import { describe, expect, it, vi } from 'vitest'
import { createWorkItemColumnFlip, measureWorkItemColumns, visibleWorkItemColumnCells } from './workItemColumnFlip'

function fixture() {
  let positions = new Map([['title', 0], ['assignee', 320], ['status', 430]])
  let callback: FrameRequestCallback = () => {}
  let disabled = false
  const cancel = vi.fn()
  const animate = vi.fn(() => ({ cancel, onfinish: null }) as unknown as Animation)
  const measure = vi.fn(() => positions)
  const flip = createWorkItemColumnFlip({ measure, disabled: () => disabled,
    targets: () => ['title', 'status', 'priority'].map(key => ({ key, element: { animate } as unknown as HTMLElement })),
    requestFrame: fn => { callback = fn; return 7 }, cancelFrame: vi.fn() })
  return { flip, animate, cancel, measure, frame: () => callback(0),
    positions: (next: [string, number][]) => { positions = new Map(next) }, disable: () => { disabled = true } }
}

describe('列补位 FLIP', () => {
  it('绘制前测量，固定列不动，后续列平移，新列淡入', () => {
    const f = fixture(), snapshot = f.flip.capture()
    f.positions([['title', 0], ['status', 320], ['priority', 450]])
    f.flip.play(snapshot)
    expect(f.animate).not.toHaveBeenCalled()
    f.frame()
    expect(f.measure).toHaveBeenCalledTimes(2)
    expect(f.animate.mock.calls).toEqual([
      [[{ transform: 'translateX(110px)' }, { transform: 'translateX(0px)' }], { duration: 160, easing: 'ease-out' }],
      [[{ opacity: 0 }, { opacity: 1 }], { duration: 160, easing: 'ease-out' }],
    ])
  })
  it('新测量前取消旧动画，旧快照不能再启动，销毁取消剩余动画', () => {
    const f = fixture(), old = f.flip.capture()
    f.positions([['status', 320]])
    f.flip.play(old); f.frame()
    const next = f.flip.capture()
    expect(f.cancel).toHaveBeenCalledOnce()
    f.flip.play(old); f.frame()
    expect(f.animate).toHaveBeenCalledOnce()
    f.positions([['status', 400]])
    f.flip.play(next); f.frame(); f.flip.cancel()
    expect(f.cancel).toHaveBeenCalledTimes(2)
  })
  it('减少动画、拖列或调宽期间跳过测量和动画，并在播放前重新检查', () => {
    const f = fixture(), snapshot = f.flip.capture()
    f.flip.play(snapshot); f.disable(); f.frame()
    expect(f.animate).not.toHaveBeenCalled()
    expect(f.flip.capture()).toBeUndefined()
    expect(f.measure).toHaveBeenCalledOnce()
  })
  it('分组模式取第一个展开分组的列头，忽略折叠行和视口外行', () => {
    const root = document.createElement('div')
    root.innerHTML = '<table><tbody><tr class="work-item-group-columns work-item-group-collapsed"><td class="monday-column--status"></td></tr><tr class="work-item-group-columns"><td class="monday-column--status"></td></tr><tr><td class="monday-column--status"></td></tr></tbody></table>'
    const rect = (top: number, height = 36) => ({ top, bottom: top + height, left: 100, right: 600, width: 500, height } as DOMRect)
    root.getBoundingClientRect = () => rect(0, 400)
    const rows = root.querySelectorAll('tr')
    rows.forEach((row, index) => {
      row.getBoundingClientRect = () => rect(index === 2 ? 500 : 50, index === 0 ? 0 : 36)
      row.firstElementChild!.getBoundingClientRect = () => rect(50)
    })
    expect(measureWorkItemColumns(root)).toEqual(new Map([['status', 100]]))
    expect(visibleWorkItemColumnCells(root)).toEqual([{ key: 'status', element: rows[1]!.firstElementChild }])
  })
})
