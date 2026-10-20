import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { prepareDraft } from './prepare-release.mjs'
import { repository, ciWorkflowPath, packageName, releaseAssetNames, requiredJobs, attestedFileNames } from './policy.mjs'
import { packageManifest, sbomBytes } from './test-fixtures.mjs'

const sha = 'a'.repeat(40)
const digest = value => createHash('sha256').update(value).digest('hex')
const env = { GITHUB_REPOSITORY: repository, GITHUB_EVENT_NAME: 'workflow_run' }
const now = new Date('2026-10-20T02:00:00Z')

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-draft-test-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  let assetId = 0
  let releaseId = 100
  const packageBytes = Buffer.from('main CI package bytes')
  const sboms = sbomBytes()
  const run = {
    id: 3001, run_attempt: 1, workflow_id: 42, name: 'Platform CI', path: ciWorkflowPath,
    repository: { full_name: repository }, head_repository: { full_name: repository },
    event: 'push', head_branch: 'main', head_sha: sha, status: 'completed', conclusion: 'success',
  }
  const github = {
    calls: [], uploads: [], deleted: [], runs: [run], assets: new Map(), comparison: 'ahead', latestOrder: 'behind', draftOrder: {},
    releases: [{ id: 50, tag_name: 'server-v2026.10.19.1', draft: false, published_at: '2026-10-19T08:00:00Z', target_commitish: 'b'.repeat(40) }],
    tags: [{ ref: 'refs/tags/server-v2026.10.19.1' }, { ref: 'refs/tags/server-v2026.10.20.2' }],
    files: {
      [packageName]: packageBytes,
      [`${packageName}.sha256`]: Buffer.from(`${digest(packageBytes)}  ${packageName}\n`),
      'artifact-manifest.json': Buffer.from(JSON.stringify(packageManifest(sha, packageBytes, sboms))),
      ...Object.fromEntries(sboms),
      'verification-report.json': Buffer.from(JSON.stringify({ schemaVersion: 1, sourceCommit: sha, packageGate: 'PASS', localGate: 'PASS' })),
    },
    archive: Buffer.from('main CI artifact archive'),
    jobs: requiredJobs.map(name => ({ name, run_id: 3001, run_attempt: 1, head_sha: sha, status: 'completed', conclusion: 'success' })),
    pulls: {
      ['c'.repeat(40)]: [{ number: 121, title: 'feat(timer): 支持并行计时', merged_at: '2026-10-19T09:00:00Z', user: { login: 'dev' }, head: { ref: 'feature/parallel' }, base: { ref: 'dev' },
        body: '说明\n\n## 发布说明\n<!-- 每行一条 -->\n- 新增｜桌面：托盘左键直接打开快捷工时小窗口。\n- 运维｜服务端：发布后管理员核对 Nginx 配置。\n' }],
      ['e'.repeat(40)]: [{ number: 120, title: 'fix(web): 修复表格滚动', merged_at: '2026-10-19T08:00:00Z', user: { login: 'dev' }, head: { ref: 'fix/scroll' }, base: { ref: 'dev' }, body: '旧 PR 没有发布说明段' }],
    },
    get(endpoint) {
      this.calls.push(endpoint)
      const pulls = endpoint.match(/^\/commits\/([0-9a-f]{40})\/pulls$/u)
      if (pulls) return this.pulls[pulls[1]] ?? []
      if (endpoint === `/compare/${sha}...main?per_page=1`) return { status: this.comparison, merge_base_commit: { sha } }
      if (endpoint === '/git/ref/tags/server-v2026.10.19.1') return { object: { type: 'commit', sha: 'b'.repeat(40) } }
      if (endpoint === `/compare/${sha}...${'b'.repeat(40)}?per_page=1`) return { status: this.latestOrder }
      const draftOrder = endpoint.match(/^\/compare\/([0-9a-f]{40})\.\.\.([0-9a-f]{40})\?per_page=1$/u)
      if (draftOrder && draftOrder[2] === sha) return { status: this.draftOrder[draftOrder[1]] ?? 'diverged' }
      if (endpoint === '/actions/workflows/m0-18-ci.yml') return { id: 42, path: ciWorkflowPath }
      if (endpoint === '/actions/runs/3001/attempts/1') return run
      if (endpoint === `/compare/server-v2026.10.19.1...${sha}`) {
        return {
          commits: [
            { sha: 'c'.repeat(40), parents: [{}], commit: { message: 'feat(timer): 支持并行计时 (TT-4)\n\nbody' } },
            { sha: 'd'.repeat(40), parents: [{}, {}], commit: { message: "Merge pull request #9 from dev" } },
            { sha: 'e'.repeat(40), parents: [{}], commit: { message: 'fix(web): 修复表格滚动 (#120)' } },
          ],
          files: [{ filename: 'backend/src/main/resources/db/migration/workitem/V79__add_note.sql', status: 'added' }],
        }
      }
      throw new Error(`Unexpected GitHub request: ${endpoint}`)
    },
    list(endpoint) {
      this.calls.push(endpoint)
      if (endpoint === '/releases?per_page=100') return this.releases
      if (endpoint === '/git/matching-refs/tags/server-v') return this.tags
      if (endpoint.startsWith('/actions/workflows/42/runs?')) return this.runs
      if (endpoint === '/actions/runs/3001/attempts/1/jobs?per_page=100') return this.jobs
      if (endpoint === '/actions/runs/3001/artifacts?per_page=100') {
        return [{ id: 900, name: `m1-15-server-${sha}-1`, expired: false, digest: `sha256:${digest(this.archive)}`, workflow_run: { id: 3001, head_branch: 'main', head_sha: sha } }]
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
      if (method === 'POST' && endpoint === '/releases') {
        const release = { id: ++releaseId, html_url: `https://github.com/${repository}/releases/tag/untagged-${releaseId}`, ...body }
        this.releases.push(release)
        return release
      }
      const match = endpoint.match(/^\/releases\/(\d+)$/u)
      if (method === 'PATCH' && match) return Object.assign(this.releases.find(release => release.id === Number(match[1])), body)
      if (method === 'DELETE' && match) {
        this.deleted.push(Number(match[1]))
        this.releases = this.releases.filter(release => release.id !== Number(match[1]))
        return null
      }
      throw new Error(`Unexpected GitHub write: ${method} ${endpoint}`)
    },
    addAsset(id, name, bytes) {
      assert(![...this.assets.values()].some(item => item.releaseId === id && item.asset.name === name), 'assets are never overwritten')
      this.assets.set(`${id}/${name}`, { releaseId: id, bytes, asset: { id: ++assetId, name, state: 'uploaded', size: bytes.length, digest: `sha256:${digest(bytes)}` } })
    },
    uploadAsset(id, file) {
      if (this.failUpload === path.basename(file)) throw new Error('Simulated upload disconnect')
      this.uploads.push(path.basename(file))
      this.addAsset(id, path.basename(file), fs.readFileSync(file))
    },
  }
  const unpack = (_archive, directory) => {
    fs.mkdirSync(directory)
    for (const [name, bytes] of Object.entries(github.files)) fs.writeFileSync(path.join(directory, name), bytes)
  }
  github.attestations = []
  const attest = async (directory, ciRun, options) => {
    for (const name of attestedFileNames) assert(fs.statSync(path.join(directory, name)).isFile(), `attested file missing: ${name}`)
    github.attestations.push({ runId: ciRun.id, attempt: ciRun.run_attempt, uploadsSoFar: github.uploads.length, label: options.label })
    if (github.attestationError) throw new Error(github.attestationError)
  }
  const draft = (options = {}) => prepareDraft({ github, sourceCommit: sha, env, directory: fs.mkdtempSync(path.join(root, 'run-')), now, unpack, attest, log: () => {}, inventory: () => [{ file: 'workitem/V79__add_note.sql', classification: 'expand' }], ...options })
  return { github, draft, packageBytes }
}

