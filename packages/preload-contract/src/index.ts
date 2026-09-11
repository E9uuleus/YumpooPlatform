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
}

export interface DesktopTimerBridge {
  show(mode?: TimerWindowMode, activate?: boolean): Promise<void>
  hide(): Promise<void>
  setMode(mode: TimerWindowMode): Promise<void>
  getWindowState(): Promise<{ mode: TimerWindowMode; pinned: boolean; surface: 'main' | 'timer'; savedAt: number; orb: TimerOrbLayout; hovered?: boolean }>
  setOrbLayout(change: TimerOrbChange): Promise<TimerOrbLayout>
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

export type TimerWindowMode = 'compact' | 'picker'

export interface TimerOrbLayout {
  canvas?: { width: number; height: number; left: number; top: number }
  titleVisible?: boolean
  size: number
  side: 'left' | 'right' | null
  detailWidth: number
}

export interface TimerOrbChange {
  titleVisible?: boolean
  size?: number
  details?: boolean
}

export interface DesktopTimerState {
  accountId: string | null
  rowVersion: number
  connected: boolean
  busy: boolean
  savedAt: number
  running: { sessionId: string; workItemId: string | null; title: string; startedAt: string } | null
  recent: { workItemId: string; title: string } | null
}

export type DesktopTimerCommand =
  | { requestId: string; action: 'stop'; sessionId: string }
  | { requestId: string; action: 'start'; workItemId: string }
