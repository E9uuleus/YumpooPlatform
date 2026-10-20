import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { exemptPull, noteCategories, noteScopes, parseReleaseNotes } from './release-notes.mjs'

const example = ['## 发布说明', '- 修复｜桌面：托盘左键打开的小窗口不再显示 404。', '- 运维｜服务端：管理员需……（没有可见或运维影响时只写「- 无」）']

/** The PR check: every PR except dependency bumps, back-merges and dev promotions carries valid release notes. */
export function evaluatePullRequest(pull) {
  const exempt = exemptPull(pull)
  if (exempt) return { exempt, errors: [] }
  return { exempt: null, errors: parseReleaseNotes(pull.body).errors }
}

export function policyReport({ exempt, errors }) {
  if (exempt) return `发布说明检查：豁免（${exempt}）。`
  if (!errors.length) return '发布说明检查：通过。'
  return [
    '发布说明检查未通过：',
    ...errors.map(error => `- ${error}`),
    '',
    `PR 正文需要一个「## 发布说明」段，每行 \`- 类别｜范围：描述\`；类别为 ${noteCategories.join('、')}，范围为 ${noteScopes.join('、')}。示例：`,
    '',
    '```',
    ...example,
    '```',
  ].join('\n')
}

/** Workflow summary for drafts whose range still contains PRs or commits without valid release notes. */
export function missingSummary(missing) {
  return [`**${missing.length} 项未提供合格的发布说明**，已在草稿「发布信息」中列出；补写 PR 正文后重跑工作流即可刷新：`,
    ...missing.map(item => `- ${item.label}：${item.reason}`)].join('\n')
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const event = JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'))
  const result = evaluatePullRequest(event.pull_request)
  const report = policyReport(result)
  console.log(report)
  if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${report}\n`)
  if (result.errors.length) process.exitCode = 1
}
