import { createHash, generateKeyPairSync, sign } from 'node:crypto'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  canonicalStatement, compareVersions, parseSignedUpdate, UpdateAuthorizationError, verifyUpdateAuthorization, type SignedUpdate,
} from '../src/main/update-signature'

// Produced by tools/release/desktop-update-signature.mjs with its deterministic fixture key; the release tool's
// own test re-signs the same statement and must reproduce these bytes exactly.
const fixture = JSON.parse(readFileSync(path.join(__dirname, 'fixtures', 'signed-update.json'), 'utf8')) as {
  publicKey: string
  installer: string
  signed: SignedUpdate
}
const keys = [{ keyId: fixture.signed.keyId, publicKey: fixture.publicKey }]
const installer = Buffer.from(fixture.installer)
const downloaded = {
  version: '1.0.1',
  file: 'YumpooDesktop-Setup-1.0.1-x64.exe',
  sha512: createHash('sha512').update(installer).digest('base64'),
  size: installer.byteLength,
}

function verifyWith(overrides: Partial<Parameters<typeof verifyUpdateAuthorization>[0]>) {
  return () => verifyUpdateAuthorization({ signed: fixture.signed, keys, channel: 'stable', currentVersion: '1.0.0', downloaded, ...overrides })
}

function code(run: () => void): string | undefined {
  try {
    run()
    return undefined
  } catch (error) {
    return error instanceof UpdateAuthorizationError ? error.code : 'UNEXPECTED'
  }
}

describe('更新授权签名', () => {
  it('发布工具签出的声明在桌面端通过校验', () => {
    expect(parseSignedUpdate(fixture.signed)).toEqual(fixture.signed)
    expect(code(verifyWith({}))).toBeUndefined()
  })

  it('篡改文件、声明与下载不符或渠道不符均被拒绝', () => {
    expect(code(verifyWith({ downloaded: { ...downloaded, sha512: createHash('sha512').update('tampered').digest('base64') } }))).toBe('DIGEST_MISMATCH')
    expect(code(verifyWith({ downloaded: { ...downloaded, size: downloaded.size + 1 } }))).toBe('SIZE_MISMATCH')
    expect(code(verifyWith({ downloaded: { ...downloaded, file: 'YumpooDesktop-Setup-1.0.2-x64.exe' } }))).toBe('FILE_MISMATCH')
    expect(code(verifyWith({ downloaded: { ...downloaded, version: '1.0.2' } }))).toBe('VERSION_MISMATCH')
    expect(code(verifyWith({ channel: 'beta' }))).toBe('CHANNEL_MISMATCH')
  })

  it('改动声明任一字段都会使签名失效', () => {
    const forged = { ...fixture.signed, statement: { ...fixture.signed.statement, size: 1 } }
    expect(code(verifyWith({ signed: forged }))).toBe('SIGNATURE_INVALID')
  })

  it('未知 keyId 与其他私钥的签名均被拒绝', () => {
    expect(code(verifyWith({ keys: [{ keyId: 'ed25519-0000000000000000', publicKey: fixture.publicKey }] }))).toBe('UNKNOWN_KEY')
    const { privateKey } = generateKeyPairSync('ed25519')
    const signature = sign(null, Buffer.from(canonicalStatement(fixture.signed.statement)), privateKey).toString('base64')
    expect(code(verifyWith({ signed: { ...fixture.signed, signature } }))).toBe('SIGNATURE_INVALID')
    expect(code(verifyWith({ signed: { ...fixture.signed, signature: 'AAAA' } }))).toBe('SIGNATURE_MALFORMED')
  })

  it('版本必须严格递增', () => {
    expect(code(verifyWith({ currentVersion: '1.0.1' }))).toBe('VERSION_NOT_NEWER')
    expect(code(verifyWith({ currentVersion: '1.1.0' }))).toBe('VERSION_NOT_NEWER')
    expect(compareVersions('1.10.0', '1.9.9')).toBe(1)
    expect(compareVersions('2.0.0', '2.0.0')).toBe(0)
    expect(() => compareVersions('1.0.0-beta', '1.0.0')).toThrow(UpdateAuthorizationError)
  })

  it('结构不符的签名文件被拒绝', () => {
    expect(code(() => parseSignedUpdate(null))).toBe('SIGNATURE_MALFORMED')
    expect(code(() => parseSignedUpdate({ ...fixture.signed, extra: 1 }))).toBe('SIGNATURE_MALFORMED')
    expect(code(() => parseSignedUpdate({ ...fixture.signed, statement: { ...fixture.signed.statement, schema: 'other/1' } }))).toBe('STATEMENT_SCHEMA')
    expect(code(() => parseSignedUpdate({ ...fixture.signed, statement: { ...fixture.signed.statement, size: '39' } }))).toBe('STATEMENT_MALFORMED')
    const withoutTag: Record<string, unknown> = { ...fixture.signed.statement }
    delete withoutTag.tag
    expect(code(() => parseSignedUpdate({ ...fixture.signed, statement: withoutTag }))).toBe('STATEMENT_MALFORMED')
  })
})
