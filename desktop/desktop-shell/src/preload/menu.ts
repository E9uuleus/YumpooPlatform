import { contextBridge, ipcRenderer } from 'electron'
import type { DesktopInboxPreferences, DesktopMenuBridge, TimerMenuAction, TimerMenuState } from '@yumpoo/preload-contract'

const menu: DesktopMenuBridge = Object.freeze({
  getState: async () => ipcRenderer.invoke('yumpoo:menu:get-state'),
  ready: async () => { await ipcRenderer.invoke('yumpoo:menu:ready') },
  runAction: async (action: TimerMenuAction) => { await ipcRenderer.invoke('yumpoo:timer:menu-action', action) },
  getPreferences: async () => ipcRenderer.invoke('yumpoo:inbox:get-preferences'),
  setPreferences: async (change: DesktopInboxPreferences) => ipcRenderer.invoke('yumpoo:inbox:preferences', change),
  onState: (listener: (state: TimerMenuState) => void) => {
    if (typeof listener !== 'function') throw new TypeError('Menu listener must be a function')
    const wrapped = (_event: unknown, state: TimerMenuState) => listener(state)
    ipcRenderer.on('yumpoo:timer:menu-state', wrapped)
    return () => ipcRenderer.removeListener('yumpoo:timer:menu-state', wrapped)
  },
})
contextBridge.exposeInMainWorld('yumpooMenu', menu)
