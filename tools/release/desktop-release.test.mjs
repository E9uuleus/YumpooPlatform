import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { ciWorkflowPath, repository, requiredJobs } from './policy.mjs'
import {
  compareVersions, desktopArtifactFileNames, desktopReleaseAssetNames, installerName, parseLatestYml, requireUpdateDistribution,
  signatureName, validateDesktopSignature, validateDesktopTag,
} from './desktop-policy.mjs'
import { createStatement, fixtureKey, generateSigningKey, signStatement } from './desktop-update-signature.mjs'
import { prepareDesktopRelease, readDesktopCheckout, readDesktopSource } from './desktop-release.mjs'
import { planDesktopRelease, prepareDesktopDraft, signDesktopDraft } from './prepare-desktop-release.mjs'
import { rehearseDesktopFeed } from './rehearse-release.mjs'
import { collectDesktopRelease } from '../verification/collect-desktop-release.mjs'

const sha = 'a'.repeat(40)
const previousSha = 'b'.repeat(40)
const digest = value => createHash('sha256').update(value).digest('hex')
const draftEnv = {
  GITHUB_REPOSITORY: repository, GITHUB_EVENT_NAME: 'workflow_run', GITHUB_REF: 'refs/heads/dev',
  GITHUB_WORKFLOW_REF: `${repository}/.github/workflows/prepare-desktop-release.yml@refs/heads/dev`,
  GITHUB_WORKFLOW_SHA: 'f'.repeat(40),
}
const trusted = fixtureKey()
const backup = fixtureKey('yumpoo-desktop-update-backup-fixture')
const distribution = {
  schemaVersion: 1,
  origin: 'https://wecom-dev.yumpoo.com',
  update: {
    feed: 'https://wecom-dev.yumpoo.com/desktop/stable/', channel: 'stable',
    publicKeys: [trusted, backup].map(({ keyId, publicKey }) => ({ keyId, publicKey })),
  },
}
const pem = key => key.privateKey.export({ format: 'pem', type: 'pkcs8' })

/** The CI artifact exactly as collect-desktop-release.mjs writes it for `version`. */
function ciFiles(root, version) {
  const source = path.join(root, `m0-15-${version}`)
  fs.mkdirSync(source)
  const name = installerName(version)
  const bytes = Buffer.from(`installer ${version}`)
  const sha512 = createHash('sha512').update(bytes).digest('base64')
  fs.writeFileSync(path.join(source, name), bytes)
  fs.writeFileSync(path.join(source, `${name}.blockmap`), `blockmap ${version}`)
  fs.writeFileSync(path.join(source, 'latest.yml'), `version: ${version}\nfiles:\n  - url: ${name}\n    sha512: ${sha512}\n    size: ${bytes.length}\npath: ${name}\nsha512: ${sha512}\nreleaseDate: '2026-10-18T00:00:00.000Z'\n`)
  fs.writeFileSync(path.join(source, 'desktop-installer.json'), JSON.stringify({
    schemaVersion: 1, sourceCommit: sha, version, electronVersion: '43.7.9', installer: { name, bytes: bytes.length, sha256: digest(bytes) },
  }))
  const output = path.join(root, `ci-${version}`)
  collectDesktopRelease({ sourceRoot: source, outputRoot: output, sourceCommit: sha, distribution, bundled: [{ name: 'electron-updater', version: '6.8.9', license: 'MIT' }] })
  return Object.fromEntries(desktopArtifactFileNames(version).map(file => [file, fs.readFileSync(path.join(output, file))]))
}

