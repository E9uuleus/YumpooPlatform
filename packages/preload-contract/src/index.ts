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
}
