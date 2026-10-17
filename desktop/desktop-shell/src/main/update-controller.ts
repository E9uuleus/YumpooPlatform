import type { DesktopUpdatePhase, DesktopUpdateState } from '@yumpoo/preload-contract'
import path from 'node:path'
import type { DesktopUpdateDistribution } from './distribution'
import { parseSignedUpdate, UpdateAuthorizationError, verifyUpdateAuthorization } from './update-signature'

export const FIRST_CHECK_DELAY_MS = 30_000
export const CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000
export const CHECK_JITTER_MS = 30 * 60 * 1000
const RESUME_MIN_GAP_MS = 15 * 60 * 1000

/** The slice of electron-updater's NsisUpdater this controller drives; tests substitute a fake. */
export interface UpdaterLike {
  autoDownload: boolean
  autoInstallOnAppQuit: boolean
  allowDowngrade: boolean
  disableWebInstaller: boolean
  setFeedURL(url: string): void
  checkForUpdates(): Promise<unknown>
  install(isSilent: boolean, isForceRunAfter: boolean): boolean
  on(event: string, listener: (...args: never[]) => void): unknown
}

export interface FileDigest {
  readonly sha512: string
  readonly size: number
}

export interface UpdateControllerOptions {
  readonly updater: UpdaterLike
  readonly update: DesktopUpdateDistribution
  readonly currentVersion: string
  fetchJson(url: string): Promise<unknown>
  digest(file: string): Promise<FileDigest>
  /** Install runs inside the synchronous quit path, so the final re-check cannot await. */
  digestSync(file: string): FileDigest
  removeFile(file: string): Promise<void>
  log(level: 'info' | 'warn' | 'error', event: string, fields?: Record<string, string | number | boolean | undefined>): void
  publish(state: DesktopUpdateState): void
  now(): number
  random(): number
  schedule(callback: () => void, delayMs: number): void
}

interface VerifiedUpdate {
  readonly version: string
  readonly file: string
  readonly digest: FileDigest
}

/**
 * Drives electron-updater with automatic download but never automatic install. A downloaded installer becomes
 * "ready" only after its Ed25519 authorization from the feed checks out against the pinned keys; installation
 * then happens on quit, re-checking the digest so a file swapped after verification is never run.
 */
export class UpdateController {
  private phase: DesktopUpdatePhase = 'idle'
  private version: string | undefined
  private percent: number | undefined
  private error: string | undefined
  private verified: VerifiedUpdate | undefined
  private lastCheck = 0
  private started = false

  constructor(private readonly options: UpdateControllerOptions) {
    const updater = options.updater
    updater.autoDownload = true
    updater.autoInstallOnAppQuit = false
    updater.allowDowngrade = false
    updater.disableWebInstaller = true
    updater.setFeedURL(options.update.feed)
    updater.on('checking-for-update', () => this.set('checking'))
    updater.on('update-not-available', () => this.set('idle'))
    updater.on('update-available', (info: { version?: string }) => {
      this.version = typeof info?.version === 'string' ? info.version : undefined
      this.percent = 0
      this.set('downloading')
    })
    updater.on('download-progress', (progress: { percent?: number }) => {
      if (this.phase !== 'downloading' || typeof progress?.percent !== 'number') return
      const percent = Math.max(0, Math.min(100, Math.floor(progress.percent)))
      if (percent !== this.percent) {
        this.percent = percent
        this.publish()
      }
    })
    updater.on('update-downloaded', (event: { version?: string; downloadedFile?: string }) => {
      void this.verify(event)
    })
    updater.on('error', (error: Error) => {
      this.options.log('warn', 'update-error', { phase: this.phase, error: error?.message })
      if (this.phase !== 'ready' && this.phase !== 'verifying') this.set('error', 'UPDATE_FAILED')
    })
  }