function fixture(t, { version = '1.1.0', published = [{ tag: 'desktop-v1.0.0', sha: previousSha }] } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-desktop-release-test-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  let assetId = 0
  let releaseId = 100
  const run = {
    id: 3001, run_attempt: 1, workflow_id: 42, name: 'Platform CI', path: ciWorkflowPath, html_url: 'https://github.com/run/3001',
    repository: { full_name: repository }, head_repository: { full_name: repository },
    event: 'push', head_branch: 'main', head_sha: sha, status: 'completed', conclusion: 'success',
  }
  const files = ciFiles(root, version)
  const github = {
    calls: [], uploads: [], assets: new Map(), attestations: [],
    releases: published.map(({ tag, sha: target }, index) => ({ id: 50 + index, tag_name: tag, draft: false, published_at: '2026-10-10T00:00:00Z', target_commitish: target, immutable: true })),
    tags: [],
    archive: Buffer.from('desktop CI artifact archive'),
    get(endpoint) {
      this.calls.push(endpoint)
      if (endpoint === `/compare/${sha}...main?per_page=1`) return { status: 'ahead', merge_base_commit: { sha } }
      for (const [name, value] of [['package.json', { version }], ['distribution/production.json', distribution]]) {
        if (endpoint === `/contents/desktop/desktop-shell/${name}?ref=${sha}`) {
          return { type: 'file', encoding: 'base64', content: Buffer.from(JSON.stringify(value)).toString('base64') }
        }
      }
      if (endpoint === '/actions/workflows/m0-18-ci.yml') return { id: 42, path: ciWorkflowPath }
      if (endpoint === '/actions/runs/3001/attempts/1') return run
      const tagRef = endpoint.match(/^\/git\/ref\/tags\/(desktop-v[\d.]+)$/u)
      if (tagRef) return { object: { type: 'commit', sha: tagRef[1] === `desktop-v${version}` ? sha : previousSha } }
      if (endpoint === `/compare/${previousSha}...${sha}`) {
        return { commits: [{ sha: 'c'.repeat(40) }, { sha: 'd'.repeat(40) }, { sha: 'e'.repeat(40) }] }
      }
      if (endpoint === `/commits?sha=${sha}&path=desktop&per_page=100`) {
        return [
          { sha: 'e'.repeat(40), parents: [{}], commit: { message: 'fix(desktop): 修复托盘 (DESK-9)\n\nbody' } },
          { sha: 'c'.repeat(40), parents: [{}], commit: { message: 'feat(desktop): 新增深链 (DESK-8)' } },
          { sha: 'f'.repeat(40), parents: [{}], commit: { message: 'feat(desktop): 上一版已发布的提交' } },
        ]
      }
      if (endpoint === `/commits?sha=${sha}&path=packages%2Fpreload-contract&per_page=100`) return [{ sha: 'd'.repeat(40), parents: [{}, {}], commit: { message: 'Merge dev' } }]
      const releaseByTag = endpoint.match(/^\/releases\/tags\/(desktop-v[\d.]+)$/u)
      if (releaseByTag) return this.releases.find(release => release.tag_name === releaseByTag[1] && !release.draft)
      throw new Error(`Unexpected GitHub request: ${endpoint}`)
    },
    list(endpoint) {
      this.calls.push(endpoint)
      if (endpoint === '/releases?per_page=100') return this.releases
      if (endpoint.startsWith('/git/matching-refs/tags/')) return this.tags
      if (endpoint.startsWith('/actions/workflows/42/runs?')) return [run]
      if (endpoint === '/actions/runs/3001/attempts/1/jobs?per_page=100') {
        return requiredJobs.map(name => ({ name, run_id: 3001, run_attempt: 1, head_sha: sha, status: 'completed', conclusion: 'success' }))
      }
      if (endpoint === '/actions/runs/3001/artifacts?per_page=100') {
        return [{ id: 900, name: `desktop-${sha}-1`, expired: false, digest: `sha256:${digest(this.archive)}`, workflow_run: { id: 3001, head_branch: 'main', head_sha: sha } }]
      }
      const match = endpoint.match(/^\/releases\/(\d+)\/assets\?per_page=100$/u)
      if (match) return [...this.assets.values()].filter(item => item.releaseId === Number(match[1])).map(item => item.asset)
      throw new Error(`Unexpected GitHub list: ${endpoint}`)
    },
    download(endpoint, destination) {
      this.calls.push(endpoint)
      if (endpoint === '/actions/artifacts/900/zip') return fs.writeFileSync(destination, this.archive)
      const item = [...this.assets.values()].find(value => endpoint === `/releases/assets/${value.asset.id}`)
      assert(item, `Unknown asset ${endpoint}`)
      fs.writeFileSync(destination, item.bytes)
    },
    send(method, endpoint, body) {
      this.calls.push(`${method} ${endpoint}`)
      assert.equal(`${method} ${endpoint}`, 'POST /releases')
      const release = { id: ++releaseId, html_url: `https://github.com/${repository}/releases/tag/untagged-${releaseId}`, ...body }
      this.releases.push(release)
      return release
    },
    addAsset(id, name, bytes) {
      assert(![...this.assets.values()].some(item => item.releaseId === id && item.asset.name === name), 'assets are never overwritten')
      this.assets.set(`${id}/${name}`, { releaseId: id, bytes, asset: { id: ++assetId, name, state: 'uploaded', size: bytes.length, digest: `sha256:${digest(bytes)}` } })
    },
    uploadAsset(id, file) {
      this.uploads.push(path.basename(file))
      this.addAsset(id, path.basename(file), fs.readFileSync(file))
    },
  }
  const unpack = (_archive, directory, validate) => {
    validate(Object.keys(files))
    fs.mkdirSync(directory)
    for (const [name, bytes] of Object.entries(files)) fs.writeFileSync(path.join(directory, name), bytes)
  }
  const attest = async (directory, ciRun, options) => {
    assert(options.files.length > 0 && options.files.every(name => Object.hasOwn(files, name)))
    for (const name of options.files) assert.deepEqual(fs.readFileSync(path.join(directory, name)), files[name], `Unattested desktop bytes: ${name}`)
    github.attestations.push({ label: options.label, uploadsSoFar: github.uploads.length, run: ciRun.id, files: options.files })
  }
  const checkout = { version, distribution }
  const options = extra => ({ github, sourceCommit: sha, env: draftEnv, directory: fs.mkdtempSync(path.join(root, 'run-')), checkout, unpack, attest, log: () => {}, ...extra })
  return { github, files, root, version, options, attest, run }
}

