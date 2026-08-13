import fs from 'node:fs'
import path from 'node:path'
import {
  assertM018,
  assertSchema,
  isSafeRelativePath,
  readJson,
} from './m0-18-utils.mjs'

const requiredM017FollowUps = [
  'SCHEDULED_BACKUP',
  'OFF_HOST_COPY',
  'FAILURE_ALERTING',
  'INTEGRITY_AND_SECRET_RECOVERY',
  'REAL_SCHEMA_RESTORE',
  'RETENTION_CLEANUP',
  'RPO_RTO_DRILL',
]

export function loadLiveEvidence(evidenceRoot) {
  const entries = []
  for (const directory of fs.readdirSync(evidenceRoot, { withFileTypes: true })
    .filter((entry) => /^m0-[0-9]{2}$/u.test(entry.name))
    .sort((left, right) => left.name.localeCompare(right.name, 'en'))) {
    const directoryPath = path.join(evidenceRoot, directory.name)
    const directoryMetadata = fs.lstatSync(directoryPath)
    assertM018(
      directory.isDirectory() && directoryMetadata.isDirectory() && !directoryMetadata.isSymbolicLink(),
      `${directory.name} evidence 必须是普通目录`,
    )
    const evidencePath = path.join(directoryPath, 'live-verification.json')
    if (!fs.existsSync(evidencePath)) continue
    const metadata = fs.lstatSync(evidencePath)
    assertM018(metadata.isFile() && !metadata.isSymbolicLink(), `${directory.name} live evidence 必须是普通文件`)
    const schemaPath = path.join(directoryPath, 'live-verification.schema.json')
    const examplePath = path.join(directoryPath, 'live-verification.example.json')
    assertM018(fs.existsSync(schemaPath) && fs.existsSync(examplePath), `${directory.name} 缺少 live evidence Schema 或示例`)
    for (const [file, label] of [[schemaPath, 'Schema'], [examplePath, '示例']]) {
      const fileMetadata = fs.lstatSync(file)
      assertM018(fileMetadata.isFile() && !fileMetadata.isSymbolicLink(), `${directory.name} live evidence ${label} 必须是普通文件`)
    }
    const schema = readJson(schemaPath)
    const current = readJson(evidencePath)
    const example = readJson(examplePath)
    assertSchema(schemaPath, example, `${directory.name} live evidence 示例`)
    assertSchema(schemaPath, current, `${directory.name} live evidence`)
    assertM018(current.milestone.toLocaleLowerCase('en-US') === directory.name, `${directory.name} milestone 与目录不一致`)
    const requiredChecks = schema?.properties?.checks?.required
    assertM018(Array.isArray(requiredChecks) && requiredChecks.length > 0, `${directory.name} live Schema 缺少 checks.required`)
    entries.push({
      milestone: current.milestone,
      relativePath: `evidence/${directory.name}/live-verification.json`,
      status: current.status,
      requiredChecks: [...requiredChecks].sort((a, b) => a.localeCompare(b, 'en')),
    })
  }
  return entries
}

