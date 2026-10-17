import type { BrowserWindow, IpcMainInvokeEvent } from 'electron'
import { describe, expect, it, vi } from 'vitest'

vi.mock('electron', () => ({ BrowserWindow: class {}, session: { fromPartition: () => ({}) } }))

const {
  DIAGNOSTICS_PAGE_URL, isDiagnosticsAction, isTrustedDiagnosticsSender, registerDiagnosticsScheme, resolveDiagnosticsAsset,
} = await import('../src/main/diagnostics-window')

function fixture(frameUrl = DIAGNOSTICS_PAGE_URL) {
  const mainFrame = { url: frameUrl }
  const webContents = { mainFrame }
  const window = { isDestroyed: () => false, webContents } as unknown as BrowserWindow
  const event = { sender: webContents, senderFrame: mainFrame } as unknown as IpcMainInvokeEvent
  return { window, event, webContents }
}

describe('诊断窗', () => {
  it('以标准安全的自定义协议注册页面来源', () => {
    const registerSchemesAsPrivileged = vi.fn()
    registerDiagnosticsScheme({ registerSchemesAsPrivileged })
    expect(registerSchemesAsPrivileged).toHaveBeenCalledWith([{ scheme: 'yumpoo-diagnostics', privileges: { standard: true, secure: true } }])
    expect(DIAGNOSTICS_PAGE_URL).toBe('yumpoo-diagnostics://app/diagnostics.html')
  })

  it('协议只提供三个打包文件', () => {
    expect(resolveDiagnosticsAsset('yumpoo-diagnostics://app/diagnostics.html')).toEqual({ file: 'diagnostics.html', contentType: 'text/html; charset=utf-8' })
    expect(resolveDiagnosticsAsset('yumpoo-diagnostics://app/page.js?x=1')?.file).toBe('page.js')
    expect(resolveDiagnosticsAsset('yumpoo-diagnostics://app/diagnostics.css')?.contentType).toBe('text/css; charset=utf-8')
    for (const url of [
      'yumpoo-diagnostics://app/../main/index.js',
      'yumpoo-diagnostics://app/%2e%2e/main/index.js',
      'yumpoo-diagnostics://app/main/index.js',
      'yumpoo-diagnostics://other/diagnostics.html',
      'https://app/diagnostics.html',
      'not a url',
    ]) {
      expect(resolveDiagnosticsAsset(url)).toBeUndefined()
    }
  })

  it('只接受白名单动作', () => {
    expect(isDiagnosticsAction('export')).toBe(true)
    expect(isDiagnosticsAction('restart-normal')).toBe(true)
    expect(isDiagnosticsAction('rm -rf')).toBe(false)
    expect(isDiagnosticsAction(undefined)).toBe(false)
  })

  it('IPC 只信任诊断窗主框架中的打包页面', () => {
    const trusted = fixture()
    expect(isTrustedDiagnosticsSender(trusted.event, trusted.window)).toBe(true)

    const otherPage = fixture('https://yumpoo.example.com/')
    expect(isTrustedDiagnosticsSender(otherPage.event, otherPage.window)).toBe(false)

    const subframe = fixture()
    const frameEvent = { sender: subframe.webContents, senderFrame: { url: DIAGNOSTICS_PAGE_URL } } as unknown as IpcMainInvokeEvent
    expect(isTrustedDiagnosticsSender(frameEvent, subframe.window)).toBe(false)

    const otherWindow = fixture()
    expect(isTrustedDiagnosticsSender(trusted.event, otherWindow.window)).toBe(false)
    expect(isTrustedDiagnosticsSender(trusted.event, null)).toBe(false)
    const destroyed = { ...trusted.window, isDestroyed: () => true } as unknown as BrowserWindow
    expect(isTrustedDiagnosticsSender(trusted.event, destroyed)).toBe(false)
  })
})
