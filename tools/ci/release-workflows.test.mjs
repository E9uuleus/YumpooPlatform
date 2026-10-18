import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { parse } from 'yaml'

const read = name => parse(fs.readFileSync(new URL(`../../.github/workflows/${name}`, import.meta.url), 'utf8'))
const ci = read('m0-18-ci.yml')
const release = read('windows-release.yml')
const prepare = read('prepare-release.yml')
const rollback = read('server-rollback.yml')
const promotion = read('main-promotion-policy.yml')
const backMerge = read('back-merge.yml')
const supplyChain = read('supply-chain.yml')
const desktopPrepare = read('prepare-desktop-release.yml')
const desktopRelease = read('desktop-release.yml')
const dependabot = parse(fs.readFileSync(new URL('../../.github/dependabot.yml', import.meta.url), 'utf8'))
const releaseWorkflows = { release, prepare, rollback, promotion, backMerge, supplyChain, desktopPrepare, desktopRelease }
const allWorkflows = { ci, ...releaseWorkflows }
const signingSecret = 'DESKTOP_UPDATE_SIGNING_KEY'
const steps = job => job.steps ?? []
const deploySecrets = ['WINDOWS_DEPLOY_HOST', 'WINDOWS_DEPLOY_SSH_KEY', 'WINDOWS_DEPLOY_KNOWN_HOSTS']

test('every release workflow keeps the shared supply-chain and injection guards', () => {
  for (const [name, workflow] of Object.entries(releaseWorkflows)) {
    assert.deepEqual(workflow.permissions, { contents: 'read' }, `${name}: default token must be read-only`)
    assert(!workflow.on.pull_request_target, `${name}: privileged PR trigger is forbidden`)
    for (const job of Object.values(workflow.jobs)) {
      assert(!job['continue-on-error'], `${name}: failures must not be swallowed`)
      for (const step of steps(job)) {
        if (step.uses) assert.match(step.uses, /^[^@]+@[0-9a-f]{40}$/u, `${name}: actions must be pinned to a commit`)
        if (step.uses?.startsWith('actions/checkout@')) assert.equal(step.with?.['persist-credentials'], false, `${name}: checkout must not persist credentials`)
        // Release jobs install nothing, so no cache written by another run can reach the publishing path.
        if (step.uses?.startsWith('actions/setup-node@')) assert.equal(step.with?.['package-manager-cache'], false, `${name}: release tooling must not restore a package cache`)
        assert(!JSON.stringify(step.with ?? {}).includes('secrets.'), `${name}: secrets go through env only`)
        if (step.run) assert(!step.run.includes('${{'), `${name}: expressions must go through env, never into shell text`)
        assert(!Object.keys(step.env ?? {}).includes('GITHUB_TOKEN'), `${name}: use GH_TOKEN only`)
      }
    }
  }
})

test('release publish only verifies and deploys; it can no longer write Release assets', () => {
  assert.deepEqual(release.on.release.types, ['published'])
  assert.deepEqual(release.concurrency, { group: 'windows-server-release-wecom-dev', 'cancel-in-progress': false })
  assert.deepEqual(release.jobs.verify.permissions, { actions: 'read', attestations: 'read', contents: 'read' })
  assert.deepEqual(release.jobs.deploy.permissions, { actions: 'read', attestations: 'read', contents: 'read' })
  assert.deepEqual(release.jobs.deploy.needs, ['verify'])
  assert.equal(release.jobs.deploy.environment.name, 'wecom-dev')
  assert(steps(release.jobs.verify).some(step => step.run === 'node tools/release/promote-release.mjs'))
  const submit = steps(release.jobs.deploy).find(step => step.run === 'node tools/release/ssh-deploy.mjs')
  assert.equal(submit.env.DEPLOY_MODE, 'deploy')
  assert(!steps(release.jobs.verify).some(step => deploySecrets.some(secret => secret in (step.env ?? {}))), 'verification never sees deployment secrets')
})

test('drafts are prepared only from successful main push CI and never touch deployment secrets', () => {
  assert.deepEqual(prepare.on.workflow_run, { workflows: [ci.name], types: ['completed'], branches: ['main'] })
  const job = prepare.jobs.draft
  for (const fragment of ["github.event.workflow_run.conclusion == 'success'", "github.event.workflow_run.event == 'push'", 'github.event.workflow_run.head_repository.full_name == github.repository']) {
    assert(job.if.includes(fragment), `draft job must require ${fragment}`)
  }
  assert.deepEqual(job.permissions, { actions: 'read', attestations: 'read', contents: 'write' })
  assert.equal(job.environment, undefined)
  assert(!steps(job).some(step => deploySecrets.some(secret => secret in (step.env ?? {}))))
  assert(steps(job).some(step => step.run === 'node tools/release/prepare-release.mjs'))
})

