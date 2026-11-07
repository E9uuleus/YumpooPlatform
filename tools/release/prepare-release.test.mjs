import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { changedFiles, prepareDraft } from './prepare-release.mjs'
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
      throw new Error(`Unexpected GitHub request: ${endpoint}`)
    },
    list(endpoint) {
      this.calls.push(endpoint)
      if (endpoint === `/compare/${'b'.repeat(40)}...${sha}?per_page=100`) {
        // Paged compare: commits continue on later pages, total_commits counts them all.
        return [
          { total_commits: 3, commits: [
            { sha: 'c'.repeat(40), parents: [{}], commit: { message: 'feat(timer): 支持并行计时 (TT-4)\n\nbody' } },
            { sha: 'd'.repeat(40), parents: [{}, {}], commit: { message: "Merge pull request #9 from dev" } },
          ] },
          { total_commits: 3, commits: [{ sha: 'e'.repeat(40), parents: [{}], commit: { message: 'fix(web): 修复表格滚动 (#120)' } }] },
        ]
      }
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
  github.changeRanges = []
  const changes = (base, head) => {
    github.changeRanges.push([base, head])
    return [{ filename: 'backend/src/main/resources/db/migration/workitem/V79__add_note.sql', status: 'added' }]
  }
  const draft = (options = {}) => prepareDraft({ github, sourceCommit: sha, env, directory: fs.mkdtempSync(path.join(root, 'run-')), now, unpack, attest, log: () => {}, inventory: () => [{ file: 'workitem/V79__add_note.sql', classification: 'expand' }], changes, ...options })
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
  assert.deepEqual(github.changeRanges, [['b'.repeat(40), sha]], 'changed files span the previous release commit to the source')
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

test('changed files come from the full git range, past the 300 files a compare response lists', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-range-test-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const git = (...args) => {
    const result = spawnSync('git', ['-c', 'user.name=test', '-c', 'user.email=test@example.invalid', ...args], { cwd: root, encoding: 'utf8' })
    assert.equal(result.status, 0, result.stderr)
    return result.stdout.trim()
  }
  const write = (file, text) => {
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true })
    fs.writeFileSync(path.join(root, file), text)
  }
  git('init', '-q')
  write('kept.txt', 'v1\n')
  write('gone.txt', 'old\n')
  git('add', '.')
  git('commit', '-q', '-m', 'chore: base')
  const base = git('rev-parse', 'HEAD')
  for (let index = 0; index < 320; index += 1) write(`bulk/f${index}.txt`, `${index}\n`)
  write('kept.txt', 'v2\n')
  fs.rmSync(path.join(root, 'gone.txt'))
  write('backend/src/main/resources/db/migration/workitem/V99__说明.sql', 'SELECT 1;\n')
  git('add', '-A')
  git('commit', '-q', '-m', 'feat: bulk')
  const head = git('rev-parse', 'HEAD')

  const files = changedFiles(root, base, head)
  assert.equal(files.length, 323)
  assert.deepEqual(files.find(file => file.filename.endsWith('.sql')), { filename: 'backend/src/main/resources/db/migration/workitem/V99__说明.sql', status: 'added' })
  assert.deepEqual(files.find(file => file.filename === 'kept.txt'), { filename: 'kept.txt', status: 'modified' })
  assert.deepEqual(files.find(file => file.filename === 'gone.txt'), { filename: 'gone.txt', status: 'removed' })
  assert.throws(() => changedFiles(root, 'f'.repeat(40), head), /git diff .* failed/u, 'a commit missing from a shallow checkout fails the draft')
  assert.throws(() => changedFiles(root, 'HEAD~1', head), /full commit SHAs/u)
})
