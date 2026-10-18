import type { DesktopDiagnosticsBridge, DiagnosticsAction } from '@yumpoo/preload-contract'
import { contextBridge, ipcRenderer } from 'electron'

// Loaded only into the packaged diagnostics window; the web app's preload never exposes these channels.
const bridge: DesktopDiagnosticsBridge = {
  getReport: () => ipcRenderer.invoke('yumpoo:diagnostics:report'),
  runAction: (action: DiagnosticsAction) => ipcRenderer.invoke('yumpoo:diagnostics:action', action),
}

contextBridge.exposeInMainWorld('yumpooDiagnostics', Object.freeze(bridge))
