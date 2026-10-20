import { describe, expect, it } from 'vitest'
import type { TimerCandidate } from '@yumpoo/api-client'
import { atMinute, dayWindow, HOUR, MINUTE } from '../my-time/day/dayModel'
import { groupCandidates, placementHint, quickPlan } from './quickTime'

const timezone = 'Asia/Shanghai'
const date = '2026-10-19'
const candidate = (workItemId: string, projectId: string, projectName: string) => ({ workItemId, projectId, projectName, title: workItemId }) as TimerCandidate

describe('快捷工时', () => {
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
})
