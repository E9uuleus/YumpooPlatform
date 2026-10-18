import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { releaseBranch, nextReleaseTag, validatePublishedRelease } from './policy.mjs'
import { createGithub, unpackArtifact } from './github.mjs'
import { verifyBuildAttestations } from './attestation.mjs'
import {
  assertOnReleaseBranch, ciWorkflowId, downloadCiPackage, indexAssets, inspectPackage, resolveTagCommit, selectCiAttempt, verifyReleaseAssets,
} from './promote-release.mjs'
import { desktopReleases, latestPublished, readDesktopCheckout } from './desktop-release.mjs'

// Read-only weekly rehearsal of the release chain for the current main head: no draft, upload, tag or deployment.
// A published head is re-verified as deployment would verify it; an unpublished head runs every draft check.
export async function rehearseRelease({ github, env, directory, now = new Date(), unpack = unpackArtifact, attest = verifyBuildAttestations }) {
  const sourceCommit = (await github.get(`/branches/${releaseBranch}`)).commit?.sha
  assert(/^[0-9a-f]{40}$/u.test(sourceCommit), `Cannot resolve ${releaseBranch} head`)
  await assertOnReleaseBranch(github, sourceCommit)
  // A read-only token lists published Releases only; drafts stay invisible and are never needed here.
  const releases = (await github.list('/releases?per_page=100'))
    .filter(release => typeof release.tag_name === 'string' && release.tag_name.startsWith('server-v') && !release.draft)
  const published = releases.find(release => release.target_commitish === sourceCommit)
  if (published) {
    const tag = published.tag_name
    const release = validatePublishedRelease(await github.get(`/releases/tags/${encodeURIComponent(tag)}`), tag)
    assert.equal(await resolveTagCommit(github, tag), sourceCommit, `${tag} no longer points to the ${releaseBranch} head`)
    const { provenance } = await verifyReleaseAssets({ github, release, tag, sourceCommit, env, directory, attest })
    return { mode: 'published', sourceCommit, tag, ciRunId: provenance.ciRunId, ciRunAttempt: provenance.ciRunAttempt }
  }
  const run = await selectCiAttempt(github, await ciWorkflowId(github), sourceCommit)
  const downloaded = await downloadCiPackage(github, run, directory, unpack)
  await inspectPackage(downloaded, sourceCommit)
  await attest(downloaded, run, { env, label: 'rehearsal of CI bytes' })
  const tags = (await github.list('/git/matching-refs/tags/server-v')).map(ref => String(ref.ref).replace(/^refs\/tags\//u, ''))
  const tag = nextReleaseTag([...tags, ...releases.map(release => release.tag_name)], now)
  return { mode: 'draft', sourceCommit, tag, ciRunId: run.id, ciRunAttempt: run.run_attempt }
}

export async function fetchFeedFile(url) {
  const response = await fetch(url, { cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(30_000) })
  assert(response.ok, `Desktop feed returned HTTP ${response.status} for ${url}`)
  return Buffer.from(await response.arrayBuffer())
}

/**
 * The live feed must serve exactly the latest.yml and release.json of the newest published desktop Release:
 * anything else means the engine skipped a publication, the feed was edited by hand or Nginx serves another root.
 */
export async function rehearseDesktopFeed({ github, distribution, fetchFile = fetchFeedFile }) {
  if (!distribution?.update) return { mode: 'skipped', reason: 'the production distribution has no update feed' }
  const latest = latestPublished(await desktopReleases(github))
  if (!latest) return { mode: 'skipped', reason: 'no desktop Release is published yet' }
  const names = ['latest.yml', 'release.json']
  const assets = indexAssets(await github.list(`/releases/${latest.id}/assets?per_page=100`), names)
  for (const name of names) {
    assert(assets.has(name), `${latest.tag_name} has no ${name} asset`)
    const served = await fetchFile(new URL(name, distribution.update.feed).href)
    assert.equal(`sha256:${createHash('sha256').update(served).digest('hex')}`, assets.get(name).digest,
      `The desktop feed serves a ${name} that differs from ${latest.tag_name}; check the last Desktop Release run and the host feed`)
  }
  return { mode: 'feed', tag: latest.tag_name, feed: distribution.update.feed }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-rehearsal-'))
  try {
    const github = createGithub()
    const result = await rehearseRelease({ github, env: process.env, directory })
    const desktop = await rehearseDesktopFeed({ github, distribution: readDesktopCheckout().distribution })
    const summary = [
      result.mode === 'published'
        ? `Release rehearsal passed: ${releaseBranch} head ${result.sourceCommit} is published as **${result.tag}** and still verifies (CI ${result.ciRunId}/${result.ciRunAttempt}, SBOM, attestation).`
        : `Release rehearsal passed: a draft for ${releaseBranch} head ${result.sourceCommit} would be **${result.tag}** from CI ${result.ciRunId}/${result.ciRunAttempt}; nothing was created.`,
      desktop.mode === 'feed'
        ? `Desktop feed ${desktop.feed} serves the latest.yml and release.json of **${desktop.tag}**.`
        : `Desktop feed check skipped: ${desktop.reason}.`,
    ].join('\n')
    console.log(summary)
    if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${summary}\n`)
  } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  } finally {
    fs.rmSync(directory, { recursive: true, force: true })
  }
}
