import { randomUUID } from 'node:crypto'
import { app, BrowserWindow, dialog, ipcMain, type IpcMainInvokeEvent } from 'electron'
import { isTrustedAuthIpcSender } from './auth-ipc'
import { createWindowOptions } from './window-policy'
import { installSecurityGuards } from './security-guards'

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
export function validTimerId(value: unknown): value is string { return typeof value === 'string' && uuid.test(value) }

export class TimerWindowController {
  private window: BrowserWindow | null = null
  private projectId: string | undefined
  private approvedExit = false
  private exitRequest: string | undefined
  private exitTimeout: ReturnType<typeof setTimeout> | undefined

  constructor(private readonly main: () => BrowserWindow | null, private readonly origin: string, private readonly preload: string) {}

  private trusted(event: IpcMainInvokeEvent, mainOnly = false): void {
    if (!isTrustedAuthIpcSender(event, this.main(), this.origin) && (mainOnly || !isTrustedAuthIpcSender(event, this.window, this.origin))) throw new Error('UNTRUSTED_IPC_SENDER')
  }

  install(): void {
    ipcMain.handle('yumpoo:timer:show', async event => { this.trusted(event, true); await this.show() })
    ipcMain.handle('yumpoo:timer:project', async (event, projectId: unknown) => {
      this.trusted(event, true)
      if (!validTimerId(projectId)) throw new Error('INVALID_TIMER_PROJECT')
      if (this.projectId === projectId) return
      this.projectId = projectId
      if (this.window) this.window.webContents.send('yumpoo:timer:project-changed', projectId)
      await this.show()
    })
    ipcMain.handle('yumpoo:timer:pin', (event, pinned: unknown) => {
      this.trusted(event)
      if (typeof pinned !== 'boolean') throw new Error('INVALID_TIMER_PIN')
      this.window?.setAlwaysOnTop(pinned)
    })
    ipcMain.handle('yumpoo:timer:refresh', event => {
      this.trusted(event)
      for (const target of [this.main(), this.window]) {
        if (target && !target.isDestroyed() && target.webContents !== event.sender) target.webContents.send('yumpoo:timer:changed')
      }
    })
    ipcMain.handle('yumpoo:timer:open-item', async (event, projectId: unknown, itemId: unknown) => {
      this.trusted(event)
      if (!validTimerId(projectId) || !validTimerId(itemId)) throw new Error('INVALID_TIMER_ITEM')
      const main = this.main()
      if (!main) return
      await main.loadURL(new URL(`/projects/${projectId}/overview?workItemId=${itemId}`, this.origin).href)
      if (main.isMinimized()) main.restore()
      main.show(); main.focus()
    })
    ipcMain.handle('yumpoo:timer:exit-received', (event, requestId: unknown) => {
      this.trusted(event, true)
      if (!validTimerId(requestId) || requestId !== this.exitRequest) throw new Error('INVALID_TIMER_EXIT')
      clearTimeout(this.exitTimeout)
    })
    ipcMain.handle('yumpoo:timer:exit-complete', (event, requestId: unknown, allow: unknown) => {
      this.trusted(event, true)
      if (!validTimerId(requestId) || typeof allow !== 'boolean' || requestId !== this.exitRequest) throw new Error('INVALID_TIMER_EXIT')
      clearTimeout(this.exitTimeout); this.exitRequest = undefined
      if (allow) { this.approvedExit = true; app.quit() }
    })
    app.on('before-quit', event => {
      if (!this.approvedExit && this.main()) { event.preventDefault(); this.requestExit() }
    })
  }

  attachMain(window: BrowserWindow): void {
    window.on('close', event => { if (!this.approvedExit) { event.preventDefault(); this.requestExit() } })
  }

  private requestExit(): void {
    if (this.exitRequest) return
    const main = this.main()
    if (!main || main.isDestroyed()) return
    const requestId = randomUUID()
    this.exitRequest = requestId
    if (main.isMinimized()) main.restore()
    main.show(); main.focus()
    main.webContents.send('yumpoo:timer:exit-request', requestId)
    this.exitTimeout = setTimeout(() => {
      if (this.exitRequest !== requestId) return
      void dialog.showMessageBox(main, { type: 'warning', message: '应用未能确认计时状态', detail: '可取消退出后重试，或明确选择继续计时并退出。计时不会自动停止。', buttons: ['取消退出', '继续计时并退出'], defaultId: 0, cancelId: 0 }).then(result => {
        if (this.exitRequest !== requestId) return
        this.exitRequest = undefined
        if (result.response === 1) { this.approvedExit = true; app.quit() }
      })
    }, 30000)
  }

  private async show(): Promise<void> {
    if (this.window && !this.window.isDestroyed()) { this.window.showInactive(); return }
    const window = new BrowserWindow({ ...createWindowOptions(this.preload, app.isPackaged), width: 380, height: 560, minWidth: 320, minHeight: 360, alwaysOnTop: true, title: 'Yumpoo 小计时器' })
    this.window = window
    installSecurityGuards(window.webContents, this.origin)
    window.on('close', event => { if (!this.approvedExit) { event.preventDefault(); window.hide() } })
    window.on('closed', () => { this.window = null })
    window.once('ready-to-show', () => window.showInactive())
    await window.loadURL(new URL(`/timer${this.projectId ? `?projectId=${this.projectId}` : ''}`, this.origin).href)
  }
}
