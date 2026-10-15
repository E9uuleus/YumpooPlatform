import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import {
  repository, ciWorkflowPath, releaseWorkflowPath, packageName, artifactFileNames, requiredJobs,
  validateTag, validateWorkflowContext, validateDevAncestry, validateRun, validateJobs, validateArtifact,
  validateProvenance, validatePackageMetadata, validateAssetDigest, validateArtifactEntries,
} from './policy.mjs'

const sourceCommit = 'a'.repeat(40)
const otherCommit = 'b'.repeat(40)
const tag = 'server-v1.0.0'
const digest = 'c'.repeat(64)
const workflowId = 12
const context = {
  GITHUB_REPOSITORY: repository, GITHUB_EVENT_NAME: 'release', GITHUB_REF: `refs/tags/${tag}`,
  GITHUB_WORKFLOW_REF: `${repository}/${releaseWorkflowPath}@refs/tags/${tag}`,
  GITHUB_SHA: sourceCommit, GITHUB_WORKFLOW_SHA: sourceCommit,
}
const ci = {
  id: 123, run_attempt: 2, workflow_id: workflowId, name: 'Platform CI', path: `${ciWorkflowPath}@refs/heads/dev`,
  repository: { full_name: repository }, head_repository: { full_name: repository },
  event: 'push', head_branch: 'dev', head_sha: sourceCommit, status: 'completed', conclusion: 'success',
}
const jobs = requiredJobs.map(name => ({ name, run_id: ci.id, run_attempt: ci.run_attempt, head_sha: sourceCommit, status: 'completed', conclusion: 'success' }))
const artifact = {
  id: 99, name: `m1-15-server-${sourceCommit}-2`, expired: false, digest: `sha256:${digest}`,
  workflow_run: { id: ci.id, head_branch: 'dev', head_sha: sourceCommit },
}
const provenance = {
  schemaVersion: 1, repository, tag, sourceCommit, ciRunId: ci.id, ciRunAttempt: ci.run_attempt,
  artifactName: artifact.name, packageSha256: digest, packageBytes: 42,
}

test('target tag, executing ref and workflow SHA must all agree', () => {
  validateWorkflowContext(context, tag, sourceCommit)
  validateWorkflowContext({ ...context, GITHUB_EVENT_NAME: 'workflow_dispatch' }, tag, sourceCommit)
  for (const tag of ['desktop-v1', 'server-v', 'server-v1;echo bad', 'server-v1\nnext', 'server-v../bad']) assert.throws(() => validateTag(tag))
  for (const changes of [
    { GITHUB_REF: 'refs/heads/dev' }, { GITHUB_WORKFLOW_REF: `${repository}/${releaseWorkflowPath}@refs/heads/dev` },
    { GITHUB_SHA: otherCommit }, { GITHUB_WORKFLOW_SHA: otherCommit }, { GITHUB_EVENT_NAME: 'pull_request' },
    { GITHUB_REPOSITORY: 'attacker/fork' },
  ]) assert.throws(() => validateWorkflowContext({ ...context, ...changes }, tag, sourceCommit))
})

test('dev ancestry rejects divergent/ahead-release commits and mismatched merge bases', () => {
  for (const status of ['ahead', 'identical']) validateDevAncestry({ status, merge_base_commit: { sha: sourceCommit } }, sourceCommit)
  for (const status of ['behind', 'diverged']) assert.throws(() => validateDevAncestry({ status, merge_base_commit: { sha: sourceCommit } }, sourceCommit))
  assert.throws(() => validateDevAncestry({ status: 'ahead', merge_base_commit: { sha: otherCommit } }, sourceCommit))
})

test('CI eligibility cannot be satisfied by PR, dispatch, wrong branch/SHA/workflow or a failed attempt', () => {
  validateRun(ci, { workflowId, sourceCommit, runId: 123, attempt: 2 })
  for (const change of [
    { event: 'pull_request' }, { event: 'workflow_dispatch' }, { head_branch: 'feature/test' }, { head_sha: otherCommit },
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
  ]) assert.throws(() => validateArtifact({ ...artifact, ...change }, ci))
})

test('provenance has a fixed schema and cannot redirect source or CI attempt', () => {
  validateProvenance(provenance, { tag, sourceCommit })
  for (const change of [
    { schemaVersion: 2 }, { repository: 'attacker/fork' }, { tag: 'server-v2' }, { sourceCommit: otherCommit },
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

test('workflow serializes all server releases without cancelling the active deployment', () => {
  const workflow = fs.readFileSync(new URL('../../.github/workflows/windows-release.yml', import.meta.url), 'utf8')
  assert.match(workflow, /concurrency:\s+group: windows-server-release-wecom-dev\s+cancel-in-progress: false/u)
  assert.match(workflow, /release:\s+types: \[published\]/u)
  assert.match(workflow, /environment:\s+name: wecom-dev/u)
  assert.match(workflow, /needs: \[promote\]/u)
  const deployJob = workflow.split('\n  deploy:')[1]
  assert.match(deployJob, /permissions:\s+actions: read\s+contents: read/u)
  assert(!deployJob.includes('contents: write'))
  assert(!workflow.includes('pull_request_target'))
  assert(!workflow.includes('GITHUB_TOKEN:'))
  assert(!workflow.includes('ssh-keyscan'))
  for (const line of workflow.split('\n').filter(line => line.includes('run:'))) assert(!line.includes('${{'), 'No event input may be interpolated in shell commands')
  for (const line of workflow.split('\n').filter(line => line.includes('uses:'))) assert.match(line, /@[0-9a-f]{40}\b/u)
})
