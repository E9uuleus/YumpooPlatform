import { createApp, nextTick } from 'vue'
import type { DashboardChartResult, DashboardWidget } from '@yumpoo/api-client'
import DashboardExportSheet from './DashboardExportSheet.vue'
import { EXPORT_PAGE, paginateDashboard } from './dashboardExportLayout'

export interface DashboardPdfInput {
  name: string
  exportedAt: string
  projects: string[]
  filters: string[]
  widgets: DashboardWidget[]
  results: Map<string, DashboardChartResult>
}

/** Renders the wide layout off-screen in the light theme and rasterizes each A4 page at 2x. */
export async function exportDashboardPdf(input: DashboardPdfInput): Promise<Blob> {
  const [{ jsPDF }, { domToPng }] = await Promise.all([import('jspdf'), import('modern-screenshot')])
  const host = document.createElement('div')
  host.className = 'dashboard-export-host'
  host.setAttribute('aria-hidden', 'true')
  document.body.append(host)
  const app = createApp(DashboardExportSheet, { ...input, pages: paginateDashboard(input.widgets) })
  try {
    app.mount(host)
    await document.fonts.ready
    await nextTick()
    await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
    const pdf = new jsPDF({ orientation: 'landscape', unit: 'mm', format: 'a4', compress: true })
    pdf.setProperties({ title: input.name, creator: 'Yumpoo' })
    const pages = [...host.querySelectorAll<HTMLElement>('.dashboard-export-page')]
    for (const [index, page] of pages.entries()) {
      const image = await domToPng(page, { scale: 2, width: EXPORT_PAGE.width, height: EXPORT_PAGE.height })
      if (index) pdf.addPage('a4', 'landscape')
      pdf.addImage(image, 'PNG', 0, 0, 297, 210, undefined, 'FAST')
    }
    return pdf.output('blob')
  } finally {
    app.unmount()
    host.remove()
  }
}
