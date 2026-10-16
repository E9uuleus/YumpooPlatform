import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import {
  releaseBranch, ciWorkflowPath, packageName, releaseAssetNames, releaseWorkflowPath, expectedArtifactName,
  validateTag, validateWorkflowContext, validateBranchAncestry, validateRun, validateJobs,
  validateArtifact, validateProvenance, validatePackageMetadata, validateAssetDigest, validatePublishedRelease,
} from './policy.mjs'
import { createGithub, fileDigest, readJson, unpackArtifact } from './github.mjs'

export async function resolveTagCommit(github, tag) {
  validateTag(tag)
  let object = (await github.get(`/git/ref/tags/${encodeURIComponent(tag)}`)).object
  for (let depth = 0; object?.type === 'tag' && depth < 8; depth += 1) {
    assert(/^[0-9a-f]{40}$/u.test(object.sha), 'Invalid annotated tag object')
    object = (await github.get(`/git/tags/${object.sha}`)).object
  }
  assert.equal(object?.type, 'commit', 'Release tag must resolve to a commit')
  return object.sha
}

export async function assertOnReleaseBranch(github, sourceCommit) {
  validateBranchAncestry(await github.get(`/compare/${sourceCommit}...${releaseBranch}?per_page=1`), sourceCommit)
}

export async function resolveSource(github, tag, env, workflowPath = releaseWorkflowPath) {
  const sourceCommit = await resolveTagCommit(github, tag)
  validateWorkflowContext(env, tag, sourceCommit, workflowPath)
  await assertOnReleaseBranch(github, sourceCommit)
  return sourceCommit
}

export async function ciWorkflowId(github) {
  const workflow = await github.get(`/actions/workflows/${path.posix.basename(ciWorkflowPath)}`)
  assert.equal(workflow.path, ciWorkflowPath, 'Unexpected CI workflow path')
  assert(Number.isSafeInteger(workflow.id) && workflow.id > 0, 'Invalid CI workflow ID')
  return workflow.id
}

export async function verifyCiAttempt(github, workflowId, sourceCommit, runId, attempt) {
  const run = validateRun(await github.get(`/actions/runs/${runId}/attempts/${attempt}`), {
    workflowId, sourceCommit, runId, attempt,
  })
  const jobs = await github.list(`/actions/runs/${runId}/attempts/${attempt}/jobs?per_page=100`, 'jobs')
  validateJobs(jobs, run)
  return run
}

export async function selectCiAttempt(github, workflowId, sourceCommit) {
  const runs = await github.list(`/actions/workflows/${workflowId}/runs?event=push&branch=${releaseBranch}&head_sha=${sourceCommit}&status=success&per_page=100`, 'workflow_runs')
  const candidates = runs.filter(run => {
    try { validateRun(run, { workflowId, sourceCommit }); return true } catch { return false }
  }).sort((left, right) => right.id - left.id || right.run_attempt - left.run_attempt)
  for (const candidate of candidates) {
    try {
      return await verifyCiAttempt(github, workflowId, sourceCommit, candidate.id, candidate.run_attempt)
    } catch (error) {
      console.warn(`CI run ${candidate.id} attempt ${candidate.run_attempt} is not eligible: ${error.message}`)
    }
  }
  throw new Error(`No successful push/${releaseBranch} Platform CI attempt with all mandatory jobs for this exact commit; rerun the full Platform CI first`)
}

export function indexAssets(assets) {
  const result = new Map()
  for (const asset of assets.filter(asset => releaseAssetNames.includes(asset.name))) {
    assert(!result.has(asset.name), `Duplicate Release asset: ${asset.name}`)
    assert(Number.isSafeInteger(asset.id) && asset.id > 0, 'Invalid Release asset ID')
    result.set(asset.name, asset)
  }
  return result
}

export async function downloadAsset(github, asset, directory) {
  const destination = path.join(directory, asset.name)
  await github.download(`/releases/assets/${asset.id}`, destination)
  validateAssetDigest(asset, await fileDigest(destination), fs.statSync(destination).size)
  return destination
}

