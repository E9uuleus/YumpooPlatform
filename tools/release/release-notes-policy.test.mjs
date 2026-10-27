import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { evaluatePullRequest, missingSummary, policyReport } from './release-notes-policy.mjs'

const pull = (body, extra = {}) => ({ body, user: { login: 'm' }, head: { ref: 'fix/x' }, base: { ref: 'dev' }, ...extra })

test('a PR passes with valid notes or 「- 无」 and fails with an actionable report otherwise', () => {
  assert.deepEqual(evaluatePullRequest(pull('## 发布说明\n- 修复｜桌面：托盘小窗口不再显示 404。')), { exempt: null, errors: [] })
  assert.deepEqual(evaluatePullRequest(pull('## 发布说明\n- 无')), { exempt: null, errors: [] })
  const failed = evaluatePullRequest(pull('修复托盘 (DS-1)'))
  assert.deepEqual(failed.errors, ['缺少「## 发布说明」段'])
  const report = policyReport(failed)
  assert.match(report, /^发布说明检查未通过：\n- 缺少「## 发布说明」段/u)
  assert.match(report, /类别为 新增、改进、修复、安全、运维，范围为 Web、桌面、服务端/u)
  assert.equal(policyReport({ exempt: null, errors: [] }), '发布说明检查：通过。')
})

test('hotfixes into main need notes; dependency bumps, back-merges and promotions do not', () => {
  assert.equal(evaluatePullRequest(pull('', { head: { ref: 'hotfix/feed' }, base: { ref: 'main' } })).errors.length, 1)
  for (const extra of [{ user: { login: 'dependabot[bot]' } }, { head: { ref: 'backmerge/e903ba9ec605' } }, { head: { ref: 'dev' }, base: { ref: 'main' } }]) {
    const result = evaluatePullRequest(pull('', extra))
    assert(result.exempt)
    assert.match(policyReport(result), /豁免/u)
  }
})

test('the draft summary lists every gap for the publisher', () => {
  assert.equal(missingSummary([{ label: '#12 旧 PR', reason: '缺少「## 发布说明」段' }]),
    '**1 项未提供合格的发布说明**，已在草稿「发布信息」中列出；补写 PR 正文后重跑工作流即可刷新：\n- #12 旧 PR：缺少「## 发布说明」段')
})

test('the CLI reads the pull_request event and fails the check on invalid notes', t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-notes-'))
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }))
  const script = fileURLToPath(new URL('./release-notes-policy.mjs', import.meta.url))
  const check = body => {
    const event = path.join(directory, `${Math.random()}.json`)
    fs.writeFileSync(event, JSON.stringify({ pull_request: pull(body) }))
    return spawnSync(process.execPath, [script], { encoding: 'utf8', env: { ...process.env, GITHUB_EVENT_PATH: event, GITHUB_STEP_SUMMARY: '' } })
  }
  const passed = check('## 发布说明\n- 无')
  assert.equal(passed.status, 0)
  assert.match(passed.stdout, /通过/u)
  const failed = check('## 发布说明\n- 修复｜桌面：fix(desktop): 托盘')
  assert.equal(failed.status, 1)
  assert.match(failed.stdout, /type\(scope\)/u)
})
