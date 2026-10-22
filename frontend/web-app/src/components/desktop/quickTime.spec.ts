import { describe, expect, it } from 'vitest'
import type { TimerCandidate } from '@yumpoo/api-client'
import { atMinute, dayWindow, HOUR, MINUTE, type DayBlock } from '../my-time/day/dayModel'
import { groupCandidates, placementHint, quickPlan, rangeFromPlan, rangeProblem, rangeRecordable, stepDate, stepperLabel } from './quickTime'

const timezone = 'Asia/Shanghai'
const date = '2026-10-19'
const at = (minute: number) => atMinute(date, minute, timezone)
const block = (start: number, end: number | null): DayBlock => ({ id: `s${start}`, workItemId: 'x', projectId: 'p1', title: '', itemNo: null,
  projectName: null, colorToken: null, start: at(start), end: end === null ? null : at(end), canEdit: true, etag: '' })
const candidate = (workItemId: string, projectId: string, projectName: string) => ({ workItemId, projectId, projectName, title: workItemId }) as TimerCandidate

describe('快捷工时', () => {
  it.each([
    ['2026-10-19', -1, 'yesterday', '2026-10-18'],
    ['2026-10-18', -1, 'pick', '2026-10-17'],
    ['2026-10-17', 1, 'yesterday', '2026-10-18'],
    ['2026-10-19', 1, 'today', '2026-10-19'],
  ] as const)('日期 %s 步进 %s', (target, delta, choice, picked) => {
    expect(stepDate(target, delta, date)).toEqual({ choice, picked })
  })
  it('显示日期自身的星期', () => {
    expect(stepperLabel('2026-10-19')).toBe('10月19日 周一')
  })
  it('按侧边栏顺序分组，并把时长排入上班后的空档', () => {
    const groups = groupCandidates([candidate('a', 'p2', '运维中心'), candidate('b', 'p1', 'Yumpoo 平台'), candidate('c', 'p2', '运维中心')],
      [{ id: 'p1', name: 'Yumpoo 平台' }, { id: 'p3', name: '设计系统' }])
    expect(groups.map(group => [group.projectId, group.items.map(item => item.workItemId)])).toEqual([['p1', ['b']], ['p2', ['a', 'c']]])
    const day = {
      blocks: [{ id: 's', workItemId: 'x', projectId: 'p1', title: '', itemNo: null, projectName: null, colorToken: null,
        start: atMinute(date, 540, timezone), end: atMinute(date, 870, timezone), canEdit: true, etag: '' }],
      now: atMinute(date, 960, timezone), workday: { start: atMinute(date, 540, timezone), end: atMinute(date, 1080, timezone) },
      dayRange: dayWindow(date, timezone), limit: atMinute(date, 960, timezone),
    }
    expect(placementHint(quickPlan(day, HOUR), date, date, timezone, day.dayRange.end)).toBe('排入今日空档 14:30 – 15:30')
    expect(placementHint(quickPlan(day, 2 * HOUR), date, date, timezone, day.dayRange.end)).toBe('排入今日空档 14:30 – 16:00 · 还差 30 分钟')
    expect(placementHint(quickPlan({ ...day, limit: day.workday.start }, 15 * MINUTE), '2026-10-17', date, timezone, day.dayRange.end))
      .toBe('10月17日上班后已没有空档')
  })
  it('改时段从自动计划的第一个开始起算，没有空档时以可记录的最晚时刻结束', () => {
    const plan = { entries: [{ start: at(870), end: at(900) }, { start: at(930), end: at(960) }], leftoverMs: 0, truncated: false }
    expect(rangeFromPlan(plan, HOUR, at(960))).toEqual({ start: at(870), end: at(930) })
    expect(rangeFromPlan({ entries: [], leftoverMs: HOUR, truncated: false }, HOUR, at(960))).toEqual({ start: at(900), end: at(960) })
  })
  it.each([
    [600, 600, '结束时间要晚于开始时间'],
    [660, 600, '结束时间要晚于开始时间'],
    [600, 610, '至少记录 15 分钟'],
    [900, 990, '不能记录未来的时间'],
    [600, 660, ''],
  ])('时段 %s–%s 的校验', (start, end, problem) => {
    expect(rangeProblem({ start: at(start), end: at(end) }, dayWindow(date, timezone), at(960))).toBe(problem)
  })
  it('时段早于当天开始时提示', () => {
    const day = dayWindow(date, timezone)
    expect(rangeProblem({ start: day.start - HOUR, end: day.start + HOUR }, day, at(960))).toBe('开始时间不能早于当天')
  })
  it('只计算时段里还没有记录的部分，进行中的计时算到现在', () => {
    const blocks = [block(540, 570), block(600, null)]
    expect(rangeRecordable({ start: at(540), end: at(660) }, blocks, at(630))).toBe(30 * MINUTE + 30 * MINUTE)
    expect(rangeRecordable({ start: at(540), end: at(570) }, blocks, at(630))).toBe(0)
    expect(rangeRecordable({ start: at(480), end: at(540) }, blocks, at(630))).toBe(HOUR)
  })
})
