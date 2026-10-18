import assert from 'node:assert/strict'
import { createPrivateKey } from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { repository } from './policy.mjs'
import {
  compareVersions, desktopArtifactFileNames, desktopReleaseAssetNames, desktopReleaseJson, desktopSigningSecret, desktopTag,
  requireUpdateDistribution, signatureName, validateDesktopProvenance, validateDesktopReleaseJson, validateDesktopSignature,
  validateDesktopTag, expectedDesktopArtifactName, installerName,
} from './desktop-policy.mjs'
import { createStatement, signStatement } from './desktop-update-signature.mjs'
import {
  describeFile, desktopReleases, downloadDesktopCiPackage, inspectDesktopPackage, latestPublished, readDesktopCheckout,
} from './desktop-release.mjs'
import { desktopChanges, desktopNoteTitles, desktopReleaseBody } from './desktop-release-notes.mjs'
import { createGithub, readJson, unpackArtifact } from './github.mjs'
import { assertOnReleaseBranch, ciWorkflowId, downloadAsset, indexAssets, selectCiAttempt, verifyCiAttempt } from './promote-release.mjs'
import { verifyBuildAttestations } from './attestation.mjs'
import { notifyWeCom } from './notify.mjs'

/**
 * Decides whether this main commit gets a desktop draft. The desktop channel moves only when the shell's own
 * version moves: 0.x is the retired portable line, and a version that is published, not newer than the latest
 * published one, or already drafted from another commit is left alone.
 */
export async function planDesktopRelease({ github, sourceCommit, env, checkout }) {
  assert(/^[0-9a-f]{40}$/u.test(sourceCommit), 'Invalid source commit')
  assert.equal(env.GITHUB_REPOSITORY, repository, 'Unexpected repository')
  assert(['workflow_run', 'workflow_dispatch'].includes(env.GITHUB_EVENT_NAME), 'Unsupported draft trigger')
  await assertOnReleaseBranch(github, sourceCommit)
  const { version, distribution } = checkout
  if (version.startsWith('0.')) return { skip: `Desktop version ${version} is the retired 0.x portable line; the desktop channel starts at 1.0.0` }
  requireUpdateDistribution(distribution)
  const tag = desktopTag(version)
  const releases = await desktopReleases(github)
  const sameTag = releases.filter(release => release.tag_name === tag)
  const published = sameTag.find(release => !release.draft)
  if (published) {
    return { skip: published.target_commitish === sourceCommit ? `${tag} is already published for this commit` : `${tag} is already published; bump desktop/desktop-shell/package.json to release desktop changes` }
  }
  const latest = latestPublished(releases)
  if (latest && compareVersions(version, validateDesktopTag(latest.tag_name)) <= 0) return { skip: `${tag} is not newer than the published ${latest.tag_name}` }
  const drafts = sameTag.filter(release => release.draft)
  assert(drafts.length <= 1, `Several drafts are named ${tag}; delete the extra drafts manually`)
  const [draft] = drafts
  // Drafts are never deleted or retargeted automatically: a draft someone is test-installing must keep its bytes.
  if (draft && draft.target_commitish !== sourceCommit) {
    return { skip: `Draft ${tag} already exists for ${draft.target_commitish}; publish or delete it, or bump the desktop version` }
  }
  if (!draft && (await github.list(`/git/matching-refs/tags/${tag}`)).some(ref => ref.ref === `refs/tags/${tag}`)) {
    return { skip: `Tag ${tag} already exists without a Release; bump the desktop version` }
  }
  return { tag, version, distribution, draft, latest }
}

