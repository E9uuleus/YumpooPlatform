import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { releaseBranch, validatePublishedRelease } from './policy.mjs'
import {
  compareVersions, desktopArtifactFileNames, desktopManifestName, desktopReleaseAssetNames, desktopSbomName, expectedDesktopArtifactName,
  installerName, requireUpdateDistribution, signatureName, validateDesktopArtifactEntries, validateDesktopManifest, validateDesktopProvenance,
  validateDesktopReleaseJson, validateDesktopSbom, validateDesktopSignature, validateDesktopTag, validateDesktopVersion,
  validateDesktopWorkflowContext, validateLatestYml,
} from './desktop-policy.mjs'
import { createGithub, readJson, unpackArtifact } from './github.mjs'
import { verifyBuildAttestations } from './attestation.mjs'
import { assertOnReleaseBranch, ciWorkflowId, downloadAsset, indexAssets, resolveTagCommit, verifyCiAttempt } from './promote-release.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')

/** The desktop version and distribution of the checked-out commit, which is always the commit being released. */
export function readDesktopCheckout(root = repositoryRoot) {
  const desktop = path.join(root, 'desktop', 'desktop-shell')
  return {
    version: validateDesktopVersion(readJson(path.join(desktop, 'package.json')).version),
    distribution: readJson(path.join(desktop, 'distribution', 'production.json')),
  }
}

export async function desktopReleases(github) {
  return (await github.list('/releases?per_page=100')).filter(release => {
    try { validateDesktopTag(release.tag_name); return true } catch { return false }
  })
}

export function latestPublished(releases) {
  return releases.filter(release => !release.draft && release.published_at)
    .sort((left, right) => compareVersions(validateDesktopTag(right.tag_name), validateDesktopTag(left.tag_name)))[0]
}

/** sha256 (hex), sha512 (base64) and size in one streaming pass; installers are around 100 MB. */
export async function describeFile(file) {
  assert(fs.lstatSync(file).isFile(), `Expected a regular file: ${path.basename(file)}`)
  const sha256 = createHash('sha256')
  const sha512 = createHash('sha512')
  let bytes = 0
  for await (const chunk of fs.createReadStream(file)) {
    sha256.update(chunk)
    sha512.update(chunk)
    bytes += chunk.length
  }
  return { bytes, sha256: sha256.digest('hex'), sha512: sha512.digest('base64') }
}

export async function downloadDesktopCiPackage(github, run, directory, version, unpack = unpackArtifact) {
  const name = expectedDesktopArtifactName(run.head_sha, run.run_attempt)
  const artifacts = (await github.list(`/actions/runs/${run.id}/artifacts?per_page=100`, 'artifacts')).filter(item => item.name === name)
  assert.equal(artifacts.length, 1, 'Expected exactly one desktop artifact for the selected CI attempt')
  const [artifact] = artifacts
  assert(Number.isSafeInteger(artifact.id) && artifact.id > 0, 'Invalid artifact ID')
  assert.equal(artifact.expired, false, 'Desktop CI artifact expired; rerun the full Platform CI on this commit')
  assert.equal(artifact.workflow_run?.id, run.id, 'Artifact belongs to another run')
  assert.equal(artifact.workflow_run?.head_branch, releaseBranch, `Artifact is not from ${releaseBranch}`)
  assert.equal(artifact.workflow_run?.head_sha, run.head_sha, 'Artifact commit mismatch')
  assert(/^sha256:[0-9a-f]{64}$/u.test(artifact.digest), 'Missing GitHub artifact digest')
  const archive = path.join(directory, 'desktop-artifact.zip')
  await github.download(`/actions/artifacts/${artifact.id}/zip`, archive)
  assert.equal(`sha256:${(await describeFile(archive)).sha256}`, artifact.digest, 'GitHub CI artifact archive digest mismatch')
  const packageDirectory = path.join(directory, 'desktop-ci')
  await unpack(archive, packageDirectory, entries => validateDesktopArtifactEntries(entries, version))
  return packageDirectory
}

