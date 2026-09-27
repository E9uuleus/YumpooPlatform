import path from 'node:path'
import { readFileSync } from 'node:fs'
import { mkdir, rename, writeFile } from 'node:fs/promises'
import { app, ipcMain, nativeImage, Notification, type BrowserWindow, type IpcMainInvokeEvent, type NativeImage, type Tray } from 'electron'
import type { DesktopInboxPreferences, DesktopInboxReason, DesktopInboxState } from '@yumpoo/preload-contract'
import { isTrustedAuthIpcSender } from './auth-ipc'
import { applicationIcon } from './application-icon'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const BALLOON_LIFETIME_MS = 10_000
const REASONS = new Set<DesktopInboxReason>(['MENTION', 'REPLY', 'COMMENT', 'ASSIGNED', 'PROJECT_MEMBER_ADDED', 'PROJECT_MEMBER_REMOVED', 'PROJECT_OWNER_ASSIGNED', 'PROJECT_OWNER_TRANSFERRED'])
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const keys = (value: Record<string, unknown>, allowed: string[]) => Object.keys(value).every(key => allowed.includes(key))
const validId = (value: unknown): value is string => typeof value === 'string' && UUID.test(value)

export function validInboxState(value: unknown): value is DesktopInboxState {
  if (!record(value) || !keys(value, ['accountId', 'unreadCount', 'latest'])) return false
  if (!(value.accountId === null || validId(value.accountId)) || !Number.isSafeInteger(value.unreadCount) || (value.unreadCount as number) < 0) return false
  if (!Array.isArray(value.latest) || value.latest.length > 5 || value.latest.length > (value.unreadCount as number)) return false
  if (value.accountId === null && (value.unreadCount !== 0 || value.latest.length !== 0)) return false
  const ids = new Set<string>()
  return value.latest.every(item => {
    if (!record(item) || !keys(item, ['id', 'reason', 'actorName', 'createdAt']) || !validId(item.id) || ids.has(item.id)) return false
    ids.add(item.id)
    return REASONS.has(item.reason as DesktopInboxReason)
      && (item.actorName === null || (typeof item.actorName === 'string' && item.actorName.length <= 200 && !/[\u0000-\u001f\u007f]/.test(item.actorName)))
      && typeof item.createdAt === 'string' && item.createdAt.length <= 40 && /^\d{4}-\d{2}-\d{2}T/.test(item.createdAt) && Number.isFinite(Date.parse(item.createdAt))
  })
}

function validPreferences(value: unknown): value is DesktopInboxPreferences {
  return record(value) && keys(value, ['toasts']) && typeof value.toasts === 'boolean'
}

export class InboxPreferenceStore {
  private readonly filePath: string | undefined
  private value: DesktopInboxPreferences = { toasts: true }
  private writing: Promise<void> = Promise.resolve()

  constructor(userDataPath?: string) {
    this.filePath = userDataPath ? path.join(userDataPath, 'inbox-preferences.json') : undefined
    if (!this.filePath) return
    try {
      const raw = readFileSync(this.filePath)
      if (raw.length > 4096) return
      const parsed: unknown = JSON.parse(raw.toString('utf8'))
      if (record(parsed) && parsed.version === 1 && keys(parsed, ['version', 'toasts']) && typeof parsed.toasts === 'boolean') this.value = { toasts: parsed.toasts }
    } catch { /* A missing or damaged store uses the default. */ }
  }

  get(): DesktopInboxPreferences { return { ...this.value } }

  update(change: DesktopInboxPreferences): DesktopInboxPreferences {
    if (!validPreferences(change)) throw new Error('INVALID_INBOX_PREFERENCES')
    if (change.toasts === this.value.toasts) return this.get()
    this.value = { toasts: change.toasts }
    const filePath = this.filePath
    if (filePath) {
      const content = JSON.stringify({ version: 1, ...this.value })
      this.writing = this.writing.then(async () => {
        await mkdir(path.dirname(filePath), { recursive: true })
        await writeFile(`${filePath}.tmp`, content, { flag: 'w' })
        await rename(`${filePath}.tmp`, filePath)
      }).catch(() => { console.error('[YUMPOO_INBOX_PREFERENCES_WRITE_FAILED]') })
    }
    return this.get()
  }

