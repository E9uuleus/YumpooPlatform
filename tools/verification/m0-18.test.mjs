import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import YAML from 'yaml'
import { extractOpenApiBaseline } from '../openapi/extract-openapi-baseline.mjs'
import { assertNoSensitiveMaterial } from './create-m0-18-evidence-pack.mjs'
import {
  loadLiveEvidence,
  validateDeferredAcceptance,
  validateM018EvidenceContracts,
} from './m0-18-evidence.mjs'
import {
  assertDirectoryTargetWithin,
  assertExactPayload,
  assertSchema,
  collectRegularFiles,
  fileRecords,
  isSafeRelativePath,
  readJson,
  validateGitRef,
} from './m0-18-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')

test('Git baseline ref rejects injection, traversal and all-zero commits', () => {
  assert.equal(validateGitRef('origin/dev'), 'origin/dev')
  assert.equal(validateGitRef('a'.repeat(40)), 'a'.repeat(40))
  for (const reference of ['', '-dev', '../dev', 'refs//heads/dev', 'refs/heads/dev@{1}', '0'.repeat(40)]) {
    assert.throws(() => validateGitRef(reference), /M0-18/u)
  }
})

test('OpenAPI baseline extraction is commit-bound and fails on empty or missing contracts', (context) => {
  const fixture = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-m018-git-'))
  context.after(() => fs.rmSync(fixture, { recursive: true, force: true }))
  git(fixture, ['init', '--quiet'])
  git(fixture, ['config', 'user.email', 'm018@example.invalid'])
  git(fixture, ['config', 'user.name', 'M0-18 Test'])
  const contractPath = path.join(fixture, 'contracts', 'openapi', 'yumpoo-v1.yaml')
  fs.mkdirSync(path.dirname(contractPath), { recursive: true })
  const expected = 'openapi: 3.0.3\ninfo:\n  title: Fixture\n  version: 1.0.0\npaths: {}\n'
  fs.writeFileSync(contractPath, expected, 'utf8')
  git(fixture, ['add', 'contracts/openapi/yumpoo-v1.yaml'])
  git(fixture, ['commit', '--quiet', '-m', 'fixture'])
  const goodCommit = git(fixture, ['rev-parse', 'HEAD']).trim()
  const outputPath = path.join(fixture, 'result', 'baseline.yaml')
  const metadataPath = path.join(fixture, 'result', 'metadata.json')
  const metadata = extractOpenApiBaseline({ repositoryRoot: fixture, reference: goodCommit, outputPath, metadataPath })
  assert.equal(fs.readFileSync(outputPath, 'utf8'), expected)
  assert.equal(metadata.baseCommit, goodCommit)
  assert.equal(readJson(metadataPath).sha256, metadata.sha256)
  assert.throws(
    () => extractOpenApiBaseline({ repositoryRoot: fixture, reference: 'refs/heads/missing', outputPath, metadataPath }),
    /M0-18/u,
  )

  fs.writeFileSync(contractPath, '', 'utf8')
  git(fixture, ['add', 'contracts/openapi/yumpoo-v1.yaml'])
  git(fixture, ['commit', '--quiet', '-m', 'empty'])
  assert.throws(
    () => extractOpenApiBaseline({ repositoryRoot: fixture, reference: 'HEAD', outputPath, metadataPath }),
    /M0-18/u,
  )

  fs.rmSync(contractPath)
  git(fixture, ['add', '-A'])
  git(fixture, ['commit', '--quiet', '-m', 'missing'])
  assert.throws(
    () => extractOpenApiBaseline({ repositoryRoot: fixture, reference: 'HEAD', outputPath, metadataPath }),
    /M0-18/u,
  )
})

test('OpenAPI compatibility check fails when no baseline is supplied', () => {
  const result = spawnSync(process.execPath, [path.join(repositoryRoot, 'tools', 'openapi', 'check-openapi-compat.mjs')], {
    cwd: repositoryRoot,
    encoding: 'utf8',
  })
  assert.notEqual(result.status, 0)
  assert.match(`${result.stdout}\n${result.stderr}`, /必须显式传入历史基线/u)
})

test('M0-18 evidence contracts match the live NOT_RUN exact set', () => {
  const result = validateM018EvidenceContracts(repositoryRoot)
  assert.deepEqual(result.liveEvidence.filter((item) => item.status === 'NOT_RUN').map((item) => item.milestone), [
    'M0-12',
    'M0-14',
    'M0-15',
    'M0-16',
  ])
  assert.equal(result.liveEvidence.find((item) => item.milestone === 'M0-13')?.status, 'PASS')
})

