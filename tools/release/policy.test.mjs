import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import {
  repository, ciWorkflowPath, releaseWorkflowPath, rollbackWorkflowPath, packageName, artifactFileNames, requiredJobs,
  validateTag, validateWorkflowContext, validateBranchAncestry, validateRun, validateJobs, validateArtifact,
  validatePublishedRelease, nextReleaseTag, releaseDate,
  validateProvenance, validatePackageMetadata, validateAssetDigest, validateArtifactEntries,
} from './policy.mjs'

const sourceCommit = 'a'.repeat(40)
const otherCommit = 'b'.repeat(40)
const tag = 'server-v2026.10.20.1'
const digest = 'c'.repeat(64)
const workflowId = 12
const context = {
  GITHUB_REPOSITORY: repository, GITHUB_EVENT_NAME: 'release', GITHUB_REF: `refs/tags/${tag}`,
  GITHUB_WORKFLOW_REF: `${repository}/${releaseWorkflowPath}@refs/tags/${tag}`,
  GITHUB_SHA: sourceCommit, GITHUB_WORKFLOW_SHA: sourceCommit,
}
const ci = {
  id: 123, run_attempt: 2, workflow_id: workflowId, name: 'Platform CI', path: `${ciWorkflowPath}@refs/heads/main`,
  repository: { full_name: repository }, head_repository: { full_name: repository },
  event: 'push', head_branch: 'main', head_sha: sourceCommit, status: 'completed', conclusion: 'success',
}
const jobs = requiredJobs.map(name => ({ name, run_id: ci.id, run_attempt: ci.run_attempt, head_sha: sourceCommit, status: 'completed', conclusion: 'success' }))
const artifact = {
  id: 99, name: `m1-15-server-${sourceCommit}-2`, expired: false, digest: `sha256:${digest}`,
  workflow_run: { id: ci.id, head_branch: 'main', head_sha: sourceCommit },
}
const provenance = {
  schemaVersion: 1, repository, tag, sourceCommit, ciRunId: ci.id, ciRunAttempt: ci.run_attempt,
  artifactName: artifact.name, packageSha256: digest, packageBytes: 42,
}

test('target tag, executing ref and workflow SHA must all agree', () => {
  validateWorkflowContext(context, tag, sourceCommit)
  validateWorkflowContext({ ...context, GITHUB_EVENT_NAME: 'workflow_dispatch' }, tag, sourceCommit)
  validateWorkflowContext({ ...context, GITHUB_EVENT_NAME: 'workflow_dispatch', GITHUB_WORKFLOW_REF: `${repository}/${rollbackWorkflowPath}@refs/tags/${tag}` }, tag, sourceCommit, rollbackWorkflowPath)
  assert.throws(() => validateWorkflowContext(context, tag, sourceCommit, '.github/workflows/other.yml'))
  for (const tag of ['desktop-v1', 'server-v', 'server-v1.0.0', 'server-v2026.10.20.0', 'server-v2026.10.20.01', 'server-v2026.13.01.1',
    'server-v2026.02.30.1', 'server-v2026.10.20.1000', 'server-v2026.10.20.1;echo bad', 'server-v2026.10.20.1\nnext']) assert.throws(() => validateTag(tag))
  for (const changes of [
    { GITHUB_REF: 'refs/heads/dev' }, { GITHUB_WORKFLOW_REF: `${repository}/${releaseWorkflowPath}@refs/heads/dev` },
    { GITHUB_SHA: otherCommit }, { GITHUB_WORKFLOW_SHA: otherCommit }, { GITHUB_EVENT_NAME: 'pull_request' },
    { GITHUB_REPOSITORY: 'attacker/fork' },
  ]) assert.throws(() => validateWorkflowContext({ ...context, ...changes }, tag, sourceCommit))
})

test('main ancestry rejects divergent/ahead-release commits and mismatched merge bases', () => {
  for (const status of ['ahead', 'identical']) validateBranchAncestry({ status, merge_base_commit: { sha: sourceCommit } }, sourceCommit)
  for (const status of ['behind', 'diverged']) assert.throws(() => validateBranchAncestry({ status, merge_base_commit: { sha: sourceCommit } }, sourceCommit))
  assert.throws(() => validateBranchAncestry({ status: 'ahead', merge_base_commit: { sha: otherCommit } }, sourceCommit))
})

test('release numbering follows the Beijing date and never reuses an existing number', () => {
  assert.equal(releaseDate(new Date('2026-10-19T15:59:59Z')), '2026.10.19')
  assert.equal(releaseDate(new Date('2026-10-19T16:00:00Z')), '2026.10.20')
  const now = new Date('2026-10-20T02:00:00Z')
  assert.equal(nextReleaseTag([], now), 'server-v2026.10.20.1')
  assert.equal(nextReleaseTag(['server-v2026.10.20.1', 'server-v2026.10.20.7', 'server-v2026.10.19.9', 'server-vlegacy', 'desktop-v2026.10.20.30'], now), 'server-v2026.10.20.8')
})