  flush(): Promise<void> { return this.writing }
}

/** Electron's native bitmap uses premultiplied BGRA bytes on the supported little-endian desktops. */
export function composeUnreadBadge(bitmap: Uint8Array, width: number, height: number): Buffer {
  if (!Number.isSafeInteger(width) || !Number.isSafeInteger(height) || width < 1 || height < 1 || bitmap.length !== width * height * 4) throw new Error('INVALID_BADGE_BITMAP')
  const output = Buffer.from(bitmap)
  const radius = Math.max(1, Math.min(width, height) * .19)
  const centerX = width - radius, centerY = radius
  for (let y = 0; y < Math.min(height, Math.ceil(radius * 2)); y++) {
    for (let x = Math.max(0, Math.floor(width - radius * 2)); x < width; x++) {
      if ((x + .5 - centerX) ** 2 + (y + .5 - centerY) ** 2 > radius ** 2) continue
      const offset = (y * width + x) * 4
      output[offset] = 68; output[offset + 1] = 68; output[offset + 2] = 239; output[offset + 3] = 255
    }
  }
  return output
}

export function inboxAlertText(item: DesktopInboxState['latest'][number]): string {
  const actor = item.actorName?.trim() || '有人'
  const action = item.reason === 'MENTION' ? '提到了你' : item.reason === 'REPLY' ? '回复了你'
    : item.reason === 'COMMENT' ? '评论了你关注的工作项' : item.reason === 'ASSIGNED' ? '给你指派了工作项' : '更新了你的项目成员身份'
  return `${actor} ${action}`
}

interface InboxSurfaces {
  inboxTray(): Tray | undefined
  setInboxBadge(count: number | undefined, image: NativeImage): void
  showMain(): void
  isInboxPreferencesSender(event: IpcMainInvokeEvent): boolean
}

interface Alert { id: string | null; body: string; generation: number }

export class InboxNotifier {
  private accountId: string | null = null
  private watermark = -Infinity
  private watermarkIds = new Set<string>()
  private unreadCount = 0
  private baselinePending = false
  private generation = 0
  private installed = false
  private readonly icon = applicationIcon().resize({ width: 32, height: 32 })
  private readonly badgedIcon = nativeImage.createFromBitmap(composeUnreadBadge(this.icon.toBitmap(), 32, 32), { width: 32, height: 32 })
  private balloon: Alert | undefined
  private balloonTimeout: ReturnType<typeof setTimeout> | undefined
  private balloons: Alert[] = []
  private readonly notifications = new Set<Notification>()

  constructor(private readonly main: () => BrowserWindow | null, private readonly origin: string,
    private readonly surfaces: InboxSurfaces, private readonly prefs = new InboxPreferenceStore(), private readonly platform = process.platform) {}

  install(): void {
    if (this.installed) return
    this.installed = true
    ipcMain.handle('yumpoo:inbox:state', (event, state: unknown) => {
      this.trusted(event)
      if (!validInboxState(state)) throw new Error('INVALID_INBOX_STATE')
      this.publish(state)
    })
    ipcMain.handle('yumpoo:inbox:get-preferences', event => { this.trusted(event, true); return this.prefs.get() })
    ipcMain.handle('yumpoo:inbox:preferences', (event, change: unknown) => {
      this.trusted(event, true)
      if (!validPreferences(change)) throw new Error('INVALID_INBOX_PREFERENCES')
      const result = this.prefs.update(change)
      if (!result.toasts) this.clearAlerts()
      return result
    })
    this.surfaces.inboxTray()?.on('balloon-click', () => {
      const alert = this.balloon
      clearTimeout(this.balloonTimeout)
      this.balloon = undefined
      if (alert) this.open(alert)
      this.nextBalloon()
    })
    // Native close events have no notification ID and may arrive after the next balloon opens.
    // An alert-owned timer advances the queue without assigning a late close to a different alert.
    this.badge(undefined)
  }

