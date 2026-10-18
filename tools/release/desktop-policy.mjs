import assert from 'node:assert/strict'
import { repository } from './policy.mjs'
import { STATEMENT_SCHEMA, keyIdFor, verifySigned } from './desktop-update-signature.mjs'

// The desktop channel has its own tag line, draft and feed; it shares the main CI run, attestation and engine with
// the server release line but never its tags, assets or deployment state.
export const desktopReleaseWorkflowPath = '.github/workflows/desktop-release.yml'
export const desktopPrepareWorkflowPath = '.github/workflows/prepare-desktop-release.yml'
export const desktopSbomName = 'yumpoo-desktop.cdx.json'
export const desktopManifestName = 'desktop-manifest.json'
export const desktopSbomComponent = { group: '@yumpoo', name: 'desktop-shell' }
export const desktopSigningSecret = 'DESKTOP_UPDATE_SIGNING_KEY'
const versionPattern = /^(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})$/u
const tagPattern = /^desktop-v((?:0|[1-9]\d{0,5})\.(?:0|[1-9]\d{0,5})\.(?:0|[1-9]\d{0,5}))$/u
const commitPattern = /^[0-9a-f]{40}$/u
const digestPattern = /^[0-9a-f]{64}$/u

export function validateDesktopVersion(version) {
  assert(typeof version === 'string' && versionPattern.test(version), 'Desktop version must be X.Y.Z')
  return version
}

/** Returns the version a desktop-vX.Y.Z tag names. */
export function validateDesktopTag(tag) {
  const match = typeof tag === 'string' && tag.match(tagPattern)
  assert(match, 'Invalid desktop release tag; expected desktop-vX.Y.Z')
  return match[1]
}

export function compareVersions(left, right) {
  const a = validateDesktopVersion(left).split('.').map(Number)
  const b = validateDesktopVersion(right).split('.').map(Number)
  for (let index = 0; index < 3; index += 1) if (a[index] !== b[index]) return a[index] - b[index]
  return 0
}

export const desktopTag = version => `desktop-v${validateDesktopVersion(version)}`
export const installerName = version => `YumpooDesktop-Setup-${validateDesktopVersion(version)}-x64.exe`
export const blockmapName = version => `${installerName(version)}.blockmap`
export const signatureName = version => `${installerName(version)}.sig.json`
export const expectedDesktopArtifactName = (sourceCommit, attempt) => `desktop-${sourceCommit}-${attempt}`

/** Built and attested by the main push CI, uploaded as the `desktop-<sha>-<attempt>` artifact. */
export const desktopArtifactFileNames = version => [installerName(version), blockmapName(version), 'latest.yml', desktopSbomName, desktopManifestName]
/** What the update feed serves; latest.yml and release.json are replaced last. */
export const desktopFeedFileNames = version => [installerName(version), blockmapName(version), signatureName(version), 'latest.yml', 'release.json']
export const desktopReleaseAssetNames = version => [...desktopArtifactFileNames(version), 'provenance.json', 'release.json', signatureName(version)]

export function validateDesktopArtifactEntries(entries, version) {
  assert.deepEqual([...entries].sort(), [...desktopArtifactFileNames(version)].sort(), 'Desktop CI artifact has unexpected files or duplicate entries')
}

export function validateDesktopProvenance(value, { tag, sourceCommit }) {
  assert.deepEqual(Object.keys(value).sort(), [
    'schemaVersion', 'repository', 'tag', 'version', 'sourceCommit', 'ciRunId', 'ciRunAttempt', 'artifactName', 'installerSha256', 'installerBytes',
  ].sort(), 'Unexpected desktop provenance fields')
  assert.equal(value.schemaVersion, 1, 'Unsupported desktop provenance schema')
  assert.equal(value.repository, repository, 'Wrong provenance repository')
  assert.equal(value.tag, tag, 'Provenance tag mismatch')
  assert.equal(value.version, validateDesktopTag(tag), 'Provenance version mismatch')
  assert.equal(value.sourceCommit, sourceCommit, 'Provenance commit mismatch')
  assert(Number.isSafeInteger(value.ciRunId) && value.ciRunId > 0, 'Invalid provenance CI run')
  assert(Number.isSafeInteger(value.ciRunAttempt) && value.ciRunAttempt > 0, 'Invalid provenance CI attempt')
  assert.equal(value.artifactName, expectedDesktopArtifactName(sourceCommit, value.ciRunAttempt), 'Provenance artifact mismatch')
  assert(digestPattern.test(value.installerSha256), 'Invalid installer digest')
  assert(Number.isSafeInteger(value.installerBytes) && value.installerBytes > 0, 'Invalid installer size')
  return value
}

