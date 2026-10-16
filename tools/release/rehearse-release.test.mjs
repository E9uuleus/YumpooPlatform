import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { rehearseRelease } from './rehearse-release.mjs'
import { repository, ciWorkflowPath, packageName, requiredJobs } from './policy.mjs'
import { digest, packageManifest, sbomBytes } from './test-fixtures.mjs'

const sha = 'a'.repeat(40)
const now = new Date('2026-10-20T02:00:00Z')

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-rehearsal-test-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  let assetId = 0
  const packageBytes = Buffer.from('main CI package bytes')
  const sboms = sbomBytes()
  const run = {
    id: 3001, run_attempt: 1, workflow_id: 42, name: 'Platform CI', path: ciWorkflowPath,
    repository: { full_name: repository }, head_repository: { full_name: repository },
    event: 'push', head_branch: 'main', head_sha: sha, status: 'completed', conclusion: 'success',
  }
  const files = {
    [packageName]: packageBytes,
    [`${packageName}.sha256`]: Buffer.from(`${digest(packageBytes)}  ${packageName}\n`),
    'artifact-manifest.json': Buffer.from(JSON.stringify(packageManifest(sha, packageBytes, sboms))),
    ...Object.fromEntries(sboms),
    'verification-report.json': Buffer.from(JSON.stringify({ schemaVersion: 1, sourceCommit: sha, packageGate: 'PASS', localGate: 'PASS' })),
  }
  const archive = Buffer.from('main CI artifact archive')
  const github = {
    calls: [], assets: [],
    releases: [{ id: 50, tag_name: 'server-v2026.10.19.1', draft: false, target_commitish: 'b'.repeat(40) }],
    jobs: requiredJobs.map(name => ({ name, run_id: 3001, run_attempt: 1, head_sha: sha, status: 'completed', conclusion: 'success' })),
    get(endpoint) {
      this.calls.push(endpoint)
      if (endpoint === '/branches/main') return { commit: { sha } }
      if (endpoint === `/compare/${sha}...main?per_page=1`) return { status: 'identical', merge_base_commit: { sha } }
      if (endpoint === '/actions/workflows/m0-18-ci.yml') return { id: 42, path: ciWorkflowPath }
      if (endpoint === '/actions/runs/3001/attempts/1') return run
      if (endpoint === '/releases/tags/server-v2026.10.20.1') return this.releases.find(release => release.id === 51)
      if (endpoint === '/git/ref/tags/server-v2026.10.20.1') return { object: { type: 'commit', sha } }
      throw new Error(`Unexpected GitHub request: ${endpoint}`)
    },
    list(endpoint) {
      this.calls.push(endpoint)
      if (endpoint === '/releases?per_page=100') return this.releases
      if (endpoint === '/git/matching-refs/tags/server-v') return [{ ref: 'refs/tags/server-v2026.10.19.1' }, { ref: 'refs/tags/server-v2026.10.20.1' }]
      if (endpoint.startsWith('/actions/workflows/42/runs?')) return [run]
      if (endpoint === '/actions/runs/3001/attempts/1/jobs?per_page=100') return this.jobs
      if (endpoint === '/actions/runs/3001/artifacts?per_page=100') {
        return [{ id: 900, name: `m1-15-server-${sha}-1`, expired: false, digest: `sha256:${digest(archive)}`, workflow_run: { id: 3001, head_branch: 'main', head_sha: sha } }]
      }
      if (endpoint === '/releases/51/assets?per_page=100') return this.assets.map(item => item.asset)
      throw new Error(`Unexpected GitHub list: ${endpoint}`)
    },
    download(endpoint, destination) {
      this.calls.push(endpoint)
      if (endpoint === '/actions/artifacts/900/zip') return fs.writeFileSync(destination, archive)
      const item = this.assets.find(value => endpoint === `/releases/assets/${value.asset.id}`)
      assert(item, `Unknown asset ${endpoint}`)
      fs.writeFileSync(destination, item.bytes)
    },
    publish() {
      this.releases.push({ id: 51, tag_name: 'server-v2026.10.20.1', draft: false, published_at: '2026-10-20T01:00:00Z', immutable: true, target_commitish: sha })
      const provenance = {
        schemaVersion: 1, repository, tag: 'server-v2026.10.20.1', sourceCommit: sha, ciRunId: 3001, ciRunAttempt: 1,
        artifactName: `m1-15-server-${sha}-1`, packageSha256: digest(packageBytes), packageBytes: packageBytes.length,
      }
      for (const [name, bytes] of [...Object.entries(files).filter(([name]) => name !== 'verification-report.json'), ['provenance.json', Buffer.from(JSON.stringify(provenance))]]) {
        this.assets.push({ bytes, asset: { id: ++assetId, name, state: 'uploaded', size: bytes.length, digest: `sha256:${digest(bytes)}` } })
      }
    },
  }
  const unpack = (_archive, directory) => {
    fs.mkdirSync(directory)
    for (const [name, bytes] of Object.entries(files)) fs.writeFileSync(path.join(directory, name), bytes)
  }
  const attested = []
  const attest = async (_directory, ciRun) => { attested.push(`${ciRun.id}/${ciRun.run_attempt}`) }
  const rehearse = () => rehearseRelease({ github, env: {}, directory: fs.mkdtempSync(path.join(root, 'run-')), now, unpack, attest })
  return { github, rehearse, attested }
}

test('an unpublished main head runs every draft check and only predicts the tag', async t => {
  const { github, rehearse, attested } = fixture(t)
  const result = await rehearse()
  assert.deepEqual(result, { mode: 'draft', sourceCommit: sha, tag: 'server-v2026.10.20.2', ciRunId: 3001, ciRunAttempt: 1 })
  assert.deepEqual(attested, ['3001/1'])
  assert(!('send' in github) && !('uploadAsset' in github), 'the rehearsal has no write path')
})

test('a published main head is re-verified exactly as deployment would verify it', async t => {
  const { github, rehearse, attested } = fixture(t)
  github.publish()
  const result = await rehearse()
  assert.deepEqual(result, { mode: 'published', sourceCommit: sha, tag: 'server-v2026.10.20.1', ciRunId: 3001, ciRunAttempt: 1 })
  assert.deepEqual(attested, ['3001/1'])
  assert(!github.calls.includes('/actions/artifacts/900/zip'), 'a published head is checked from its immutable assets')
  github.releases.find(release => release.id === 51).immutable = false
  await assert.rejects(rehearse(), /not immutable/u)
})

test('a broken chain fails the rehearsal instead of passing silently', async t => {
  const { github, rehearse } = fixture(t)
  github.jobs[1].conclusion = 'failure'
  await assert.rejects(rehearse(), /No successful push\/main Platform CI/u)
})