test('deferred acceptance rejects omissions, listed PASS evidence and incomplete required checks', () => {
  const deferred = readJson(path.join(repositoryRoot, 'evidence', 'm0-18', 'deferred-acceptance.json'))
  const liveEvidence = loadLiveEvidence(path.join(repositoryRoot, 'evidence'))
  const missing = structuredClone(deferred)
  missing.liveVerifications.shift()
  assert.throws(() => validateDeferredAcceptance({ deferred: missing, liveEvidence, repositoryRoot }), /M0-18/u)

  const listedPass = structuredClone(deferred)
  const m013 = structuredClone(listedPass.liveVerifications[0])
  Object.assign(m013, {
    id: 'LIVE-M0-13',
    milestone: 'M0-13',
    evidencePath: 'evidence/m0-13/live-verification.json',
  })
  listedPass.liveVerifications.splice(1, 0, m013)
  assert.throws(() => validateDeferredAcceptance({ deferred: listedPass, liveEvidence, repositoryRoot }), /M0-18/u)

  const incomplete = structuredClone(deferred)
  incomplete.liveVerifications[0].requiredChecks.pop()
  assert.throws(() => validateDeferredAcceptance({ deferred: incomplete, liveEvidence, repositoryRoot }), /M0-18/u)
})

test('strict schemas reject extra fields and malformed hashes', () => {
  const schema = path.join(repositoryRoot, 'evidence', 'm0-18', 'verification-report.schema.json')
  const example = readJson(path.join(repositoryRoot, 'evidence', 'm0-18', 'verification-report.example.json'))
  const extra = { ...example, unexpected: true }
  assert.throws(() => assertSchema(schema, extra, 'extra'), /M0-18/u)
  const badHash = structuredClone(example)
  badHash.digests.currentOpenApi = 'not-a-hash'
  assert.throws(() => assertSchema(schema, badHash, 'bad hash'), /M0-18/u)

  const ciStage = structuredClone(example)
  ciStage.validationMode = 'WINDOWS_X64_CI_STAGE'
  ciStage.gates.serverSmoke = 'NOT_RUN'
  ciStage.checks.serverSmokePassed = false
  ciStage.limitations.push('WINDOWS_FULL_CHAIN_NOT_RUN')
  assert.doesNotThrow(() => assertSchema(schema, ciStage, 'CI stage'))

  const falseFullChain = structuredClone(ciStage)
  falseFullChain.gates.serverSmoke = 'PASS'
  assert.throws(() => assertSchema(schema, falseFullChain, 'false full chain'), /M0-18/u)

  const missingBaseLimitation = structuredClone(ciStage)
  missingBaseLimitation.limitations = missingBaseLimitation.limitations.filter(
    (item) => item !== 'LIVE_EVIDENCE_NOT_IMPLIED',
  )
  assert.throws(() => assertSchema(schema, missingBaseLimitation, 'missing base limitation'), /M0-18/u)
})

test('portable handoff rejects tampering, missing and extra files', (context) => {
  const fixture = handoffFixture(context)
  assert.doesNotThrow(() => assertExactPayload(fixture.root, fixture.manifest, 'portable-handoff.json', fixture.commit))

  fs.appendFileSync(path.join(fixture.root, 'web', 'index.html'), 'tampered', 'utf8')
  assert.throws(() => assertExactPayload(fixture.root, fixture.manifest, 'portable-handoff.json', fixture.commit), /M0-18/u)
  fs.writeFileSync(path.join(fixture.root, 'web', 'index.html'), '<main>M0-18</main>', 'utf8')
  fs.rmSync(path.join(fixture.root, 'server', 'yumpoo-server.jar'))
  assert.throws(() => assertExactPayload(fixture.root, fixture.manifest, 'portable-handoff.json', fixture.commit), /M0-18/u)
  fs.writeFileSync(path.join(fixture.root, 'server', 'yumpoo-server.jar'), 'jar-bytes', 'utf8')
  fs.writeFileSync(path.join(fixture.root, 'web', 'extra.js'), 'extra', 'utf8')
  assert.throws(() => assertExactPayload(fixture.root, fixture.manifest, 'portable-handoff.json', fixture.commit), /M0-18/u)
})