export function validateDesktopPrepareContext(env) {
  assert.equal(env.GITHUB_REPOSITORY, repository, 'Unexpected repository')
  assert(['workflow_run', 'workflow_dispatch'].includes(env.GITHUB_EVENT_NAME), 'Unsupported draft trigger')
  assert.equal(env.GITHUB_REF, 'refs/heads/dev', 'Run desktop preparation from the protected dev branch')
  assert.equal(env.GITHUB_WORKFLOW_REF, `${repository}/${desktopPrepareWorkflowPath}@refs/heads/dev`, 'Desktop preparation must use the protected workflow')
  assert(commitPattern.test(env.GITHUB_WORKFLOW_SHA), 'Invalid preparation workflow commit')
}

export function validateDesktopWorkflowContext(env, tag, sourceCommit) {
  validateDesktopTag(tag)
  assert(commitPattern.test(sourceCommit), 'Invalid source commit')
  assert.equal(env.GITHUB_REPOSITORY, repository, 'Unexpected repository')
  assert(['release', 'workflow_dispatch'].includes(env.GITHUB_EVENT_NAME), 'Unsupported deployment trigger')
  assert.equal(env.GITHUB_REF, `refs/tags/${tag}`, 'Run this workflow on the target release tag')
  assert.equal(env.GITHUB_WORKFLOW_REF, `${repository}/${desktopReleaseWorkflowPath}@refs/tags/${tag}`, 'Workflow ref must be the target tag')
  assert.equal(env.GITHUB_SHA, sourceCommit, 'The release tag moved or does not match the workflow commit')
  assert.equal(env.GITHUB_WORKFLOW_SHA, sourceCommit, 'Workflow code must come from the target commit')
}

/**
 * The subset of electron-builder's latest.yml the updater reads: version, the single installer entry and the
 * top-level path/sha512 pair. Anything else (releaseDate, isAdminRightsRequired) is ignored.
 */
export function parseLatestYml(text) {
  const result = { files: [] }
  let current
  for (const raw of String(text).split(/\r?\n/u)) {
    if (!raw.trim()) continue
    const item = raw.match(/^ {2}- ([A-Za-z][A-Za-z0-9]*): (.+)$/u)
    const nested = raw.match(/^ {4}([A-Za-z][A-Za-z0-9]*): (.+)$/u)
    const top = raw.match(/^([A-Za-z][A-Za-z0-9]*):(?: (.*))?$/u)
    if (item) {
      current = {}
      result.files.push(current)
      current[item[1]] = unquote(item[2])
    } else if (nested && current) {
      current[nested[1]] = unquote(nested[2])
    } else if (top) {
      current = undefined
      if (top[1] !== 'files') result[top[1]] = unquote(top[2] ?? '')
    } else {
      throw new Error(`Unsupported latest.yml line: ${raw.slice(0, 80)}`)
    }
  }
  return result
}

function unquote(value) {
  const trimmed = value.trim()
  return /^'.*'$/u.test(trimmed) ? trimmed.slice(1, -1).replaceAll("''", "'") : /^".*"$/u.test(trimmed) ? JSON.parse(trimmed) : trimmed
}

export function validateLatestYml(text, { version, installer }) {
  const latest = parseLatestYml(text)
  assert.equal(latest.version, version, 'latest.yml version mismatch')
  assert.equal(latest.files.length, 1, 'latest.yml must list exactly one installer')
  const [file] = latest.files
  assert.equal(file.url, installer.name, 'latest.yml installer name mismatch')
  assert.equal(file.sha512, installer.sha512, 'latest.yml installer sha512 mismatch')
  assert.equal(Number(file.size), installer.bytes, 'latest.yml installer size mismatch')
  assert.equal(latest.path, installer.name, 'latest.yml path mismatch')
  assert.equal(latest.sha512, installer.sha512, 'latest.yml sha512 mismatch')
  return latest
}

/** Validates desktop-manifest.json against the files it describes; `files` maps name → { sha256, sha512, bytes }. */
export function validateDesktopManifest(manifest, { sourceCommit, version, distribution, files }) {
  assert.deepEqual(Object.keys(manifest).sort(), ['schemaVersion', 'sourceCommit', 'version', 'electronVersion', 'channel', 'feed', 'installer', 'files'].sort(),
    'Unexpected desktop manifest fields')
  assert.equal(manifest.schemaVersion, 1, 'Unsupported desktop manifest')
  assert.equal(manifest.sourceCommit, sourceCommit, 'Desktop manifest commit mismatch')
  assert.equal(manifest.version, version, 'Desktop manifest version mismatch')
  assert(versionPattern.test(manifest.electronVersion), 'Invalid Electron version')
  assert.equal(manifest.channel, distribution.update.channel, 'Desktop manifest channel mismatch')
  assert.equal(manifest.feed, distribution.update.feed, 'Desktop manifest feed mismatch')
  assert.equal(manifest.installer, installerName(version), 'Desktop manifest installer mismatch')
  const described = desktopArtifactFileNames(version).filter(name => name !== desktopManifestName)
  assert.deepEqual(manifest.files.map(file => file.name).sort(), [...described].sort(), 'Desktop manifest must describe every artifact file')
  for (const entry of manifest.files) {
    const actual = files.get(entry.name)
    assert(actual, `Missing desktop file: ${entry.name}`)
    assert.equal(entry.sha256, actual.sha256, `Desktop file digest mismatch: ${entry.name}`)
    assert.equal(entry.bytes, actual.bytes, `Desktop file size mismatch: ${entry.name}`)
    if (entry.name === manifest.installer) assert.equal(entry.sha512, actual.sha512, 'Desktop installer sha512 mismatch')
  }
  return manifest
}

