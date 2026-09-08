import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { parse, stringify } from 'yaml'
import { assertRequiredJobs, requiredJobs } from './gate.mjs'
import { assertWorkflowSafety } from './workflow-policy.mjs'
import { verificationEnvironment } from './environment.mjs'
import { checkHistory } from './history-policy.mjs'
import { plan } from './plan.mjs'
import { assertStageReport } from './reports.mjs'
import { verifyContentCategoryRefactorAssets } from '../verification/content-category-refactor-assets.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const workflow = () => parse(fs.readFileSync(path.join(root, '.github/workflows/m0-18-ci.yml'), 'utf8'))
const passed = () => Object.fromEntries(requiredJobs.map(id => [id, { result: 'success', outputs: { completed: 'true' } }]))

test('merge gate accepts only complete successful executions', () => {
  assert.doesNotThrow(() => assertRequiredJobs(passed()))
  for (const cancelled of ['true', 'unknown', '']) {
    assert.throws(() => assertRequiredJobs(passed(), requiredJobs, cancelled), /取消/u)
  }
  for (const id of requiredJobs) {
    for (const result of ['failure', 'cancelled', 'skipped', 'timed_out', 'neutral', 'pending', undefined]) {
      const needs = passed()
      needs[id].result = result
      assert.throws(() => assertRequiredJobs(needs), /未完整成功/u)
    }
    const missingJob = passed()
    delete missingJob[id]
    assert.throws(() => assertRequiredJobs(missingJob), /依赖集合/u)
    for (const completed of [undefined, '', 'false', true]) {
      const missingReceipt = passed()
      missingReceipt[id].outputs.completed = completed
      assert.throws(() => assertRequiredJobs(missingReceipt), /未完整成功/u)
    }
  }
  assert.throws(() => assertRequiredJobs({ ...passed(), unregistered: { result: 'success' } }), /依赖集合/u)
})

test('workflow preserves mandatory gates, immutable actions and exact artifact handoff', () => {
  assertWorkflowSafety(workflow())
  const renamedStep = workflow()
  renamedStep.jobs.linux.steps.find(step => step.run === 'pnpm ci:backend').name = 'Backend verification'
  assert.doesNotThrow(() => assertWorkflowSafety(renamedStep))
  const brokenVariants = [
    value => { value.jobs.windows.needs = ['linux'] },
    value => { delete value.jobs.windows.if },
    value => { value.jobs.windows_delivery.if = '${{ false }}' },
    value => { value.jobs.linux.steps.find(step => step.run === 'pnpm ci:backend').if = '${{ false }}' },
    value => { value.jobs.linux.steps.find(step => step.run === 'pnpm ci:backend')['continue-on-error'] = true },
    value => { value.jobs.linux.steps = value.jobs.linux.steps.filter(step => step.run !== 'pnpm ci:portable') },
    value => { value.jobs.extra = { steps: [] } },
    value => { value.on.pull_request.paths = ['frontend/**'] },
    value => { value.jobs.windows.steps[0].uses = 'actions/checkout@main' },
  ]
  for (const mutate of brokenVariants) {
    const value = workflow()
    mutate(value)
    assert.throws(() => assertWorkflowSafety(value))
  }
})

test('flat static plan runs shared capabilities once and Windows reuses the tested server payload', () => {
  const steps = plan('static')
  assert.equal(new Set(steps.map(step => step.id)).size, steps.length)
  assert.equal(new Set(steps.map(step => JSON.stringify([step.tool, step.args]))).size, steps.length)
  assert(!steps.some(step => step.args.includes('verify:node') || step.args.includes('verify:m1-13')))
  assert.equal(steps.filter(step => step.id === 'event-compatibility').length, 1)
  assert.equal(steps.filter(step => step.id === 'generated-client').length, 1)
  assert.deepEqual(plan('backend').find(step => step.id === 'backend-regression').args, ['clean', 'verify'])
  assert(plan('backend').some(step => step.args.includes('-DargLine=-Xmx96m')))
  assert(!plan('windows').some(step => step.tool === 'maven' || step.args.includes('build')))
})

test('handoff prerequisites reject stale, partial and failed stage reports', () => {
  const expected = { stage: 'backend', sourceHash: 'working-tree', sourceCommit: 'commit', baseRef: 'base', steps: plan('backend') }
  const report = { ...expected, status: 'PASS', startedAt: '2026-09-08T00:00:00Z', completedAt: '2026-09-08T00:01:00Z',
    steps: expected.steps.map(step => ({ id: step.id, status: 'PASS' })) }
  assert.doesNotThrow(() => assertStageReport(report, expected))
  for (const changed of [{ sourceHash: 'previous-tree' }, { sourceCommit: 'previous-commit' }, { baseRef: 'previous-base' },
    { status: 'FAIL' }, { steps: report.steps.slice(1) }, { steps: [{ id: 'backend-regression', status: 'PASS' }] },
    { completedAt: 'invalid' }]) {
    assert.throws(() => assertStageReport({ ...report, ...changed }, expected), /旧报告或不完整/u)
  }
})