test('portable handoff rejects Windows case collisions', { skip: process.platform === 'win32' }, (context) => {
  const fixture = handoffFixture(context)
  fs.writeFileSync(path.join(fixture.root, 'web', 'INDEX.HTML'), 'collision', 'utf8')
  assert.throws(
    () => fileRecords(fixture.root, collectRegularFiles(fixture.root), () => 'WEB_ASSET'),
    /M0-18/u,
  )
})

test('portable handoff rejects symbolic links', { skip: process.platform === 'win32' }, (context) => {
  const fixture = handoffFixture(context)
  fs.symlinkSync(path.join(fixture.root, 'server', 'yumpoo-server.jar'), path.join(fixture.root, 'web', 'jar-link'))
  assert.throws(() => collectRegularFiles(fixture.root), /M0-18/u)
})

test('evidence pack scanner rejects sensitive fields and absolute paths', (context) => {
  const safeRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-m018-safe-'))
  context.after(() => fs.rmSync(safeRoot, { recursive: true, force: true }))
  fs.writeFileSync(path.join(safeRoot, 'safe.json'), '{"sourceCommit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}\n', 'utf8')
  assert.doesNotThrow(() => assertNoSensitiveMaterial(safeRoot))
  fs.writeFileSync(path.join(safeRoot, 'secret.json'), '{"token":"sensitive-value"}\n', 'utf8')
  assert.throws(() => assertNoSensitiveMaterial(safeRoot), /M0-18/u)
  fs.writeFileSync(path.join(safeRoot, 'secret.json'), '{"memberFingerprint":"abcdef"}\n', 'utf8')
  assert.throws(() => assertNoSensitiveMaterial(safeRoot), /M0-18/u)
  fs.writeFileSync(path.join(safeRoot, 'secret.json'), '{"value":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJtMDE4LXVzZXIifQ.abcdefghijklmnopqrstuvwxyz012345"}\n', 'utf8')
  assert.throws(() => assertNoSensitiveMaterial(safeRoot), /M0-18/u)
  fs.writeFileSync(path.join(safeRoot, 'secret.json'), '{"value":"-----BEGIN PRIVATE KEY-----\\nabc\\n-----END PRIVATE KEY-----"}\n', 'utf8')
  assert.throws(() => assertNoSensitiveMaterial(safeRoot), /M0-18/u)

  const pathRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-m018-path-'))
  context.after(() => fs.rmSync(pathRoot, { recursive: true, force: true }))
  fs.writeFileSync(path.join(pathRoot, 'leak.json'), '{"diagnostic":"C:\\\\Users\\\\runner\\\\work"}\n', 'utf8')
  assert.throws(() => assertNoSensitiveMaterial(pathRoot), /M0-18/u)
})

test('path allowlist rejects traversal, absolute paths and backslashes', () => {
  assert.equal(isSafeRelativePath('m0-17/verification-report.json'), true)
  for (const candidate of ['../report.json', '/tmp/report.json', 'C:/report.json', 'm0-17\\report.json', 'm0-17//report.json']) {
    assert.equal(isSafeRelativePath(candidate), false)
  }
})

test('recursive output targets must stay inside the owned output directory', (context) => {
  const parent = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-m018-owned-'))
  context.after(() => fs.rmSync(parent, { recursive: true, force: true }))
  assert.equal(assertDirectoryTargetWithin(parent, path.join(parent, 'evidence-pack'), 'fixture'), path.join(parent, 'evidence-pack'))
  assert.throws(() => assertDirectoryTargetWithin(parent, parent, 'fixture'), /M0-18/u)
  assert.throws(() => assertDirectoryTargetWithin(parent, path.dirname(parent), 'fixture'), /M0-18/u)
})

test('full M0-18 runtime chain is Windows x64 only', () => {
  const full = fs.readFileSync(path.join(repositoryRoot, 'tools', 'verification', 'verify-m0-18.mjs'), 'utf8')
  const portable = fs.readFileSync(path.join(repositoryRoot, 'tools', 'verification', 'verify-m0-18-portable.mjs'), 'utf8')
  const windows = fs.readFileSync(path.join(repositoryRoot, 'tools', 'verification', 'verify-m0-18-windows.mjs'), 'utf8')
  const serverSmoke = fs.readFileSync(path.join(repositoryRoot, 'tools', 'verification', 'smoke-m0-16-server.mjs'), 'utf8')
  assert.match(full, /process\.platform === 'win32' && process\.arch === 'x64'/u)
  assert.match(full, /WINDOWS_X64_FULL/u)
  assert.doesNotMatch(full, /smoke:m0-16:server/u)
  assert.doesNotMatch(portable, /smoke:m0-16:server|smoke:desktop/u)
  assert.match(windows, /validationMode === 'WINDOWS_X64_FULL'[\s\S]+smoke:m0-16:server/u)
  assert.match(windows, /smoke:desktop/u)
  assert.match(serverSmoke, /process\.platform === 'win32' && process\.arch === 'x64'/u)
  assert.doesNotMatch(serverSmoke, /\bss\b|parseSsListeners/u)
})

