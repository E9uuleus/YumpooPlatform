import { validateDesktopTag } from './desktop-policy.mjs'
import { resolveTagCommit } from './promote-release.mjs'
import { collectReleaseEntries } from './release-notes.mjs'

/**
 * Release notes of every PR since the previous published desktop tag. Entries are filtered to 桌面 when rendered,
 * so a desktop change counts wherever its files live (the quick panel, for example, is a web page).
 */
export async function desktopCollected(github, { sourceCommit, previous }) {
  if (!previous) return { entries: [], pulls: [], dependencies: [], missing: [] }
  const previousCommit = await resolveTagCommit(github, previous.tag_name, validateDesktopTag)
  const comparison = await github.get(`/compare/${previousCommit}...${sourceCommit}`)
  return collectReleaseEntries(github, comparison.commits ?? [])
}
