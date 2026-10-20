import { repository } from './policy.mjs'

// Commit subjects still group the promotion PR comment; Release bodies come from PR release notes instead.
const sections = [
  ['feat', '新功能'], ['fix', '问题修复'], ['perf', '性能'], ['refactor', '重构'],
  ['docs', '文档'], ['test', '测试'], ['ci', 'CI 与构建'], ['build', 'CI 与构建'],
]
const migrationPrefix = 'backend/src/main/resources/db/migration/'
const migrationFile = /^backend\/src\/main\/resources\/db\/migration\/.+\.sql$/u
// GitHub compare 最多返回 250 个提交与 300 个文件；达到上限时在说明中提示截断。
const compareCommitLimit = 250
const compareFileLimit = 300

export function commitSection(subject) {
  const type = subject.match(/^([a-z]+)(?:\([^)]*\))?!?:\s/u)?.[1]
  return sections.find(([key]) => key === type)?.[1] ?? '其他'
}

/** 「运维」 goes to the upgrade impact for administrators; the other categories are what users see. */
export const noteCategories = ['新增', '改进', '修复', '安全', '运维']
export const userCategories = ['新增', '改进', '修复', '安全']
export const noteScopes = ['Web', '桌面', '服务端']
const maxEntries = 10
const forbidden = [
  [/(?<![A-Za-z0-9])(?!(?:UTF|SHA|ISO|TLS|HTTP|CVE|GBK)-)[A-Z][A-Z0-9]{0,7}-\d+(?:\s*[～~–-]\s*\d+)?(?![A-Za-z0-9])/u, '步骤编号'],
  [/^[a-z]+(?:\([^)]*\))?!?:/u, 'type(scope): 前缀'],
  [/(?<![0-9A-Za-z])(?=[0-9a-f]*[a-f])(?=[0-9a-f]*\d)[0-9a-f]{7,40}(?![0-9A-Za-z])/u, 'commit SHA'],
  [/#\d+/u, 'PR 引用（生成时自动附加）'],
]

/**
 * Parses the single `## 发布说明` section of a PR body: one `- 类别｜范围：描述` per line, or `- 无` alone.
 * HTML comments (the template's guidance) are ignored; anything else in the section is an error.
 */