export function validateDesktopSbom(document, { version, electronVersion }) {
  assert.equal(document?.bomFormat, 'CycloneDX', 'Desktop SBOM is not CycloneDX')
  assert(/^1\.[5-9]$/u.test(document.specVersion), 'Unsupported CycloneDX version')
  const root = document.metadata?.component
  assert.equal(root?.type, 'application', 'Desktop SBOM root must be the application')
  assert.equal(root.group, desktopSbomComponent.group, 'Wrong desktop SBOM root group')
  assert.equal(root.name, desktopSbomComponent.name, 'Wrong desktop SBOM root component')
  assert.equal(root.version, version, 'Desktop SBOM version mismatch')
  assert(Array.isArray(document.components) && document.components.length > 1, 'Desktop SBOM lists no bundled components')
  assert(document.components.every(item => typeof item.purl === 'string' && item.purl.startsWith('pkg:')), 'Every desktop SBOM component needs a package URL')
  // The runtime ships inside the installer, so it must be listed even though it is a devDependency of the workspace.
  assert(document.components.some(item => item.purl === `pkg:npm/electron@${electronVersion}`), 'Desktop SBOM must list the Electron runtime')
  return document
}

/** The download page reads this; it is generated from verified data at draft time and published as an asset. */
export function desktopReleaseJson({ version, channel, sourceCommit, installer, notes }) {
  return {
    schemaVersion: 1,
    channel,
    version,
    tag: desktopTag(version),
    sourceCommit,
    installer: { name: installer.name, bytes: installer.bytes, sha256: installer.sha256 },
    notes: notes.slice(0, 20).map(note => String(note).slice(0, 200)),
  }
}

export function validateDesktopReleaseJson(value, { version, channel, sourceCommit, installer }) {
  assert.deepEqual(Object.keys(value).sort(), ['schemaVersion', 'channel', 'version', 'tag', 'sourceCommit', 'installer', 'notes'].sort(), 'Unexpected release.json fields')
  assert.equal(value.schemaVersion, 1, 'Unsupported release.json schema')
  assert.equal(value.channel, channel, 'release.json channel mismatch')
  assert.equal(value.version, version, 'release.json version mismatch')
  assert.equal(value.tag, desktopTag(version), 'release.json tag mismatch')
  assert.equal(value.sourceCommit, sourceCommit, 'release.json commit mismatch')
  assert.deepEqual(value.installer, { name: installer.name, bytes: installer.bytes, sha256: installer.sha256 }, 'release.json installer mismatch')
  assert(Array.isArray(value.notes) && value.notes.length <= 20 && value.notes.every(note => typeof note === 'string' && note.length <= 200), 'Invalid release.json notes')
  return value
}

/**
 * The same checks the shell runs before installing (update-signature.ts), against the keys the released commit
 * ships: a signature from any other key would be rejected by every client, so it must not reach the feed.
 */
export function validateDesktopSignature(signed, { distribution, version, sourceCommit, installer }) {
  assert.deepEqual(Object.keys(signed ?? {}).sort(), ['keyId', 'signature', 'statement'], 'Unexpected signature fields')
  const key = distribution.update.publicKeys.find(candidate => candidate.keyId === signed.keyId)
  assert(key, `Signature key ${signed.keyId} is not trusted by this release's distribution`)
  assert(verifySigned(signed, key.publicKey), 'Desktop update signature is invalid')
  assert.deepEqual(signed.statement, {
    schema: STATEMENT_SCHEMA, channel: distribution.update.channel, version, file: installer.name,
    sha512: installer.sha512, size: installer.bytes, tag: desktopTag(version), commit: sourceCommit,
  }, 'Signed statement does not describe this installer')
  return signed
}

export function requireUpdateDistribution(distribution) {
  assert(distribution?.update, 'distribution/production.json has no update feed; the desktop channel needs a feed and signing keys')
  assert(/^https:\/\/[^/]+\/desktop\/[a-z][a-z0-9-]{0,15}\/$/u.test(distribution.update.feed), 'Production update feed must be https://<host>/desktop/<channel>/')
  assert(distribution.update.feed.endsWith(`/desktop/${distribution.update.channel}/`), 'Feed path must end with the channel')
  const keys = distribution.update.publicKeys
  assert(Array.isArray(keys) && keys.length >= 1 && keys.length <= 4, 'The distribution must trust 1–4 update keys')
  for (const key of keys) {
    assert(Buffer.from(String(key.publicKey), 'base64').byteLength === 32, 'Update keys must be raw Ed25519 public keys')
    assert.equal(key.keyId, keyIdFor(key.publicKey), 'Update key IDs must be derived from their public keys')
  }
  return distribution
}
