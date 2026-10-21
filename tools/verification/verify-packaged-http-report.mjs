import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { assertDelivery, assertSchema, readJson, gitHead } from './delivery-utils.mjs'

function validateFreshReport(repositoryRoot, report) {
  const currentSha = gitHead(repositoryRoot)
  assertDelivery(report.gitSha === currentSha, 'PACKAGED_HTTP report is not bound to the current Git SHA')
  const startedAt = Date.parse(report.startedAt)
  const completedAt = Date.parse(report.completedAt)
  const now = Date.now()
  assertDelivery(Number.isFinite(startedAt) && Number.isFinite(completedAt), 'PACKAGED_HTTP report timestamps are invalid')
  assertDelivery(startedAt <= completedAt, 'PACKAGED_HTTP report completion precedes start')
  assertDelivery(completedAt <= now + 5 * 60_000 && completedAt >= now - 30 * 60_000, 'PACKAGED_HTTP report is stale or from the future')
  const gateStartedAt = process.env.YUMPOO_M113_STARTED_AT
  if (gateStartedAt) {
    assertDelivery(startedAt >= Date.parse(gateStartedAt), 'PACKAGED_HTTP report predates the current gate run')
  }
  const serialized = JSON.stringify(report)
  assertDelivery(!serialized.includes(path.resolve(repositoryRoot)), 'PACKAGED_HTTP report exposes an absolute repository path')
  const stringValues = collectStringValues(report)
  assertDelivery(!stringValues.some((value) => value.match(/(__Host-|member-id|session-key|password|secret)/iu)), 'PACKAGED_HTTP report exposes sensitive runtime data')
}

function collectStringValues(value) {
  if (typeof value === 'string') return [value]
  if (Array.isArray(value)) return value.flatMap(collectStringValues)
  if (value && typeof value === 'object') return Object.values(value).flatMap(collectStringValues)
  return []
}


const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const report = readJson(path.join(repositoryRoot, 'out', 'packaged-http', 'verification-report.json'))
assertSchema(path.join(repositoryRoot, 'tools', 'verification', 'schemas', 'packaged-http-report.schema.json'), report, 'packaged HTTP report')
validateFreshReport(repositoryRoot, report)
console.log('Packaged HTTP report 已校验')