const draftOf = github => github.releases.find(release => release.draft)

test('desktop tags, versions and latest.yml parse strictly', () => {
  assert.equal(validateDesktopTag('desktop-v1.2.3'), '1.2.3')
  for (const tag of ['desktop-v1.2', 'desktop-v01.2.3', 'server-v2026.10.18.1', 'desktop-v1.2.3-beta']) assert.throws(() => validateDesktopTag(tag))
  assert(compareVersions('1.10.0', '1.9.9') > 0)
  assert.equal(compareVersions('2.0.0', '2.0.0'), 0)
  const latest = parseLatestYml("version: 1.0.0\nfiles:\n  - url: a.exe\n    sha512: x==\n    size: 3\n    isAdminRightsRequired: false\npath: a.exe\nsha512: x==\nreleaseDate: '2026'\n")
  assert.deepEqual(latest.files, [{ url: 'a.exe', sha512: 'x==', size: '3', isAdminRightsRequired: 'false' }])
  assert.throws(() => parseLatestYml('version: 1\n  garbage'), /Unsupported/u)
})

test('the production distribution must name the channel feed and keys derived from themselves', () => {
  assert.doesNotThrow(() => requireUpdateDistribution(distribution))
  assert.throws(() => requireUpdateDistribution({ origin: distribution.origin }), /no update feed/u)
  assert.throws(() => requireUpdateDistribution({ ...distribution, update: { ...distribution.update, feed: 'https://wecom-dev.yumpoo.com/updates/' } }))
  const wrongId = { ...distribution, update: { ...distribution.update, publicKeys: [{ keyId: backup.keyId, publicKey: trusted.publicKey }] } }
  assert.throws(() => requireUpdateDistribution(wrongId), /derived/u)
})

test('the tracked production distribution trusts exactly the primary and offline backup keys, never a test key', () => {
  const file = new URL('../../desktop/desktop-shell/distribution/production.json', import.meta.url)
  const raw = fs.readFileSync(file, 'utf8')
  assert.doesNotMatch(raw, /PRIVATE|privateKey|-----BEGIN/u, 'only public keys may be committed')
  const { distribution: production, version } = readDesktopCheckout()
  assert.match(version, /^\d+\.\d+\.\d+$/u)
  requireUpdateDistribution(production)
  assert.equal(production.update.feed, `${production.origin}/desktop/stable/`)
  // Pinned on purpose: changing a trusted key is a key rotation and must show up in review as a test change.
  assert.deepEqual(production.update.publicKeys.map(key => key.keyId), ['ed25519-4e821e83241d7403', 'ed25519-edf5f6dd1fe98a5f'])
  const testKeys = [fixtureKey(), fixtureKey('yumpoo-desktop-update-backup-fixture')].map(key => key.keyId)
  assert(!production.update.publicKeys.some(key => testKeys.includes(key.keyId)), 'fixture keys are public and must never be trusted by a shipped build')
})