export async function downloadCiPackage(github, run, directory, unpack = unpackArtifact) {
  const name = expectedArtifactName(run.head_sha, run.run_attempt)
  const artifacts = (await github.list(`/actions/runs/${run.id}/artifacts?per_page=100`, 'artifacts')).filter(item => item.name === name)
  assert.equal(artifacts.length, 1, 'Expected exactly one package artifact for the selected CI attempt')
  const artifact = validateArtifact(artifacts[0], run)
  const archive = path.join(directory, 'ci-artifact.zip')
  await github.download(`/actions/artifacts/${artifact.id}/zip`, archive)
  assert.equal(`sha256:${await fileDigest(archive)}`, artifact.digest, 'GitHub CI artifact archive digest mismatch')
  const packageDirectory = path.join(directory, 'ci-package')
  await unpack(archive, packageDirectory)
  const report = readJson(path.join(packageDirectory, 'verification-report.json'))
  assert.equal(report.schemaVersion, 1, 'Invalid CI package report')
  assert.equal(report.sourceCommit, run.head_sha, 'CI package report commit mismatch')
  assert.equal(report.packageGate, 'PASS', 'CI package verification did not pass')
  assert.equal(report.localGate, 'PASS', 'CI local verification did not pass')
  return packageDirectory
}

export async function inspectPackage(directory, sourceCommit, provenance) {
  const file = path.join(directory, packageName)
  assert(fs.lstatSync(file).isFile(), 'Server package must be a regular file')
  const digest = await fileDigest(file)
  const bytes = fs.statSync(file).size
  validatePackageMetadata({
    manifest: readJson(path.join(directory, 'artifact-manifest.json')),
    checksum: fs.readFileSync(path.join(directory, `${packageName}.sha256`), 'utf8'),
    digest, bytes, sourceCommit, provenance,
  })
  return { digest, bytes }
}

// Deployment never uploads: the immutable published Release must already carry every asset attached to its draft.
export async function prepareRelease({ github, tag, env, directory, workflowPath = releaseWorkflowPath }) {
  const sourceCommit = await resolveSource(github, tag, env, workflowPath)
  const release = validatePublishedRelease(await github.get(`/releases/tags/${encodeURIComponent(tag)}`), tag)
  if (env.GITHUB_EVENT_NAME === 'release') {
    const event = readJson(env.GITHUB_EVENT_PATH)
    assert.equal(event.action, 'published', 'Only release.published may deploy')
    assert.equal(event.release?.id, release.id, 'Release event refers to another Release')
  }
  const workflowId = await ciWorkflowId(github)
  const assets = indexAssets(await github.list(`/releases/${release.id}/assets?per_page=100`))
  assert(releaseAssetNames.every(name => assets.has(name)), 'Release assets are incomplete; publish only drafts prepared by the Prepare Release workflow')
  const retained = path.join(directory, 'release')
  fs.mkdirSync(retained, { recursive: true })
  const provenance = validateProvenance(readJson(await downloadAsset(github, assets.get('provenance.json'), retained)), { tag, sourceCommit })
  await verifyCiAttempt(github, workflowId, sourceCommit, provenance.ciRunId, provenance.ciRunAttempt)
  for (const name of releaseAssetNames.filter(name => name !== 'provenance.json')) {
    await downloadAsset(github, assets.get(name), retained)
  }
  await inspectPackage(retained, sourceCommit, provenance)
  // Fail before SSH if the tag or Release changed during the downloads.
  assert.equal(await resolveTagCommit(github, tag), sourceCommit, 'Release tag changed during verification')
  assert.equal((await github.get(`/releases/tags/${encodeURIComponent(tag)}`)).id, release.id, 'Release was replaced during verification')
  return { directory: retained, provenance }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-release-'))
  try {
    const { provenance } = await prepareRelease({ github: createGithub(), tag: process.env.RELEASE_TAG, env: process.env, directory })
    console.log(`Verified ${provenance.tag}: ${provenance.sourceCommit}, CI ${provenance.ciRunId}/${provenance.ciRunAttempt}, SHA-256 ${provenance.packageSha256}`)
  } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  } finally {
    fs.rmSync(directory, { recursive: true, force: true })
  }
}
