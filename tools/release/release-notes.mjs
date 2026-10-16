import { repository } from './policy.mjs'

const sections = [
  ['feat', '新功能'], ['fix', '问题修复'], ['perf', '性能'], ['refactor', '重构'],
  ['docs', '文档'], ['test', '测试'], ['ci', 'CI 与构建'], ['build', 'CI 与构建'],
]
const migrationFile = /^backend\/src\/main\/resources\/db\/migration\/.+\.sql$/u
// GitHub compare 最多返回 250 个提交与 300 个文件；达到上限时在说明中提示截断。
const compareCommitLimit = 250
const compareFileLimit = 300

export function commitSection(subject) {
  const type = subject.match(/^([a-z]+)(?:\([^)]*\))?!?:\s/u)?.[1]
  return sections.find(([key]) => key === type)?.[1] ?? '其他'
}

export function releaseNotes({ tag, sourceCommit, run, previousTag, comparison, otherDrafts = [] }) {
  const lines = [
    `## ${tag}`,
    '',
    `- 来源提交：\`${sourceCommit}\``,
    `- 验证：[Platform CI #${run.id}（第 ${run.run_attempt} 次运行）](https://github.com/${repository}/actions/runs/${run.id}/attempts/${run.run_attempt})`,
    `- 对比范围：${previousTag ? `[${previousTag}...${sourceCommit.slice(0, 8)}](https://github.com/${repository}/compare/${previousTag}...${sourceCommit})` : '首个 main 发布版本'}`,
    '',
  ]
  const commits = (comparison?.commits ?? []).filter(commit => (commit.parents?.length ?? 1) <= 1)
  const grouped = new Map()
  for (const commit of commits) {
    const subject = String(commit.commit?.message ?? '').split('\n')[0].trim()
    if (!subject) continue
    const title = commitSection(subject)
    if (!grouped.has(title)) grouped.set(title, [])
    grouped.get(title).push(`- ${subject}（\`${commit.sha.slice(0, 8)}\`）`)
  }
  for (const title of [...new Set(sections.map(([, value]) => value)), '其他']) {
    if (grouped.has(title)) lines.push(`### ${title}`, '', ...grouped.get(title), '')
  }
  if (!commits.length) lines.push('### 变更', '', previousTag ? '- 与上一版本相比没有新的非合并提交。' : '- 首个 main 发布版本，不生成逐条变更。', '')
  const files = comparison?.files ?? []
  const migrations = files.filter(file => file.status === 'added' && migrationFile.test(file.filename)).map(file => file.filename.split('/').pop())
  const contracts = files.filter(file => /^contracts\/(?:openapi|events)\//u.test(file.filename)).map(file => file.filename)
  lines.push('### 数据库与契约', '')
  lines.push(migrations.length ? `- 新增迁移：${migrations.map(name => `\`${name}\``).join('、')}。是否只扩展以发布包内 \`windows/release-metadata.json\` 为准。` : '- 没有新增数据库迁移。')
  lines.push(contracts.length ? `- 契约变更：${contracts.map(name => `\`${name}\``).join('、')}。` : '- 没有 API 或事件契约变更。')
  if (commits.length >= compareCommitLimit || files.length >= compareFileLimit) lines.push('- 变更较多，GitHub 对比结果已截断；完整内容见对比链接。')
  if (otherDrafts.length) lines.push('', `> 旧草稿 ${otherDrafts.map(name => `\`${name}\``).join('、')} 已被本草稿取代：请勿发布，确认后可手动删除。`)
  lines.push('', '> 本草稿由 Prepare Release 工作流根据 main 的 CI 产物生成。点击 Publish 即为上线决定，部署会自动开始；不要修改或替换附件。')
  return `${lines.join('\n')}\n`
}
