import path from 'node:path'
import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises'
import type { Cookies, SafeStorage } from 'electron'
import type { DesktopSessionBundle } from './desktop-auth'

const STORE_FILE = 'electron-session.bin'
const MAX_STORE_BYTES = 16 * 1024
const SESSION_COOKIE = '__Host-yumpoo-session'
const CSRF_COOKIE = '__Host-yumpoo-csrf'

export class DesktopCredentialStore {
  private readonly filePath: string

  constructor(
    userDataPath: string,
    private readonly storage: Pick<SafeStorage, 'isEncryptionAvailable' | 'encryptString' | 'decryptString'>,
  ) {
    this.filePath = path.join(userDataPath, 'auth', STORE_FILE)
  }

  async save(bundle: DesktopSessionBundle, webOrigin: string): Promise<void> {
    this.requireEncryption()
    const encrypted = this.storage.encryptString(JSON.stringify({
      ...bundle,
      webOrigin: new URL(webOrigin).origin,
    }))
    const directory = path.dirname(this.filePath)
    const temporary = `${this.filePath}.tmp`
    await mkdir(directory, { recursive: true })
    await writeFile(temporary, encrypted, { flag: 'w' })
    await rename(temporary, this.filePath)
  }

  async load(webOrigin: string, now = Date.now()): Promise<DesktopSessionBundle | undefined> {
    this.requireEncryption()
    try {
      const encrypted = await readFile(this.filePath)
      if (encrypted.length === 0 || encrypted.length > MAX_STORE_BYTES) {
        await this.clear()
        return undefined
      }
      const value = JSON.parse(this.storage.decryptString(encrypted)) as Partial<StoredDesktopSession>
      if (!validBundle(value)
        || value.webOrigin !== new URL(webOrigin).origin
        || Date.parse(value.absoluteExpiresAt) <= now) {
        await this.clear()
        return undefined
      }
      return Object.freeze({
        sessionCredential: value.sessionCredential,
        csrfCredential: value.csrfCredential,
        absoluteExpiresAt: value.absoluteExpiresAt,
      })
    } catch {
      await this.clear()
      return undefined
    }
  }

  async clear(): Promise<void> {
    await rm(this.filePath, { force: true }).catch(() => undefined)
    await rm(`${this.filePath}.tmp`, { force: true }).catch(() => undefined)
  }

  private requireEncryption(): void {
    if (!this.storage.isEncryptionAvailable()) {
      throw new Error('Windows protected credential storage is unavailable')
    }
  }
}

interface StoredDesktopSession extends DesktopSessionBundle {
  readonly webOrigin: string
}

export async function installSessionCookies(
  cookies: Cookies,
  webOrigin: string,
  bundle: DesktopSessionBundle,
): Promise<void> {
  const origin = new URL(webOrigin).origin
  const expirationDate = Date.parse(bundle.absoluteExpiresAt) / 1000
  await Promise.all([
    cookies.set({
      url: `${origin}/`, name: SESSION_COOKIE, value: bundle.sessionCredential,
      path: '/', secure: true, httpOnly: true, sameSite: 'lax', expirationDate,
    }),
    cookies.set({
      url: `${origin}/`, name: CSRF_COOKIE, value: bundle.csrfCredential,
      path: '/', secure: true, httpOnly: false, sameSite: 'lax', expirationDate,
    }),
  ])
}

export async function clearSessionCookies(cookies: Cookies, webOrigin: string): Promise<void> {
  const origin = new URL(webOrigin).origin
  await Promise.all([
    cookies.remove(`${origin}/`, SESSION_COOKIE),
    cookies.remove(`${origin}/`, CSRF_COOKIE),
  ])
}

function validBundle(value: Partial<StoredDesktopSession>): value is StoredDesktopSession {
  return validCredential(value.sessionCredential)
    && validCredential(value.csrfCredential)
    && typeof value.absoluteExpiresAt === 'string'
    && Number.isFinite(Date.parse(value.absoluteExpiresAt))
    && typeof value.webOrigin === 'string'
    && value.webOrigin === new URL(value.webOrigin).origin
}

function validCredential(value: unknown): value is string {
  return typeof value === 'string'
    && value.length >= 43
    && value.length <= 256
    && /^[A-Za-z0-9._~-]+$/.test(value)
}
