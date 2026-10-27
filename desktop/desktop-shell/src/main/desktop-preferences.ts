import path from 'node:path'
import { readFileSync } from 'node:fs'
import { mkdir, rename, writeFile } from 'node:fs/promises'
import type { DesktopAppearance, DesktopPreferences, DesktopPreferencesChange, RailMode, RailProjects, RailSize } from '@yumpoo/preload-contract'
import { MAX_RAIL_PROJECTS } from './timer-geometry'

const STORE_FILE = 'desktop-preferences.json'
const MAX_STORE_BYTES = 4 * 1024
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const KEYS = ['railEnabled', 'railMode', 'railSize', 'railProjects', 'aboveTaskbar', 'autoDownload', 'launchAtLogin']
// Ctrl, Alt, Shift and Super in this order, at least one of Ctrl, Alt or Super, then one key; the settings page composes the same form.
const ACCELERATOR = /^(?=.*(?:Ctrl|Alt|Super)\+)(?:Ctrl\+)?(?:Alt\+)?(?:Shift\+)?(?:Super\+)?(?:[A-Z0-9]|F(?:[1-9]|1\d|2[0-4])|Space|Up|Down|Left|Right|Home|End|PageUp|PageDown|Insert|Delete)$/

export const DEFAULT_DESKTOP_PREFERENCES: Readonly<DesktopPreferences> = Object.freeze({
  railEnabled: true, railMode: 'autohide', railSize: 'medium', railProjects: null,
  // A global shortcut takes the keys from every other application (Ctrl+Alt is AltGr on Windows), so the person records one.
  aboveTaskbar: false, shortcut: null, autoDownload: true,
})

export const validRailMode = (value: unknown): value is RailMode => value === 'autohide' || value === 'always'
export const validRailSize = (value: unknown): value is RailSize => value === 'small' || value === 'medium' || value === 'large'
export function validAppearance(value: unknown): value is DesktopAppearance {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const appearance = value as DesktopAppearance
  return Object.keys(value).every(key => key === 'theme' || key === 'density')
    && ['system', 'light', 'dark', 'night'].includes(appearance.theme)
    && ['comfortable', 'compact'].includes(appearance.density)
}
export const validAccelerator = (value: unknown): value is string => typeof value === 'string' && ACCELERATOR.test(value)

export const validRailLabel = (value: unknown): value is string =>
  typeof value === 'string' && value === value.trim() && [...value].length >= 1 && [...value].length <= 3

export function validRailProjects(value: unknown): value is RailProjects {
  const projects = value as RailProjects | null
  if (!projects || typeof projects !== 'object' || typeof projects.owner !== 'string' || !Array.isArray(projects.items)) return false
  const [company, user, ...rest] = projects.owner.split(':')
  return uuid.test(company ?? '') && uuid.test(user ?? '') && !rest.length && projects.items.length <= MAX_RAIL_PROJECTS
    && projects.items.every(item => !!item && typeof item === 'object' && uuid.test(String(item.id))
      && typeof item.name === 'string' && item.name.length > 0 && item.name.length <= 64
      && (item.label === undefined || validRailLabel(item.label)))
    && new Set(projects.items.map(item => item.id)).size === projects.items.length
}

export function validDesktopPreferencesChange(value: unknown): value is DesktopPreferencesChange {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const change = value as Record<string, unknown>
  return Object.keys(change).every(key => KEYS.includes(key))
    && ['railEnabled', 'aboveTaskbar', 'autoDownload', 'launchAtLogin'].every(key => change[key] === undefined || typeof change[key] === 'boolean')
    && (change.railMode === undefined || validRailMode(change.railMode))
    && (change.railSize === undefined || validRailSize(change.railSize))
    && (change.railProjects === undefined || change.railProjects === null || validRailProjects(change.railProjects))
}

/** Same storage rules as TimerPreferenceStore; without a user data path the preferences only live for this process. */
export class DesktopPreferenceStore {
  private readonly filePath: string | undefined
  private value: DesktopPreferences
  private writing: Promise<void> = Promise.resolve()
  private readonly listeners = new Set<(value: DesktopPreferences) => void>()

  constructor(userDataPath?: string) {
    this.filePath = userDataPath ? path.join(userDataPath, STORE_FILE) : undefined
    this.value = { ...DEFAULT_DESKTOP_PREFERENCES, ...this.read() }
  }

  get(): DesktopPreferences { return { ...this.value } }

  update(change: Partial<DesktopPreferences>): DesktopPreferences {
    const next = { ...this.value, ...sanitize(change as Record<string, unknown>) }
    if (JSON.stringify(next) !== JSON.stringify(this.value)) {
      this.value = next
      this.persist()
      this.listeners.forEach(listener => listener(this.get()))
    }
    return this.get()
  }

  onChange(listener: (value: DesktopPreferences) => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  flush(): Promise<void> { return this.writing }

  private read(): Partial<DesktopPreferences> {
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
    }).catch(() => { console.error('[YUMPOO_DESKTOP_PREFERENCES_WRITE_FAILED]') })
  }
}

function sanitize(value: Record<string, unknown>): Partial<DesktopPreferences> {
  const result: Partial<DesktopPreferences> = {}
  if (validAppearance(value.menuAppearance)) result.menuAppearance = { ...value.menuAppearance }
  if (typeof value.railEnabled === 'boolean') result.railEnabled = value.railEnabled
  if (validRailMode(value.railMode)) result.railMode = value.railMode
  if (validRailSize(value.railSize)) result.railSize = value.railSize
  if (value.railProjects === null) result.railProjects = null
  else if (validRailProjects(value.railProjects)) {
    result.railProjects = { owner: value.railProjects.owner, items: value.railProjects.items.map(item => ({ id: item.id, name: item.name, ...(item.label ? { label: item.label } : {}) })) }
  }
  if (typeof value.aboveTaskbar === 'boolean') result.aboveTaskbar = value.aboveTaskbar
  if (value.shortcut === null || validAccelerator(value.shortcut)) result.shortcut = value.shortcut
  if (typeof value.autoDownload === 'boolean') result.autoDownload = value.autoDownload
  return result
}