// Step 1, without the signing key: create the draft and attach the attested CI bytes, provenance and release.json.
export async function prepareDesktopDraft({ github, sourceCommit, env, directory, checkout, unpack = unpackArtifact, attest = verifyBuildAttestations, log = console.log }) {
  const planned = await planDesktopRelease({ github, sourceCommit, env, checkout })
  if (planned.skip) return { skipped: planned.skip }
  const { tag, version, distribution, latest } = planned
  let { draft } = planned
  const names = desktopReleaseAssetNames(version)
  const uploadNames = names.filter(name => name !== signatureName(version))
  const workflowId = await ciWorkflowId(github)
  let pinned
  if (draft) {
    const existing = indexAssets(await github.list(`/releases/${draft.id}/assets?per_page=100`), names)
    if (existing.has('provenance.json')) {
      pinned = validateDesktopProvenance(readJson(await downloadAsset(github, existing.get('provenance.json'), fs.mkdtempSync(path.join(directory, 'pin-')))), { tag, sourceCommit })
    }
  }
  // An interrupted earlier attempt pinned its CI attempt in provenance.json; never switch to another attempt.
  const run = pinned
    ? await verifyCiAttempt(github, workflowId, sourceCommit, pinned.ciRunId, pinned.ciRunAttempt)
    : await selectCiAttempt(github, workflowId, sourceCommit)
  const created = !draft
  let changes
  if (!draft) {
    changes = await desktopChanges(github, { sourceCommit, previous: latest })
    draft = await github.send('POST', '/releases', {
      tag_name: tag, target_commitish: sourceCommit, name: `Yumpoo Desktop ${version}`, draft: true,
      body: desktopReleaseBody({ tag, version, sourceCommit, run, previous: latest, changes }),
    })
  }
  assert.equal(draft.draft, true, 'Prepared desktop Release must stay a draft')
  assert.equal(draft.target_commitish, sourceCommit, 'Desktop draft targets another commit')

  const assets = indexAssets(await github.list(`/releases/${draft.id}/assets?per_page=100`), names)
  if (!uploadNames.every(name => assets.has(name))) {
    const downloaded = await downloadDesktopCiPackage(github, run, directory, version, unpack)
    const { installer } = await inspectDesktopPackage(downloaded, { sourceCommit, version, distribution })
    // Unattested bytes never reach the draft.
    await attest(downloaded, run, { env, log, label: 'desktop CI bytes before upload', files: desktopArtifactFileNames(version) })
    const provenance = pinned ?? validateDesktopProvenance({
      schemaVersion: 1, repository, tag, version, sourceCommit, ciRunId: run.id, ciRunAttempt: run.run_attempt,
      artifactName: expectedDesktopArtifactName(sourceCommit, run.run_attempt), installerSha256: installer.sha256, installerBytes: installer.bytes,
    }, { tag, sourceCommit })
    assert.equal(provenance.installerSha256, installer.sha256, 'CI installer differs from the pinned provenance')
    const retained = path.join(directory, 'desktop-draft')
    fs.mkdirSync(retained, { recursive: true })
    fs.writeFileSync(path.join(retained, 'provenance.json'), `${JSON.stringify(provenance, null, 2)}\n`, { mode: 0o600 })
    for (const name of desktopArtifactFileNames(version)) fs.copyFileSync(path.join(downloaded, name), path.join(retained, name))
    if (!assets.has('release.json')) {
      changes ??= await desktopChanges(github, { sourceCommit, previous: latest })
      const releaseJson = desktopReleaseJson({ version, channel: distribution.update.channel, sourceCommit, installer, notes: desktopNoteTitles(changes) })
      fs.writeFileSync(path.join(retained, 'release.json'), `${JSON.stringify(releaseJson, null, 2)}\n`, 'utf8')
    }
    // Existing assets are verified and never replaced; provenance goes up first to pin the CI attempt.
    for (const [name, asset] of assets) {
      if (name === 'release.json') continue
      const file = await describeFile(path.join(retained, name))
      assert.equal(asset.digest, `sha256:${file.sha256}`, `Existing draft asset differs: ${name}`)
      assert.equal(asset.size, file.bytes, `Existing draft asset size differs: ${name}`)
    }
    for (const name of ['provenance.json', ...desktopArtifactFileNames(version), 'release.json']) {
      if (!assets.has(name)) await github.uploadAsset(draft.id, path.join(retained, name))
    }
  }
  const provenance = await verifyDraftAssets({ github, draft, tag, version, sourceCommit, distribution, run, env, directory, attest, log, names: uploadNames })
  return { tag, version, releaseId: draft.id, url: draft.html_url, created, provenance }
}

async function verifyDraftAssets({ github, draft, tag, version, sourceCommit, distribution, run, env, directory, attest, log, names }) {
  const final = indexAssets(await github.list(`/releases/${draft.id}/assets?per_page=100`), desktopReleaseAssetNames(version))
  assert(names.every(name => final.has(name)), 'Desktop draft assets did not persist')
  const verified = fs.mkdtempSync(path.join(directory, 'verified-'))
  for (const name of names) await downloadAsset(github, final.get(name), verified)
  const provenance = validateDesktopProvenance(readJson(path.join(verified, 'provenance.json')), { tag, sourceCommit })
  const { installer } = await inspectDesktopPackage(verified, { sourceCommit, version, distribution })
  assert.equal(installer.sha256, provenance.installerSha256, 'Persisted installer differs from provenance')
  if (run) await attest(verified, run, { env, log, label: 'persisted desktop draft assets', files: desktopArtifactFileNames(version) })
  validateDesktopReleaseJson(readJson(path.join(verified, 'release.json')), { version, channel: distribution.update.channel, sourceCommit, installer })
  return provenance
}