test('old, unchanged and already drafted versions get no new draft', async t => {
  for (const [settings, reason] of [
    [{ version: '0.1.0' }, /0\.x portable line/u],
    [{ version: '1.0.0' }, /already published/u],
    [{ version: '0.9.0' }, /0\.x/u],
    [{ version: '1.0.1', published: [{ tag: 'desktop-v1.2.0', sha: previousSha }] }, /not newer/u],
  ]) {
    const { options } = fixture(t, settings)
    assert.match((await planDesktopRelease(options())).skip, reason)
  }
  const { options, github } = fixture(t)
  github.releases.push({ id: 77, tag_name: 'desktop-v1.1.0', draft: true, target_commitish: 'c'.repeat(40) })
  assert.match((await prepareDesktopDraft(options())).skipped, /already exists for c{40}/u)
  assert.deepEqual(github.uploads, [])
})

test('preparation reads candidate JSON at an exact main commit while executing the protected workflow revision', async t => {
  const { github, options, version } = fixture(t)
  assert.deepEqual(await readDesktopSource(github, sha), { version, distribution })
  assert.deepEqual(github.calls, [
    `/compare/${sha}...main?per_page=1`,
    `/contents/desktop/desktop-shell/package.json?ref=${sha}`,
    `/contents/desktop/desktop-shell/distribution/production.json?ref=${sha}`,
  ])
  for (const fields of [
    { GITHUB_REF: 'refs/heads/feature/unreviewed' },
    { GITHUB_WORKFLOW_REF: `${repository}/.github/workflows/prepare-desktop-release.yml@refs/heads/feature/unreviewed` },
    { GITHUB_WORKFLOW_SHA: '' },
  ]) await assert.rejects(planDesktopRelease(options({ env: { ...draftEnv, GITHUB_EVENT_NAME: 'workflow_dispatch', ...fields } })))
  await assert.rejects(readDesktopSource(github, 'feature/unreviewed'), /Invalid source commit/u)
  github.calls.length = 0
  github.get = endpoint => {
    github.calls.push(endpoint)
    return { status: 'diverged', merge_base_commit: { sha: previousSha } }
  }
  await assert.rejects(readDesktopSource(github, sha), /ancestor/u)
  assert.deepEqual(github.calls, [`/compare/${sha}...main?per_page=1`], 'unreviewed candidates are rejected before reading their configuration')
  assert.deepEqual(github.uploads, [])
})

test('a draft carries the attested CI bytes, provenance first, notes limited to desktop commits in range', async t => {
  const { github, options, version } = fixture(t)
  const result = await prepareDesktopDraft(options())
  assert.equal(result.tag, 'desktop-v1.1.0')
  assert.equal(result.created, true)
  assert.deepEqual(github.uploads, ['provenance.json', ...desktopArtifactFileNames(version), 'release.json'])
  assert.deepEqual(github.attestations.map(entry => [entry.label, entry.uploadsSoFar]),
    [['desktop CI bytes before upload', 0], ['persisted desktop draft assets', 7]])
  const draft = draftOf(github)
  assert.match(draft.body, /新增深链[\s\S]*修复托盘/u, 'oldest first, merge commits and earlier releases excluded')
  assert.doesNotMatch(draft.body, /上一版已发布|Merge dev/u)
  const releaseJson = JSON.parse(github.assets.get(`${draft.id}/release.json`).bytes)
  assert.deepEqual(releaseJson.notes, ['feat(desktop): 新增深链 (DESK-8)', 'fix(desktop): 修复托盘 (DESK-9)'])
  assert.equal(releaseJson.installer.name, installerName(version))
  // A rerun finds every asset in place and uploads nothing.
  await prepareDesktopDraft(options())
  assert.equal(github.uploads.length, 7)
})

