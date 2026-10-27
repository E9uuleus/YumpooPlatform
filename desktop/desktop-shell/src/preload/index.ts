import type {
  DesktopAuthErrorCode,
  DesktopAuthPhase,
  DesktopAuthStatus,
  DesktopAuthStartOptions,
  DesktopBridge,
  DesktopPreferencesBridge,
  DesktopPreferencesChange,
  DesktopPreferencesState,
  DesktopQuickBridge,
  DesktopRailBridge,
  QuickPanelContext,
  RailProjects,
  RailState,
  DesktopExportFile,
  DesktopFilesBridge,
  DesktopInboxBridge,
  DesktopInboxState,
  DesktopInboxPreferences,
  DesktopTimerBridge,
  DesktopAppearance,
  DesktopExitPhase,
  DesktopTimerState,
  DesktopTimerCommand,
  DesktopUpdateBridge,
  DesktopUpdatePhase,
  DesktopUpdateState,
  TimerMenuAction,
  TimerMenuState,
  TimerWindowMode,
  TimerOrbChange,
  TimerPreferencesChange,
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
  'AUTH_SERVICE_FAILED',
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
  start: async (options?: DesktopAuthStartOptions): Promise<void> => {
    await ipcRenderer.invoke('yumpoo:auth:start', { remember: options?.remember !== false })
  },
  cancel: async (): Promise<void> => {
    await ipcRenderer.invoke('yumpoo:auth:cancel')
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
  setPreferences: async (change: TimerPreferencesChange) => ipcRenderer.invoke('yumpoo:timer:preferences', change),
  onMenuState: (listener: (state: TimerMenuState) => void) => {
    if (typeof listener !== 'function') throw new TypeError('Timer listener must be a function')
    const wrapped = (_event: unknown, value: TimerMenuState) => {
      if (value && typeof value === 'object' && typeof value.signedIn === 'boolean' && (value.display === 'orb' || value.display === 'dock')) listener(value)
    }
    ipcRenderer.on('yumpoo:timer:menu-state', wrapped)
    return () => ipcRenderer.removeListener('yumpoo:timer:menu-state', wrapped)
  },
  runMenuAction: async (action: TimerMenuAction) => { await ipcRenderer.invoke('yumpoo:timer:menu-action', action) },
  dragWindow: async (active: boolean) => { await ipcRenderer.invoke('yumpoo:timer:drag', active) },
  onDragging: (listener: (dragging: boolean) => void) => {
    if (typeof listener !== 'function') throw new TypeError('Timer listener must be a function')
    const wrapped = (_event: unknown, dragging: unknown) => { if (typeof dragging === 'boolean') listener(dragging) }
    ipcRenderer.on('yumpoo:timer:dragging', wrapped)
    return () => ipcRenderer.removeListener('yumpoo:timer:dragging', wrapped)
  },
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
    const wrapped = (_event: unknown, mode: unknown) => { if (mode === 'compact' || mode === 'picker' || mode === 'settings') listener(mode) }
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
  reportExitPhase: async (id: string, phase: DesktopExitPhase) => ipcRenderer.invoke('yumpoo:timer:exit-phase', id, phase),
  publishAppearance: async (appearance: DesktopAppearance) => { await ipcRenderer.invoke('yumpoo:timer:appearance', appearance) },
  onExitCancelled: (listener: (id: string) => void) => {
    if (typeof listener !== 'function') throw new TypeError('Timer listener must be a function')
    const wrapped = (_event: unknown, value: unknown) => { if (typeof value === 'string') listener(value) }
    ipcRenderer.on('yumpoo:timer:exit-cancelled', wrapped)
    return () => ipcRenderer.removeListener('yumpoo:timer:exit-cancelled', wrapped)
  },
  completeExit: async (id: string, allow: boolean) => { await ipcRenderer.invoke('yumpoo:timer:exit-complete', id, allow) },
  openWorkItem: async (project: string, item: string) => { await ipcRenderer.invoke('yumpoo:timer:open-item', project, item) },
})