export function parseReleaseNotes(body) {
  let content = String(body ?? '')
  let previous
  do {
    previous = content
    content = content.replace(/<!--[\s\S]*?-->/gu, '')
  } while (content !== previous)
  const lines = content.split(/\r?\n/u)
  const starts = lines.flatMap((line, index) => /^##\s+发布说明\s*$/u.test(line.trim()) ? [index] : [])
  if (starts.length !== 1) return { entries: [], none: false, errors: [starts.length ? '「## 发布说明」段只能有一个' : '缺少「## 发布说明」段'] }
  const entries = []
  const errors = []
  let none = false
  for (const raw of lines.slice(starts[0] + 1)) {
    const line = raw.trim()
    if (/^#{1,2}\s/u.test(line)) break
    if (!line) continue
    const item = line.match(/^[-*]\s+(.+)$/u)?.[1].trim()
    if (!item) { errors.push(`不是条目：${line}`); continue }
    if (item === '无') { none = true; continue }
    const match = item.match(/^(\S+?)\s*[｜|]\s*(\S+?)\s*[：:]\s*(.+)$/u)
    if (!match) { errors.push(`格式应为「- 类别｜范围：描述」：${item}`); continue }
    const [, category, scope, text] = match
    const length = [...text].length
    const leak = forbidden.find(([pattern]) => pattern.test(text))?.[1]
    const problem = !noteCategories.includes(category) ? `类别只能是 ${noteCategories.join('、')}`
      : !noteScopes.includes(scope) ? `范围只能是 ${noteScopes.join('、')}`
        : length < 5 || length > 120 ? '描述长度须为 5–120 字'
          : leak && `描述不能包含${leak}`
    if (problem) errors.push(`${problem}：${item}`)
    else entries.push({ category, scope, text })
  }
  if (none && (entries.length || errors.length)) errors.push('「- 无」必须是唯一条目')
  if (!none && !entries.length && !errors.length) errors.push('「发布说明」段为空；没有可见或运维影响时写「- 无」')
  if (entries.length > maxEntries) errors.push(`每个 PR 最多 ${maxEntries} 条发布说明`)
  return { entries, none, errors }
}

/** PRs whose content is described elsewhere: dependency bumps, back-merges of main and dev promotions. */
export function exemptPull(pull) {
  if (pull.user?.login === 'dependabot[bot]') return 'dependabot'
  if (String(pull.head?.ref ?? '').startsWith('backmerge/')) return 'backmerge'
  if (pull.base?.ref === 'main' && pull.head?.ref === 'dev') return 'promotion'
  return null
}

/**
 * Release notes of every PR behind the non-merge commits of a range, in commit order and once per PR. A commit with
 * no PR, or a PR without valid notes, is reported as missing rather than failing the draft: the PR check blocks it
 * at merge time, so only older PRs and direct commits reach here.
 */
export async function collectReleaseEntries(github, commits) {
  const seen = new Set()
  const result = { entries: [], pulls: [], dependencies: [], missing: [] }
  for (const commit of commits.filter(item => (item.parents?.length ?? 1) <= 1)) {
    const subject = String(commit.commit?.message ?? '').split('\n')[0].trim()
    const candidates = (await github.get(`/commits/${commit.sha}/pulls`)).filter(pull => pull.merged_at)
    const pull = candidates.find(item => !exemptPull(item)) ?? candidates[0]
    if (!pull) { result.missing.push({ label: `\`${commit.sha.slice(0, 8)}\` ${subject}`, reason: '没有关联的 PR' }); continue }
    if (seen.has(pull.number)) continue
    seen.add(pull.number)
    result.pulls.push(pull.number)
    const exempt = exemptPull(pull)
    if (exempt === 'dependabot') { result.dependencies.push(pull.number); continue }
    if (exempt) { result.missing.push({ label: `#${pull.number} ${pull.title}`, reason: '提交只经晋升或回合并 PR 进入' }); continue }
    const parsed = parseReleaseNotes(pull.body)
    if (parsed.errors.length) result.missing.push({ label: `#${pull.number} ${pull.title}`, reason: parsed.errors[0] })
    else result.entries.push(...parsed.entries.map(entry => ({ ...entry, pull: pull.number })))
  }
  return result
}

const ciLink = run => `[Platform CI #${run.id}（第 ${run.run_attempt} 次运行）](https://github.com/${repository}/actions/runs/${run.id}/attempts/${run.run_attempt})`
const compareLink = (previousTag, sourceCommit) => `[${previousTag}...${sourceCommit.slice(0, 8)}](https://github.com/${repository}/compare/${previousTag}...${sourceCommit})`
const entryLine = entry => `- ${entry.scope}｜${entry.text}（#${entry.pull}）`

function summaryLine(entries, admin) {
  const counts = userCategories.map(category => `${category} ${entries.filter(entry => entry.category === category).length}`)
  return `${counts.join(' · ')} · 需要管理员操作：${admin.length ? '是' : '否'}`
}

function updateLines(entries, empty) {
  const lines = ['### 更新内容', '']
  for (const category of userCategories) {
    const items = entries.filter(entry => entry.category === category)
    if (items.length) lines.push(`**${category}**`, '', ...items.map(entryLine), '')
  }
  if (lines.length === 2) lines.push(`- ${empty}`, '')
  return lines
}

function infoLines({ sourceCommit, previousTag, firstRelease, run, collected, truncated }) {
  const { pulls, dependencies, missing } = collected
  return [
    '### 发布信息', '',
    `- 来源提交：\`${sourceCommit}\``,
    `- 上一版本：${previousTag ?? firstRelease}`,
    ...(previousTag ? [`- 对比范围：${compareLink(previousTag, sourceCommit)}`] : []),
    `- 验证：${ciLink(run)}`,
    `- 包含 PR：${pulls.length ? pulls.map(number => `#${number}`).join('、') : '无'}`,
    ...(dependencies.length ? [`- 依赖更新：${dependencies.length} 项（${dependencies.map(number => `#${number}`).join('、')}）`] : []),
    `- 未提供发布说明：${missing.length ? `${missing.length} 项，补写 PR 正文后重跑工作流即可刷新本说明` : '无'}`,
    ...missing.map(item => `  - ${item.label}：${item.reason}`),
    ...(truncated ? ['- 变更较多，GitHub 对比结果已截断；完整内容见对比链接。'] : []),
    '',
  ]
}

function migrationLines(files, inventory) {
  const added = files.filter(file => file.status === 'added' && migrationFile.test(file.filename))
    .map(file => file.filename.slice(migrationPrefix.length))
  const classified = added.map(file => ({ file, classification: inventory.find(item => item.file === file)?.classification }))
  const label = { expand: '只扩展，可快速回退', breaking: '**破坏性，不可快速回退**' }
  return {
    breaking: classified.some(item => item.classification === 'breaking'),
    line: classified.length
      ? `- 数据库迁移：${classified.map(item => `\`${item.file.split('/').pop()}\`（${label[item.classification] ?? '未能分类，以发布包内 `windows/release-metadata.json` 为准'}）`).join('、')}`
      : '- 数据库迁移：无',
  }
}

const notices = ({ supersededDrafts = [], otherDrafts = [] }) => [
  ...(supersededDrafts.length ? [`> 旧草稿 ${supersededDrafts.map(name => `\`${name}\``).join('、')} 已被本草稿取代：请勿发布，确认后可手动删除。`, ''] : []),
  ...(otherDrafts.length ? [`> 另有草稿 ${otherDrafts.map(name => `\`${name}\``).join('、')} 的源码不早于本草稿：发布前请核对来源 SHA，只发布需要上线的那一个。`, ''] : []),
]

/** Server draft body: 更新内容 → 升级影响 → 发布信息. `inventory` classifies migrations from the released checkout. */
export function releaseNotes({ tag, sourceCommit, run, previousTag, comparison, collected, inventory = [], supersededDrafts = [], otherDrafts = [] }) {
  const files = comparison?.files ?? []
  const admin = collected.entries.filter(entry => entry.category === '运维')
  const migrations = migrationLines(files, inventory)
  const contracts = files.filter(file => /^contracts\/(?:openapi|events)\//u.test(file.filename)).map(file => `\`${file.filename}\``)
  const engine = files.filter(file => file.filename.startsWith('deployment/windows/cd/')).map(file => `\`${file.filename}\``)
  const truncated = (comparison?.commits?.length ?? 0) >= compareCommitLimit || files.length >= compareFileLimit
  const lines = [
    `## 服务端 ${tag}`, '',
    summaryLine(collected.entries, [...admin, ...engine]), '',
    ...updateLines(collected.entries, previousTag ? '本版本没有面向使用者的变更。' : '首个 main 发布版本，不生成逐条变更。'),
    '### 升级影响', '',
    migrations.line,
    `- API / 事件契约：${contracts.length ? `${contracts.join('、')} 有变化` : '无变更'}`,
    engine.length
      ? `- 部署引擎：**有变化，Publish 前管理员需用本草稿的服务器 ZIP 在主机运行 \`Update-YumpooEngine.ps1\`**（${engine.join('、')}）`
      : '- 部署引擎：无变化',
    `- 管理员操作：${admin.length ? '' : '无'}`,
    ...admin.map(entry => `  ${entryLine(entry)}`),
    `- 回退：${!previousTag ? '首个版本，没有可回退的版本' : migrations.breaking ? '含破坏性迁移，不能用 Server Rollback 快速回退' : `可用 Server Rollback 回退到 \`${previousTag}\``}`,
    '',
    ...infoLines({ sourceCommit, previousTag, firstRelease: '无（首个 main 发布版本）', run, collected, truncated }),
    ...notices({ supersededDrafts, otherDrafts }),
    '> 本草稿由 Prepare Release 工作流根据 main 的 CI 产物生成，条目来自各 PR 的「发布说明」段。点击 Publish 即为上线决定，部署会自动开始；不要修改或替换附件。',
  ]
  return `${lines.join('\n')}\n`
}

export const desktopEntries = collected => ({ ...collected, entries: collected.entries.filter(entry => entry.scope === '桌面') })

/** Desktop draft body: the same layout limited to 桌面 entries, plus the test-install checklist. */
export function desktopReleaseBody({ tag, version, sourceCommit, run, previous, collected }) {
  const desktop = desktopEntries(collected)
  const admin = desktop.entries.filter(entry => entry.category === '运维')
  const lines = [
    `## 桌面端 ${version}`, '',
    summaryLine(desktop.entries, admin), '',
    ...updateLines(desktop.entries, previous ? '本版本没有面向使用者的桌面变更。' : '首个安装版：按用户安装，支持自动更新、诊断与修复和安全模式。'),
    '### 升级影响', '',
    `- 管理员操作：${admin.length ? '' : '无'}`,
    ...admin.map(entry => `  ${entryLine(entry)}`),
    '- 回退：桌面不能回退，只能用更高版本前滚。',
    '',
    ...infoLines({ sourceCommit, previousTag: previous?.tag_name, firstRelease: '无（首个经发布渠道分发的版本）', run, collected, truncated: false }),
    '### 发布前试装', '',
    `- 下载本草稿的 \`YumpooDesktop-Setup-${version}-x64.exe\`，在 2–3 台机器上试装并核对版本。`,
    `- 点击 Publish 即为桌面上线决定；发布后 Desktop Release 校验 ${tag} 并写入更新源，客户端在 4 小时内或重启后自动更新。`,
    '- 桌面不能回退，只能用更高版本前滚。',
  ]
  return `${lines.join('\n')}\n`
}

/** What the download page and the settings card show: user-facing 桌面 entries only, never the operator ones. */
export function desktopNotes(collected, previous) {
  const notes = desktopEntries(collected).entries.filter(entry => userCategories.includes(entry.category))
    .map(entry => `${entry.category}：${entry.text}`)
  if (notes.length) return notes.slice(0, 20)
  return [previous ? '桌面客户端维护更新' : '首个安装版：按用户安装，支持自动更新、诊断与修复和安全模式']
}
