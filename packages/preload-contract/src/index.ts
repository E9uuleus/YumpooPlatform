export type DesktopAuthPhase =
  | 'IDLE'
  | 'OPENING_BROWSER'
  | 'WAITING_FOR_CALLBACK'
  | 'EXCHANGING'
  | 'SUCCEEDED'
  | 'FAILED'

export type DesktopAuthErrorCode =
  | 'AUTH_DISABLED'
  | 'AUTH_IN_PROGRESS'
  | 'BROWSER_OPEN_FAILED'
  | 'INVALID_CALLBACK'
  | 'NO_PENDING_ATTEMPT'
  | 'ATTEMPT_EXPIRED'
  | 'STATE_MISMATCH'
  | 'EXCHANGE_FAILED'
  | 'IPC_REJECTED'

export interface DesktopAuthStatus {
  readonly phase: DesktopAuthPhase
  readonly errorCode?: DesktopAuthErrorCode
}

export interface DesktopAuthBridge {
  isEnabled(): Promise<boolean>
  start(): Promise<void>
  clear(): Promise<void>
  onStatus(listener: (status: DesktopAuthStatus) => void): () => void
}

export interface DesktopBridge {
  readonly client: 'electron'
  readonly auth: DesktopAuthBridge
  readonly timer: DesktopTimerBridge
  readonly inbox?: DesktopInboxBridge
}

export type DesktopInboxReason = 'MENTION' | 'REPLY' | 'COMMENT' | 'ASSIGNED' | 'PROJECT_MEMBER_ADDED' | 'PROJECT_MEMBER_REMOVED' | 'PROJECT_OWNER_ASSIGNED' | 'PROJECT_OWNER_TRANSFERRED'

export interface DesktopInboxState {
  accountId: string | null
  unreadCount: number
  latest: Array<{ id: string; reason: DesktopInboxReason; actorName: string | null; createdAt: string }>
}

export interface DesktopInboxPreferences { toasts: boolean }

export interface DesktopInboxBridge {
  publishState(state: DesktopInboxState): Promise<void>
  onOpen(listener: (notificationId: string | null) => void): () => void
  getPreferences(): Promise<DesktopInboxPreferences>
  setPreferences(change: DesktopInboxPreferences): Promise<DesktopInboxPreferences>
}

export interface DesktopTimerBridge {
  show(mode?: TimerWindowMode, activate?: boolean): Promise<void>
  hide(): Promise<void>
  setMode(mode: TimerWindowMode): Promise<void>
  getWindowState(): Promise<TimerWindowState>
  setOrbLayout(change: TimerOrbChange): Promise<TimerOrbLayout>
  /** Optional because the remotely deployed web app can be newer than the installed shell. */
  setPreferences?(change: TimerPreferencesChange): Promise<TimerPreferences>
  onMenuState?(listener: (state: TimerMenuState) => void): () => void
  runMenuAction?(action: TimerMenuAction): Promise<void>
  /** Moves the compact window with the cursor for drags that start on a button, which cannot be a native drag region. */
  dragWindow?(active: boolean): Promise<void>
  onDragging?(listener: (dragging: boolean) => void): () => void
  setAlwaysOnTop(value: boolean): Promise<void>
  refresh(): Promise<void>
  publishState(state: DesktopTimerState): Promise<void>
  onCommand(listener: (command: DesktopTimerCommand) => void): () => void
  completeCommand(requestId: string, success: boolean): Promise<void>
  onCommandFailed(listener: () => void): () => void
  onMode(listener: (mode: TimerWindowMode) => void): () => void
  onOrbHover(listener: (hovered: boolean) => void): () => void
  onRefresh(listener: () => void): () => void
  onExitRequest(listener: (requestId: string) => void): () => void
  acknowledgeExit(requestId: string): Promise<void>
  completeExit(requestId: string, allow: boolean): Promise<void>
  openWorkItem(projectId: string, workItemId: string): Promise<void>
}

export type TimerWindowMode = 'compact' | 'picker' | 'settings'
export type TimerDisplayStyle = 'orb' | 'dock'
export type TimerOrbSize = 'small' | 'medium' | 'large'
export type TimerDockSide = 'left' | 'right'

export interface TimerPreferences {
  display: TimerDisplayStyle
  orbSize: TimerOrbSize
  dockSide: TimerDockSide
}

export type TimerPreferencesChange = Partial<TimerPreferences>

export interface TimerWindowState {
  mode: TimerWindowMode
  pinned: boolean
  surface: 'main' | 'timer'
  savedAt: number
  orb: TimerOrbLayout
  hovered?: boolean
  preferences?: TimerPreferences
}

export interface TimerOrbLayout {
  canvas?: { width: number; height: number; left: number; top: number }
  size: number
  side: 'left' | 'right' | null
  detailWidth: number
  /** Present only while the compact surface is docked to a screen edge. */
  dock?: { side: TimerDockSide; expanded: boolean }
}

export interface TimerOrbChange {
  details?: boolean
}

export type TimerMenuAction =
  | 'open-main'
  | 'open-inbox'
  | 'find'
  | 'settings'
  | 'toggle'
  | 'show-orb'
  | 'show-dock'
  | 'hide'
  | 'toggle-pin'
  | 'exit'
  | 'close'

export interface TimerMenuState {
  inbox?: { unreadCount: number }
  signedIn: boolean
  ready: boolean
  enabled: boolean
  running: { title: string; startedAt: string } | null
  recent: { title: string } | null
  clockOffsetMs: number
  savedAt: number
  visible: boolean
  display: TimerDisplayStyle
  pinned: boolean
}

export interface DesktopTimerState {
  accountId: string | null
  rowVersion: number
  connected: boolean
  busy: boolean
  savedAt: number
  running: { sessionId: string; workItemId: string | null; title: string; startedAt: string } | null
  recent: { workItemId: string; title: string } | null
  /** Server clock minus local clock, so other surfaces can tick the same duration. */
  clockOffsetMs?: number
}

export type DesktopTimerCommand =
  | { requestId: string; action: 'stop'; sessionId: string }
  | { requestId: string; action: 'start'; workItemId: string }
