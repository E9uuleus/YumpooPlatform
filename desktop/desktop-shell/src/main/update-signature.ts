import { createPublicKey, verify } from 'node:crypto'
import type { DesktopUpdateKey } from './distribution'

export const UPDATE_STATEMENT_SCHEMA = 'yumpoo-desktop-update/1'
// SubjectPublicKeyInfo header for a raw 32-byte Ed25519 key (RFC 8410).
const ED25519_SPKI_PREFIX = Buffer.from('302a300506032b6570032100', 'hex')
const STATEMENT_FIELDS = ['schema', 'channel', 'version', 'file', 'sha512', 'size', 'tag', 'commit'] as const
const VERSION = /^(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})$/u

/** What the release pipeline authorized: exactly one installer for one version on one channel. */
export interface UpdateStatement {
  readonly schema: typeof UPDATE_STATEMENT_SCHEMA
  readonly channel: string
  readonly version: string
  readonly file: string
  /** Base64 SHA-512 of the installer, the same encoding latest.yml uses. */
  readonly sha512: string
  readonly size: number
  readonly tag: string
  readonly commit: string
}

export interface SignedUpdate {
  readonly keyId: string
  readonly statement: UpdateStatement
  /** Base64 Ed25519 signature over canonicalStatement(statement). */
  readonly signature: string
}

export interface DownloadedUpdate {
  readonly version: string
  readonly file: string
  readonly sha512: string
  readonly size: number
}

export class UpdateAuthorizationError extends Error {
  constructor(readonly code: string) {
    super(code)
  }
}

/** Fixed field order with no whitespace; tools/release/desktop-update-signature.mjs must produce identical bytes. */
export function canonicalStatement(statement: UpdateStatement): string {
  return JSON.stringify(Object.fromEntries(STATEMENT_FIELDS.map(field => [field, statement[field]])))
}

function fail(code: string): never {
  throw new UpdateAuthorizationError(code)
}

function exactFields(value: unknown, fields: readonly string[], code: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail(code)
  const keys = Object.keys(value).sort()
  if (keys.join() !== [...fields].sort().join()) fail(code)
  return value as Record<string, unknown>
}

export function parseSignedUpdate(value: unknown): SignedUpdate {
  const signed = exactFields(value, ['keyId', 'statement', 'signature'], 'SIGNATURE_MALFORMED')
  const statement = exactFields(signed.statement, STATEMENT_FIELDS, 'STATEMENT_MALFORMED')
  if (statement.schema !== UPDATE_STATEMENT_SCHEMA) fail('STATEMENT_SCHEMA')
  for (const field of ['channel', 'version', 'file', 'sha512', 'tag', 'commit'] as const) {
    if (typeof statement[field] !== 'string' || (statement[field] as string).length > 256) fail('STATEMENT_MALFORMED')
  }
  if (!Number.isSafeInteger(statement.size) || (statement.size as number) <= 0) fail('STATEMENT_MALFORMED')
  if (typeof signed.keyId !== 'string' || typeof signed.signature !== 'string') fail('SIGNATURE_MALFORMED')
  return signed as unknown as SignedUpdate
}

export function compareVersions(left: string, right: string): number {
  const a = VERSION.exec(left)
  const b = VERSION.exec(right)
  if (!a || !b) fail('VERSION_INVALID')
  for (let index = 1; index <= 3; index += 1) {
    const difference = Number(a[index]) - Number(b[index])
    if (difference !== 0) return Math.sign(difference)
  }
  return 0
}

/**
 * Accepts a downloaded installer only when a pinned key signed a statement naming this exact file, digest,
 * size, version and channel, and the version moves forward. Any mismatch throws; nothing is installed.
 */
export function verifyUpdateAuthorization(options: {
  readonly signed: SignedUpdate
  readonly keys: readonly DesktopUpdateKey[]
  readonly channel: string
  readonly currentVersion: string
  readonly downloaded: DownloadedUpdate
}): void {
  const { signed, downloaded } = options
  const statement = signed.statement
  const key = options.keys.find(candidate => candidate.keyId === signed.keyId)
  if (!key) fail('UNKNOWN_KEY')
  const signature = Buffer.from(signed.signature, 'base64')
  if (signature.byteLength !== 64 || signature.toString('base64') !== signed.signature) fail('SIGNATURE_MALFORMED')
  const publicKey = createPublicKey({ key: Buffer.concat([ED25519_SPKI_PREFIX, Buffer.from(key.publicKey, 'base64')]), format: 'der', type: 'spki' })
  if (!verify(null, Buffer.from(canonicalStatement(statement), 'utf8'), publicKey, signature)) fail('SIGNATURE_INVALID')
  if (statement.channel !== options.channel) fail('CHANNEL_MISMATCH')
  if (statement.version !== downloaded.version) fail('VERSION_MISMATCH')
  if (compareVersions(statement.version, options.currentVersion) <= 0) fail('VERSION_NOT_NEWER')
  if (statement.file !== downloaded.file) fail('FILE_MISMATCH')
  if (statement.size !== downloaded.size) fail('SIZE_MISMATCH')
  if (statement.sha512 !== downloaded.sha512) fail('DIGEST_MISMATCH')
}
