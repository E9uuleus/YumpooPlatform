import type { DesktopExportFile } from '@yumpoo/preload-contract'
import type { BrowserWindow, Dialog, IpcMain, IpcMainInvokeEvent } from 'electron'
import path from 'node:path'
import { isTrustedAuthIpcSender } from './auth-ipc'

export const SAVE_EXPORT_CHANNEL = 'yumpoo:files:save-export'
export const MAX_EXPORT_BYTES = 50 * 1024 * 1024

const EXPORT_TYPES: Record<DesktopExportFile['mimeType'], { extension: string; name: string }> = {
  'application/pdf': { extension: '.pdf', name: 'PDF 文件' },
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': { extension: '.xlsx', name: 'Excel 工作簿' },
}

export interface ExportFileIpcOptions {
  readonly ipcMain: Pick<IpcMain, 'handle'>
  readonly dialog: Pick<Dialog, 'showSaveDialog'>
  readonly getMainWindow: () => BrowserWindow | null
  readonly allowedOrigin: string
  readonly downloadsPath: () => string
  readonly writeFile: (filePath: string, data: Uint8Array) => Promise<void>
}

/** Only a sanitized base name reaches the dialog; the renderer never chooses a directory. */
export function validateExportFile(value: unknown): DesktopExportFile & { safeName: string } {
  if (!value || typeof value !== 'object') throw new Error('INVALID_EXPORT_FILE')
  const file = value as Partial<DesktopExportFile>
  const type = typeof file.mimeType === 'string' ? EXPORT_TYPES[file.mimeType as DesktopExportFile['mimeType']] : undefined
  if (!type || !(file.data instanceof Uint8Array) || file.data.byteLength === 0 || file.data.byteLength > MAX_EXPORT_BYTES
    || typeof file.fileName !== 'string' || file.fileName.length > 200) throw new Error('INVALID_EXPORT_FILE')
  const base = path.basename(file.fileName.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_')).trim().replace(/^\.+/, '')
  const stem = base.toLowerCase().endsWith(type.extension) ? base.slice(0, -type.extension.length) : base
  return { fileName: file.fileName, mimeType: file.mimeType as DesktopExportFile['mimeType'], data: file.data,
    safeName: `${stem.slice(0, 150) || '导出'}${type.extension}` }
}

export function installExportFileIpc(options: ExportFileIpcOptions): void {
  options.ipcMain.handle(SAVE_EXPORT_CHANNEL, async (event: IpcMainInvokeEvent, value: unknown) => {
    const window = options.getMainWindow()
    if (!isTrustedAuthIpcSender(event, window, options.allowedOrigin) || !window) throw new Error('UNTRUSTED_IPC_SENDER')
    const file = validateExportFile(value)
    const type = EXPORT_TYPES[file.mimeType]
    const result = await options.dialog.showSaveDialog(window, {
      defaultPath: path.join(options.downloadsPath(), file.safeName),
      filters: [{ name: type.name, extensions: [type.extension.slice(1)] }],
    })
    if (result.canceled || !result.filePath) return false
    const target = path.extname(result.filePath).toLowerCase() === type.extension ? result.filePath : `${result.filePath}${type.extension}`
    await options.writeFile(target, file.data)
    return true
  })
}
