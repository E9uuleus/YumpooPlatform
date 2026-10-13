import { describe, expect, it, vi } from 'vitest'
import { projectStatisticsTemplate, worklogGroups } from './projectStatisticsTemplate'
import { toApiDate } from '../team/teamDashboardModel'

describe('project statistics template', () => {
  it('uses live overdue counts, cumulative duration and descending member worklogs', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-13T04:00:00Z'))
    try {
      const template = projectStatisticsTemplate('Asia/Shanghai')
      expect(template.overdue.chart?.filters?.categories).toEqual(['TODO', 'IN_PROGRESS'])
      expect(template.overdue.chart?.filters?.dueTo).toEqual(toApiDate('2026-10-12'))
      expect(template.duration.chart?.filters).toBeNull()
      expect(template.inProgress.chart?.filters?.includeArchived).toBe(false)

      const groups = worklogGroups({
        from: toApiDate('2026-10-05'), to: toApiDate('2026-10-11'), asOf: new Date(),
        members: [
          { userId: 'a', displayName: '成员甲', projectMember: true },
          { userId: 'b', displayName: '成员乙', projectMember: false },
        ],
        entries: [
          { userId: 'a', workItemId: 'a1', itemNo: 'P-1', title: '任务一', durationMs: 60_000 },
          { userId: 'b', workItemId: 'b1', itemNo: 'P-2', title: '任务二', durationMs: 120_000 },
          { userId: 'b', workItemId: 'b2', itemNo: 'P-3', title: '任务三', durationMs: 240_000 },
        ],
      })
      expect(groups.map(group => [group.userId, group.totalMs])).toEqual([['b', 360_000], ['a', 60_000]])
      expect(groups[0]?.entries.map(entry => entry.workItemId)).toEqual(['b2', 'b1'])
    } finally {
      vi.useRealTimers()
    }
  })
})
