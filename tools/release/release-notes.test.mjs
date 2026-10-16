import assert from 'node:assert/strict'
import test from 'node:test'
import { commitSection, releaseNotes } from './release-notes.mjs'
import { notifyWeCom } from './notify.mjs'

const sha = 'a'.repeat(40)
const run = { id: 3001, run_attempt: 2 }

test('commit subjects group by conventional type and unknown types fall into 其他', () => {
  assert.equal(commitSection('feat(timer): x (TT-1)'), '新功能')
  assert.equal(commitSection('fix!: breaking fix'), '问题修复')
  assert.equal(commitSection('build(deps): bump'), 'CI 与构建')
  assert.equal(commitSection('Merge branch dev'), '其他')
})

test('notes link the CI attempt, skip merge commits and flag migrations, contracts and truncation', () => {
  const comparison = {
    commits: [
      { sha: 'b'.repeat(40), parents: [{}], commit: { message: 'feat(api): 新接口 (A-1)' } },
      { sha: 'c'.repeat(40), parents: [{}, {}], commit: { message: 'Merge pull request #1' } },
    ],
    files: [
      { filename: 'backend/src/main/resources/db/migration/workitem/V80__x.sql', status: 'added' },
      { filename: 'backend/src/main/resources/db/migration/workitem/V79__y.sql', status: 'modified' },
      { filename: 'contracts/openapi/yumpoo-v1.yaml', status: 'modified' },
    ],
  }
  const notes = releaseNotes({ tag: 'server-v2026.10.20.1', sourceCommit: sha, run, previousTag: 'server-v2026.10.19.2', comparison })
  assert.match(notes, /actions\/runs\/3001\/attempts\/2/u)
  assert.match(notes, /compare\/server-v2026\.10\.19\.2\.\.\.a{40}/u)
  assert.match(notes, /### 新功能\n\n- feat\(api\): 新接口 \(A-1\)（`bbbbbbbb`）/u)
  assert.doesNotMatch(notes, /Merge pull request/u)
  assert.match(notes, /新增迁移：`V80__x\.sql`/u)
  assert.doesNotMatch(notes, /V79__y/u)
  assert.match(notes, /契约变更：`contracts\/openapi\/yumpoo-v1\.yaml`/u)
  assert.doesNotMatch(notes, /截断/u)
  const truncated = releaseNotes({ tag: 'server-v2026.10.20.1', sourceCommit: sha, run, previousTag: 'server-v2026.10.19.2', comparison: { commits: comparison.commits, files: Array.from({ length: 300 }, (_, index) => ({ filename: `f${index}`, status: 'modified' })) } })
  assert.match(truncated, /截断/u)
  assert.match(releaseNotes({ tag: 'server-v2026.10.20.1', sourceCommit: sha, run, previousTag: undefined, comparison: null }), /首个 main 发布版本/u)
})

test('GitHub-side notifications accept only the robot URL and never fail the workflow', async () => {
  const calls = []
  const send = async (url, options) => { calls.push({ url, body: JSON.parse(options.body) }); return { ok: true } }
  assert.equal(await notifyWeCom({}, 'x', send), false)
  for (const url of ['https://example.invalid/cgi-bin/webhook/send?key=a', 'https://qyapi.weixin.qq.com:443/cgi-bin/webhook/send?key=a']) {
    assert.equal(await notifyWeCom({ WECOM_RELEASE_WEBHOOK: url }, 'x', send), false)
  }
  assert.deepEqual(calls, [])
  assert.equal(await notifyWeCom({ WECOM_RELEASE_WEBHOOK: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc-123' }, '**草稿就绪**', send), true)
  assert.deepEqual(calls[0].body, { msgtype: 'markdown', markdown: { content: '**草稿就绪**' } })
  assert.equal(await notifyWeCom({ WECOM_RELEASE_WEBHOOK: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc' }, 'x', async () => { throw new TypeError('network down') }), false)
})