  reset(): void {
    this.generation++
    this.accountId = null
    this.watermark = -Infinity
    this.watermarkIds.clear()
    this.unreadCount = 0
    this.baselinePending = false
    this.clearAlerts()
    this.badge(undefined)
  }

  private trusted(event: IpcMainInvokeEvent, preferences = false): void {
    if (!isTrustedAuthIpcSender(event, this.main(), this.origin) && !(preferences && this.surfaces.isInboxPreferencesSender(event))) throw new Error('UNTRUSTED_IPC_SENDER')
  }

  private publish(state: DesktopInboxState): void {
    if (state.accountId === null) { this.reset(); return }
    const first = this.accountId !== state.accountId
    if (first) { this.reset(); this.accountId = state.accountId }
    const establishingBaseline = first || this.baselinePending
    this.baselinePending = establishingBaseline && state.unreadCount > 0 && state.latest.length === 0
    const fresh = state.latest.filter(item => Date.parse(item.createdAt) > this.watermark || (Date.parse(item.createdAt) === this.watermark && !this.watermarkIds.has(item.id)))
    const newCount = Math.max(fresh.length, state.unreadCount - this.unreadCount)
    for (const item of state.latest) {
      const timestamp = Date.parse(item.createdAt)
      if (timestamp > this.watermark) { this.watermark = timestamp; this.watermarkIds.clear() }
      if (timestamp === this.watermark) this.watermarkIds.add(item.id)
    }
    this.unreadCount = state.unreadCount
    this.badge(state.unreadCount)
    if (establishingBaseline || fresh.length === 0 || this.main()?.isFocused() || !this.prefs.get().toasts) return
    const alerts: Alert[] = newCount > 3 ? [{ id: null, body: `你有 ${newCount} 条新通知`, generation: this.generation }]
      : fresh.map(item => ({ id: item.id, body: inboxAlertText(item), generation: this.generation }))
    for (const alert of alerts) {
      if (this.platform === 'win32') this.balloons.push(alert)
      else if (Notification.isSupported()) {
        const notification = new Notification({ title: 'YumpooPlatform', body: alert.body, icon: this.icon })
        this.notifications.add(notification)
        notification.on('click', () => this.open(alert))
        notification.on('close', () => this.notifications.delete(notification))
        notification.show()
      }
    }
    if (this.platform === 'win32') this.nextBalloon()
  }

  private badge(count: number | undefined): void {
    const total = count ?? 0
    this.surfaces.setInboxBadge(count, total > 0 ? this.badgedIcon : this.icon)
    const main = this.main()
    if (this.platform === 'win32') {
      if (main && !main.isDestroyed()) main.setOverlayIcon(total > 0 ? this.badgedIcon : null, total > 0 ? `${total} 条未读` : '')
    } else app.setBadgeCount(total)
  }

  private nextBalloon(): void {
    if (this.balloon) return
    if (this.main()?.isFocused() || !this.prefs.get().toasts) { this.balloons = []; return }
    const next = this.balloons.shift()
    if (!next) return
    this.balloon = next
    this.surfaces.inboxTray()?.displayBalloon({ title: 'YumpooPlatform', content: next.body, icon: this.icon, respectQuietTime: true })
    this.balloonTimeout = setTimeout(() => {
      if (this.balloon !== next || next.generation !== this.generation) return
      this.balloon = undefined
      this.surfaces.inboxTray()?.removeBalloon()
      this.nextBalloon()
    }, BALLOON_LIFETIME_MS)
    this.balloonTimeout.unref()
  }

  private open(alert: Alert): void {
    if (alert.generation !== this.generation || !this.accountId) return
    this.surfaces.showMain()
    const main = this.main()
    if (main && !main.isDestroyed()) main.webContents.send('yumpoo:inbox:open', alert.id)
  }

  private clearAlerts(): void {
    clearTimeout(this.balloonTimeout)
    this.balloons = []; this.balloon = undefined
    if (this.platform === 'win32') this.surfaces.inboxTray()?.removeBalloon()
    for (const notification of this.notifications) notification.close()
    this.notifications.clear()
  }
}