const desktopInbox: DesktopInboxBridge = Object.freeze({
  supportedReasons: Object.freeze(['MENTION', 'REPLY', 'COMMENT', 'ASSIGNED', 'PROJECT_MEMBER_ADDED',
    'PROJECT_MEMBER_REMOVED', 'PROJECT_OWNER_ASSIGNED', 'PROJECT_OWNER_TRANSFERRED', 'CONNECTION_CREATED',
    'PROJECT_DELETION_SCHEDULED', 'PROJECT_DELETION_REMINDER', 'PROJECT_DELETION_CANCELLED'] as const),
  publishState: async (state: DesktopInboxState) => { await ipcRenderer.invoke('yumpoo:inbox:state', state) },
  getPreferences: async () => ipcRenderer.invoke('yumpoo:inbox:get-preferences'),
  setPreferences: async (change: DesktopInboxPreferences) => ipcRenderer.invoke('yumpoo:inbox:preferences', change),
  onOpen: (listener: (notificationId: string | null) => void) => {
    if (typeof listener !== 'function') throw new TypeError('Inbox listener must be a function')
    const wrapped = (_event: unknown, value: unknown) => {
      if (value === null || (typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value))) listener(value)
    }
    ipcRenderer.on('yumpoo:inbox:open', wrapped)
    return () => ipcRenderer.removeListener('yumpoo:inbox:open', wrapped)
  },
})

const desktopFiles: DesktopFilesBridge = Object.freeze({
  saveExport: async (file: DesktopExportFile) => {
    if (!file || typeof file.fileName !== 'string' || typeof file.mimeType !== 'string' || !(file.data instanceof Uint8Array)) {
      throw new TypeError('Export file is invalid')
    }
    return (await ipcRenderer.invoke('yumpoo:files:save-export', { fileName: file.fileName, mimeType: file.mimeType, data: file.data })) === true
  },
})

const UPDATE_PHASES = new Set<DesktopUpdatePhase>(['disabled', 'idle', 'checking', 'available', 'downloading', 'verifying', 'ready', 'error'])
const shortText = (value: unknown) => typeof value === 'string' && value.length > 0 && value.length <= 64

function sanitizeUpdateState(value: unknown): DesktopUpdateState | undefined {
  if (!value || typeof value !== 'object') return undefined
  const state = value as Record<string, unknown>
  if (typeof state.phase !== 'string' || !UPDATE_PHASES.has(state.phase as DesktopUpdatePhase) || !shortText(state.currentVersion)
    || (state.channel !== null && !shortText(state.channel))) return undefined
  return Object.freeze({
    phase: state.phase as DesktopUpdatePhase,
    currentVersion: state.currentVersion as string,
    channel: state.channel as string | null,
    ...(shortText(state.version) ? { version: state.version as string } : {}),
    ...(typeof state.percent === 'number' && state.percent >= 0 && state.percent <= 100 ? { percent: state.percent } : {}),
    ...(shortText(state.error) ? { error: state.error as string } : {}),
    ...(typeof state.checkedAt === 'number' && Number.isFinite(state.checkedAt) && state.checkedAt > 0 ? { checkedAt: state.checkedAt } : {}),
  })
}

const desktopUpdate: DesktopUpdateBridge = Object.freeze({
  getState: async () => {
    const state = sanitizeUpdateState(await ipcRenderer.invoke('yumpoo:update:get-state'))
    if (!state) throw new TypeError('Update state is invalid')
    return state
  },
  check: async () => { await ipcRenderer.invoke('yumpoo:update:check') },
  restart: async () => { await ipcRenderer.invoke('yumpoo:update:restart') },
  download: async () => { await ipcRenderer.invoke('yumpoo:update:download') },
  onState: (listener: (state: DesktopUpdateState) => void) => {
    if (typeof listener !== 'function') throw new TypeError('Update listener must be a function')
    const wrapped = (_event: unknown, value: unknown) => {
      const state = sanitizeUpdateState(value)
      if (state) listener(state)
    }
    ipcRenderer.on('yumpoo:update:state', wrapped)
    return () => ipcRenderer.removeListener('yumpoo:update:state', wrapped)
  },
})