test('the first channel release says so instead of claiming a maintenance update', async t => {
  const { github, options } = fixture(t, { version: '1.0.0', published: [] })
  assert.equal((await prepareDesktopDraft(options())).tag, 'desktop-v1.0.0')
  const draft = draftOf(github)
  assert.match(draft.body, /上一版本：无（首个经发布渠道分发的版本）/u)
  assert.deepEqual(JSON.parse(github.assets.get(`${draft.id}/release.json`).bytes).notes, ['首个安装版：按用户安装，支持自动更新、诊断与修复和安全模式'])
})

test('only a key the released build trusts may sign, and the signature covers the draft installer', async t => {
  const { github, options, version } = fixture(t)
  await prepareDesktopDraft(options())
  await assert.rejects(signDesktopDraft(options({ privateKeyPem: undefined })), /DESKTOP_UPDATE_SIGNING_KEY/u)
  await assert.rejects(signDesktopDraft(options({ privateKeyPem: generateSigningKey().privatePem })), /not trusted/u)
  const signed = await signDesktopDraft(options({ privateKeyPem: pem(trusted) }))
  assert.equal(signed.keyId, trusted.keyId)
  assert.deepEqual(github.attestations.at(-1).files, [installerName(version)])
  assert.equal(github.attestations.at(-1).uploadsSoFar, 7, 'attestation precedes the signature upload')
  assert.equal(github.uploads.at(-1), signatureName(version))
  const again = await signDesktopDraft(options({ privateKeyPem: undefined }))
  assert.equal(again.created, false, 'an attached authorization is verified, not replaced')
})

test('signing rejects a replaced installer even when its mutable provenance and GitHub digest were replaced too', async t => {
  const { github, options, version } = fixture(t)
  await prepareDesktopDraft(options())
  const draft = draftOf(github)
  const replace = (name, bytes) => {
    const item = github.assets.get(`${draft.id}/${name}`)
    item.bytes = bytes
    Object.assign(item.asset, { digest: `sha256:${digest(bytes)}`, size: bytes.length })
  }
  const payload = Buffer.from('replacement that was never attested by CI')
  const provenance = JSON.parse(github.assets.get(`${draft.id}/provenance.json`).bytes)
  Object.assign(provenance, { installerSha256: digest(payload), installerBytes: payload.length })
  replace(installerName(version), payload)
  replace('provenance.json', Buffer.from(JSON.stringify(provenance)))
  await assert.rejects(signDesktopDraft(options({ privateKeyPem: pem(trusted) })), /Unattested desktop bytes/u)
  assert(!github.uploads.includes(signatureName(version)), 'no client-trusted authorization is produced for replaced draft bytes')
})

test('signing independently checks the pinned main CI attempt before authorizing the draft', async t => {
  const { github, options, version, run } = fixture(t)
  await prepareDesktopDraft(options())
  run.conclusion = 'failure'
  await assert.rejects(signDesktopDraft(options({ privateKeyPem: pem(trusted) })), /CI attempt did not succeed/u)
  assert(!github.uploads.includes(signatureName(version)))
  assert.equal(github.attestations.length, 2, 'failed CI is rejected before the signing attestation')
})

