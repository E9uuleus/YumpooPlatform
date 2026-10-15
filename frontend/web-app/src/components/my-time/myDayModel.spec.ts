import { describe, expect, it } from 'vitest'
import { gaps, lanes, placeSequential, type DayBlock } from './myDayModel'

const block = (id: string, start: number, end: number | null): DayBlock => ({
  id, workItemId: id, title: id, itemNo: null, projectName: null, colorToken: null,
  start, end, canEdit: end !== null, allocatedMs: 0, parallelItemCount: 0,
})

describe('我的一天区间安排', () => {
  it('空白时段扣除重叠区间的并集，并裁剪到当天和当前时刻', () => {
    expect(gaps([block('a', -5, 10), block('b', 5, 20), block('c', 40, null)], 0, 100, 60))
      .toEqual([[20, 40]])
    expect(gaps([], 0, 100, 60)).toEqual([[0, 60]])
    expect(gaps([block('a', 80, 120)], 0, 100, 200)).toEqual([[0, 80]])
  })

  it('并行区间分配不同泳道，边界相接的区间复用空闲泳道', () => {
    expect([...lanes([block('c', 20, 30), block('a', 0, 20), block('b', 5, null)], 40)])
      .toEqual([['a', 0], ['b', 1], ['c', 0]])
  })

  it('按行从指定时间依次排入空白，一条记录可跨越多个空白段', () => {
    expect(placeSequential([{ workItemId: 'a', durationMs: 20 }, { workItemId: 'b', durationMs: 5 }], [[0, 10], [20, 40]], 5))
      .toEqual({ entries: [{ workItemId: 'a', start: 5, end: 10 }, { workItemId: 'a', start: 20, end: 35 }, { workItemId: 'b', start: 35, end: 40 }], leftoverMs: 0 })
  })

  it('空白不足时累计当前行和后续行未排入的时长', () => {
    expect(placeSequential([{ workItemId: 'a', durationMs: 30 }, { workItemId: 'b', durationMs: 10 }], [[0, 10], [20, 30]], 0))
      .toEqual({ entries: [{ workItemId: 'a', start: 0, end: 10 }, { workItemId: 'a', start: 20, end: 30 }], leftoverMs: 20 })
  })
})