test('desktop drafts come from the same main push CI; only the sign job enters the signing Environment', () => {
  assert.deepEqual(desktopPrepare.on.workflow_run, prepare.on.workflow_run)
  assert.notDeepEqual(desktopPrepare.concurrency.group, prepare.concurrency.group, 'desktop drafts never queue behind or replace server drafts')
  const { draft, sign } = desktopPrepare.jobs
  for (const fragment of ["github.ref == 'refs/heads/dev'", "github.event_name == 'workflow_dispatch'", "github.event.workflow_run.conclusion == 'success'", "github.event.workflow_run.event == 'push'", 'github.event.workflow_run.head_repository.full_name == github.repository']) {
    assert(draft.if.includes(fragment), `desktop preparation guard missing: ${fragment}`)
  }
  assert.equal(draft.environment, undefined)
  assert.deepEqual(draft.permissions, { actions: 'read', attestations: 'read', contents: 'write' })
  assert(steps(draft).some(step => step.run === 'node tools/release/prepare-desktop-release.mjs draft' && step.id === 'draft'))
  assert.equal(draft.outputs.tag, '${{ steps.draft.outputs.tag }}')
  assert.deepEqual(sign.needs, ['draft'])
  assert.equal(sign.if, "${{ needs.draft.outputs.tag != '' }}")
  assert.equal(sign.environment.name, 'desktop-signing')
  assert.deepEqual(sign.permissions, { actions: 'read', attestations: 'read', contents: 'write' })
  const signing = steps(sign).filter(step => signingSecret in (step.env ?? {}))
  assert.equal(signing.length, 1)
  assert.equal(signing[0].run, 'node tools/release/prepare-desktop-release.mjs sign')
  assert.equal(signing[0].env[signingSecret], `\${{ secrets.${signingSecret} }}`)
  for (const job of [draft, sign]) assert(!steps(job).some(step => deploySecrets.some(secret => secret in (step.env ?? {}))))
  for (const job of [draft, sign]) {
    const checkouts = steps(job).filter(step => step.uses?.startsWith('actions/checkout@'))
    assert.equal(checkouts.length, 1)
    assert.equal(checkouts[0].with.ref, '${{ github.workflow_sha }}', 'candidate commits must never supply executable release tooling')
    const prepareStep = steps(job).find(step => step.run?.startsWith('node tools/release/prepare-desktop-release.mjs '))
    assert.equal(prepareStep.env.SOURCE_COMMIT, '${{ github.event.workflow_run.head_sha || inputs.commit }}', 'the candidate is passed only as data')
  }
})

test('the update signing key reaches no other workflow, job or step', () => {
  for (const [name, workflow] of Object.entries(allWorkflows)) {
    for (const [id, job] of Object.entries(workflow.jobs)) {
      for (const step of steps(job)) {
        if (!JSON.stringify(step).includes(signingSecret)) continue
        assert(name === 'desktopPrepare' && id === 'sign', `${name}/${id} must not see ${signingSecret}`)
      }
    }
  }
})

test('desktop publication verifies read-only, then writes the feed through wecom-dev on its own queue', () => {
  assert.deepEqual(desktopRelease.on.release.types, ['published'])
  assert.deepEqual(Object.keys(desktopRelease.on.workflow_dispatch.inputs), ['tag'])
  assert.deepEqual(desktopRelease.concurrency, { group: 'windows-desktop-release-wecom-dev', 'cancel-in-progress': false })
  assert.notEqual(desktopRelease.concurrency.group, release.concurrency.group, 'one pending run per group: sharing would cancel queued releases')
  const { verify, publish } = desktopRelease.jobs
  assert(verify.if.includes("startsWith(github.event.release.tag_name, 'desktop-v')"))
  assert(release.jobs.verify.if.includes("startsWith(github.event.release.tag_name, 'server-v')"), 'server releases never pick up desktop tags')
  assert.deepEqual(verify.permissions, { actions: 'read', attestations: 'read', contents: 'read' })
  assert.deepEqual(publish.permissions, { actions: 'read', attestations: 'read', contents: 'read' })
  assert.deepEqual(publish.needs, ['verify'])
  assert.equal(publish.environment.name, 'wecom-dev')
  assert(steps(verify).some(step => step.run === 'node tools/release/desktop-release.mjs'))
  assert(!steps(verify).some(step => deploySecrets.some(secret => secret in (step.env ?? {}))), 'verification never sees deployment secrets')
  const submit = steps(publish).find(step => step.run === 'node tools/release/ssh-deploy.mjs')
  assert.equal(submit.env.DEPLOY_MODE, 'desktop-publish')
})

test('rollback is a manual, serialized, code-only deployment through the same environment', () => {
  assert.deepEqual(Object.keys(rollback.on), ['workflow_dispatch'])
  assert.deepEqual(Object.keys(rollback.on.workflow_dispatch.inputs).sort(), ['reason', 'target_tag'])
  assert.deepEqual(rollback.concurrency, release.concurrency)
  assert.equal(rollback.jobs.rollback.environment.name, 'wecom-dev')
  assert.deepEqual(rollback.jobs.rollback.permissions, { actions: 'read', attestations: 'read', contents: 'read' })
  const submit = steps(rollback.jobs.rollback).find(step => step.run === 'node tools/release/ssh-deploy.mjs')
  assert.equal(submit.env.DEPLOY_MODE, 'rollback')
  assert.equal(submit.env.RELEASE_TAG, '${{ inputs.target_tag }}')
})

