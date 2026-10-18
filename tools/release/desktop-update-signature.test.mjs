import assert from 'node:assert/strict'
import { createHash, generateKeyPairSync } from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import {
  canonicalStatement, createStatement, fixtureKey, generateSigningKey, keyIdFor, rawPublicKey, signInstaller, signStatement, verifySigned,
} from './desktop-update-signature.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const fixture = JSON.parse(fs.readFileSync(path.join(root, 'desktop', 'desktop-shell', 'test', 'fixtures', 'signed-update.json'), 'utf8'))

const statementInput = {
  channel: 'stable', version: '1.0.1', file: 'YumpooDesktop-Setup-1.0.1-x64.exe',
  sha512: createHash('sha512').update('x').digest('base64'), size: 1, tag: 'desktop-v1.0.1', commit: 'b'.repeat(40),
}

test('签名与桌面端共享的金样完全一致（Ed25519 签名确定）', () => {
  const key = fixtureKey()
  assert.equal(key.publicKey, fixture.publicKey)
  assert.equal(key.keyId, fixture.signed.keyId)
  assert.deepEqual(signStatement(fixture.signed.statement, key.privateKey), fixture.signed)
  assert.equal(verifySigned(fixture.signed, fixture.publicKey), true)
})

test('规范化字段顺序固定，与输入键顺序无关', () => {
  const statement = createStatement(statementInput)
  const shuffled = Object.fromEntries(Object.entries(statement).reverse())
  assert.equal(canonicalStatement(shuffled), canonicalStatement(statement))
  assert.equal(canonicalStatement(statement).startsWith('{"schema":"yumpoo-desktop-update/1","channel":"stable","version":"1.0.1"'), true)
})

test('声明拒绝不一致或格式错误的输入', () => {
  assert.throws(() => createStatement({ ...statementInput, tag: 'desktop-v1.0.2' }), /Tag/)
  assert.throws(() => createStatement({ ...statementInput, version: '1.0' }), /Version/)
  assert.throws(() => createStatement({ ...statementInput, file: '../YumpooDesktop-Setup-1.0.1-x64.exe' }), /file name/)
  assert.throws(() => createStatement({ ...statementInput, sha512: 'abc' }), /sha512/)
  assert.throws(() => createStatement({ ...statementInput, commit: 'main' }), /commit/)
})

test('篡改声明或换用其他公钥都无法通过校验', () => {
  const key = fixtureKey()
  const signed = signStatement(createStatement(statementInput), key.privateKey)
  assert.equal(verifySigned(signed, key.publicKey), true)
  assert.equal(verifySigned({ ...signed, statement: { ...signed.statement, size: 2 } }, key.publicKey), false)
  const other = rawPublicKey(generateKeyPairSync('ed25519').publicKey)
  assert.equal(verifySigned(signed, other), false)
})

test('签署安装包写出 .sig.json，且不覆盖已有文件', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-sign-'))
  try {
    const installer = path.join(directory, 'YumpooDesktop-Setup-2.0.0-x64.exe')
    fs.writeFileSync(installer, 'installer bytes')
    const key = fixtureKey('another-fixture')
    const { target, signed } = signInstaller({ installer, channel: 'stable', version: '2.0.0', tag: 'desktop-v2.0.0', commit: 'c'.repeat(40), privateKey: key.privateKey })
    assert.deepEqual(JSON.parse(fs.readFileSync(target, 'utf8')), signed)
    assert.equal(signed.statement.size, 15)
    assert.equal(verifySigned(signed, key.publicKey), true)
    assert.throws(() => signInstaller({ installer, channel: 'stable', version: '2.0.0', tag: 'desktop-v2.0.0', commit: 'c'.repeat(40), privateKey: key.privateKey }), /EEXIST/)
  } finally {
    fs.rmSync(directory, { recursive: true, force: true })
  }
})

test('生成的密钥 keyId 由公钥派生', () => {
  const key = generateSigningKey()
  assert.match(key.privatePem, /^-----BEGIN PRIVATE KEY-----/u)
  assert.equal(Buffer.from(key.publicKey, 'base64').byteLength, 32)
  assert.equal(key.keyId, keyIdFor(key.publicKey))
  assert.match(key.keyId, /^ed25519-[0-9a-f]{16}$/u)
})
