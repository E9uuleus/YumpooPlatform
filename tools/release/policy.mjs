import assert from 'node:assert/strict'

export const repository = 'E9uuleus/YumpooPlatform'
export const ciWorkflowPath = '.github/workflows/m0-18-ci.yml'
export const releaseWorkflowPath = '.github/workflows/windows-release.yml'
export const packageName = 'yumpoo-windows-server-m1-15.zip'
export const releaseAssetNames = [packageName, `${packageName}.sha256`, 'artifact-manifest.json', 'provenance.json']
export const artifactFileNames = [packageName, `${packageName}.sha256`, 'artifact-manifest.json', 'verification-report.json']
export const requiredJobs = ['M0 Portable Gate', 'Windows Delivery', 'M0 Windows x64 Gate']
const commitPattern = /^[0-9a-f]{40}$/u
const digestPattern = /^[0-9a-f]{64}$/u

export function validateTag(tag) {
  assert(typeof tag === 'string' && /^server-v[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/u.test(tag), 'Invalid server release tag')
  return tag
}

export function validateWorkflowContext(env, tag, sourceCommit) {
  validateTag(tag)
  assert(commitPattern.test(sourceCommit), 'Invalid source commit')
  assert.equal(env.GITHUB_REPOSITORY, repository, 'Unexpected repository')
  assert(['release', 'workflow_dispatch'].includes(env.GITHUB_EVENT_NAME), 'Unsupported deployment trigger')
  assert.equal(env.GITHUB_REF, `refs/tags/${tag}`, 'Run this workflow on the target release tag')
  assert.equal(env.GITHUB_WORKFLOW_REF, `${repository}/${releaseWorkflowPath}@refs/tags/${tag}`, 'Workflow ref must be the target tag')
  assert.equal(env.GITHUB_SHA, sourceCommit, 'The release tag moved or does not match the workflow commit')
  assert.equal(env.GITHUB_WORKFLOW_SHA, sourceCommit, 'Workflow code must come from the target commit')
}

export function validateDevAncestry(comparison, sourceCommit) {
  assert(['ahead', 'identical'].includes(comparison.status), 'Release commit is not an ancestor of dev')
  assert.equal(comparison.merge_base_commit?.sha, sourceCommit, 'Release commit is outside dev history')
}

export function validateRun(run, { workflowId, sourceCommit, runId = run.id, attempt = run.run_attempt }) {
  assert(Number.isSafeInteger(runId) && runId > 0, 'Invalid CI run ID')
  assert(Number.isSafeInteger(attempt) && attempt > 0, 'Invalid CI attempt')
  assert.equal(run.id, runId, 'Wrong CI run')
  assert.equal(run.run_attempt, attempt, 'Wrong CI attempt')
  assert.equal(run.workflow_id, workflowId, 'Wrong CI workflow ID')
  assert.equal(run.name, 'Platform CI', 'Wrong CI workflow name')
  assert.equal(run.path?.split('@')[0], ciWorkflowPath, 'Wrong CI workflow path')
  assert.equal(run.repository?.full_name, repository, 'Wrong CI repository')
  assert.equal(run.head_repository?.full_name, repository, 'Fork CI artifacts are not deployable')
  assert.equal(run.event, 'push', 'Only push CI artifacts are deployable')
  assert.equal(run.head_branch, 'dev', 'Only dev CI artifacts are deployable')
  assert.equal(run.head_sha, sourceCommit, 'CI commit differs from release tag')
  assert.equal(run.status, 'completed', 'CI attempt is incomplete')
  assert.equal(run.conclusion, 'success', 'CI attempt did not succeed')
  return run
}

export function validateJobs(jobs, run) {
  for (const name of requiredJobs) {
    const matches = jobs.filter(job => job.name === name)
    assert.equal(matches.length, 1, `Expected exactly one required job: ${name}`)
    const job = matches[0]
    assert.equal(job.run_id, run.id, `Wrong CI run for ${name}`)
    assert.equal(job.run_attempt, run.run_attempt, `Wrong CI attempt for ${name}`)
    assert.equal(job.head_sha, run.head_sha, `Wrong source commit for ${name}`)
    assert.equal(job.status, 'completed', `Incomplete required job: ${name}`)
    assert.equal(job.conclusion, 'success', `Required job did not succeed: ${name}`)
  }
}

export function expectedArtifactName(sourceCommit, attempt) {
  return `m1-15-server-${sourceCommit}-${attempt}`
}

export function validateArtifact(artifact, run) {
  assert(Number.isSafeInteger(artifact.id) && artifact.id > 0, 'Invalid artifact ID')
  assert.equal(artifact.name, expectedArtifactName(run.head_sha, run.run_attempt), 'Wrong CI artifact name/attempt')
  assert.equal(artifact.expired, false, 'CI artifact expired; use a previously retained Release or rerun the full CI')
  assert.equal(artifact.workflow_run?.id, run.id, 'Artifact belongs to another run')
  assert.equal(artifact.workflow_run?.head_branch, 'dev', 'Artifact is not from dev')
  assert.equal(artifact.workflow_run?.head_sha, run.head_sha, 'Artifact commit mismatch')
  assert(/^sha256:[0-9a-f]{64}$/u.test(artifact.digest), 'Missing GitHub artifact digest')
  return artifact
}

export function validateProvenance(value, { tag, sourceCommit }) {
  assert.deepEqual(Object.keys(value).sort(), [
    'schemaVersion', 'repository', 'tag', 'sourceCommit', 'ciRunId', 'ciRunAttempt', 'artifactName', 'packageSha256', 'packageBytes',
  ].sort(), 'Unexpected provenance fields')
  assert.equal(value.schemaVersion, 1, 'Unsupported provenance schema')
  assert.equal(value.repository, repository, 'Wrong provenance repository')
  assert.equal(value.tag, tag, 'Provenance tag mismatch')
  assert.equal(value.sourceCommit, sourceCommit, 'Provenance commit mismatch')
  assert(Number.isSafeInteger(value.ciRunId) && value.ciRunId > 0, 'Invalid provenance CI run')
  assert(Number.isSafeInteger(value.ciRunAttempt) && value.ciRunAttempt > 0, 'Invalid provenance CI attempt')
  assert.equal(value.artifactName, expectedArtifactName(sourceCommit, value.ciRunAttempt), 'Provenance artifact mismatch')
  assert(digestPattern.test(value.packageSha256), 'Invalid package digest')
  assert(Number.isSafeInteger(value.packageBytes) && value.packageBytes > 0, 'Invalid package size')
  return value
}

export function validatePackageMetadata({ manifest, checksum, digest, bytes, sourceCommit, provenance }) {
  assert.equal(manifest.schemaVersion, 1, 'Unsupported package manifest')
  assert.equal(manifest.milestone, 'M1-15', 'Wrong package milestone')
  assert.equal(manifest.sourceCommit, sourceCommit, 'Package manifest commit mismatch')
  assert.equal(manifest.server?.platform, 'windows', 'Wrong package platform')
  assert.equal(manifest.server?.architecture, 'x64', 'Wrong package architecture')
  assert.equal(manifest.server?.runtimeMode, 'WINDOWS_SERVICE_WINSW', 'Package must support the WinSW service runtime')
  assert.equal(manifest.artifacts?.length, 1, 'Expected exactly one server package')
  assert.equal(manifest.artifacts[0].name, packageName, 'Wrong package filename')
  assert.equal(manifest.artifacts[0].sha256, digest, 'Package manifest digest mismatch')
  assert.equal(manifest.artifacts[0].bytes, bytes, 'Package manifest size mismatch')
  assert.equal(checksum.trim(), `${digest}  ${packageName}`, 'Package checksum file mismatch')
  if (provenance) {
    assert.equal(provenance.packageSha256, digest, 'Package provenance digest mismatch')
    assert.equal(provenance.packageBytes, bytes, 'Package provenance size mismatch')
  }
}

export function validateAssetDigest(asset, digest, bytes) {
  assert.equal(asset.state, 'uploaded', `Release asset is incomplete: ${asset.name}`)
  assert.equal(asset.size, bytes, `Release asset size mismatch: ${asset.name}`)
  assert.equal(asset.digest, `sha256:${digest}`, `Release asset digest mismatch: ${asset.name}`)
}

export function validateArtifactEntries(entries) {
  assert.deepEqual([...entries].sort(), [...artifactFileNames].sort(), 'CI artifact has unexpected files or duplicate entries')
}

export function validateStatus(status, request) {
  assert.equal(status.schemaVersion, 1, 'Unsupported deployment status')
  for (const key of ['requestId', 'tag', 'sourceCommit']) assert.equal(status[key], request[key], `Deployment status ${key} mismatch`)
  assert(['running', 'succeeded', 'failed', 'rolled_back', 'recovery_required'].includes(status.state), 'Unknown deployment state')
  assert(typeof status.phase === 'string' && /^[A-Za-z0-9_.-]{1,100}$/u.test(status.phase), 'Invalid deployment phase')
  assert(typeof status.updatedAt === 'string' && Number.isFinite(Date.parse(status.updatedAt)), 'Invalid deployment status timestamp')
  return status
}
