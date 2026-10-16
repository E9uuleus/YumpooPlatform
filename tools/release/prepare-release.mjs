import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import {
  repository, releaseAssetNames, expectedArtifactName, nextReleaseTag, validateTag, validateProvenance, validateAssetDigest,
} from './policy.mjs'
import { createGithub, fileDigest, readJson, unpackArtifact } from './github.mjs'
import {
  assertOnReleaseBranch, ciWorkflowId, downloadAsset, downloadCiPackage, indexAssets, inspectPackage, resolveTagCommit, selectCiAttempt, verifyCiAttempt,
} from './promote-release.mjs'
import { releaseNotes } from './release-notes.mjs'
import { notifyWeCom } from './notify.mjs'

const serverRelease = release => typeof release.tag_name === 'string' && release.tag_name.startsWith('server-v')

// Builds the draft that a person publishes: tag, notes and every verified asset are fixed before publishing,
// because an immutable Release can no longer change its assets afterwards.
export async function prepareDraft({ github, sourceCommit, env, directory, now = new Date(), unpack = unpackArtifact, log = console.log }) {
  assert(/^[0-9a-f]{40}$/u.test(sourceCommit), 'Invalid source commit')
  assert.equal(env.GITHUB_REPOSITORY, repository, 'Unexpected repository')
  assert(['workflow_run', 'workflow_dispatch'].includes(env.GITHUB_EVENT_NAME), 'Unsupported draft trigger')
  await assertOnReleaseBranch(github, sourceCommit)
  const workflowId = await ciWorkflowId(github)
  const releases = (await github.list('/releases?per_page=100')).filter(serverRelease)
  const published = releases.find(release => !release.draft && release.target_commitish === sourceCommit)
  if (published) {
    log(`${sourceCommit} is already published as ${published.tag_name}; no draft is needed`)
    return { tag: published.tag_name, releaseId: published.id, url: published.html_url, created: false, published: true }
  }
  const previous = releases.filter(release => !release.draft && release.published_at)
    .sort((left, right) => Date.parse(right.published_at) - Date.parse(left.published_at))[0]
  if (previous) {
    // A draft for a commit older than what is already live could only ever be a downgrade candidate.
    const latest = await resolveTagCommit(github, previous.tag_name)
    const order = await github.get(`/compare/${sourceCommit}...${latest}?per_page=1`)
    assert(order.status !== 'ahead', `${previous.tag_name} already published a newer commit; refusing a draft for older ${sourceCommit}`)
  }
  let draft = releases.find(release => release.draft && release.target_commitish === sourceCommit)
  // Drafts are never deleted automatically: GitHub deletes by ID unconditionally, so a draft published between
  // listing and deleting would lose its immutable Release. Other drafts are only reported for manual cleanup.
  // Only a draft whose source is an ancestor of this commit is superseded; any other draft is reported separately
  // because the most recently generated draft is not necessarily the newest source.
  const supersededDrafts = []
  const otherDrafts = []
  for (const other of releases.filter(release => release.draft && release !== draft)) {
    const ancestor = /^[0-9a-f]{40}$/u.test(other.target_commitish)
      && (await github.get(`/compare/${other.target_commitish}...${sourceCommit}?per_page=1`)).status === 'ahead'
    ;(ancestor ? supersededDrafts : otherDrafts).push(other.tag_name)
  }
  let pinned
  const created = !draft
  if (draft) {
    const existing = indexAssets(await github.list(`/releases/${draft.id}/assets?per_page=100`))
    if (existing.has('provenance.json')) {
      const pinDirectory = fs.mkdtempSync(path.join(directory, 'pin-'))
      pinned = validateProvenance(readJson(await downloadAsset(github, existing.get('provenance.json'), pinDirectory)), { tag: draft.tag_name, sourceCommit })
    }
  }
  // An interrupted earlier attempt pinned its CI attempt in provenance.json; never switch to another attempt.
  const run = pinned
    ? await verifyCiAttempt(github, workflowId, sourceCommit, pinned.ciRunId, pinned.ciRunAttempt)
    : await selectCiAttempt(github, workflowId, sourceCommit)
  if (!draft) {
    const tags = (await github.list('/git/matching-refs/tags/server-v')).map(ref => String(ref.ref).replace(/^refs\/tags\//u, ''))
    const tag = nextReleaseTag([...tags, ...releases.map(release => release.tag_name)], now)
    const comparison = previous ? await github.get(`/compare/${encodeURIComponent(previous.tag_name)}...${sourceCommit}`) : null
    draft = await github.send('POST', '/releases', {
      tag_name: tag, target_commitish: sourceCommit, name: tag, draft: true,
      body: releaseNotes({ tag, sourceCommit, run, previousTag: previous?.tag_name, comparison, supersededDrafts, otherDrafts }),
    })
  }
  assert.equal(draft.draft, true, 'Prepared Release must stay a draft')
  assert.equal(draft.target_commitish, sourceCommit, 'Draft targets another commit')
  const tag = validateTag(draft.tag_name)

  const assets = indexAssets(await github.list(`/releases/${draft.id}/assets?per_page=100`))
  const retained = path.join(directory, 'release')
  fs.mkdirSync(retained, { recursive: true })
  let provenance = pinned
  if (!releaseAssetNames.every(name => assets.has(name))) {
    const downloaded = await downloadCiPackage(github, run, directory, unpack)
    const { digest, bytes } = await inspectPackage(downloaded, sourceCommit, provenance)
    provenance ??= validateProvenance({
      schemaVersion: 1, repository, tag, sourceCommit, ciRunId: run.id, ciRunAttempt: run.run_attempt,
      artifactName: expectedArtifactName(sourceCommit, run.run_attempt), packageSha256: digest, packageBytes: bytes,
    }, { tag, sourceCommit })
    fs.writeFileSync(path.join(retained, 'provenance.json'), `${JSON.stringify(provenance, null, 2)}\n`, { mode: 0o600 })
    for (const name of releaseAssetNames.filter(name => name !== 'provenance.json')) {
      fs.copyFileSync(path.join(downloaded, name), path.join(retained, name))
    }
    // Existing assets are verified first and never replaced; provenance goes up first to pin the CI attempt.
    for (const [name, asset] of assets) {
      if (name === 'provenance.json') continue
      const file = path.join(retained, name)
      validateAssetDigest(asset, await fileDigest(file), fs.statSync(file).size)
    }
    for (const name of ['provenance.json', ...releaseAssetNames.filter(name => name !== 'provenance.json')]) {
      if (!assets.has(name)) await github.uploadAsset(draft.id, path.join(retained, name))
    }
  }
  const final = indexAssets(await github.list(`/releases/${draft.id}/assets?per_page=100`))
  assert(releaseAssetNames.every(name => final.has(name)), 'Draft assets did not persist')
  const verified = fs.mkdtempSync(path.join(directory, 'verified-'))
  provenance = validateProvenance(readJson(await downloadAsset(github, final.get('provenance.json'), verified)), { tag, sourceCommit })
  for (const name of releaseAssetNames.filter(name => name !== 'provenance.json')) await downloadAsset(github, final.get(name), verified)
  await inspectPackage(verified, sourceCommit, provenance)
  return { tag, releaseId: draft.id, url: draft.html_url, created, published: false, provenance, supersededDrafts, otherDrafts }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-draft-'))
  try {
    const result = await prepareDraft({ github: createGithub(), sourceCommit: process.env.SOURCE_COMMIT, env: process.env, directory })
    if (!result.published) {
      const stale = result.supersededDrafts.length ? ` Older drafts ${result.supersededDrafts.join(', ')} are superseded: do not publish them; delete them manually.` : ''
      const other = result.otherDrafts.length ? ` Drafts ${result.otherDrafts.join(', ')} are not older than this commit: compare source SHAs before publishing.` : ''
      const summary = `Draft **${result.tag}** is ready for ${process.env.SOURCE_COMMIT}: ${result.url}.${stale}${other}`
      console.log(summary)
      if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${summary}\n`)
      await notifyWeCom(process.env, [
        `**Yumpoo 发布草稿已就绪：${result.tag}**`,
        `> 提交：${process.env.SOURCE_COMMIT.slice(0, 8)}`,
        `> 草稿：[查看并发布](${result.url})`,
        '> 点击 Publish 即为上线决定。',
        ...(result.supersededDrafts.length ? [`> 旧草稿 ${result.supersededDrafts.join('、')} 已被取代，请勿发布，可手动删除。`] : []),
        ...(result.otherDrafts.length ? [`> 另有草稿 ${result.otherDrafts.join('、')} 的源码不早于本草稿，发布前请核对来源 SHA。`] : []),
      ].join('\n'))
    }
  } catch (error) {
    console.error(error.message)
    await notifyWeCom(process.env, `**Yumpoo 发布草稿生成失败**\n> 提交：${String(process.env.SOURCE_COMMIT).slice(0, 8)}\n> 请查看 Prepare Release 工作流日志。`)
    process.exitCode = 1
  } finally {
    fs.rmSync(directory, { recursive: true, force: true })
  }
}