export function validateDeferredAcceptance({ deferred, liveEvidence, repositoryRoot }) {
  const packageScripts = readJson(path.join(repositoryRoot, 'package.json'), 'package.json').scripts ?? {}
  const liveItems = deferred.liveVerifications
  const itemMilestones = liveItems.map((item) => item.milestone)
  assertM018(new Set(itemMilestones).size === itemMilestones.length, '延期清单 live milestone 重复')
  assertM018(
    JSON.stringify(itemMilestones) === JSON.stringify([...itemMilestones].sort((a, b) => a.localeCompare(b, 'en'))),
    '延期清单 liveVerifications 必须按 milestone 排序',
  )
  const actualNotRun = liveEvidence
    .filter((entry) => entry.status === 'NOT_RUN')
    .map((entry) => `${entry.milestone}|${entry.relativePath}`)
    .sort()
  const declaredNotRun = liveItems
    .map((item) => `${item.milestone}|${item.evidencePath}`)
    .sort()
  assertM018(JSON.stringify(actualNotRun) === JSON.stringify(declaredNotRun), '延期清单与实际 live NOT_RUN 集合不一致')

  const byMilestone = new Map(liveEvidence.map((entry) => [entry.milestone, entry]))
  for (const item of liveItems) {
    const live = byMilestone.get(item.milestone)
    assertM018(live?.status === 'NOT_RUN', `${item.milestone} 不是实际 NOT_RUN evidence`)
    assertM018(item.id === `LIVE-${item.milestone}`, `${item.milestone} 延期 ID 不正确`)
    assertM018(item.evidencePath === live.relativePath, `${item.milestone} evidencePath 不正确`)
    const liveScript = `verify:${item.milestone.toLocaleLowerCase('en-US')}:live`
    const expectedCommand = Object.hasOwn(packageScripts, liveScript) ? `pnpm ${liveScript}` : null
    assertM018(item.acceptanceCommand === expectedCommand, `${item.milestone} acceptanceCommand 与实际脚本不一致`)
    const sortedChecks = [...item.requiredChecks].sort((a, b) => a.localeCompare(b, 'en'))
    assertM018(new Set(sortedChecks).size === sortedChecks.length, `${item.milestone} requiredChecks 重复`)
    assertM018(JSON.stringify(item.requiredChecks) === JSON.stringify(sortedChecks), `${item.milestone} requiredChecks 未排序`)
    assertM018(JSON.stringify(sortedChecks) === JSON.stringify(live.requiredChecks), `${item.milestone} requiredChecks 与 live Schema 不一致`)
  }
  for (const entry of liveEvidence.filter((item) => item.status === 'PASS')) {
    assertM018(!itemMilestones.includes(entry.milestone), `${entry.milestone} 已 PASS，不得进入延期清单`)
  }

  const followUps = deferred.m017OperationalFollowUps
  const followUpIds = followUps.map((item) => item.id)
  assertM018(JSON.stringify(followUpIds) === JSON.stringify(requiredM017FollowUps), 'M0-17 后续运维项必须完整且按规范顺序排列')
  for (const followUp of followUps) {
    if (followUp.status === 'PASS') {
      assertM018(isSafeRelativePath(followUp.completionEvidence), `${followUp.id} completionEvidence 路径不安全`)
      const evidencePath = path.resolve(repositoryRoot, ...followUp.completionEvidence.split('/'))
      assertM018(evidencePath.startsWith(`${path.resolve(repositoryRoot)}${path.sep}`), `${followUp.id} completionEvidence 越界`)
      const metadata = fs.lstatSync(evidencePath, { throwIfNoEntry: false })
      assertM018(metadata?.isFile() && !metadata.isSymbolicLink(), `${followUp.id} completionEvidence 不存在或不是普通文件`)
    } else {
      assertM018(followUp.completionEvidence === null, `${followUp.id} 未完成时不得填写完成证据`)
    }
  }
}

export function validateM018EvidenceContracts(repositoryRoot) {
  const evidenceRoot = path.join(repositoryRoot, 'evidence')
  const m018Root = path.join(evidenceRoot, 'm0-18')
  const pairs = [
    ['deferred-acceptance.schema.json', 'deferred-acceptance.example.json'],
    ['portable-handoff.schema.json', 'portable-handoff.example.json'],
    ['verification-report.schema.json', 'verification-report.example.json'],
    ['evidence-manifest.schema.json', 'evidence-manifest.example.json'],
  ]
  for (const [schemaName, exampleName] of pairs) {
    const schemaPath = path.join(m018Root, schemaName)
    assertSchema(schemaPath, readJson(path.join(m018Root, exampleName)), exampleName)
  }
  const deferredPath = path.join(m018Root, 'deferred-acceptance.json')
  const deferred = readJson(deferredPath)
  assertSchema(path.join(m018Root, 'deferred-acceptance.schema.json'), deferred, 'deferred-acceptance.json')
  const liveEvidence = loadLiveEvidence(evidenceRoot)
  validateDeferredAcceptance({ deferred, liveEvidence, repositoryRoot })
  return { deferred, liveEvidence }
}
