import assert from 'node:assert/strict'
import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, sign, verify } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

// Ed25519 update authorization for the desktop channel. The desktop shell verifies these statements with its
// pinned public keys (desktop/desktop-shell/src/main/update-signature.ts); both sides must canonicalize identically.
// Built-ins only: release workflows run these tools without installing dependencies.
export const STATEMENT_SCHEMA = 'yumpoo-desktop-update/1'
const STATEMENT_FIELDS = ['schema', 'channel', 'version', 'file', 'sha512', 'size', 'tag', 'commit']
const ED25519_SPKI_PREFIX = Buffer.from('302a300506032b6570032100', 'hex')
const ED25519_PKCS8_PREFIX = Buffer.from('302e020100300506032b657004220420', 'hex')

export function canonicalStatement(statement) {
  return JSON.stringify(Object.fromEntries(STATEMENT_FIELDS.map(field => [field, statement[field]])))
}

export function rawPublicKey(publicKey) {
  const der = publicKey.export({ format: 'der', type: 'spki' })
  assert(der.subarray(0, ED25519_SPKI_PREFIX.length).equals(ED25519_SPKI_PREFIX), 'Not an Ed25519 public key')
  return der.subarray(ED25519_SPKI_PREFIX.length).toString('base64')
}

/** Derived from the key itself, so a statement can never name a key other than the one that signed it. */
export function keyIdFor(publicKeyBase64) {
  return `ed25519-${createHash('sha256').update(Buffer.from(publicKeyBase64, 'base64')).digest('hex').slice(0, 16)}`
}

/** Deterministic key for regression fixtures only; it is never trusted by any shipped distribution. */
export function fixtureKey(label = 'yumpoo-desktop-update-fixture') {
  const seed = createHash('sha256').update(label).digest()
  const privateKey = createPrivateKey({ key: Buffer.concat([ED25519_PKCS8_PREFIX, seed]), format: 'der', type: 'pkcs8' })
  const publicKey = rawPublicKey(createPublicKey(privateKey))
  return { privateKey, publicKey, keyId: keyIdFor(publicKey) }
}

export function fileDigests(file) {
  const data = fs.readFileSync(file)
  return { sha512: createHash('sha512').update(data).digest('base64'), size: data.byteLength }
}

export function createStatement({ channel, version, file, sha512, size, tag, commit }) {
  assert(/^[a-z][a-z0-9-]{0,15}$/u.test(channel), 'Invalid channel')
  assert(/^(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})$/u.test(version), 'Version must be X.Y.Z')
  assert(path.basename(file) === file && /^YumpooDesktop-Setup-[\d.]+-x64\.exe$/u.test(file), 'Unexpected installer file name')
  assert(Buffer.from(sha512, 'base64').byteLength === 64, 'sha512 must be base64 SHA-512')
  assert(Number.isSafeInteger(size) && size > 0, 'Invalid size')
  assert.equal(tag, `desktop-v${version}`, 'Tag must be desktop-v<version>')
  assert(/^[0-9a-f]{40}$/u.test(commit), 'Invalid commit')
  return { schema: STATEMENT_SCHEMA, channel, version, file, sha512, size, tag, commit }
}

export function signStatement(statement, privateKey) {
  const publicKey = rawPublicKey(createPublicKey(privateKey))
  const signature = sign(null, Buffer.from(canonicalStatement(statement), 'utf8'), privateKey).toString('base64')
  return { keyId: keyIdFor(publicKey), statement, signature }
}

export function verifySigned(signed, publicKeyBase64) {
  const key = createPublicKey({ key: Buffer.concat([ED25519_SPKI_PREFIX, Buffer.from(publicKeyBase64, 'base64')]), format: 'der', type: 'spki' })
  return signed.keyId === keyIdFor(publicKeyBase64)
    && verify(null, Buffer.from(canonicalStatement(signed.statement), 'utf8'), key, Buffer.from(signed.signature, 'base64'))
}

/** Signs one installer and writes <installer>.sig.json next to it; refuses to overwrite. */
export function signInstaller({ installer, channel, version, tag, commit, privateKey }) {
  const { sha512, size } = fileDigests(installer)
  const statement = createStatement({ channel, version, file: path.basename(installer), sha512, size, tag, commit })
  const signed = signStatement(statement, privateKey)
  const target = `${installer}.sig.json`
  fs.writeFileSync(target, `${JSON.stringify(signed, null, 2)}\n`, { flag: 'wx' })
  return { target, signed }
}

export function generateSigningKey() {
  const { privateKey, publicKey } = generateKeyPairSync('ed25519')
  const raw = rawPublicKey(publicKey)
  return { privatePem: privateKey.export({ format: 'pem', type: 'pkcs8' }), publicKey: raw, keyId: keyIdFor(raw) }
}

function option(args, name) {
  const index = args.indexOf(name)
  const value = index >= 0 ? args[index + 1] : undefined
  assert(value && !value.startsWith('--'), `Missing ${name}`)
  return value
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const [command, ...args] = process.argv.slice(2)
  if (command === 'keygen') {
    // The private key goes to a file the maintainer moves into the signing Environment secret and then deletes;
    // only the public half is printed, ready for distribution/<channel>.json.
    const out = path.resolve(option(args, '--private-key-out'))
    const key = generateSigningKey()
    fs.writeFileSync(out, key.privatePem, { flag: 'wx', mode: 0o600 })
    console.log(JSON.stringify({ keyId: key.keyId, publicKey: key.publicKey }, null, 2))
  } else if (command === 'sign') {
    const pem = process.env[option(args, '--private-key-env')]
    assert(pem, 'Private key environment variable is empty')
    const { target, signed } = signInstaller({
      installer: path.resolve(option(args, '--installer')),
      channel: option(args, '--channel'),
      version: option(args, '--version'),
      tag: option(args, '--tag'),
      commit: option(args, '--commit'),
      privateKey: createPrivateKey(pem),
    })
    console.log(`Signed ${signed.statement.file} with ${signed.keyId}: ${target}`)
  } else {
    console.error('Usage: desktop-update-signature.mjs keygen --private-key-out <file> | sign --installer <exe> --channel <c> --version <v> --tag <t> --commit <sha> --private-key-env <NAME>')
    process.exitCode = 2
  }
}
