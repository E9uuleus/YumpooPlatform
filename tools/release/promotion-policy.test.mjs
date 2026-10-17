import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { allowedPromotionHead, breakingMigrationLabel, commentMarker, evaluatePromotion } from './promotion-policy.mjs'
import { repository } from './policy.mjs'

function repositoryFixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-promotion-test-'))
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
  write('backend/src/main/resources/db/migration/project/V1__create_project.sql', 'CREATE TABLE yumpoo.project (id uuid PRIMARY KEY, name text);\n')
  write('contracts/migrations/rollback-safety-exceptions.json', '{"schemaVersion":1,"exceptions":[]}\n')
  write('tools/openapi/breaking-change-exceptions.json', '{"schemaVersion":1,"exceptions":[]}\n')
  write('contracts/openapi/yumpoo-v1.yaml', 'openapi: 3.0.3\n')
  write('deployment/windows/cd/Invoke-YumpooDeploy.ps1', '# engine baseline\n')
  git('add', '.')
  git('commit', '-q', '-m', 'chore: base')
  const base = git('rev-parse', 'HEAD')
  const commit = (message, files) => {
    for (const [file, text] of Object.entries(files)) write(file, text)
    git('add', '.')
    git('commit', '-q', '-m', message)
    return git('rev-parse', 'HEAD')
  }
  const pullRequest = (head, overrides = {}) => ({
    number: 5, base: { ref: 'main', sha: base }, head: { ref: 'dev', sha: head, repo: { full_name: repository } }, labels: [], ...overrides,
  })
  return { root, commit, pullRequest, base, git }
}

test('only dev or hotfix branches from this repository may enter main', () => {
  for (const head of ['dev', 'hotfix/login-timeout', 'hotfix/v2.1']) assert(allowedPromotionHead(head))
  for (const head of ['main', 'feature/x', 'fix/x', 'hotfix/', 'hotfix/../dev', 'Hotfix/x', 'dev2']) assert(!allowedPromotionHead(head))
})

