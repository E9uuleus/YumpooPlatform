import type { DashboardWidget } from '@yumpoo/api-client'

/** A4 landscape at 96 CSS px/in; the grid keeps the live 12-column, 40px-row, 8px-gutter geometry. */
export const EXPORT_PAGE = { width: 1123, height: 794, padding: 28, cover: 96, running: 28, footer: 24, row: 40, gutter: 8 } as const
export const EXPORT_GRID_WIDTH = EXPORT_PAGE.width - EXPORT_PAGE.padding * 2 + EXPORT_PAGE.gutter * 2
export const EXPORT_COLUMN = EXPORT_GRID_WIDTH / 12
/** Pages may shrink slightly to keep one more row of widgets instead of leaving half a page blank. */
export const MIN_EXPORT_SCALE = 0.8

export interface ExportPage { top: number; bottom: number; scale: number; widgets: DashboardWidget[] }

export function exportRowsPerPage(first: boolean): number {
  const { height, padding, footer, cover, running, row } = EXPORT_PAGE
  return Math.floor((height - padding * 2 - footer - (first ? cover : running)) / row)
}

/** Breaks only on rows no widget crosses; a block taller than one page is scaled down instead of cut. */
export function paginateDashboard(widgets: DashboardWidget[], firstRows = exportRowsPerPage(true), nextRows = exportRowsPerPage(false)): ExportPage[] {
  const sorted = [...widgets].sort((a, b) => a.wide.y - b.wide.y || a.wide.x - b.wide.x)
  const crossed = (row: number) => sorted.some(w => w.wide.y < row && row < w.wide.y + w.wide.h)
  const pages: ExportPage[] = []
  let rest = sorted
  while (rest.length) {
    const top = Math.min(...rest.map(w => w.wide.y)), capacity = pages.length ? nextRows : firstRows
    const ends = [...new Set(rest.map(w => w.wide.y + w.wide.h))].filter(row => !crossed(row)).sort((a, b) => a - b)
    const fitting = ends.filter(row => row - top <= capacity / MIN_EXPORT_SCALE)
    const bottom = fitting.length ? fitting[fitting.length - 1]! : ends[0]!
    pages.push({ top, bottom, scale: Math.min(1, capacity / (bottom - top)), widgets: rest.filter(w => w.wide.y < bottom) })
    rest = rest.filter(w => w.wide.y >= bottom)
  }
  return pages
}
