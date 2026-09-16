import { describe, expect, it } from 'vitest'
import type { DashboardBucket, DashboardConnection } from '@yumpoo/api-client'
import { defaultConfiguration, drillFilters, emptyFilters, newWidget, statusGroups } from './dashboardModel'
describe('dashboard model', () => {
  it('keeps the default layouts within each canvas without overlap', () => {
    const widgets = defaultConfiguration().widgets
    expect(widgets).toHaveLength(8)
    for (const [layout, columns] of [['wide', 12], ['medium', 6]] as const) {
      for (const [index, widget] of widgets.entries()) {
        const a = widget[layout]
        expect(a.x + a.w).toBeLessThanOrEqual(columns)
        for (const other of widgets.slice(index + 1)) { const b = other[layout]; expect(a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h).toBe(false) }
      }
    }
    const added = newWidget('STATUS', widgets)
    expect(added.wide.y).toBe(22); expect(added.medium.y).toBe(44)
  })
  it('merges only identical status definitions and preserves drilldown keys', () => {
    const bucket = (project: string, color: string): DashboardBucket => ({ kind: 'STATUS', key: `${project}:OPEN`, projectId: project, userId: null, label: '进行中', code: 'OPEN', category: 'IN_PROGRESS', colorToken: color, count: 2, inProgress: 2, done: 0, durationMs: 0 })
    const projects: DashboardConnection[] = ['a', 'b', 'c'].map(id => ({ id, name: id, code: id, lifecycle: 'ACTIVE', available: true }))
    const groups = statusGroups([bucket('a', 'BLUE'), bucket('b', 'BLUE'), bucket('c', 'RED')], projects, 'STATUS')
    expect(groups).toHaveLength(2); expect(groups[0]?.count).toBe(4); expect(groups[0]?.keys).toEqual(['a:OPEN', 'b:OPEN'])
    expect(statusGroups([bucket('a', 'BLUE'), bucket('c', 'RED')], projects, 'CATEGORY')).toHaveLength(1)
  })
  it('drills into the selected items without changing global filters', () => {
    const base = emptyFilters(); base.assignees = ['person']; base.query = 'release'
    const details = drillFilters(base, newWidget('PROJECT_TIME'), { kind: 'PROJECT', keys: ['project'] })
    expect(details).toMatchObject({ projectIds: ['project'], assignees: ['person'], query: 'release', hasTime: true })
    expect(base.projectIds).toEqual([]); expect(base.hasTime).toBe(false)
  })
})
