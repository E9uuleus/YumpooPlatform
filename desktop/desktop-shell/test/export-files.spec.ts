import type { BrowserWindow, IpcMainInvokeEvent } from 'electron'
import path from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { installExportFileIpc, SAVE_EXPORT_CHANNEL, validateExportFile } from '../src/main/export-files'

const origin = 'https://yumpoo.example.com'
const pdf = 'application/pdf'

function fixture(saveTo: string | undefined) {
  const frame = { url: `${origin}/workspace/w/dashboards` }
  const webContents = { mainFrame: frame }
  const mainWindow = { isDestroyed: () => false, webContents } as unknown as BrowserWindow
  const event = { sender: webContents, senderFrame: frame } as unknown as IpcMainInvokeEvent
  let handler: ((event: IpcMainInvokeEvent, value: unknown) => Promise<boolean>) | undefined
  const dialog = { showSaveDialog: vi.fn(async () => ({ canceled: !saveTo, filePath: saveTo ?? '' })) }
  const writeFile = vi.fn(async () => undefined)
  installExportFileIpc({
    ipcMain: { handle: (channel: string, listener: never) => { if (channel === SAVE_EXPORT_CHANNEL) handler = listener } } as never,
    dialog: dialog as never,
    getMainWindow: () => mainWindow,
    allowedOrigin: origin,
    downloadsPath: () => path.join('C:', 'Users', 'me', 'Downloads'),
    writeFile,
  })
  return { event, dialog, writeFile, invoke: (e: IpcMainInvokeEvent, value: unknown) => handler!(e, value) }
}

describe('导出文件保存 IPC', () => {
  it('只接受白名单类型并清理文件名', () => {
    const data = new Uint8Array([1])
    expect(validateExportFile({ fileName: '../报表:Q3?.pdf', mimeType: pdf, data }).safeName).toBe('_报表_Q3_.pdf')
    expect(validateExportFile({ fileName: '工时', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', data }).safeName).toBe('工时.xlsx')
    expect(() => validateExportFile({ fileName: 'a.exe', mimeType: 'application/x-msdownload', data })).toThrow('INVALID_EXPORT_FILE')
    expect(() => validateExportFile({ fileName: 'a.pdf', mimeType: pdf, data: new Uint8Array() })).toThrow('INVALID_EXPORT_FILE')
  })

  it('经原生对话框保存，取消时返回 false，拒绝伪造 sender', async () => {
    const saved = fixture(path.join('D:', 'out', '仪表板'))
    await expect(saved.invoke(saved.event, { fileName: '仪表板.pdf', mimeType: pdf, data: new Uint8Array([1, 2]) })).resolves.toBe(true)
    expect(saved.dialog.showSaveDialog.mock.calls[0]![1]).toMatchObject({ defaultPath: path.join('C:', 'Users', 'me', 'Downloads', '仪表板.pdf') })
    expect(saved.writeFile).toHaveBeenCalledWith(path.join('D:', 'out', '仪表板.pdf'), new Uint8Array([1, 2]))

    const canceled = fixture(undefined)
    await expect(canceled.invoke(canceled.event, { fileName: 'a.pdf', mimeType: pdf, data: new Uint8Array([1]) })).resolves.toBe(false)
    expect(canceled.writeFile).not.toHaveBeenCalled()

    const forged = { ...saved.event, senderFrame: { url: 'https://attacker.example' } } as never
    await expect(saved.invoke(forged, { fileName: 'a.pdf', mimeType: pdf, data: new Uint8Array([1]) })).rejects.toThrow('UNTRUSTED_IPC_SENDER')
  })
})