test('publishing verifies the immutable Release, its CI attempt, attestations and the authorization', async t => {
  const { github, options, version } = fixture(t, { version: '1.1.0' })
  await prepareDesktopDraft(options())
  await signDesktopDraft(options({ privateKeyPem: pem(trusted) }))
  const draft = draftOf(github)
  Object.assign(draft, { draft: false, published_at: '2026-10-18T08:00:00Z', immutable: true })
  const env = {
    GITHUB_REPOSITORY: repository, GITHUB_EVENT_NAME: 'workflow_dispatch', GITHUB_REF: 'refs/tags/desktop-v1.1.0',
    GITHUB_WORKFLOW_REF: `${repository}/.github/workflows/desktop-release.yml@refs/tags/desktop-v1.1.0`, GITHUB_SHA: sha, GITHUB_WORKFLOW_SHA: sha,
  }
  const verify = extra => prepareDesktopRelease({ ...options(), tag: 'desktop-v1.1.0', env, ...extra })
  const verified = await verify()
  assert.equal(verified.installer.name, installerName(version))
  assert.deepEqual(fs.readdirSync(verified.directory).sort(), [...desktopReleaseAssetNames(version)].sort())
  await assert.rejects(verify({ env: { ...env, GITHUB_WORKFLOW_REF: `${repository}/.github/workflows/windows-release.yml@refs/tags/desktop-v1.1.0` } }), /Workflow ref/u)
  await assert.rejects(verify({ checkout: { version: '1.2.0', distribution } }), /another desktop version/u)
  // A shipped build that no longer trusts the signer must not receive this update.
  const rotated = { ...distribution, update: { ...distribution.update, publicKeys: [{ keyId: backup.keyId, publicKey: backup.publicKey }] } }
  await assert.rejects(verify({ checkout: { version, distribution: rotated } }), /not trusted/u)
  Object.assign(draft, { immutable: false })
  await assert.rejects(verify(), /immutable/u)
})

test('the weekly rehearsal requires the feed to serve the newest published latest.yml and release.json', async () => {
  const bytes = { 'latest.yml': Buffer.from('version: 1.1.0\n'), 'release.json': Buffer.from('{"version":"1.1.0"}') }
  const asset = (id, name) => ({ id, name, state: 'uploaded', size: bytes[name].length, digest: `sha256:${digest(bytes[name])}` })
  const github = {
    list(endpoint) {
      if (endpoint === '/releases?per_page=100') {
        return [
          { id: 1, tag_name: 'desktop-v1.0.0', draft: false, published_at: '2026-10-01T00:00:00Z' },
          { id: 2, tag_name: 'desktop-v1.1.0', draft: false, published_at: '2026-10-02T00:00:00Z' },
          { id: 3, tag_name: 'server-v2026.10.18.1', draft: false, published_at: '2026-10-18T00:00:00Z' },
        ]
      }
      if (endpoint === '/releases/2/assets?per_page=100') return [asset(20, 'latest.yml'), asset(21, 'release.json')]
      throw new Error(`Unexpected list ${endpoint}`)
    },
  }
  const requested = []
  const served = { ...bytes }
  const fetchFile = async url => { requested.push(url); return served[url.split('/').at(-1)] }
  assert.deepEqual(await rehearseDesktopFeed({ github, distribution, fetchFile }), { mode: 'feed', tag: 'desktop-v1.1.0', feed: distribution.update.feed })
  assert.deepEqual(requested, ['https://wecom-dev.yumpoo.com/desktop/stable/latest.yml', 'https://wecom-dev.yumpoo.com/desktop/stable/release.json'])
  served['release.json'] = Buffer.from('{"version":"1.0.0"}')
  await assert.rejects(rehearseDesktopFeed({ github, distribution, fetchFile }), /differs from desktop-v1\.1\.0/u)
  assert.equal((await rehearseDesktopFeed({ github, distribution: { origin: distribution.origin }, fetchFile })).mode, 'skipped')
  assert.equal((await rehearseDesktopFeed({ github: { list: () => [] }, distribution, fetchFile })).mode, 'skipped')
})

test('a signature for other bytes or another version is rejected', () => {
  const installer = { name: installerName('1.1.0'), sha512: createHash('sha512').update('x').digest('base64'), bytes: 1 }
  const statement = createStatement({ channel: 'stable', version: '1.1.0', file: installer.name, sha512: installer.sha512, size: 1, tag: 'desktop-v1.1.0', commit: sha })
  const signed = signStatement(statement, trusted.privateKey)
  assert.doesNotThrow(() => validateDesktopSignature(signed, { distribution, version: '1.1.0', sourceCommit: sha, installer }))
  assert.throws(() => validateDesktopSignature(signed, { distribution, version: '1.1.0', sourceCommit: sha, installer: { ...installer, bytes: 2 } }), /does not describe/u)
  assert.throws(() => validateDesktopSignature({ ...signed, signature: signStatement({ ...statement, version: '1.2.0', tag: 'desktop-v1.2.0' }, trusted.privateKey).signature }, { distribution, version: '1.1.0', sourceCommit: sha, installer }), /invalid/u)
})
