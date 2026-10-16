import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { prepareRelease, resolveSource } from './promote-release.mjs'
import { repository, ciWorkflowPath, releaseWorkflowPath, rollbackWorkflowPath, packageName, requiredJobs } from './policy.mjs'

const sha = 'a'.repeat(40)
const tag = 'server-v2026.10.20.1'
const digest = value => createHash('sha256').update(value).digest('hex')
const env = {
  GITHUB_REPOSITORY: repository, GITHUB_EVENT_NAME: 'workflow_dispatch', GITHUB_REF: `refs/tags/${tag}`,
  GITHUB_WORKFLOW_REF: `${repository}/${releaseWorkflowPath}@refs/tags/${tag}`,
  GITHUB_SHA: sha, GITHUB_WORKFLOW_SHA: sha,
}

// A published Release as Prepare Release leaves it: immutable, with every verified asset attached to the draft.
function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-release-test-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  let counter = 0
  const packageBytes = Buffer.from('original CI package bytes: do not rebuild or rezip')
  const run = {
    id: 1001, run_attempt: 2, workflow_id: 42, name: 'Platform CI', path: ciWorkflowPath,
    repository: { full_name: repository }, head_repository: { full_name: repository },
    event: 'push', head_branch: 'main', head_sha: sha, status: 'completed', conclusion: 'success',
  }
  const github = {
    run, calls: [], assets: new Map(),
    release: { id: 7, tag_name: tag, draft: false, published_at: '2026-10-20T00:00:00Z', immutable: true },
    jobs: requiredJobs.map(name => ({ name, run_id: 1001, run_attempt: 2, head_sha: sha, status: 'completed', conclusion: 'success' })),
    get(endpoint) {
      this.calls.push(endpoint)
      if (endpoint === `/git/ref/tags/${tag}`) return { object: { type: 'commit', sha } }
      if (endpoint === `/compare/${sha}...main?per_page=1`) return { status: 'ahead', merge_base_commit: { sha } }
      if (endpoint === `/releases/tags/${tag}`) return this.release
      if (endpoint === '/actions/workflows/m0-18-ci.yml') return { id: 42, name: 'M0-18 CI', path: ciWorkflowPath }
      if (endpoint === '/actions/runs/1001/attempts/2') return this.run
      throw new Error(`Unexpected GitHub request: ${endpoint}`)
    },
    list(endpoint) {
      this.calls.push(endpoint)
      if (endpoint === '/releases/7/assets?per_page=100') return [...this.assets.values()].map(value => value.asset)
      if (endpoint === '/actions/runs/1001/attempts/2/jobs?per_page=100') return this.jobs
      throw new Error(`Unexpected GitHub list: ${endpoint}`)
    },
    download(endpoint, destination) {
      this.calls.push(endpoint)
      const item = [...this.assets.values()].find(value => endpoint === `/releases/assets/${value.asset.id}`)
      assert(item, `Unknown asset ${endpoint}`)
      fs.writeFileSync(destination, item.bytes)
    },
    addAsset(name, bytes) {
      this.assets.set(name, { bytes, asset: { id: ++counter, name, state: 'uploaded', size: bytes.length, digest: `sha256:${digest(bytes)}` } })
    },
  }
  github.addAsset('provenance.json', Buffer.from(JSON.stringify({
    schemaVersion: 1, repository, tag, sourceCommit: sha, ciRunId: 1001, ciRunAttempt: 2,
    artifactName: `m1-15-server-${sha}-2`, packageSha256: digest(packageBytes), packageBytes: packageBytes.length,
  })))
  github.addAsset(packageName, packageBytes)
  github.addAsset(`${packageName}.sha256`, Buffer.from(`${digest(packageBytes)}  ${packageName}\n`))
  github.addAsset('artifact-manifest.json', Buffer.from(JSON.stringify({
    schemaVersion: 1, milestone: 'M1-15', sourceCommit: sha,
    server: { platform: 'windows', architecture: 'x64', runtimeMode: 'WINDOWS_SERVICE_WINSW' },
    artifacts: [{ name: packageName, bytes: packageBytes.length, sha256: digest(packageBytes) }],
  })))
  const prepare = (options = {}) => prepareRelease({ github, tag, env, directory: fs.mkdtempSync(path.join(root, 'attempt-')), ...options })
  return { github, prepare, packageBytes }
}

test('a complete immutable Release is verified against its pinned main CI attempt', async t => {
  const { github, prepare, packageBytes } = fixture(t)
  const result = await prepare()
  assert.deepEqual(fs.readFileSync(path.join(result.directory, packageName)), packageBytes)
  assert.equal(result.provenance.ciRunId, 1001)
  assert(github.calls.includes('/actions/runs/1001/attempts/2/jobs?per_page=100'))
  assert(!('upload' in github) && !('uploadAsset' in github), 'deployment must never write Release assets')
})

test('drafts, mutable Releases and incomplete assets never reach deployment', async t => {
  const { github, prepare } = fixture(t)
  for (const change of [{ immutable: false }, { draft: true }, { published_at: null }]) {
    github.release = { ...github.release, ...change }
    await assert.rejects(prepare(), /immutable|published/u)
    github.release = { id: 7, tag_name: tag, draft: false, published_at: '2026-10-20T00:00:00Z', immutable: true }
  }
  github.assets.delete('artifact-manifest.json')
  await assert.rejects(prepare(), /assets are incomplete/u)
})

test('tampered assets and a provenance redirect fail closed', async t => {
  const { github, prepare } = fixture(t)
  const original = github.assets.get(packageName)
  github.assets.set(packageName, { ...original, bytes: Buffer.from('a manually replaced package') })
  await assert.rejects(prepare(), /Release asset (size|digest) mismatch/u)
  github.assets.set(packageName, original)
  github.jobs[2].conclusion = 'skipped'
  await assert.rejects(prepare(), /Required job did not succeed/u)
  github.jobs[2].conclusion = 'success'
  const changed = JSON.parse(github.assets.get('provenance.json').bytes)
  changed.ciRunId = 4444
  github.addAsset('provenance.json', Buffer.from(JSON.stringify(changed)))
  await assert.rejects(prepare(), /Unexpected GitHub request/u)
})

test('CI from dev is not deployable once main is the release line', async t => {
  const { github, prepare } = fixture(t)
  github.run = { ...github.run, head_branch: 'dev' }
  await assert.rejects(prepare(), /Only main CI artifacts are deployable/u)
})

test('rollback runs on the target tag with its own workflow path', async t => {
  const { prepare } = fixture(t)
  const rollbackEnv = { ...env, GITHUB_WORKFLOW_REF: `${repository}/${rollbackWorkflowPath}@refs/tags/${tag}` }
  await assert.rejects(prepare({ env: rollbackEnv }), /Workflow ref must be the target tag/u)
  const result = await prepare({ env: rollbackEnv, workflowPath: rollbackWorkflowPath })
  assert.equal(result.provenance.tag, tag)
})

test('annotated tags resolve to commits and tag movement fails before CI access', async t => {
  const { github } = fixture(t)
  const originalGet = github.get.bind(github)
  github.get = endpoint => {
    if (endpoint === `/git/ref/tags/${tag}`) return { object: { type: 'tag', sha: 'b'.repeat(40) } }
    if (endpoint === `/git/tags/${'b'.repeat(40)}`) return { object: { type: 'commit', sha } }
    return originalGet(endpoint)
  }
  assert.equal(await resolveSource(github, tag, env), sha)
  github.get = () => ({ object: { type: 'commit', sha: 'c'.repeat(40) } })
  await assert.rejects(resolveSource(github, tag, env), /tag moved/u)
})
