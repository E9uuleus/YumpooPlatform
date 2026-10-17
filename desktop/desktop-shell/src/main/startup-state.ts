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