test('workflow locks triggers, two stable jobs, fail-closed dependency and immutable action SHAs', () => {
  const workflowPath = path.join(repositoryRoot, '.github', 'workflows', 'm0-18-ci.yml')
  const source = fs.readFileSync(workflowPath, 'utf8')
  const workflow = YAML.parse(source)
  assert.equal(workflow.name, 'M0-18 CI')
  assert.deepEqual(Object.keys(workflow.on).sort(), ['pull_request', 'push'])
  assert.deepEqual(workflow.on.pull_request.branches, ['dev'])
  assert.deepEqual(workflow.on.push.branches, ['dev'])
  assert.deepEqual(workflow.permissions, { contents: 'read' })
  assert.deepEqual(Object.keys(workflow.jobs).sort(), ['linux', 'windows'])
  assert.equal(workflow.jobs.linux.name, 'M0 Portable Gate')
  assert.equal(workflow.jobs.linux['runs-on'], 'ubuntu-24.04')
  assert.equal(workflow.jobs.linux['timeout-minutes'], 60)
  assert.equal(workflow.jobs.windows.name, 'M0 Windows x64 Gate')
  assert.equal(workflow.jobs.windows['runs-on'], 'windows-2022')
  assert.equal(workflow.jobs.windows['timeout-minutes'], 30)
  assert.deepEqual(workflow.jobs.windows.needs, ['linux'])
  assert.equal(workflow.jobs.windows.if, '${{ always() }}')
  assert.match(source, /needs\.linux\.result[^\n]+success/u)
  assert.doesNotMatch(source, /^\s*(?:paths|paths-ignore):/mu)
  assert.doesNotMatch(source, /pull_request_target|continue-on-error|workflow_dispatch/u)
  assert.doesNotMatch(source, /xvfb-run|smoke:m0-16:server/u)
  assert.match(source, /YUMPOO_M018_VALIDATION_MODE:\s+WINDOWS_X64_CI_STAGE/u)
  assert.doesNotMatch(source, /^\s*strategy:/mu)
  assert.match(source, /retention-days:\s+1/u)
  assert.match(source, /retention-days:\s+30/u)
  assert.equal((source.match(/if-no-files-found:\s+error/gu) ?? []).length, 2)

  const uses = [...source.matchAll(/uses:\s+([^@\s]+)@([^\s]+)/gu)]
  assert.equal(uses.length, 11)
  assert(uses.every((match) => /^[0-9a-f]{40}$/u.test(match[2])))
  for (const sha of [
    '3d3c42e5aac5ba805825da76410c181273ba90b1',
    '820762786026740c76f36085b0efc47a31fe5020',
    'b6effb05e454b25005698d916606bdc6ffcbf961',
    '0977fd99725f1db4007ccb2928dbb4e90d06cc86',
    '043fb46d1a93c77aae656e7c1c64a875d1fc6a0a',
    '3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c',
  ]) {
    assert.match(source, new RegExp(sha, 'u'))
  }
})

function handoffFixture(context) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-m018-handoff-'))
  context.after(() => fs.rmSync(root, { recursive: true, force: true }))
  fs.mkdirSync(path.join(root, 'server'), { recursive: true })
  fs.mkdirSync(path.join(root, 'web'), { recursive: true })
  fs.writeFileSync(path.join(root, 'server', 'yumpoo-server.jar'), 'jar-bytes', 'utf8')
  fs.writeFileSync(path.join(root, 'web', 'index.html'), '<main>M0-18</main>', 'utf8')
  const files = fileRecords(root, collectRegularFiles(root), (relativePath) =>
    relativePath === 'server/yumpoo-server.jar' ? 'SERVER_JAR' : 'WEB_ASSET',
  )
  const commit = 'a'.repeat(40)
  fs.writeFileSync(path.join(root, 'portable-handoff.json'), '{}\n', 'utf8')
  return { root, commit, manifest: { sourceCommit: commit, files } }
}

function git(cwd, args) {
  const result = spawnSync('git', args, { cwd, encoding: 'utf8' })
  assert.equal(result.status, 0, `${result.stderr || result.stdout}`)
  return result.stdout
}