/** Every CI file must match the manifest, latest.yml must describe the installer and the SBOM must list Electron. */
export async function inspectDesktopPackage(directory, { sourceCommit, version, distribution }) {
  const files = new Map()
  for (const name of desktopArtifactFileNames(version).filter(name => name !== desktopManifestName)) {
    files.set(name, await describeFile(path.join(directory, name)))
  }
  const manifest = validateDesktopManifest(readJson(path.join(directory, desktopManifestName)), { sourceCommit, version, distribution, files })
  const installer = { name: installerName(version), ...files.get(installerName(version)) }
  validateLatestYml(fs.readFileSync(path.join(directory, 'latest.yml'), 'utf8'), { version, installer })
  validateDesktopSbom(readJson(path.join(directory, desktopSbomName)), { version, electronVersion: manifest.electronVersion })
  return { manifest, installer }
}

// Every check a published desktop Release must pass before its files may reach the feed.
export async function verifyDesktopReleaseAssets({ github, release, tag, sourceCommit, checkout, env, directory, attest = verifyBuildAttestations, log = console.log }) {
  const version = validateDesktopTag(tag)
  assert.equal(checkout.version, version, 'The released commit declares another desktop version')
  const distribution = requireUpdateDistribution(checkout.distribution)
  const names = desktopReleaseAssetNames(version)
  const assets = indexAssets(await github.list(`/releases/${release.id}/assets?per_page=100`), names)
  assert(names.every(name => assets.has(name)), 'Desktop Release assets are incomplete; publish only drafts prepared and signed by Prepare Desktop Release')
  const retained = path.join(directory, 'desktop-release')
  fs.mkdirSync(retained, { recursive: true })
  const provenance = validateDesktopProvenance(readJson(await downloadAsset(github, assets.get('provenance.json'), retained)), { tag, sourceCommit })
  const run = await verifyCiAttempt(github, await ciWorkflowId(github), sourceCommit, provenance.ciRunId, provenance.ciRunAttempt)
  for (const name of names.filter(name => name !== 'provenance.json')) await downloadAsset(github, assets.get(name), retained)
  const { installer } = await inspectDesktopPackage(retained, { sourceCommit, version, distribution })
  assert.equal(installer.sha256, provenance.installerSha256, 'Installer digest differs from provenance')
  assert.equal(installer.bytes, provenance.installerBytes, 'Installer size differs from provenance')
  await attest(retained, run, { env, log, label: 'published desktop Release assets', files: desktopArtifactFileNames(version) })
  validateDesktopReleaseJson(readJson(path.join(retained, 'release.json')), { version, channel: distribution.update.channel, sourceCommit, installer })
  const signed = validateDesktopSignature(readJson(path.join(retained, signatureName(version))), { distribution, version, sourceCommit, installer })
  log(`Desktop update authorization verified: ${installer.name} signed by ${signed.keyId}`)
  return { directory: retained, provenance, version, installer }
}

// Publishing never uploads: the immutable published Release must already carry every asset its draft received.
export async function prepareDesktopRelease({ github, tag, env, directory, checkout = readDesktopCheckout(), attest = verifyBuildAttestations, log = console.log }) {
  validateDesktopTag(tag)
  const sourceCommit = await resolveTagCommit(github, tag, validateDesktopTag)
  validateDesktopWorkflowContext(env, tag, sourceCommit)
  await assertOnReleaseBranch(github, sourceCommit)
  const release = validatePublishedRelease(await github.get(`/releases/tags/${encodeURIComponent(tag)}`), tag)
  if (env.GITHUB_EVENT_NAME === 'release') {
    const event = readJson(env.GITHUB_EVENT_PATH)
    assert.equal(event.action, 'published', 'Only release.published may publish to the feed')
    assert.equal(event.release?.id, release.id, 'Release event refers to another Release')
  }
  const verified = await verifyDesktopReleaseAssets({ github, release, tag, sourceCommit, checkout, env, directory, attest, log })
  assert.equal(await resolveTagCommit(github, tag, validateDesktopTag), sourceCommit, 'Release tag changed during verification')
  assert.equal((await github.get(`/releases/tags/${encodeURIComponent(tag)}`)).id, release.id, 'Release was replaced during verification')
  return { ...verified, sourceCommit }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-desktop-release-'))
  try {
    const { provenance, installer } = await prepareDesktopRelease({ github: createGithub(), tag: process.env.RELEASE_TAG, env: process.env, directory })
    console.log(`Verified ${provenance.tag}: ${provenance.sourceCommit}, CI ${provenance.ciRunId}/${provenance.ciRunAttempt}, ${installer.name} SHA-256 ${installer.sha256}`)
  } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  } finally {
    fs.rmSync(directory, { recursive: true, force: true })
  }
}
