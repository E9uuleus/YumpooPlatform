import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { collectDesktopRelease } from './collect-desktop-release.mjs'
import { desktopArtifactFileNames } from '../release/desktop-policy.mjs'
import { fixtureKey } from '../release/desktop-update-signature.mjs'

const sourceCommit = 'a'.repeat(40)
const version = '1.0.0'
const installer = `YumpooDesktop-Setup-${version}-x64.exe`
const { keyId, publicKey } = fixtureKey()
const distribution = {
  schemaVersion: 1,
  origin: 'https://wecom-dev.yumpoo.com',
  update: { feed: 'https://wecom-dev.yumpoo.com/desktop/stable/', channel: 'stable', publicKeys: [{ keyId, publicKey }] },
}
const bundled = [
  { name: 'electron-updater', version: '6.8.9', license: 'MIT' },
  { name: '@scope/inlined', version: '1.2.3', license: 'Apache-2.0' },
]

function build(t, { latest } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-desktop-collect-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const sourceRoot = path.join(root, 'release')
  fs.mkdirSync(sourceRoot)
  const bytes = Buffer.from('installer bytes')
  const sha512 = createHash('sha512').update(bytes).digest('base64')
  fs.writeFileSync(path.join(sourceRoot, installer), bytes)
  fs.writeFileSync(path.join(sourceRoot, `${installer}.blockmap`), 'blockmap')
  fs.writeFileSync(path.join(sourceRoot, 'latest.yml'), latest ?? [
    `version: ${version}`, 'files:', `  - url: ${installer}`, `    sha512: ${sha512}`, `    size: ${bytes.length}`,
    `path: ${installer}`, `sha512: ${sha512}`, "releaseDate: '2026-10-18T00:00:00.000Z'", '',
  ].join('\n'))
  fs.writeFileSync(path.join(sourceRoot, 'desktop-installer.json'), JSON.stringify({
    schemaVersion: 1, sourceCommit, version, electronVersion: '43.7.9',
    installer: { name: installer, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') },
  }))
  return { sourceRoot, outputRoot: path.join(root, 'out', 'desktop') }
}

test('collects exactly the tested installer, its update metadata, an SBOM with Electron and a manifest', t => {
  const { sourceRoot, outputRoot } = build(t)
  const manifest = collectDesktopRelease({ sourceRoot, outputRoot, sourceCommit, distribution, bundled })
  assert.deepEqual(fs.readdirSync(outputRoot).sort(), [...desktopArtifactFileNames(version)].sort())
  assert.equal(manifest.feed, distribution.update.feed)
  const sbom = JSON.parse(fs.readFileSync(path.join(outputRoot, 'yumpoo-desktop.cdx.json'), 'utf8'))
  assert.deepEqual(sbom.components.map(component => component.purl), [
    'pkg:npm/electron@43.7.9', 'pkg:npm/electron-updater@6.8.9', 'pkg:npm/%40scope/inlined@1.2.3',
  ])
  // Re-collecting the same build is byte-identical, so a rerun attests the same subjects.
  const first = fs.readFileSync(path.join(outputRoot, 'yumpoo-desktop.cdx.json'))
  collectDesktopRelease({ sourceRoot, outputRoot, sourceCommit, distribution, bundled })
  assert(first.equals(fs.readFileSync(path.join(outputRoot, 'yumpoo-desktop.cdx.json'))))
})

test('refuses a build from another commit, a latest.yml for other bytes and a distribution without a feed', t => {
  const { sourceRoot, outputRoot } = build(t)
  assert.throws(() => collectDesktopRelease({ sourceRoot, outputRoot, sourceCommit: 'b'.repeat(40), distribution, bundled }), /another commit/u)
  assert.throws(() => collectDesktopRelease({ sourceRoot, outputRoot, sourceCommit, distribution: { ...distribution, update: undefined }, bundled }), /no update feed/u)
  const tampered = build(t, { latest: `version: ${version}\nfiles:\n  - url: ${installer}\n    sha512: AAAA\n    size: 15\npath: ${installer}\nsha512: AAAA\n` })
  assert.throws(() => collectDesktopRelease({ ...tampered, sourceCommit, distribution, bundled }), /sha512 mismatch/u)
})
