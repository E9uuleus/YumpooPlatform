import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import path from 'node:path'

export const STARTUP_STATE_FILE = 'startup-state.json'
export const CRASH_LOOP_THRESHOLD = 3
export const HEALTHY_AFTER_MS = 60_000
const MAX_STATE_BYTES = 4096

/**
 * Counts launches that never became healthy. A launch only counts as healthy once the main window loaded
 * and stayed up for a minute, or the person quit normally after a successful load; a crash, a forced kill
 * or a launch that never loads leaves the counter raised for the next start.
 */
export class StartupStateStore {
  private readonly file: string

  constructor(userDataPath: string) {
    this.file = path.join(userDataPath, STARTUP_STATE_FILE)
  }

  /** Records this launch and returns how many earlier launches in a row did not become healthy. */
  begin(): number {
    const previous = this.read()
    this.write(previous + 1)
    return previous
  }

  markHealthy(): void {
    this.write(0)
  }

  /** Starts a new streak at this launch, for a launch that failed after an earlier healthy stretch. */
  markFailed(): void {
    this.write(1)
  }

  private read(): number {
    try {
      const raw = readFileSync(this.file)
      if (raw.byteLength > MAX_STATE_BYTES) return 0
      const value = JSON.parse(raw.toString('utf8')) as { version?: unknown; consecutiveFailures?: unknown }
      return value.version === 1 && Number.isSafeInteger(value.consecutiveFailures) && (value.consecutiveFailures as number) >= 0
        ? Math.min(value.consecutiveFailures as number, 1000) : 0
    } catch {
      return 0
    }
  }

  private write(consecutiveFailures: number): void {
    const temporary = `${this.file}.tmp`
    try {
      mkdirSync(path.dirname(this.file), { recursive: true })
      writeFileSync(temporary, `${JSON.stringify({ version: 1, consecutiveFailures })}\n`, 'utf8')
      renameSync(temporary, this.file)
    } catch {
      // An unwritable profile is reported by diagnostics; it must not block startup.
    }
  }
}

interface HealthTimers {
  set(callback: () => void, ms: number): unknown
  clear(handle: unknown): void
}

const systemTimers: HealthTimers = {
  set: (callback, ms) => setTimeout(callback, ms).unref(),
  clear: handle => clearTimeout(handle as ReturnType<typeof setTimeout>),
}

/**
 * Tracks whether this launch became healthy. Loading the main window starts the one-minute timer; a renderer
 * crash cancels it, and from then on neither the timer nor a normal quit clears the failure streak. A crash
 * after the launch already became healthy starts a new streak at one.
 */
export class LaunchHealth {
  private state: 'starting' | 'loaded' | 'healthy' | 'failed' = 'starting'
  private timer: unknown

  constructor(
    private readonly store: Pick<StartupStateStore, 'begin' | 'markHealthy' | 'markFailed'>,
    private readonly timers: HealthTimers = systemTimers,
  ) {}

  begin(): number {
    return this.store.begin()
  }

  loaded(): void {
    if (this.state !== 'starting') return
    this.state = 'loaded'
    this.timer = this.timers.set(() => this.becomeHealthy(), HEALTHY_AFTER_MS)
  }

  failed(): void {
    if (this.state === 'failed') return
    if (this.state === 'loaded') this.timers.clear(this.timer)
    if (this.state === 'healthy') this.store.markFailed()
    this.state = 'failed'
  }

  /** A normal quit after a successful load ends the streak even within the first minute. */
  quitting(): void {
    if (this.state !== 'loaded') return
    this.timers.clear(this.timer)
    this.becomeHealthy()
  }

  private becomeHealthy(): void {
    if (this.state !== 'loaded') return
    this.state = 'healthy'
    this.store.markHealthy()
  }
}
