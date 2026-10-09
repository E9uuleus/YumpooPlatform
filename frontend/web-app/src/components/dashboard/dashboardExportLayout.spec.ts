import { describe, expect, it } from 'vitest'
import { defaultConfiguration, newWidget } from './dashboardModel'
import { exportRowsPerPage, paginateDashboard } from './dashboardExportLayout'

describe('dashboard PDF pagination', () => {
  it('breaks only between widgets and keeps the wide layout rows', () => {
    expect([exportRowsPerPage(true), exportRowsPerPage(false)]).toEqual([15, 17])
    const widgets = defaultConfiguration().widgets
    const pages = paginateDashboard(widgets)
    expect(pages.map(page => [page.top, page.bottom, page.scale, page.widgets.length])).toEqual([[0, 16, 0.9375, 6], [16, 25, 1, 2]])
    expect(pages.flatMap(page => page.widgets)).toHaveLength(widgets.length)
  })
  it('scales a block taller than one page instead of cutting it', () => {
    const tall = newWidget('CHART')
    tall.wide = { x: 0, y: 2, w: 12, h: 30 }
    const [page] = paginateDashboard([tall])
    expect(page).toMatchObject({ top: 2, bottom: 32, scale: 0.5 })
  })
})
