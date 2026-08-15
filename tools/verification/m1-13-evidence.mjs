import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import {
  assertM018,
  assertSchema,
  isSafeRelativePath,
  readJson,
} from './m0-18-utils.mjs'

const expectedIam = Array.from({ length: 14 }, (_, index) => `IAM-${String(index + 1).padStart(3, '0')}`)
const expectedAcl = ['ACL-001', 'ACL-009', 'ACL-010', 'ACL-011', 'ACL-013', 'ACL-014']
const expectedApi = ['API-001', 'API-002', 'API-003', 'API-004']
const expectedM2Acl = ['ACL-002', 'ACL-003', 'ACL-004', 'ACL-005', 'ACL-006', 'ACL-007', 'ACL-008', 'ACL-012']

export function validateM113Evidence(repositoryRoot, { requireReport = false } = {}) {
  const evidenceRoot = path.join(repositoryRoot, 'evidence', 'm1-13')
  const matrixPath = path.join(evidenceRoot, 'acceptance-matrix.json')
  const matrix = readJson(matrixPath)
  assertSchema(
    path.join(evidenceRoot, 'acceptance-matrix.schema.json'),
    matrix,
    'M1-13 acceptance matrix',
  )
  assertSchema(
    path.join(evidenceRoot, 'verification-report.schema.json'),
    readJson(path.join(evidenceRoot, 'verification-report.example.json')),
    'M1-13 verification report example',
  )

  assertExact(matrix.requirements.iam, expectedIam, 'M1-13 IAM')
  assertExact(matrix.requirements.acl, expectedAcl, 'M1-13 ACL')
  assertExact(matrix.requirements.api, expectedApi, 'M1-13 API')
  assertExact(matrix.deferred.m2Acl, expectedM2Acl, 'M2 deferred ACL')

  const required = new Set([...expectedIam, ...expectedAcl, ...expectedApi])
  const covered = new Set()
  for (const item of matrix.coverage) {
    assertM018(isSafeRelativePath(item.evidencePath), `M1-13 evidence path is unsafe: ${item.evidencePath}`)
    const evidencePath = path.resolve(repositoryRoot, ...item.evidencePath.split('/'))
    assertM018(evidencePath.startsWith(`${path.resolve(repositoryRoot)}${path.sep}`), `M1-13 evidence path escaped repository: ${item.evidencePath}`)
    const metadata = fs.lstatSync(evidencePath, { throwIfNoEntry: false })
    assertM018(metadata?.isFile() && !metadata.isSymbolicLink(), `M1-13 evidence is missing or not a regular file: ${item.evidencePath}`)
    assertM018(new Set(item.requirementIds).size === item.requirementIds.length, `M1-13 coverage contains duplicate IDs: ${item.evidencePath}`)
    for (const id of item.requirementIds) {
      assertM018(required.has(id), `M1-13 coverage contains an out-of-scope ID: ${id}`)
      covered.add(id)
    }
  }
  assertExact([...covered].sort(), [...required].sort(), 'M1-13 covered requirement union')

  const liveEvidence = matrix.deferred.liveVerifications.map((item) => {
    assertM018(isSafeRelativePath(item.evidencePath), `M1-13 live evidence path is unsafe: ${item.evidencePath}`)
    const evidencePath = path.join(repositoryRoot, ...item.evidencePath.split('/'))
    const schemaPath = path.join(path.dirname(evidencePath), 'live-verification.schema.json')
    const evidence = readJson(evidencePath)
    assertSchema(schemaPath, evidence, `${item.milestone} live evidence`)
    assertM018(evidence.milestone === item.milestone, `${item.milestone} live evidence milestone mismatch`)
    assertM018(['NOT_RUN', 'ENV_PENDING', 'PASS'].includes(evidence.status), `${item.milestone} live evidence status is unsupported`)
    return {
      milestone: item.milestone,
      status: evidence.status,
      targetGate: item.targetGate,
    }
  })

  validateFixtureSource(repositoryRoot)

  let report = null
  const reportPath = path.join(repositoryRoot, 'out', 'm1-13', 'verification-report.json')
  if (requireReport) {
    report = readJson(reportPath, 'M1-13 runtime verification report')
    assertSchema(
      path.join(evidenceRoot, 'verification-report.schema.json'),
      report,
      'M1-13 runtime verification report',
    )
    validateFreshReport(repositoryRoot, report, liveEvidence)
  }
  return { matrix, liveEvidence, report }
}

