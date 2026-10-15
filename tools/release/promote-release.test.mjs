import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { prepareRelease, resolveSource } from './promote-release.mjs'
import { repository, ciWorkflowPath, releaseWorkflowPath, packageName, releaseAssetNames, requiredJobs } from './policy.mjs'

const sha = 'a'.repeat(40)
const tag = 'server-v1.2.3'
const digest = value => createHash('sha256').update(value).digest('hex')
const env = {
  GITHUB_REPOSITORY: repository, GITHUB_EVENT_NAME: 'workflow_dispatch', GITHUB_REF: `refs/tags/${tag}`,
  GITHUB_WORKFLOW_REF: `${repository}/${releaseWorkflowPath}@refs/tags/${tag}`,
  GITHUB_SHA: sha, GITHUB_WORKFLOW_SHA: sha,
}

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-release-test-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  let counter = 0
  const packageBytes = Buffer.from('original CI package bytes: do not rebuild or rezip')
  const run = {
    id: 1001, run_attempt: 2, workflow_id: 42, name: 'Platform CI', path: ciWorkflowPath,
    repository: { full_name: repository }, head_repository: { full_name: repository },
    event: 'push', head_branch: 'dev', head_sha: sha, status: 'completed', conclusion: 'success',
  }
  const files = {
    [packageName]: packageBytes,
    [`${packageName}.sha256`]: Buffer.from(`${digest(packageBytes)}  ${packageName}\n`),
    'artifact-manifest.json': Buffer.from(JSON.stringify({
      schemaVersion: 1, milestone: 'M1-15', sourceCommit: sha,
      server: { platform: 'windows', architecture: 'x64', runtimeMode: 'WINDOWS_SERVICE_WINSW' },
      artifacts: [{ name: packageName, bytes: packageBytes.length, sha256: digest(packageBytes) }],
    })),
    'verification-report.json': Buffer.from(JSON.stringify({ schemaVersion: 1, sourceCommit: sha, packageGate: 'PASS', localGate: 'PASS' })),
  }
  const github = {
    run, files, packageBytes, archive: Buffer.from('the CI artifact zip archive'), calls: [], uploaded: [], assets: new Map(),
    runs: [run], jobs: requiredJobs.map(name => ({ name, run_id: 1001, run_attempt: 2, head_sha: sha, status: 'completed', conclusion: 'success' })),
    artifact: {
      id: 999, name: `m1-15-server-${sha}-2`, expired: false,
      workflow_run: { id: 1001, head_branch: 'dev', head_sha: sha },
    },
    get(endpoint) {
      this.calls.push(endpoint)
      if (endpoint === `/git/ref/tags/${tag}`) return { object: { type: 'commit', sha } }
      if (endpoint === `/compare/${sha}...dev?per_page=1`) return { status: 'ahead', merge_base_commit: { sha } }
      if (endpoint === `/releases/tags/${tag}`) return { id: 7, tag_name: tag, draft: false, published_at: '2026-10-15T00:00:00Z' }
      if (endpoint === '/actions/workflows/m0-18-ci.yml') return { id: 42, name: 'M0-18 CI', path: ciWorkflowPath }
      if (endpoint === '/actions/runs/1001/attempts/2') return this.run
      throw new Error(`Unexpected GitHub request: ${endpoint}`)
    },
    list(endpoint) {
      this.calls.push(endpoint)
      if (endpoint === '/releases/7/assets?per_page=100') return [...this.assets.values()].map(value => value.asset)
      if (endpoint.startsWith('/actions/workflows/42/runs?')) return this.runs
      if (endpoint === '/actions/runs/1001/attempts/2/jobs?per_page=100') return this.jobs
      if (endpoint === '/actions/runs/1001/artifacts?per_page=100') return [this.artifact]
      throw new Error(`Unexpected GitHub list: ${endpoint}`)
    },
    download(endpoint, destination) {
      this.calls.push(endpoint)
      if (endpoint === '/actions/artifacts/999/zip') fs.writeFileSync(destination, this.archive)
      else {
        const item = [...this.assets.values()].find(value => endpoint === `/releases/assets/${value.asset.id}`)
        assert(item, `Unknown asset ${endpoint}`)
        fs.writeFileSync(destination, item.bytes)
      }
    },
    addAsset(name, bytes) {
      this.assets.set(name, { bytes, asset: { id: ++counter, name, state: 'uploaded', size: bytes.length, digest: `sha256:${digest(bytes)}` } })
    },
    upload(actualTag, file) {
      assert.equal(actualTag, tag)
      const name = path.basename(file)
      assert(!this.assets.has(name), 'Existing assets must never be overwritten')
      if (this.failUpload === name) throw new Error('Simulated upload disconnect')
      this.uploaded.push(name)
      this.addAsset(name, fs.readFileSync(file))
    },
  }
  github.artifact.digest = `sha256:${digest(github.archive)}`
  const unpack = (_archive, directory) => {
    fs.mkdirSync(directory)
    for (const [name, bytes] of Object.entries(github.files)) fs.writeFileSync(path.join(directory, name), bytes)
  }
  const prepare = options => {
    const directory = fs.mkdtempSync(path.join(root, 'attempt-'))
    return prepareRelease({ github, tag, env, directory, unpack, ...options })
  }
  return { github, prepare, root }
}

