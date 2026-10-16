import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { releaseBranch } from './policy.mjs'
import { createGithub } from './github.mjs'

// A dev→main merge commit adds no tree change of its own, so only hotfix content shows up in dev...main.
export function mainHasChangesMissingFromDev(root, run = spawnSync) {
  const result = run('git', ['diff', '--quiet', `origin/dev...origin/${releaseBranch}`], { cwd: root, encoding: 'utf8' })
  if (result.status === 0) return false
  if (result.status === 1) return true
  throw new Error(`git diff failed: ${(result.stderr || '').trim()}`)
}

export function currentMainCommit(root, run = spawnSync) {
  const result = run('git', ['rev-parse', '--verify', `origin/${releaseBranch}^{commit}`], { cwd: root, encoding: 'utf8' })
  const commit = String(result.stdout || '').trim()
  if (result.status !== 0 || !/^[0-9a-f]{40}$/u.test(commit)) throw new Error(`Cannot resolve origin/${releaseBranch}: ${(result.stderr || '').trim()}`)
  return commit
}

// Runs after every push to main or dev, so a back-merge that left later hotfixes behind is reconciled again.
export async function openBackMerge({ github, root, mainSha, log = console.log, hasChanges = mainHasChangesMissingFromDev }) {
  if (!/^[0-9a-f]{40}$/u.test(String(mainSha))) throw new Error('Invalid main commit')
  if (!hasChanges(root)) {
    log(`${releaseBranch} has no changes missing from dev; no back-merge needed`)
    return null
  }
  const open = (await github.list('/pulls?state=open&base=dev&per_page=100')).filter(pull => String(pull.head?.ref).startsWith('backmerge/'))
  for (const pull of open) {
    const order = await github.get(`/compare/${mainSha}...${pull.head.sha}?per_page=1`)
    if (['identical', 'ahead'].includes(order.status)) {
      log(`Back-merge PR #${pull.number} already contains ${mainSha}`)
      return pull
    }
  }
  for (const pull of open) {
    // Only a branch that is still an ancestor of main can move forward without rewriting anyone's work.
    const order = await github.get(`/compare/${pull.head.sha}...${mainSha}?per_page=1`)
    if (order.status !== 'ahead') continue
    await github.send('PATCH', `/git/refs/heads/${pull.head.ref}`, { sha: mainSha, force: false })
    log(`Fast-forwarded back-merge PR #${pull.number} to ${mainSha}`)
    return pull
  }
  // A dedicated branch keeps main untouched when the PR needs "Update branch" against a moving dev.
  const branch = `backmerge/${mainSha.slice(0, 12)}`
  await github.send('POST', '/git/refs', { ref: `refs/heads/${branch}`, sha: mainSha })
  const pull = await github.send('POST', '/pulls', {
    title: `chore(release): 回合并 ${releaseBranch} 到 dev（${mainSha.slice(0, 8)}）`,
    head: branch, base: 'dev',
    body: [
      `${releaseBranch} 上有 dev 尚未包含的改动（通常是热修复），需要合回 dev，避免下一次晋升丢失或冲突。`,
      '',
      '- **必须使用「Create a merge commit」合并**：squash 不会让 dev 包含这些提交，本工作流会再次开 PR。',
      '- 本 PR 由工作流创建，GitHub 不会为它自动触发 CI；head 提交沿用 main 推送 CI 的检查结果。若提示分支落后，点击 Update branch 会以你的身份触发 CI。',
      '- 之后若 main 再有热修复，本工作流会把这个分支快进到最新 main；分支已被更新而无法快进时，会另开一个回合并 PR。',
    ].join('\n'),
  })
  log(`Opened back-merge PR #${pull.number}`)
  return pull
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
    const pull = await openBackMerge({ github: createGithub(), root, mainSha: currentMainCommit(root) })
    if (pull && process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `Back-merge PR: ${pull.html_url}\n`)
  } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  }
}