test('only published immutable Releases of the exact tag are deployable', () => {
  const release = { id: 7, tag_name: tag, draft: false, published_at: '2026-10-20T00:00:00Z', immutable: true }
  validatePublishedRelease(release, tag)
  for (const change of [{ draft: true }, { published_at: null }, { immutable: false }, { immutable: undefined }, { tag_name: 'server-v2026.10.20.2' }]) {
    assert.throws(() => validatePublishedRelease({ ...release, ...change }, tag))
  }
})

test('CI eligibility cannot be satisfied by PR, dispatch, wrong branch/SHA/workflow or a failed attempt', () => {
  validateRun(ci, { workflowId, sourceCommit, runId: 123, attempt: 2 })
  for (const change of [
    { event: 'pull_request' }, { event: 'workflow_dispatch' }, { head_branch: 'feature/test' }, { head_branch: 'dev' }, { head_sha: otherCommit },
    { workflow_id: 13 }, { path: '.github/workflows/other.yml' }, { name: 'Other CI' },
    { status: 'in_progress' }, { conclusion: 'cancelled' }, { conclusion: 'failure' },
    { run_attempt: 1 }, { id: 124 }, { repository: { full_name: 'other/repository' } },
    { head_repository: { full_name: 'attacker/fork' } },
  ]) assert.throws(() => validateRun({ ...ci, ...change }, { workflowId, sourceCommit, runId: 123, attempt: 2 }))
})

test('every required job must belong to the same successful attempt', () => {
  validateJobs(jobs, ci)
  assert.throws(() => validateJobs(jobs.slice(0, 2), ci))
  assert.throws(() => validateJobs([...jobs, jobs[0]], ci))
  for (const change of [
    { conclusion: 'failure' }, { conclusion: 'skipped' }, { conclusion: 'cancelled' },
    { status: 'in_progress' }, { run_attempt: 1 }, { head_sha: otherCommit }, { run_id: 124 },
  ]) assert.throws(() => validateJobs([{ ...jobs[0], ...change }, ...jobs.slice(1)], ci))
})

test('artifact identity binds exact run, attempt, branch, SHA and archive digest', () => {
  validateArtifact(artifact, ci)
  for (const change of [
    { expired: true }, { name: `m1-15-server-${sourceCommit}-1` }, { digest: null },
    { workflow_run: { ...artifact.workflow_run, id: 124 } },
    { workflow_run: { ...artifact.workflow_run, head_sha: otherCommit } },
    { workflow_run: { ...artifact.workflow_run, head_branch: 'feature/test' } },
    { workflow_run: { ...artifact.workflow_run, head_branch: 'dev' } },
  ]) assert.throws(() => validateArtifact({ ...artifact, ...change }, ci))
})

test('provenance has a fixed schema and cannot redirect source or CI attempt', () => {
  validateProvenance(provenance, { tag, sourceCommit })
  for (const change of [
    { schemaVersion: 2 }, { repository: 'attacker/fork' }, { tag: 'server-v2026.10.20.2' }, { sourceCommit: otherCommit },
    { ciRunId: '123' }, { ciRunAttempt: 3 }, { packageBytes: 0 }, { packageSha256: 'wrong' }, { script: 'remote.ps1' },
  ]) assert.throws(() => validateProvenance({ ...provenance, ...change }, { tag, sourceCommit }))
})

test('package bytes must agree with manifest, checksum and provenance', () => {
  const manifest = {
    schemaVersion: 1, milestone: 'M1-15', sourceCommit,
    server: { platform: 'windows', architecture: 'x64', runtimeMode: 'WINDOWS_SERVICE_WINSW' },
    artifacts: [{ name: packageName, bytes: 42, sha256: digest }],
  }
  const input = { manifest, checksum: `${digest}  ${packageName}\n`, digest, bytes: 42, sourceCommit, provenance }
  validatePackageMetadata(input)
  for (const changes of [
    { digest: 'e'.repeat(64) }, { bytes: 43 }, { checksum: `${digest}  other.zip` },
    { manifest: { ...manifest, sourceCommit: otherCommit } },
    { manifest: { ...manifest, server: { ...manifest.server, runtimeMode: 'MANUAL_JAVA_CONSOLE' } } },
    { provenance: { ...provenance, packageBytes: 43 } },
  ]) assert.throws(() => validatePackageMetadata({ ...input, ...changes }))
  validateAssetDigest({ state: 'uploaded', size: 42, digest: `sha256:${digest}` }, digest, 42)
  assert.throws(() => validateAssetDigest({ state: 'uploaded', size: 42, digest: `sha256:${'e'.repeat(64)}` }, digest, 42))
})

test('CI archive allows only expected files, with no duplicate or traversal entry', () => {
  validateArtifactEntries(artifactFileNames)
  for (const entries of [
    [...artifactFileNames, '../evil.ps1'], [...artifactFileNames, artifactFileNames[0]], artifactFileNames.slice(1),
    [...artifactFileNames.slice(0, -1), 'nested/verification-report.json'],
  ]) assert.throws(() => validateArtifactEntries(entries))
})
