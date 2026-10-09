import type { DesktopExportFile } from '@yumpoo/preload-contract'

export const PDF_TYPE = 'application/pdf'
export const XLSX_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'

/** `名称-20261008-1432.pdf`; characters Windows rejects in file names become `_`. */
export function exportFileName(base: string, extension: 'pdf' | 'xlsx', now = new Date()): string {
  const pad = (value: number) => String(value).padStart(2, '0')
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}`
  return `${base.replace(/[\\/:*?"<>|]/g, '_').trim().slice(0, 80) || '导出'}-${stamp}.${extension}`
}

/** Resolves false when the desktop save dialog is canceled. */
export async function saveExportFile(blob: Blob, fileName: string, mimeType: DesktopExportFile['mimeType']): Promise<boolean> {
  const desktop = window.yumpooDesktop
  if (desktop) {
    if (!desktop.files) throw new Error('当前客户端版本不支持导出文件，请升级客户端或在浏览器中导出')
    return desktop.files.saveExport({ fileName, mimeType, data: new Uint8Array(await blob.arrayBuffer()) })
  }
  const url = URL.createObjectURL(blob), link = document.createElement('a')
  link.href = url; link.download = fileName; link.rel = 'noopener'
  document.body.append(link); link.click(); link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
  return true
}
