import path from 'node:path'
import { readFileSync } from 'node:fs'
import { mkdir, rename, writeFile } from 'node:fs/promises'
import type { TimerDisplayStyle, TimerDockSide, TimerOrbSize, TimerPreferences } from '@yumpoo/preload-contract'

const STORE_FILE = 'timer-preferences.json'
const MAX_STORE_BYTES = 4 * 1024

export interface StoredTimerPreferences extends TimerPreferences {
  dockY: number
  pinned: boolean
}

export const DEFAULT_TIMER_PREFERENCES: Readonly<StoredTimerPreferences> = Object.freeze({
  display: 'orb', orbSize: 'medium', dockSide: 'right', dockY: .5, pinned: true,
})

export const validDisplay = (value: unknown): value is TimerDisplayStyle => value === 'orb' || value === 'dock'
export const validOrbSize = (value: unknown): value is TimerOrbSize => value === 'small' || value === 'medium' || value === 'large'
export const validDockSide = (value: unknown): value is TimerDockSide => value === 'left' || value === 'right'

/** Without a user data path the preferences only live for this process, which keeps tests and smoke runs side-effect free. */
export class TimerPreferenceStore {
  private readonly filePath: string | undefined
  private value: StoredTimerPreferences
  private writing: Promise<void> = Promise.resolve()

  constructor(userDataPath?: string) {
    this.filePath = userDataPath ? path.join(userDataPath, STORE_FILE) : undefined
    this.value = { ...DEFAULT_TIMER_PREFERENCES, ...this.read() }
  }

  get(): StoredTimerPreferences { return { ...this.value } }

  preferences(): TimerPreferences {
    return { display: this.value.display, orbSize: this.value.orbSize, dockSide: this.value.dockSide }
  }

  update(change: Partial<StoredTimerPreferences>): StoredTimerPreferences {
    const next = { ...this.value, ...sanitize(change as Record<string, unknown>) }
    if ((Object.keys(next) as Array<keyof StoredTimerPreferences>).some(key => next[key] !== this.value[key])) {
      this.value = next
      this.persist()
    }
    return this.get()
  }

  flush(): Promise<void> { return this.writing }

  private read(): Partial<StoredTimerPreferences> {
    if (!this.filePath) return {}
    try {
      const raw = readFileSync(this.filePath)
      if (raw.length > MAX_STORE_BYTES) return {}
      const parsed = JSON.parse(raw.toString('utf8')) as unknown
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed) && (parsed as { version?: unknown }).version === 1
        ? sanitize(parsed as Record<string, unknown>) : {}
    } catch { return {} }
  }

  private persist(): void {
    const filePath = this.filePath
    if (!filePath) return
    const content = JSON.stringify({ version: 1, ...this.value })
    this.writing = this.writing.then(async () => {
      const temporary = `${filePath}.tmp`
      await mkdir(path.dirname(filePath), { recursive: true })
      await writeFile(temporary, content, { flag: 'w' })
      await rename(temporary, filePath)
    }).catch(() => { console.error('[YUMPOO_TIMER_PREFERENCES_WRITE_FAILED]') })
  }
}

function sanitize(value: Record<string, unknown>): Partial<StoredTimerPreferences> {
  const result: Partial<StoredTimerPreferences> = {}
  if (validDisplay(value.display)) result.display = value.display
  if (validOrbSize(value.orbSize)) result.orbSize = value.orbSize
  if (validDockSide(value.dockSide)) result.dockSide = value.dockSide
  if (typeof value.dockY === 'number' && Number.isFinite(value.dockY)) result.dockY = Math.min(1, Math.max(0, value.dockY))
  if (typeof value.pinned === 'boolean') result.pinned = value.pinned
  return result
}
