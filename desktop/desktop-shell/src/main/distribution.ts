import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import path from 'node:path'

export interface DesktopUpdateKey {
  /** `ed25519-` plus the first 16 hex digits of the key's SHA-256: the signer names it in every statement. */
  readonly keyId: string
  /** Raw 32-byte Ed25519 public key, base64. */
  readonly publicKey: string
}

export interface DesktopUpdateDistribution {
  /** Directory holding latest.yml, the installers and their .sig.json authorizations; ends with a slash. */
  readonly feed: string
  readonly channel: string
  readonly publicKeys: readonly DesktopUpdateKey[]
}

/** Build-time settings baked into the packaged app; the installed shell never reads YUMPOO_WEB_URL. */
export interface DesktopDistribution {
  readonly schemaVersion: 1
  readonly origin: string
  /** Absent until a feed and signing keys exist; the shell then never checks for updates. */
  readonly update?: DesktopUpdateDistribution
}

export const DISTRIBUTION_FILE = 'distribution.json'
const LOOPBACK_HOSTS = new Set(['127.0.0.1', 'localhost'])

function record(value: unknown, label: string, fields: readonly string[], optional: readonly string[] = []): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label}必须是 JSON object`)
  const keys = Object.keys(value)
  if (fields.some(field => !keys.includes(field)) || keys.some(key => !fields.includes(key) && !optional.includes(key))) {
    throw new Error(`${label}字段白名单不正确`)
  }
  return value as Record<string, unknown>
}

function parseUpdate(value: unknown): DesktopUpdateDistribution {
  const update = record(value, '更新配置', ['feed', 'channel', 'publicKeys'])
  let feed: URL
  try {
    feed = new URL(String(update.feed))
  } catch {
    throw new Error('更新源不是有效 URL')
  }
  // Plain HTTP is accepted only on loopback, for the update end-to-end test feed.
  const secure = feed.protocol === 'https:' || (feed.protocol === 'http:' && LOOPBACK_HOSTS.has(feed.hostname))
  if (!secure || feed.href !== update.feed || !feed.pathname.endsWith('/') || feed.search || feed.hash || feed.username || feed.password) {
    throw new Error('更新源必须是以 / 结尾、不含查询和凭据的 HTTPS 地址')
  }
  if (typeof update.channel !== 'string' || !/^[a-z][a-z0-9-]{0,15}$/u.test(update.channel)) throw new Error('更新渠道名称无效')
  if (!Array.isArray(update.publicKeys) || update.publicKeys.length < 1 || update.publicKeys.length > 4) {
    throw new Error('更新公钥数量必须为 1–4 个')
  }
  const publicKeys = update.publicKeys.map((entry: unknown) => {
    const key = record(entry, '更新公钥', ['keyId', 'publicKey'])
    if (typeof key.publicKey !== 'string' || Buffer.from(key.publicKey, 'base64').byteLength !== 32
      || Buffer.from(key.publicKey, 'base64').toString('base64') !== key.publicKey) {
      throw new Error('更新公钥必须是 32 字节 Ed25519 公钥的 base64')
    }
    const expectedKeyId = `ed25519-${createHash('sha256').update(Buffer.from(key.publicKey, 'base64')).digest('hex').slice(0, 16)}`
    if (key.keyId !== expectedKeyId) throw new Error('更新公钥 keyId 必须由公钥派生')
    return Object.freeze({ keyId: key.keyId, publicKey: key.publicKey })
  })
  if (new Set(publicKeys.map(key => key.keyId)).size !== publicKeys.length) throw new Error('更新公钥 keyId 重复')
  return Object.freeze({ feed: feed.href, channel: update.channel, publicKeys: Object.freeze(publicKeys) })
}

export function parseDistribution(value: unknown): DesktopDistribution {
  const distribution = record(value, '分发配置', ['origin', 'schemaVersion'], ['update'])
  if (distribution.schemaVersion !== 1) {
    throw new Error('分发配置 schemaVersion 不受支持')
  }
  if (typeof distribution.origin !== 'string') {
    throw new Error('分发配置 origin 必须是字符串')
  }
  let origin: URL
  try {
    origin = new URL(distribution.origin)
  } catch {
    throw new Error('分发配置 origin 不是有效 URL')
  }
  if (origin.protocol !== 'https:' || origin.origin !== distribution.origin) {
    throw new Error('分发配置 origin 必须是不含路径、查询和凭据的 HTTPS origin')
  }
  return Object.freeze({
    schemaVersion: 1,
    origin: origin.origin,
    ...(distribution.update === undefined ? {} : { update: parseUpdate(distribution.update) }),
  })
}

export function loadDistribution(directory: string = __dirname): DesktopDistribution {
  const file = path.join(directory, DISTRIBUTION_FILE)
  let content: string
  try {
    content = readFileSync(file, 'utf8')
  } catch {
    throw new Error('打包桌面壳缺少分发配置')
  }
  return parseDistribution(JSON.parse(content))
}
