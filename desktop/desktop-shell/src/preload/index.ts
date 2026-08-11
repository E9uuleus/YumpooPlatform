import type {
  DesktopAuthErrorCode,
  DesktopAuthPhase,
  DesktopAuthStatus,
  DesktopBridge,
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

const desktopBridge: DesktopBridge = Object.freeze({
  client: 'electron',
  auth: desktopAuth,
})

contextBridge.exposeInMainWorld('yumpooDesktop', desktopBridge)