test('promotes verified CI bytes despite stale registry display name and pins provenance before upload', async t => {
  const { github, prepare } = fixture(t)
  github.runs.unshift({ ...github.run, id: 2000, event: 'pull_request' })
  const result = await prepare()
  assert.deepEqual(fs.readFileSync(path.join(result.directory, packageName)), github.packageBytes)
  assert.equal(result.provenance.ciRunId, 1001)
  assert.equal(result.provenance.ciRunAttempt, 2)
  assert.equal(result.provenance.packageSha256, digest(github.packageBytes))
  assert.deepEqual(github.uploaded, ['provenance.json', ...releaseAssetNames.filter(name => name !== 'provenance.json')])
  assert(github.calls.includes('/actions/runs/1001/attempts/2/jobs?per_page=100'))
})

test('complete Release retry verifies the retained attempt and survives expired CI artifacts', async t => {
  const { github, prepare } = fixture(t)
  const original = await prepare()
  github.calls = []
  github.uploaded = []
  github.runs = []
  github.artifact.expired = true
  const result = await prepare({ promote: false })
  assert.deepEqual(result.provenance, original.provenance)
  assert.deepEqual(github.uploaded, [])
  assert(github.calls.includes('/actions/runs/1001/attempts/2'))
  assert(!github.calls.some(call => call.includes('/artifacts') || call.startsWith('/actions/workflows/42/runs?')))
  assert.deepEqual(fs.readFileSync(path.join(result.directory, packageName)), github.packageBytes)
})

test('interrupted promotion resumes the pinned attempt without overwriting assets', async t => {
  const { github, prepare } = fixture(t)
  github.failUpload = packageName
  await assert.rejects(prepare(), /Simulated upload disconnect/u)
  assert.deepEqual([...github.assets.keys()], ['provenance.json'])
  const savedReceipt = github.assets.get('provenance.json').bytes
  github.failUpload = null
  github.runs = []
  await prepare()
  assert.deepEqual(github.assets.get('provenance.json').bytes, savedReceipt)
  assert.equal(github.uploaded.filter(name => name === 'provenance.json').length, 1)
  assert.equal(github.assets.size, 4)
})

test('existing mismatched bytes fail closed before uploading a provenance or replacing assets', async t => {
  const { github, prepare } = fixture(t)
  const foreign = Buffer.from('a manually replaced package')
  github.addAsset(packageName, foreign)
  await assert.rejects(prepare(), /Release asset (size|digest) mismatch/u)
  assert.deepEqual(github.assets.get(packageName).bytes, foreign)
  assert.deepEqual(github.uploaded, [])
})

test('incorrect archive digest and package manifest checksum are rejected before promotion', async t => {
  const { github, prepare } = fixture(t)
  const original = github.archive
  github.archive = Buffer.from('tampered CI archive')
  await assert.rejects(prepare(), /archive digest mismatch/u)
  github.archive = original
  github.files[`${packageName}.sha256`] = Buffer.from('a forged checksum')
  await assert.rejects(prepare(), /checksum file mismatch/u)
  assert.deepEqual(github.uploaded, [])
})

test('retained provenance cannot select another CI run or avoid job verification', async t => {
  const { github, prepare } = fixture(t)
  await prepare()
  github.jobs[2].conclusion = 'skipped'
  await assert.rejects(prepare({ promote: false }), /Required job did not succeed/u)
  github.jobs[2].conclusion = 'success'
  const changed = JSON.parse(github.assets.get('provenance.json').bytes)
  changed.ciRunId = 4444
  github.addAsset('provenance.json', Buffer.from(JSON.stringify(changed)))
  await assert.rejects(prepare({ promote: false }), /Unexpected GitHub request/u)
})

test('no successful matching CI and incomplete published assets cannot reach deployment', async t => {
  const { github, prepare } = fixture(t)
  github.runs = [{ ...github.run, event: 'workflow_dispatch' }, { ...github.run, head_branch: 'main' }]
  await assert.rejects(prepare(), /No successful push\/dev Platform CI attempt/u)
  await assert.rejects(prepare({ promote: false }), /provenance is missing/u)
  assert.deepEqual(github.uploaded, [])
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
