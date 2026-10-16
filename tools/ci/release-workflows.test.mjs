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
const releaseWorkflows = { release, prepare, rollback, promotion, backMerge }
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
        if (step.run) assert(!step.run.includes('${{'), `${name}: expressions must go through env, never into shell text`)
        assert(!Object.keys(step.env ?? {}).includes('GITHUB_TOKEN'), `${name}: use GH_TOKEN only`)
      }
    }
  }
})

test('release publish only verifies and deploys; it can no longer write Release assets', () => {
  assert.deepEqual(release.on.release.types, ['published'])
  assert.deepEqual(release.concurrency, { group: 'windows-server-release-wecom-dev', 'cancel-in-progress': false })
  assert.deepEqual(release.jobs.verify.permissions, { actions: 'read', contents: 'read' })
  assert.deepEqual(release.jobs.deploy.permissions, { actions: 'read', contents: 'read' })
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
  assert.deepEqual(job.permissions, { actions: 'read', contents: 'write' })
  assert.equal(job.environment, undefined)
  assert(!steps(job).some(step => deploySecrets.some(secret => secret in (step.env ?? {}))))
  assert(steps(job).some(step => step.run === 'node tools/release/prepare-release.mjs'))
})

test('rollback is a manual, serialized, code-only deployment through the same environment', () => {
  assert.deepEqual(Object.keys(rollback.on), ['workflow_dispatch'])
  assert.deepEqual(Object.keys(rollback.on.workflow_dispatch.inputs).sort(), ['reason', 'target_tag'])
  assert.deepEqual(rollback.concurrency, release.concurrency)
  assert.equal(rollback.jobs.rollback.environment.name, 'wecom-dev')
  assert.deepEqual(rollback.jobs.rollback.permissions, { actions: 'read', contents: 'read' })
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
