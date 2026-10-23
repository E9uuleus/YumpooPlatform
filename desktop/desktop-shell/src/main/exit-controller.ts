import { randomUUID } from 'node:crypto'
import { app, dialog, ipcMain, type BrowserWindow, type IpcMainInvokeEvent } from 'electron'
import type { DesktopExitPhase } from '@yumpoo/preload-contract'
import { isTrustedAuthIpcSender } from './auth-ipc'

const RESPONSE_TIMEOUT = 30_000
const validPhase = (value: unknown): value is DesktopExitPhase => value === 'checking' || value === 'prompt' || value === 'saving'

/** Approval freezes every surface before Electron starts closing windows. */
export class ExitController {
  private phase: DesktopExitPhase | 'idle' | 'fallback' | 'quitting' = 'idle'
  private requestId: string | undefined
  private ready = false
  private timeout: ReturnType<typeof setTimeout> | undefined

  constructor(private readonly main: () => BrowserWindow | null, private readonly origin: string,
    private readonly host: { shutdown(): void; cancelled(): void }) {}

  get quitting(): boolean { return this.phase === 'quitting' }
  get pending(): boolean { return this.phase !== 'idle' }

  install(): void {
    const trusted = (event: IpcMainInvokeEvent) => {
      if (!isTrustedAuthIpcSender(event, this.main(), this.origin)) throw new Error('UNTRUSTED_IPC_SENDER')
    }
    ipcMain.handle('yumpoo:timer:exit-received', (event, id: unknown) => {
      trusted(event); this.assertRequest(id)
      // Receipt is not completion: checking the server can still hang after this acknowledgement.
    })
    ipcMain.handle('yumpoo:timer:exit-phase', (event, id: unknown, phase: unknown) => {
      trusted(event)
      if (!validPhase(phase)) throw new Error('INVALID_TIMER_EXIT_PHASE')
      if (!this.requestId || id !== this.requestId || this.phase === 'fallback' || this.quitting) return false
      this.phase = phase
      clearTimeout(this.timeout)
      if (phase !== 'prompt') this.armTimeout()
      return true
    })
    ipcMain.handle('yumpoo:timer:exit-complete', (event, id: unknown, allow: unknown) => {
      trusted(event); this.assertRequest(id)
      if (typeof allow !== 'boolean') throw new Error('INVALID_TIMER_EXIT')
      if (this.phase !== 'fallback') this.settle(allow)
    })
    app.on('before-quit', event => {
      if (this.quitting) return
      event.preventDefault()
      this.request()
    })
    app.on('will-quit', () => clearTimeout(this.timeout))
  }

  attachMain(window: BrowserWindow): void {
    this.ready = false
    const unavailable = () => {
      this.ready = false
      if (this.pending && !this.quitting) void this.fallback()
    }
    window.webContents.on('did-start-navigation', (_event, _url, isInPlace, isMainFrame) => {
      if (isMainFrame && !isInPlace) unavailable()
    })
    window.webContents.on('render-process-gone', unavailable)
    window.on('unresponsive', unavailable)
    window.on('closed', unavailable)
  }

  /** A published state proves TimerHost and its exit listener have mounted, including on older Web builds. */
  markReady(): void { if (!this.quitting) this.ready = true }

  request(): void {
    if (this.pending) return
    const main = this.main()
    if (!main || main.isDestroyed()) { this.settle(true); return }
    this.requestId = randomUUID()
    this.phase = 'checking'
    if (!this.ready) { void this.fallback(); return }
    if (main.isMinimized()) main.restore()
    main.show(); main.focus()
    this.armTimeout()
    main.webContents.send('yumpoo:timer:exit-request', this.requestId)
  }

  private assertRequest(id: unknown): void {
    if (typeof id !== 'string' || !this.requestId || id !== this.requestId) throw new Error('INVALID_TIMER_EXIT')
  }

  private armTimeout(): void {
    clearTimeout(this.timeout)
    this.timeout = setTimeout(() => { void this.fallback() }, RESPONSE_TIMEOUT)
  }

  private async fallback(): Promise<void> {
    if (!this.requestId || this.phase === 'fallback' || this.quitting) return
    clearTimeout(this.timeout)
    this.phase = 'fallback'
    const id = this.requestId
    const main = this.main()
    if (main && !main.isDestroyed()) main.webContents.send('yumpoo:timer:exit-cancelled', id)
    const options = { type: 'warning' as const, message: '应用未能确认计时状态',
      detail: '可取消退出后重试，或明确选择继续计时并退出。计时不会自动停止；尚未确认的保存结果请重新打开后核对。',
      buttons: ['取消退出', '继续计时并退出'], defaultId: 0, cancelId: 0 }
    try {
      const result = await (main && !main.isDestroyed() ? dialog.showMessageBox(main, options) : dialog.showMessageBox(options))
      if (this.requestId === id) this.settle(result.response === 1)
    } catch {
      if (this.requestId === id) this.settle(false)
    }
  }

  private settle(allow: boolean): void {
    clearTimeout(this.timeout)
    this.requestId = undefined
    this.phase = allow ? 'quitting' : 'idle'
    if (!allow) { this.host.cancelled(); return }
    this.host.shutdown()
    // Let an IPC completion response leave before closing its sender.
    setImmediate(() => app.quit())
  }
}