function validateFixtureSource(repositoryRoot) {
  const runnerPath = path.join(
    repositoryRoot,
    'backend', 'src', 'main', 'java', 'com', 'yumpoo', 'platform',
    'identityaccess', 'api', 'M113FixtureRunner.java',
  )
  const runner = fs.readFileSync(runnerPath, 'utf8')
  assertM018(runner.includes('IdentityAcceptanceFixtureProvisioner'), 'M1-13 fixture must provision through the application service')
  assertM018(runner.includes('PlatformRoleCommandPort'), 'M1-13 fixture must grant through the public command port')
  assertM018(!runner.includes('JdbcClient') && !runner.match(/@(Get|Post|Put|Delete|Patch)Mapping/u), 'M1-13 fixture must not expose HTTP or JDBC writes')

  const stateQueryPath = path.join(
    repositoryRoot,
    'backend', 'src', 'main', 'java', 'com', 'yumpoo', 'platform',
    'identityaccess', 'infrastructure', 'verification',
    'JdbcIdentityAcceptanceFixtureStateQuery.java',
  )
  const stateQuery = fs.readFileSync(stateQueryPath, 'utf8')
  assertM018(!stateQuery.match(/\b(INSERT|UPDATE|DELETE|MERGE|TRUNCATE)\b/u), 'M1-13 fixture state adapter must remain read-only')
}

function validateFreshReport(repositoryRoot, report, liveEvidence) {
  const currentSha = gitSha(repositoryRoot)
  assertM018(report.gitSha === currentSha, 'M1-13 report is not bound to the current Git SHA')
  const startedAt = Date.parse(report.startedAt)
  const completedAt = Date.parse(report.completedAt)
  const now = Date.now()
  assertM018(Number.isFinite(startedAt) && Number.isFinite(completedAt), 'M1-13 report timestamps are invalid')
  assertM018(startedAt <= completedAt, 'M1-13 report completion precedes start')
  assertM018(completedAt <= now + 5 * 60_000 && completedAt >= now - 30 * 60_000, 'M1-13 report is stale or from the future')
  const gateStartedAt = process.env.YUMPOO_M113_STARTED_AT
  if (gateStartedAt) {
    assertM018(startedAt >= Date.parse(gateStartedAt), 'M1-13 report predates the current gate run')
  }
  assertM018(JSON.stringify(report.liveEvidence) === JSON.stringify(liveEvidence), 'M1-13 report live evidence snapshot is stale')
  const serialized = JSON.stringify(report)
  assertM018(!serialized.includes(path.resolve(repositoryRoot)), 'M1-13 report exposes an absolute repository path')
  const stringValues = collectStringValues(report)
  assertM018(!stringValues.some((value) => value.match(/(__Host-|member-id|session-key|password|secret)/iu)), 'M1-13 report exposes sensitive runtime data')
}

function collectStringValues(value) {
  if (typeof value === 'string') return [value]
  if (Array.isArray(value)) return value.flatMap(collectStringValues)
  if (value && typeof value === 'object') return Object.values(value).flatMap(collectStringValues)
  return []
}

function gitSha(repositoryRoot) {
  const result = spawnSync('git', ['rev-parse', 'HEAD'], {
    cwd: repositoryRoot,
    encoding: 'utf8',
  })
  assertM018(result.status === 0, 'Unable to resolve current Git SHA for M1-13 evidence')
  return result.stdout.trim()
}

function assertExact(actual, expected, label) {
  assertM018(new Set(actual).size === actual.length, `${label} contains duplicates`)
  assertM018(JSON.stringify(actual) === JSON.stringify(expected), `${label} set or order is incorrect`)
}
