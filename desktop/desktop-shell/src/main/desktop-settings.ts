import { app, globalShortcut, ipcMain, type BrowserWindow } from 'electron'
import type { DesktopPreferencesState } from '@yumpoo/preload-contract'
import { isTrustedAuthIpcSender } from './auth-ipc'
import { validAccelerator, validDesktopPreferencesChange, type DesktopPreferenceStore } from './desktop-preferences'

/** Sign-in launches pass this; the shell then starts with only the tray and the rail. */
export const HIDDEN_ARGUMENT = '--hidden'
const LOGIN_ITEM = { args: [HIDDEN_ARGUMENT] }

export interface DesktopSettingsOptions {
  readonly store: DesktopPreferenceStore
  readonly main: () => BrowserWindow | null
  readonly origin: string
  toggleQuick(): void
}

/**
 * Settings page bridge, global shortcut and start at sign-in. Only the main window may read or change them;
 * changes made elsewhere (reordering on the rail, choosing the rail in the timer menu) are pushed back to it.
 */
export class DesktopSettingsController {
  constructor(private readonly options: DesktopSettingsOptions) {}

  install(): void {
    const { store } = this.options
    ipcMain.handle('yumpoo:preferences:get', event => { this.trusted(event); return this.state() })
    ipcMain.handle('yumpoo:preferences:set', (event, change: unknown) => {
      this.trusted(event)
      if (!validDesktopPreferencesChange(change)) throw new Error('INVALID_DESKTOP_PREFERENCES')
      const { launchAtLogin, ...stored } = change
      // Unpackaged runs would register electron.exe itself.
      if (launchAtLogin !== undefined && app.isPackaged) app.setLoginItemSettings({ ...LOGIN_ITEM, openAtLogin: launchAtLogin })
      store.update(stored)
      return this.state()
    })
    ipcMain.handle('yumpoo:preferences:shortcut', (event, accelerator: unknown) => {
      this.trusted(event)
      if (accelerator !== null && !validAccelerator(accelerator)) throw new Error('INVALID_SHORTCUT')
      const previous = store.get().shortcut
      if (previous) globalShortcut.unregister(previous)
      if (accelerator && !this.register(accelerator)) {
        if (previous) this.register(previous)
        return false
      }
      store.update({ shortcut: accelerator })
      return true
    })
    store.onChange(() => {
      const main = this.options.main()
      if (main && !main.isDestroyed()) main.webContents.send('yumpoo:preferences:changed', this.state())
    })
    const shortcut = store.get().shortcut
    if (shortcut) this.register(shortcut)
    app.on('will-quit', () => globalShortcut.unregisterAll())
  }

  private trusted(event: Parameters<typeof isTrustedAuthIpcSender>[0]): void {
    if (!isTrustedAuthIpcSender(event, this.options.main(), this.options.origin)) throw new Error('UNTRUSTED_IPC_SENDER')
  }

  private register(accelerator: string): boolean {
    return globalShortcut.register(accelerator, () => this.options.toggleQuick())
  }

  private state(): DesktopPreferencesState {
    const value = this.options.store.get()
    return {
      ...value,
      launchAtLogin: app.isPackaged ? app.getLoginItemSettings(LOGIN_ITEM).openAtLogin : null,
      shortcutActive: !!value.shortcut && globalShortcut.isRegistered(value.shortcut),
    }
  }
}