const desktopPreferences: DesktopPreferencesBridge = Object.freeze({
  get: async () => ipcRenderer.invoke('yumpoo:preferences:get'),
  set: async (change: DesktopPreferencesChange) => ipcRenderer.invoke('yumpoo:preferences:set', change),
  setShortcut: async (accelerator: string | null) => (await ipcRenderer.invoke('yumpoo:preferences:shortcut', accelerator)) === true,
  onChange: (listener: (state: DesktopPreferencesState) => void) => {
    if (typeof listener !== 'function') throw new TypeError('Preferences listener must be a function')
    const wrapped = (_event: unknown, value: DesktopPreferencesState) => { if (value && typeof value === 'object' && typeof value.railEnabled === 'boolean') listener(value) }
    ipcRenderer.on('yumpoo:preferences:changed', wrapped)
    return () => ipcRenderer.removeListener('yumpoo:preferences:changed', wrapped)
  },
})

const QUICK_ORIGINS = new Set(['top', 'bottom', 'left', 'right'])

const desktopQuick: DesktopQuickBridge = Object.freeze({
  setPinned: async (pinned: boolean) => {
    if (typeof pinned !== 'boolean') throw new TypeError('Quick panel pinned must be a boolean')
    await ipcRenderer.invoke('yumpoo:quick:pin', pinned)
  },
  resize: async (active: boolean) => {
    if (typeof active !== 'boolean') throw new TypeError('Quick panel resize must be a boolean')
    await ipcRenderer.invoke('yumpoo:quick:resize', active)
  },
  onPinned: (listener: (pinned: boolean) => void) => {
    if (typeof listener !== 'function') throw new TypeError('Quick panel listener must be a function')
    const wrapped = (_event: unknown, value: unknown) => { if (typeof value === 'boolean') listener(value) }
    ipcRenderer.on('yumpoo:quick:pinned', wrapped)
    return () => ipcRenderer.removeListener('yumpoo:quick:pinned', wrapped)
  },
  onOpen: (listener: (context: QuickPanelContext) => void) => {
    if (typeof listener !== 'function') throw new TypeError('Quick panel listener must be a function')
    const wrapped = (_event: unknown, value: QuickPanelContext) => {
      if (value && (value.projectId === null || typeof value.projectId === 'string') && Array.isArray(value.projects) && QUICK_ORIGINS.has(value.origin)) listener(value)
    }
    ipcRenderer.on('yumpoo:quick:open', wrapped)
    return () => ipcRenderer.removeListener('yumpoo:quick:open', wrapped)
  },
  onClosing: (listener: () => void) => {
    if (typeof listener !== 'function') throw new TypeError('Quick panel listener must be a function')
    const wrapped = () => listener()
    ipcRenderer.on('yumpoo:quick:closing', wrapped)
    return () => ipcRenderer.removeListener('yumpoo:quick:closing', wrapped)
  },
  close: async () => { await ipcRenderer.invoke('yumpoo:quick:close') },
  openMain: async (path?: string) => { await ipcRenderer.invoke('yumpoo:quick:open-main', path) },
})

const desktopRail: DesktopRailBridge = Object.freeze({
  getState: async () => ipcRenderer.invoke('yumpoo:rail:get-state'),
  onState: (listener: (state: RailState) => void) => {
    if (typeof listener !== 'function') throw new TypeError('Rail listener must be a function')
    const wrapped = (_event: unknown, value: RailState) => { if (value && (value.side === 'left' || value.side === 'right')) listener(value) }
    ipcRenderer.on('yumpoo:rail:state', wrapped)
    return () => ipcRenderer.removeListener('yumpoo:rail:state', wrapped)
  },
  openProject: async (projectId: string, top: number) => { await ipcRenderer.invoke('yumpoo:rail:open', projectId, top) },
  setProjects: async (projects: RailProjects) => { await ipcRenderer.invoke('yumpoo:rail:projects', projects) },
  setVisibleCount: async (count: number) => { await ipcRenderer.invoke('yumpoo:rail:visible-count', count) },
  openSettings: async () => { await ipcRenderer.invoke('yumpoo:rail:settings') },
})

const desktopBridge: DesktopBridge = Object.freeze({
  client: 'electron',
  auth: desktopAuth,
  timer: desktopTimer,
  inbox: desktopInbox,
  files: desktopFiles,
  update: desktopUpdate,
  preferences: desktopPreferences,
  quick: desktopQuick,
  rail: desktopRail,
})

contextBridge.exposeInMainWorld('yumpooDesktop', desktopBridge)