test('a main commit gets a numbered draft with notes and every verified asset, provenance first', async t => {
  const { github, draft } = fixture(t)
  const result = await draft()
  assert.equal(result.tag, 'server-v2026.10.20.3', 'existing tags of the day, even without a Release, keep their numbers')
  assert.equal(result.created, true)
  const created = github.releases.find(release => release.id === result.releaseId)
  assert.equal(created.draft, true)
  assert.equal(created.target_commitish, sha)
  assert.match(created.body, /^## 服务端 server-v2026\.10\.20\.3\n\n新增 1 · 改进 0 · 修复 0 · 安全 0 · 需要管理员操作：是/u)
  assert.match(created.body, /### 更新内容\n\n\*\*新增\*\*\n\n- 桌面｜托盘左键直接打开快捷工时小窗口。（#121）/u)
  assert.match(created.body, /- 管理员操作：\n  - 服务端｜发布后管理员核对 Nginx 配置。（#121）/u)
  assert.match(created.body, /- 数据库迁移：`V79__add_note\.sql`（只扩展，可快速回退）/u)
  assert.match(created.body, /- 回退：可用 Server Rollback 回退到 `server-v2026\.10\.19\.1`/u)
  assert.match(created.body, /- 包含 PR：#121、#120/u)
  assert.match(created.body, / {2}- #120 fix\(web\): 修复表格滚动：缺少「## 发布说明」段/u)
  assert.doesNotMatch(created.body, /Merge pull request|TT-4/u)
  assert.equal(result.missing.length, 1)
  assert.deepEqual(github.uploads, ['provenance.json', ...releaseAssetNames.filter(name => name !== 'provenance.json')])
  assert.equal(result.provenance.ciRunId, 3001)
  assert.equal(result.provenance.tag, result.tag)
})

test('a rerun on the unpublished draft refreshes its body once PR release notes are fixed', async t => {
  const { github, draft } = fixture(t)
  const first = await draft()
  github.pulls['e'.repeat(40)][0].body = '## 发布说明\n- 修复｜Web：工作项表格横向滚动不再跳回开头。'
  const again = await draft()
  assert.equal(again.releaseId, first.releaseId)
  assert(github.calls.includes(`PATCH /releases/${first.releaseId}`))
  const body = github.releases.find(release => release.id === first.releaseId).body
  assert.match(body, /\*\*修复\*\*\n\n- Web｜工作项表格横向滚动不再跳回开头。（#120）/u)
  assert.match(body, /- 未提供发布说明：无/u)
  assert.deepEqual(again.missing, [])
})

test('CI bytes are attested before any upload and the persisted assets are attested again', async t => {
  const { github, draft } = fixture(t)
  github.attestationError = 'no attestation from main push CI run 3001 attempt 1'
  await assert.rejects(draft(), /no attestation/u)
  assert.deepEqual(github.uploads, [], 'unattested bytes never reach the draft')
  github.attestationError = null
  github.attestations = []
  await draft()
  assert.deepEqual(github.attestations, [
    { runId: 3001, attempt: 1, uploadsSoFar: 0, label: 'CI bytes before upload' },
    { runId: 3001, attempt: 1, uploadsSoFar: releaseAssetNames.length, label: 'persisted draft assets' },
  ])
})

test('an interrupted draft resumes on its pinned attempt without a new Release or re-upload', async t => {
  const { github, draft } = fixture(t)
  github.failUpload = packageName
  await assert.rejects(draft(), /Simulated upload disconnect/u)
  const releasesBefore = github.releases.length
  github.failUpload = null
  github.runs = []
  github.uploads = []
  const result = await draft()
  assert.equal(github.releases.length, releasesBefore)
  assert.equal(result.created, false)
  assert(!github.uploads.includes('provenance.json'))
  assert.equal(result.provenance.ciRunId, 3001)
})

// REL-P3 F10/F11: GitHub deletes Releases by ID unconditionally, so a draft published between listing and
// deleting would lose its immutable Release. Drafts are therefore never deleted, only reported.
test('other drafts are never deleted, only reported as superseded, and a published commit needs no draft', async t => {
  const { github, draft } = fixture(t)
  github.releases.push({ id: 60, tag_name: 'server-v2026.10.20.1', draft: true, target_commitish: 'f'.repeat(40) })
  // REL-P3 N1: a draft whose source is not older than this commit is not called superseded.
  github.releases.push({ id: 61, tag_name: 'server-v2026.10.20.2', draft: true, target_commitish: 'e'.repeat(40) })
  github.draftOrder = { ['f'.repeat(40)]: 'ahead', ['e'.repeat(40)]: 'behind' }
  const result = await draft()
  assert.deepEqual(github.deleted, [])
  assert(!github.calls.some(call => call.startsWith('DELETE ')))
  assert.deepEqual(result.supersededDrafts, ['server-v2026.10.20.1'])
  assert.deepEqual(result.otherDrafts, ['server-v2026.10.20.2'])
  const body = github.releases.find(release => release.id === result.releaseId).body
  assert.match(body, /旧草稿 `server-v2026\.10\.20\.1` 已被本草稿取代/u)
  assert.match(body, /另有草稿 `server-v2026\.10\.20\.2` 的源码不早于本草稿/u)
  assert.doesNotMatch(body, /旧草稿 `server-v2026\.10\.20\.2`/u)
  github.releases.find(release => release.target_commitish === sha).draft = false
  github.uploads = []
  const again = await draft()
  assert.equal(again.published, true)
  assert.deepEqual(github.uploads, [])
})

test('a re-run for a commit older than the live release creates nothing and deletes nothing', async t => {
  const { github, draft } = fixture(t)
  github.releases.push({ id: 60, tag_name: 'server-v2026.10.20.4', draft: true, target_commitish: 'f'.repeat(40) })
  github.latestOrder = 'ahead'
  await assert.rejects(draft(), /already published a newer commit/u)
  assert(!github.calls.some(call => /^(POST|DELETE) /u.test(call)))
  assert.deepEqual(github.releases.map(release => release.id), [50, 60])
  github.latestOrder = 'behind'
  github.runs = []
  await assert.rejects(draft(), /No successful push\/main Platform CI/u)
  assert.deepEqual(github.releases.map(release => release.id), [50, 60], 'a commit without eligible CI never removes the newer draft')
})

test('commits outside main and foreign existing assets fail closed', async t => {
  const { github, draft } = fixture(t)
  github.comparison = 'behind'
  await assert.rejects(draft(), /not an ancestor of main/u)
  github.comparison = 'ahead'
  github.releases.push({ id: 70, tag_name: 'server-v2026.10.20.3', draft: true, target_commitish: sha })
  github.addAsset(70, packageName, Buffer.from('foreign bytes'))
  await assert.rejects(draft(), /Release asset (size|digest) mismatch/u)
  await assert.rejects(draft({ sourceCommit: 'not-a-commit' }), /Invalid source commit/u)
  await assert.rejects(draft({ env: { ...env, GITHUB_EVENT_NAME: 'pull_request' } }), /Unsupported draft trigger/u)
})