test('PRs into main get the promotion check and hotfixes on main are merged back', () => {
  assert.deepEqual(ci.on.pull_request.branches, ['dev', 'main'])
  assert.deepEqual(ci.on.push.branches, ['dev', 'main'])
  assert.deepEqual(promotion.on.pull_request.branches, ['main'])
  for (const type of ['opened', 'synchronize', 'reopened', 'labeled', 'unlabeled']) assert(promotion.on.pull_request.types.includes(type))
  assert.equal(promotion.jobs.promotion.name, 'Main Promotion Policy')
  assert.deepEqual(promotion.jobs.promotion.permissions, { contents: 'read', 'pull-requests': 'write' })
  assert.equal(steps(promotion.jobs.promotion).find(step => step.uses?.startsWith('actions/checkout@')).with['fetch-depth'], 0)
  assert.deepEqual(backMerge.on, { push: { branches: ['main', 'dev'] } })
  assert.deepEqual(backMerge.jobs['back-merge'].permissions, { contents: 'write', 'pull-requests': 'write' })
})

test('supply-chain checks gate every PR and the weekly run scans, audits and rehearses read-only', () => {
  assert.deepEqual(supplyChain.on.pull_request.branches, ['dev', 'main'])
  assert(!supplyChain.on.pull_request.paths && !supplyChain.on.pull_request['paths-ignore'], 'required checks must always report')
  assert.equal(supplyChain.on.schedule.length, 1)
  assert('workflow_dispatch' in supplyChain.on)
  const { dependency_review: review, workflow_audit: audit, osv_scan: osv, release_rehearsal: rehearsal, notify } = supplyChain.jobs
  assert.equal(review.name, 'Dependency Review')
  assert.equal(review.if, "${{ github.event_name == 'pull_request' }}")
  const reviewStep = steps(review).find(step => step.uses?.startsWith('actions/dependency-review-action@'))
  assert.equal(reviewStep.with['fail-on-severity'], 'moderate')
  assert.equal(reviewStep.with['comment-summary-in-pr'], 'never', 'no PR write permission is granted')
  assert.equal(audit.name, 'Workflow Audit')
  assert.equal(audit.if, undefined, 'the workflow audit runs on PRs and weekly')
  const zizmor = steps(audit).find(step => step.uses?.startsWith('zizmorcore/zizmor-action@'))
  assert.equal(zizmor.with['advanced-security'], false, 'findings fail the job instead of needing security-events: write')
  assert.match(String(zizmor.with.version), /^\d+\.\d+\.\d+$/u, 'zizmor itself is pinned')
  for (const job of [osv, rehearsal]) assert.equal(job.if, "${{ github.event_name != 'pull_request' }}")
  assert.deepEqual(osv.strategy.matrix.ref, ['dev', 'main'])
  assert.deepEqual(rehearsal.permissions, { actions: 'read', attestations: 'read', contents: 'read' })
  assert.equal(steps(rehearsal).find(step => step.uses?.startsWith('actions/checkout@')).with.ref, 'main')
  assert(steps(rehearsal).some(step => step.run?.includes('node tools/release/rehearse-release.mjs')))
  for (const job of Object.values(supplyChain.jobs)) {
    assert(!Object.values(job.permissions ?? {}).includes('write'), 'supply-chain jobs never write')
    assert(!steps(job).some(step => deploySecrets.some(secret => secret in (step.env ?? {}))))
  }
  assert.deepEqual(notify.needs.sort(), ['osv_scan', 'release_rehearsal', 'workflow_audit'])
})

test('CI signs the deployable package and its SBOMs; release paths verify the signature', () => {
  const delivery = ci.jobs.windows_delivery
  assert.deepEqual(delivery.permissions, { contents: 'read', 'id-token': 'write', attestations: 'write' })
  assert(steps(delivery).some(step => step.uses?.startsWith('actions/attest-build-provenance@')))
  for (const [name, workflow] of Object.entries(releaseWorkflows)) {
    for (const job of Object.values(workflow.jobs)) {
      assert(!['id-token', 'attestations'].some(scope => job.permissions?.[scope] === 'write'), `${name}: only CI may sign`)
    }
  }
})

test('Dependabot updates actions, npm and Maven into dev on a weekly, grouped, cooled-down cadence', () => {
  assert.equal(dependabot.version, 2)
  assert.deepEqual(dependabot.updates.map(update => [update['package-ecosystem'], update.directory]),
    [['github-actions', '/'], ['npm', '/'], ['maven', '/backend']])
  for (const update of dependabot.updates) {
    assert.equal(update['target-branch'], 'dev')
    assert.equal(update.schedule.interval, 'weekly')
    assert(update.cooldown['default-days'] >= 7, 'fresh releases wait out the cooldown')
    assert(Object.keys(update.groups ?? {}).length > 0, 'updates are grouped')
    // Without update-types a group also takes majors (REL-P5 F14); majors must stay separate PRs.
    for (const [name, group] of Object.entries(update.groups)) {
      assert.deepEqual(group['update-types'], ['minor', 'patch'], `${update['package-ecosystem']}/${name}: majors are reviewed one by one`)
    }
  }
})