  getState(): DesktopUpdateState {
    return {
      phase: this.phase,
      currentVersion: this.options.currentVersion,
      channel: this.options.update.channel,
      ...(this.version ? { version: this.version } : {}),
      ...(this.phase === 'downloading' && this.percent !== undefined ? { percent: this.percent } : {}),
      ...(this.phase === 'error' && this.error ? { error: this.error } : {}),
    }
  }

  get readyVersion(): string | undefined {
    return this.phase === 'ready' ? this.verified?.version : undefined
  }

  /** First check shortly after launch, then every four hours with up to half an hour of jitter. */
  start(): void {
    if (this.started) return
    this.started = true
    this.options.schedule(() => this.periodic(), FIRST_CHECK_DELAY_MS)
  }

  /** After sleep the regular timer may be hours late; a resume triggers a check unless one just ran. */
  onResume(): void {
    if (this.options.now() - this.lastCheck >= RESUME_MIN_GAP_MS) this.check()
  }

  check(): void {
    if (this.phase === 'checking' || this.phase === 'downloading' || this.phase === 'verifying' || this.phase === 'ready') return
    this.lastCheck = this.options.now()
    this.options.updater.checkForUpdates().catch(() => undefined)
  }

  /** Called from the quit path; installs silently and optionally relaunches. Returns whether an installer started. */
  installOnQuit(restart: boolean): boolean {
    const verified = this.verified
    if (this.phase !== 'ready' || !verified) return false
    let digest: FileDigest
    try {
      digest = this.options.digestSync(verified.file)
    } catch {
      this.options.log('error', 'update-install-skipped', { reason: 'FILE_UNREADABLE' })
      return false
    }
    if (digest.sha512 !== verified.digest.sha512 || digest.size !== verified.digest.size) {
      this.options.log('error', 'update-install-skipped', { reason: 'FILE_CHANGED' })
      return false
    }
    this.options.log('info', 'update-install', { version: verified.version, restart })
    return this.options.updater.install(true, restart)
  }

  private periodic(): void {
    this.check()
    this.options.schedule(() => this.periodic(), CHECK_INTERVAL_MS + Math.floor(this.options.random() * CHECK_JITTER_MS))
  }

  private async verify(event: { version?: string; downloadedFile?: string }): Promise<void> {
    const file = event?.downloadedFile
    const version = event?.version
    if (typeof file !== 'string' || typeof version !== 'string') {
      this.set('error', 'UPDATE_EVENT_INVALID')
      return
    }
    this.version = version
    this.set('verifying')
    const name = path.basename(file)
    try {
      const signed = parseSignedUpdate(await this.options.fetchJson(new URL(`${encodeURIComponent(name)}.sig.json`, this.options.update.feed).href))
      const digest = await this.options.digest(file)
      verifyUpdateAuthorization({
        signed,
        keys: this.options.update.publicKeys,
        channel: this.options.update.channel,
        currentVersion: this.options.currentVersion,
        downloaded: { version, file: name, sha512: digest.sha512, size: digest.size },
      })
      this.verified = { version, file, digest }
      this.options.log('info', 'update-verified', { version, keyId: signed.keyId })
      this.set('ready')
    } catch (error) {
      const code = error instanceof UpdateAuthorizationError ? error.code : 'AUTHORIZATION_UNAVAILABLE'
      this.options.log('error', 'update-rejected', { version, reason: code })
      // A rejected installer must not stay in the updater cache where a later cache hit would offer it again.
      // electron-updater also stored its blockmap as current.blockmap (the cache root, above pending/); left in place
      // it would describe a version that never got installed and break every later differential download.
      await this.options.removeFile(file).catch(() => undefined)
      await this.options.removeFile(path.join(path.dirname(path.dirname(file)), 'current.blockmap')).catch(() => undefined)
      this.set('error', `UPDATE_REJECTED_${code}`)
    }
  }

  private set(phase: DesktopUpdatePhase, error?: string): void {
    this.phase = phase
    this.error = error
    if (phase !== 'downloading') this.percent = undefined
    this.publish()
  }

  private publish(): void {
    this.options.publish(this.getState())
  }
}
