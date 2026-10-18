import { validateDesktopTag } from './desktop-policy.mjs'
import { resolveTagCommit } from './promote-release.mjs'

export const desktopPaths = ['desktop', 'packages/preload-contract']
const maxNotes = 20

/**
 * Commits since the previous published desktop tag that touched the shell or its preload contract, oldest first.
 * The path-filtered history is read one page deep; the comparison bounds it to this release's range.
 */
export async function desktopChanges(github, { sourceCommit, previous }) {
  if (!previous) return []
  const previousCommit = await resolveTagCommit(github, previous.tag_name, validateDesktopTag)
  const comparison = await github.get(`/compare/${previousCommit}...${sourceCommit}`)
  const order = new Map((comparison.commits ?? []).map((commit, index) => [commit.sha, index]))
  const touched = new Map()
  for (const scope of desktopPaths) {
    for (const commit of await github.get(`/commits?sha=${sourceCommit}&path=${encodeURIComponent(scope)}&per_page=100`)) {
      if (order.has(commit.sha) && (commit.parents?.length ?? 1) < 2) touched.set(commit.sha, commit)
    }
  }
  return [...touched.values()].sort((left, right) => order.get(left.sha) - order.get(right.sha))
    .map(commit => ({ sha: commit.sha, title: String(commit.commit?.message ?? '').split('\n')[0].trim() }))
}

export function desktopNoteTitles(changes, previous) {
  if (changes.length) return changes.slice(0, maxNotes).map(change => change.title)
  return [previous ? '桌面客户端维护更新' : '首个安装版：按用户安装，支持自动更新、诊断与修复和安全模式']
}

export function desktopReleaseBody({ tag, version, sourceCommit, run, previous, changes }) {
  const lines = [
    `## Yumpoo Desktop ${version}`,
    '',
    `- 来源提交：\`${sourceCommit}\``,
    `- CI：[运行 ${run.id} attempt ${run.run_attempt}](${run.html_url ?? `https://github.com/E9uuleus/YumpooPlatform/actions/runs/${run.id}/attempts/${run.run_attempt}`})`,
    `- 上一版本：${previous ? previous.tag_name : '无（首个经发布渠道分发的版本）'}`,
    '',
    '### 桌面相关改动',
    '',
    ...(changes.length
      ? changes.slice(0, maxNotes).map(change => `- ${change.title} (\`${change.sha.slice(0, 8)}\`)`)
      : ['- 本范围内没有改动 `desktop/` 或 `packages/preload-contract` 的提交。']),
    ...(changes.length > maxNotes ? [`- 另有 ${changes.length - maxNotes} 个提交，见比较视图。`] : []),
    '',
    '### 发布前',
    '',
    `- 下载本草稿的 \`YumpooDesktop-Setup-${version}-x64.exe\`，在 2–3 台机器上试装并核对版本。`,
    `- 点击 Publish 即为桌面上线决定；发布后 Desktop Release 校验 ${tag} 并写入更新源，客户端在 4 小时内或重启后自动更新。`,
    '- 桌面不能回退，只能用更高版本前滚。',
  ]
  return `${lines.join('\n')}\n`
}