test('verification subprocesses cannot inherit application credentials or JVM test bypasses', () => {
  const source = { PATH: 'toolchain', JAVA_HOME: 'jdk', DOCKER_HOST: 'docker', npm_execpath: 'pnpm.cjs',
    SPRING_DATASOURCE_URL: 'external-db', spring_flyway_password: 'external-secret',
    YUMPOO_LOCAL_AUTH_ENABLED: 'true', YUMPOO_M113_FIXTURE_ENABLED: 'true', YUMPOO_M017_OUTPUT_ROOT: 'elsewhere',
    JAVA_TOOL_OPTIONS: '-DskipTests', MAVEN_ARGS: '-DskipTests', MAVEN_OPTS: '-Dspring.profiles.active=prod', NODE_OPTIONS: '--require external' }
  assert.deepEqual(verificationEnvironment(source), { PATH: 'toolchain', JAVA_HOME: 'jdk', DOCKER_HOST: 'docker', npm_execpath: 'pnpm.cjs' })
  assert.equal(source.YUMPOO_LOCAL_AUTH_ENABLED, 'true')
})

function temporary(context) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-ci-'))
  context.after(() => {
    assert(path.resolve(directory).startsWith(path.join(path.resolve(os.tmpdir()), 'yumpoo-ci-')))
    fs.rmSync(directory, { recursive: true, force: true })
  })
  return directory
}

function write(directory, relative, content) {
  const file = path.join(directory, relative)
  fs.mkdirSync(path.dirname(file), { recursive: true })
  fs.writeFileSync(file, content)
}

test('history guard permits forward additions and rejects rewritten or deleted deployed assets', context => {
  const directory = temporary(context)
  const migration = 'backend/src/main/resources/db/migration/foundation/V1__foundation.sql'
  const manifest = 'tools/openapi/breaking-change-exceptions.json'
  const freeze = 'contracts/events/freeze/workitem-m2-v1.json'
  write(directory, migration, 'SELECT 1;\n')
  write(directory, manifest, JSON.stringify({ exceptions: [{ id: 'historical', oldSha256: 'a', newSha256: 'b' }] }))
  write(directory, freeze, '{"frozen":true}\n')
  const git = args => {
    const result = spawnSync('git', ['-c', 'user.name=CI', '-c', 'user.email=ci@example.invalid',
      '-c', `core.hooksPath=${path.join(directory, 'no-hooks')}`, ...args], { cwd: directory, encoding: 'utf8' })
    assert.equal(result.status, 0, result.error?.message || result.stderr)
    return result.stdout.trim()
  }
  git(['init', '--quiet'])
  git(['add', '.'])
  git(['commit', '--quiet', '-m', 'fixture'])
  const baseline = git(['rev-parse', 'HEAD'])
  write(directory, 'backend/src/main/resources/db/migration/foundation/V2__forward.sql', 'SELECT 2;\n')
  assert.equal(checkHistory(directory, baseline), baseline)
  write(directory, 'backend/src/main/resources/db/migration/foundation/V1__duplicate.sql', 'SELECT 2;\n')
  assert.throws(() => checkHistory(directory, baseline), /唯一版本并向前追加/u)
  fs.unlinkSync(path.join(directory, 'backend/src/main/resources/db/migration/foundation/V1__duplicate.sql'))
  write(directory, migration, 'SELECT 1;\r\n')
  assert.doesNotThrow(() => checkHistory(directory, baseline))
  write(directory, migration, 'SELECT 9;\n')
  assert.throws(() => checkHistory(directory, baseline), /已交付历史/u)
  fs.unlinkSync(path.join(directory, migration))
  assert.throws(() => checkHistory(directory, baseline), /已交付历史/u)
  write(directory, migration, 'SELECT 1;\n')
  write(directory, freeze, '{"frozen":false}\n')
  assert.throws(() => checkHistory(directory, baseline), /已交付历史/u)
  write(directory, freeze, '{"frozen":true}\n')
  write(directory, manifest, JSON.stringify({ exceptions: [{ id: 'historical', oldSha256: 'a', newSha256: 'c' }] }))
  assert.throws(() => checkHistory(directory, baseline), /既有 OpenAPI 精确例外/u)
  assert.throws(() => checkHistory(directory, 'missing-ref'), /历史基线不可读取/u)
})

test('category contract allows additive API evolution without an old-to-current hash and rejects unsafe writes', context => {
  const directory = temporary(context)
  const relative = 'contracts/openapi/yumpoo-v1.yaml'
  const spec = parse(fs.readFileSync(path.join(root, relative), 'utf8'))
  spec.components.schemas.Content.properties.optionalFutureLabel = { type: 'string' }
  spec.paths['/future-health'] = { get: { operationId: 'futureHealth', responses: { 204: { description: 'OK' } } } }
  write(directory, relative, stringify(spec))
  write(directory, 'contracts/events/catalog.yaml', fs.readFileSync(path.join(root, 'contracts/events/catalog.yaml')))
  assert.doesNotThrow(() => verifyContentCategoryRefactorAssets(directory))
  delete spec.paths['/work-items/{workItemId}/content'].patch.security
  write(directory, relative, stringify(spec))
  assert.throws(() => verifyContentCategoryRefactorAssets(directory), /会话认证/u)
})
