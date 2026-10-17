import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { repository, releaseBranch } from './policy.mjs'
import { commitSection } from './release-notes.mjs'
import { createGithub } from './github.mjs'
import { evaluateNewMigrations } from '../ci/migration-policy.mjs'
import { exactBreakingChangeException, readBreakingChangeExceptions } from '../openapi/breaking-change-policy.mjs'

export const breakingMigrationLabel = 'release:breaking-migration'
export const commentMarker = '<!-- yumpoo-main-promotion-policy -->'
const openapiPath = 'contracts/openapi/yumpoo-v1.yaml'

export function allowedPromotionHead(head) {
  return head === 'dev' || /^hotfix\/[a-z0-9][a-z0-9._-]{0,63}$/u.test(head)
}

function git(root, args) {
  const result = spawnSync('git', args, { cwd: root, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 })
  if (result.status !== 0) throw new Error(`git ${args[0]} failed: ${(result.stderr || '').trim()}`)
  return result.stdout
}

const digest = text => createHash('sha256').update(text).digest('hex')

// Gathers the release delta of a PR into main; only hard rules become violations, the rest is a reviewer checklist.
export function evaluatePromotion({ pullRequest, root }) {
  const violations = []
  const base = pullRequest.base?.sha
  const head = pullRequest.head?.sha
  assert(/^[0-9a-f]{40}$/u.test(base) && /^[0-9a-f]{40}$/u.test(head), 'Pull request event is missing base/head commits')
  if (pullRequest.base?.ref !== releaseBranch) violations.push(`目标分支必须是 ${releaseBranch}`)
  if (pullRequest.head?.repo?.full_name !== repository) violations.push('晋升 PR 必须来自本仓库')
  if (!allowedPromotionHead(pullRequest.head?.ref)) violations.push(`只允许从 dev 或 hotfix/<名称> 晋升到 ${releaseBranch}，当前为 ${pullRequest.head?.ref}`)
  const labels = new Set((pullRequest.labels ?? []).map(label => label.name))

  const commits = git(root, ['log', '--no-merges', '--format=%H%x09%s', `${base}..${head}`]).split('\n').filter(Boolean)
    .map(line => ({ sha: line.slice(0, 40), subject: line.slice(41) }))
  const { added, violations: migrationViolations } = evaluateNewMigrations(root, base)
  const breaking = added.filter(item => item.classification === 'breaking')
  if (breaking.length && !labels.has(breakingMigrationLabel)) {
    violations.push(`本次发布含非只扩展迁移（${breaking.map(item => item.file).join('、')}），该版本将无法快速回退：确认已完成恢复演练后添加标签 ${breakingMigrationLabel}`)
  }
  const oldSha256 = digest(git(root, ['show', `${base}:${openapiPath}`]))
  const newSha256 = digest(fs.readFileSync(path.join(root, openapiPath)))
  const openapiChanged = oldSha256 !== newSha256
  const openapiException = openapiChanged ? exactBreakingChangeException(readBreakingChangeExceptions(root), oldSha256, newSha256) : null
  const eventFiles = git(root, ['diff', '--name-only', `${base}...${head}`, '--', 'contracts/events']).split('\n').filter(Boolean)
  const engineFiles = git(root, ['diff', '--name-only', '--no-renames', `${base}...${head}`, '--', 'deployment/windows/cd/']).split('\n').filter(Boolean)

  const grouped = new Map()
  for (const commit of commits) {
    const title = commitSection(commit.subject)
    if (!grouped.has(title)) grouped.set(title, [])
    grouped.get(title).push(`- ${commit.subject}（\`${commit.sha.slice(0, 8)}\`）`)
  }
  const lines = [
    commentMarker,
    `## 晋升到 ${releaseBranch} 的检查`,
    '',
    violations.length ? `**未通过：**\n${violations.map(item => `- ${item}`).join('\n')}` : '**硬性规则全部通过。**',
    '',
    `### 本次包含的提交（${commits.length} 个，不含合并提交）`,
    '',
  ]
  for (const [title, items] of grouped) lines.push(`**${title}**`, ...items, '')
  if (!commits.length) lines.push('- 没有新的非合并提交。', '')
  lines.push('### 数据库迁移', '')
  if (!added.length) lines.push('- 没有新增迁移。')
  for (const item of added) lines.push(`- \`${item.file}\`：${item.classification === 'expand' ? '只扩展（可快速回退）' : '**破坏性（不可快速回退）**'}`)
  if (migrationViolations.length) lines.push('- 迁移回退安全检查存在未登记的破坏性迁移，CI 的契约阶段会失败。')
  lines.push('', '### 接口与事件契约', '')
  if (!openapiChanged) lines.push('- OpenAPI 没有变化。')
  else if (openapiException) lines.push(`- OpenAPI 有变化，已有精确例外 \`${openapiException.id}\` 覆盖这一对版本。`)
  else lines.push(`- OpenAPI 有变化。若 CI 契约兼容检查失败，说明相对线上版本存在破坏性变更：请先在 dev 上登记发布级例外 \`{"oldSha256":"${oldSha256}","newSha256":"${newSha256}"}\`，并确认桌面端兼容。`)
  lines.push(eventFiles.length ? `- 事件契约变更：${eventFiles.map(file => `\`${file}\``).join('、')}` : '- 事件契约没有变化。')
  lines.push('', '### 部署引擎', '')
  if (engineFiles.length) {
    lines.push(
      '**本次包含部署引擎改动，发布前需要管理员升级主机引擎。**',
      ...engineFiles.map(file => `- \`${file}\``),
      '',
      '普通发布不会更新主机引擎。草稿就绪后，用该草稿的服务器 ZIP 在主机运行 `Update-YumpooEngine.ps1`，核对来源 commit 后再点击 Publish。',
    )
  } else lines.push('- 本次没有部署引擎改动。')
  lines.push(
    '', '### 发布前人工确认', '',
    ...(engineFiles.length ? ['- [ ] 主机管理员已用本次草稿的安装包升级部署引擎，并核对来源 commit'] : []),
    '- [ ] 未完成的功能已隐藏，或尚未合入 dev',
    '- [ ] 发布后 10 分钟观察窗口及其后 30 分钟有人值守',
    '- [ ] 避开业务高峰、周五晚间和节假日前',
    '- [ ] 上一版本运行稳定，没有未关闭的事故',
    '',
    `> 合并方式必须是「Create a merge commit」。合并后 main 的 CI 通过会自动生成草稿 Release，点击 Publish 才会上线。`,
  )
  return { violations, report: `${lines.join('\n')}\n`, breaking }
}

async function upsertComment(github, number, body) {
  const comments = await github.list(`/issues/${number}/comments?per_page=100`)
  const existing = comments.find(comment => comment.user?.login === 'github-actions[bot]' && String(comment.body).startsWith(commentMarker))
  if (existing) await github.send('PATCH', `/issues/comments/${existing.id}`, { body })
  else await github.send('POST', `/issues/${number}/comments`, { body })
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    const event = JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'))
    const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
    const { violations, report } = evaluatePromotion({ pullRequest: event.pull_request, root })
    if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, report)
    try { await upsertComment(createGithub(), event.pull_request.number, report) } catch (error) { console.warn(`Could not update the PR comment: ${error.message}`) }
    if (violations.length) throw new Error(`晋升规则未通过：\n${violations.join('\n')}`)
    console.log('晋升规则通过')
  } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  }
}