test('an expand-only promotion passes and reports grouped commits and contract state', t => {
  const { root, commit, pullRequest } = repositoryFixture(t)
  commit('feat(project): 增加备注 (P-1)', { 'backend/src/main/resources/db/migration/project/V2__add_note.sql': 'ALTER TABLE yumpoo.project ADD COLUMN note text;\n' })
  const head = commit('fix(web): 修复表单 (P-2)', { 'frontend/a.txt': 'a' })
  const { violations, report, breaking } = evaluatePromotion({ pullRequest: pullRequest(head), root })
  assert.deepEqual(violations, [])
  assert.deepEqual(breaking, [])
  assert(report.startsWith(commentMarker))
  assert.match(report, /\*\*新功能\*\*\n- feat\(project\): 增加备注/u)
  assert.match(report, /project\/V2__add_note\.sql`：只扩展/u)
  assert.match(report, /OpenAPI 没有变化/u)
  assert.match(report, /Create a merge commit/u)
  assert.match(report, /本次没有部署引擎改动/u)
  assert.doesNotMatch(report, /- \[ \] 主机管理员/u)
})

test('engine changes add an upgrade warning and manual check regardless of commit scope', t => {
  const { root, commit, pullRequest } = repositoryFixture(t)
  const head = commit('fix: 修复状态文件', {
    'deployment/windows/cd/Invoke-YumpooDeploy.ps1': '# updated engine\n',
    'deployment/windows/cd/Update-YumpooEngine.ps1': '# new upgrade entry\n',
  })
  const { report, violations } = evaluatePromotion({ pullRequest: pullRequest(head), root })
  assert.deepEqual(violations, [], 'the upgrade is a manual release prerequisite, not a promotion gate')
  assert.match(report, /发布前需要管理员升级主机引擎/u)
  assert.match(report, /`deployment\/windows\/cd\/Invoke-YumpooDeploy\.ps1`/u)
  assert.match(report, /`deployment\/windows\/cd\/Update-YumpooEngine\.ps1`/u)
  assert.match(report, /- \[ \] 主机管理员已用本次草稿的安装包升级部署引擎/u)
})

test('moving an engine file out of cd still requires an upgrade', t => {
  const { root, commit, pullRequest, git } = repositoryFixture(t)
  git('mv', 'deployment/windows/cd/Invoke-YumpooDeploy.ps1', 'deployment/windows/legacy-deploy.ps1')
  const head = commit('refactor: 移动引擎入口', {})
  const { report, violations } = evaluatePromotion({ pullRequest: pullRequest(head), root })
  assert.deepEqual(violations, [])
  assert.match(report, /`deployment\/windows\/cd\/Invoke-YumpooDeploy\.ps1`/u)
  assert.match(report, /发布前需要管理员升级主机引擎/u)
})

test('deployment docs and similarly prefixed paths do not trigger an engine warning', t => {
  const { root, commit, pullRequest } = repositoryFixture(t)
  const head = commit('docs(cd): 更新运行手册', {
    'deployment/windows/RUNBOOK.md': '# Runbook\n',
    'deployment/windows/cd-notes.md': '# Notes\n',
  })
  const { report } = evaluatePromotion({ pullRequest: pullRequest(head), root })
  assert.match(report, /本次没有部署引擎改动/u)
  assert.doesNotMatch(report, /- \[ \] 主机管理员/u)
})

test('wrong head, fork origin or wrong base are hard violations', t => {
  const { root, commit, pullRequest } = repositoryFixture(t)
  const head = commit('feat: change', { 'a.txt': 'a' })
  for (const overrides of [
    { head: { ref: 'feature/x', sha: head, repo: { full_name: repository } } },
    { head: { ref: 'dev', sha: head, repo: { full_name: 'someone/fork' } } },
    { base: { ref: 'dev', sha: pullRequest(head).base.sha } },
  ]) assert.equal(evaluatePromotion({ pullRequest: pullRequest(head, overrides), root }).violations.length, 1)
})

test('a breaking migration requires the explicit release label', t => {
  const { root, commit, pullRequest } = repositoryFixture(t)
  const sql = 'ALTER TABLE yumpoo.project DROP COLUMN name;\n'
  const file = 'project/V2__drop_name.sql'
  const head = commit('feat: drop name', {
    [`backend/src/main/resources/db/migration/${file}`]: sql,
    'contracts/migrations/rollback-safety-exceptions.json': JSON.stringify({ schemaVersion: 1, exceptions: [{
      file, sha256: createHash('sha256').update(sql).digest('hex'), classification: 'expand', reason: 'test override keeps the gate green',
    }] }),
  })
  assert.deepEqual(evaluatePromotion({ pullRequest: pullRequest(head), root }).violations, [], 'a reviewed expand override needs no label')
  const breakingHead = commit('feat: another drop', { 'backend/src/main/resources/db/migration/project/V3__drop_id.sql': 'ALTER TABLE yumpoo.project DROP COLUMN id;\n' })
  const unlabeled = evaluatePromotion({ pullRequest: pullRequest(breakingHead), root })
  assert.equal(unlabeled.violations.length, 1)
  assert(unlabeled.violations[0].includes(breakingMigrationLabel))
  assert.deepEqual(evaluatePromotion({ pullRequest: pullRequest(breakingHead, { labels: [{ name: breakingMigrationLabel }] }), root }).violations, [])
})

test('an OpenAPI change reports the exact exception pair a breaking release would need', t => {
  const { root, commit, pullRequest } = repositoryFixture(t)
  const head = commit('feat(api): change', { 'contracts/openapi/yumpoo-v1.yaml': 'openapi: 3.0.3\ninfo: {}\n', 'contracts/events/project.json': '{}' })
  const { report } = evaluatePromotion({ pullRequest: pullRequest(head), root })
  const digest = text => createHash('sha256').update(text).digest('hex')
  assert(report.includes(`"oldSha256":"${digest('openapi: 3.0.3\n')}","newSha256":"${digest('openapi: 3.0.3\ninfo: {}\n')}"`))
  assert.match(report, /contracts\/events\/project\.json/u)
})
