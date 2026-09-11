import type {
  DesktopAuthErrorCode,
  DesktopAuthPhase,
  DesktopAuthStatus,
  DesktopBridge,
  DesktopTimerBridge,
  DesktopTimerState,
  DesktopTimerCommand,
  TimerWindowMode,
  TimerOrbChange,
} from '@yumpoo/preload-contract'
import { contextBridge, ipcRenderer } from 'electron'

const AUTH_PHASES = new Set<DesktopAuthPhase>([
  'IDLE',
  'OPENING_BROWSER',
  'WAITING_FOR_CALLBACK',
  'EXCHANGING',
  'SUCCEEDED',
  'FAILED',
])
const AUTH_ERROR_CODES = new Set<DesktopAuthErrorCode>([
  'AUTH_DISABLED',
  'AUTH_IN_PROGRESS',
  'BROWSER_OPEN_FAILED',
  'INVALID_CALLBACK',
  'NO_PENDING_ATTEMPT',
  'ATTEMPT_EXPIRED',
  'STATE_MISMATCH',
  'EXCHANGE_FAILED',
  'IPC_REJECTED',
])

function sanitizeStatus(value: unknown): DesktopAuthStatus | undefined {
  if (!value || typeof value !== 'object') {
    return undefined
  }
  const candidate = value as { phase?: unknown; errorCode?: unknown }
  if (
    typeof candidate.phase !== 'string' ||
    !AUTH_PHASES.has(candidate.phase as DesktopAuthPhase)
  ) {
    return undefined
  }
  if (candidate.phase === 'FAILED') {
    if (
      typeof candidate.errorCode !== 'string' ||
      !AUTH_ERROR_CODES.has(candidate.errorCode as DesktopAuthErrorCode)
    ) {
      return undefined
    }
    return Object.freeze({
      phase: 'FAILED',
      errorCode: candidate.errorCode as DesktopAuthErrorCode,
    })
  }
  return Object.freeze({ phase: candidate.phase as DesktopAuthPhase })
}

const desktopAuth = Object.freeze({
  isEnabled: async (): Promise<boolean> =>
    Boolean(await ipcRenderer.invoke('yumpoo:auth:is-enabled')),
  start: async (): Promise<void> => {
    await ipcRenderer.invoke('yumpoo:auth:start')
  },
  clear: async (): Promise<void> => {
    await ipcRenderer.invoke('yumpoo:auth:clear')
  },
  onStatus(listener: (status: DesktopAuthStatus) => void): () => void {
    if (typeof listener !== 'function') {
      throw new TypeError('Desktop auth status listener must be a function')
    }
    const wrappedListener = (_event: unknown, value: unknown): void => {
      const safeStatus = sanitizeStatus(value)
      if (safeStatus) {
        listener(safeStatus)
      }
    }
    ipcRenderer.on('yumpoo:auth:status', wrappedListener)
    return () => {
      ipcRenderer.removeListener('yumpoo:auth:status', wrappedListener)
    }
  },
})

const desktopTimer: DesktopTimerBridge = Object.freeze({
  show: async (mode: TimerWindowMode = 'picker', activate = true) => { await ipcRenderer.invoke('yumpoo:timer:show', mode, activate) },
  hide: async () => { await ipcRenderer.invoke('yumpoo:timer:hide') },
  setMode: async (mode: TimerWindowMode) => { await ipcRenderer.invoke('yumpoo:timer:mode', mode) },
  getWindowState: async () => ipcRenderer.invoke('yumpoo:timer:window-state'),
  setOrbLayout: async (change: TimerOrbChange) => ipcRenderer.invoke('yumpoo:timer:orb-layout', change),
  setAlwaysOnTop: async (value: boolean) => { await ipcRenderer.invoke('yumpoo:timer:pin', value) },
  publishState: async (state: DesktopTimerState) => { await ipcRenderer.invoke('yumpoo:timer:state', state) },
  onCommand: (listener: (command: DesktopTimerCommand) => void) => {
    if (typeof listener !== 'function') throw new TypeError('Timer listener must be a function')
    const wrapped = (_event: unknown, value: DesktopTimerCommand) => {
      if (value && typeof value.requestId === 'string' && (value.action === 'start' || value.action === 'stop')) listener(value)
    }
    ipcRenderer.on('yumpoo:timer:command', wrapped)
    return () => ipcRenderer.removeListener('yumpoo:timer:command', wrapped)
  },
  completeCommand: async (id: string, success: boolean) => { await ipcRenderer.invoke('yumpoo:timer:command-complete', id, success) },
  onCommandFailed: (listener: () => void) => {
    if (typeof listener !== 'function') throw new TypeError('Timer listener must be a function')
    const wrapped = () => listener()
    ipcRenderer.on('yumpoo:timer:command-failed', wrapped)
    return () => ipcRenderer.removeListener('yumpoo:timer:command-failed', wrapped)
  },
  onMode: (listener: (mode: TimerWindowMode) => void) => {
    if (typeof listener !== 'function') throw new TypeError('Timer listener must be a function')
    const wrapped = (_event: unknown, mode: unknown) => { if (mode === 'compact' || mode === 'picker') listener(mode) }
    ipcRenderer.on('yumpoo:timer:mode-changed', wrapped)
    return () => ipcRenderer.removeListener('yumpoo:timer:mode-changed', wrapped)
  },
  refresh: async () => { await ipcRenderer.invoke('yumpoo:timer:refresh') },
  onOrbHover: (listener: (hovered: boolean) => void) => {
    if (typeof listener !== 'function') throw new TypeError('Timer listener must be a function')
    const wrapped = (_event: unknown, hovered: unknown) => { if (typeof hovered === 'boolean') listener(hovered) }
    ipcRenderer.on('yumpoo:timer:orb-hover', wrapped)
    return () => ipcRenderer.removeListener('yumpoo:timer:orb-hover', wrapped)
  },
  onRefresh: (listener: () => void) => {
    if (typeof listener !== 'function') throw new TypeError('Timer listener must be a function')
    const wrapped = () => { listener() }
    ipcRenderer.on('yumpoo:timer:changed', wrapped)
    return () => ipcRenderer.removeListener('yumpoo:timer:changed', wrapped)
  },
  onExitRequest: (listener: (id: string) => void) => {
    if (typeof listener !== 'function') throw new TypeError('Timer listener must be a function')
    const wrapped = (_event: unknown, value: unknown) => { if (typeof value === 'string' && /^[0-9a-f-]{36}$/i.test(value)) listener(value) }
    ipcRenderer.on('yumpoo:timer:exit-request', wrapped)
    return () => ipcRenderer.removeListener('yumpoo:timer:exit-request', wrapped)
  },
  acknowledgeExit: async (id: string) => { await ipcRenderer.invoke('yumpoo:timer:exit-received', id) },
  completeExit: async (id: string, allow: boolean) => { await ipcRenderer.invoke('yumpoo:timer:exit-complete', id, allow) },
  openWorkItem: async (project: string, item: string) => { await ipcRenderer.invoke('yumpoo:timer:open-item', project, item) },
})

const desktopBridge: DesktopBridge = Object.freeze({
  client: 'electron',
  auth: desktopAuth,
  timer: desktopTimer,
})

contextBridge.exposeInMainWorld('yumpooDesktop', desktopBridge)