// Step 2, the only code that sees the private key: authorize the exact installer already on the draft.
export async function signDesktopDraft({ github, sourceCommit, env, directory, checkout, privateKeyPem, log = console.log }) {
  const planned = await planDesktopRelease({ github, sourceCommit, env, checkout })
  if (planned.skip) return { skipped: planned.skip }
  const { tag, version, distribution, draft } = planned
  assert(draft, `No draft ${tag} for ${sourceCommit}; the draft step must run first`)
  const sigName = signatureName(version)
  const assets = indexAssets(await github.list(`/releases/${draft.id}/assets?per_page=100`), desktopReleaseAssetNames(version))
  for (const name of ['provenance.json', installerName(version)]) assert(assets.has(name), `Desktop draft is missing ${name}; rerun the draft step`)
  const retained = fs.mkdtempSync(path.join(directory, 'sign-'))
  const provenance = validateDesktopProvenance(readJson(await downloadAsset(github, assets.get('provenance.json'), retained)), { tag, sourceCommit })
  const installer = { name: installerName(version), ...await describeFile(await downloadAsset(github, assets.get(installerName(version)), retained)) }
  assert.equal(installer.sha256, provenance.installerSha256, 'Draft installer differs from provenance')
  assert.equal(installer.bytes, provenance.installerBytes, 'Draft installer size differs from provenance')
  const expectation = { distribution, version, sourceCommit, installer }
  if (assets.has(sigName)) {
    const existing = validateDesktopSignature(readJson(await downloadAsset(github, assets.get(sigName), retained)), expectation)
    log(`${sigName} is already attached and verifies with ${existing.keyId}`)
    return { tag, url: draft.html_url, keyId: existing.keyId, created: false, provenance }
  }
  assert(privateKeyPem?.includes('PRIVATE KEY'), `${desktopSigningSecret} is not configured in the desktop-signing Environment`)
  const statement = createStatement({
    channel: distribution.update.channel, version, file: installer.name, sha512: installer.sha512, size: installer.bytes, tag, commit: sourceCommit,
  })
  const signed = signStatement(statement, createPrivateKey(privateKeyPem))
  // A key the released build does not trust would produce an update every client rejects.
  validateDesktopSignature(signed, expectation)
  const file = path.join(retained, sigName)
  fs.writeFileSync(file, `${JSON.stringify(signed, null, 2)}\n`, { flag: 'wx' })
  await github.uploadAsset(draft.id, file)
  const persisted = indexAssets(await github.list(`/releases/${draft.id}/assets?per_page=100`), desktopReleaseAssetNames(version))
  assert(persisted.has(sigName), 'Desktop authorization did not persist')
  const check = fs.mkdtempSync(path.join(directory, 'signed-'))
  validateDesktopSignature(readJson(await downloadAsset(github, persisted.get(sigName), check)), expectation)
  log(`Signed ${installer.name} for ${tag} with ${signed.keyId}`)
  return { tag, url: draft.html_url, keyId: signed.keyId, created: true, provenance }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const step = process.argv[2]
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-desktop-draft-'))
  const summary = text => {
    console.log(text)
    if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${text}\n`)
  }
  try {
    assert(['draft', 'sign'].includes(step), 'Usage: prepare-desktop-release.mjs draft|sign')
    const options = { github: createGithub(), sourceCommit: process.env.SOURCE_COMMIT, env: process.env, directory, checkout: readDesktopCheckout() }
    if (step === 'draft') {
      const result = await prepareDesktopDraft(options)
      summary(result.skipped ? `No desktop draft: ${result.skipped}.` : `Desktop draft **${result.tag}** holds the attested CI bytes from run ${result.provenance.ciRunId} attempt ${result.provenance.ciRunAttempt}: ${result.url}`)
    } else {
      const result = await signDesktopDraft({ ...options, privateKeyPem: process.env[desktopSigningSecret] })
      if (result.skipped) {
        summary(`No desktop draft to sign: ${result.skipped}.`)
      } else {
        summary(`Desktop draft **${result.tag}** is authorized by ${result.keyId} and ready to test-install: ${result.url}`)
        await notifyWeCom(process.env, [
          `**Yumpoo 桌面发布草稿已就绪：${result.tag}**`,
          `> 提交：${process.env.SOURCE_COMMIT.slice(0, 8)}`,
          `> 草稿：[下载安装包试装](${result.url})`,
          '> 试装通过后点击 Publish 即为桌面上线决定。',
        ].join('\n'))
      }
    }
  } catch (error) {
    console.error(error.message)
    await notifyWeCom(process.env, `**Yumpoo 桌面发布草稿生成失败**\n> 提交：${String(process.env.SOURCE_COMMIT).slice(0, 8)}\n> 请查看 Prepare Desktop Release 工作流日志。`)
    process.exitCode = 1
  } finally {
    fs.rmSync(directory, { recursive: true, force: true })
  }
}
