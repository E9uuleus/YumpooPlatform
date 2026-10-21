import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import Ajv from 'ajv'
import addFormats from 'ajv-formats'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const schemaRoot = path.join(repositoryRoot, 'tools', 'verification', 'schemas')
const outputRoot = path.join(repositoryRoot, 'out', 'backup-restore')
function fail(message) {
  throw new Error(`M0-17 验证失败：${message}`)
}

function assert(condition, message) {
  if (!condition) fail(message)
}

function readJson(file, label) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'))
  } catch {
    fail(`${label} 不是有效 JSON`)
  }
}

function validator(schemaName) {
  const ajv = new Ajv({ allErrors: true, strict: false })
  addFormats(ajv)
  return ajv.compile(readJson(path.join(schemaRoot, schemaName), schemaName))
}

function validateGenerated(startedAt) {
  const manifestPath = path.join(outputRoot, 'backup-set', 'manifest.json')
  const retentionPath = path.join(outputRoot, 'retention-plan.json')
  const reportPath = path.join(outputRoot, 'verification-report.json')
  const manifest = validateFile('backup-restore-manifest.schema.json', manifestPath, '运行 manifest')
  const retention = validateFile('backup-restore-retention-plan.schema.json', retentionPath, '运行 retention plan')
  const report = validateFile('backup-restore-report.schema.json', reportPath, '运行 verification report')

  const paths = manifest.files.map((file) => file.path)
  assert(paths.join('\n') === [...paths].sort().join('\n'), 'manifest files 未严格排序')
  assert(new Set(paths).size === paths.length, 'manifest files 含重复路径')
  assert(
    new Set(paths.map((value) => value.toLocaleLowerCase('en-US'))).size === paths.length,
    'manifest files 含 Windows 大小写碰撞',
  )
  const database = manifest.files.filter((file) => file.role === 'DATABASE_DUMP')
  assert(database.length === 1 && database[0].path === manifest.databaseDumpPath, '数据库 dump 角色不唯一')
  assert(manifest.files.filter((file) => file.role === 'ATTACHMENT_BLOB').length >= 3, '附件样本不足')

  const currentCommit = gitHead()
  assert(manifest.sourceCommit === currentCommit, 'manifest sourceCommit 不是当前 HEAD')
  assert(report.sourceCommit === currentCommit, 'verification report sourceCommit 不是当前 HEAD')
  assert(report.backupSetId === manifest.backupSetId, '报告与 manifest 的 backupSetId 不一致')
  assert(Date.parse(report.startedAt) >= startedAt - 5_000, '运行报告不是本次门禁生成')
  assert(Date.parse(report.completedAt) >= Date.parse(report.startedAt), '运行报告时间顺序无效')
  assert(Date.parse(report.completedAt) <= Date.now() + 120_000, '运行报告完成时间位于未来')

  for (const [label, expected] of [['daily', 14], ['weekly', 8], ['monthly', 6]]) {
    const count = retention.decisions.filter((decision) => decision.labels.includes(label)).length
    assert(count === expected, `${label} 保留代际应为 ${expected}，实际为 ${count}`)
  }
  assert(
    retention.decisions.some((decision) => decision.labels.length > 1),
    'retention plan 未证明多标签集合',
  )
  assert(
    retention.decisions.some((decision) => decision.legalHold && !decision.deletionEligible),
    'retention plan 未证明 legal hold',
  )
  console.log(`M0-17 本地备份/隔离恢复已通过：${path.relative(repositoryRoot, reportPath)}`)
}

function validateFile(schemaName, file, label) {
  const validate = validator(schemaName)
  const value = readJson(file, label)
  assert(validate(value), `${label} 不符合 ${schemaName}：${ajvErrors(validate)}`)
  return value
}

function gitHead() {
  const gitMarker = path.join(repositoryRoot, '.git')
  const gitDirectory = fs.statSync(gitMarker).isDirectory()
    ? gitMarker
    : path.resolve(
        repositoryRoot,
        fs.readFileSync(gitMarker, 'utf8').trim().replace(/^gitdir:\s*/u, ''),
      )
  const head = fs.readFileSync(path.join(gitDirectory, 'HEAD'), 'utf8').trim()
  if (/^[0-9a-f]{40}$/u.test(head)) return head
  const match = head.match(/^ref:\s+(.+)$/u)
  assert(match, '无法解析当前 Git HEAD')
  const looseRef = path.join(gitDirectory, ...match[1].split('/'))
  if (fs.existsSync(looseRef)) {
    const value = fs.readFileSync(looseRef, 'utf8').trim()
    assert(/^[0-9a-f]{40}$/u.test(value), '当前 Git HEAD ref 格式无效')
    return value
  }
  const packedRefs = fs.readFileSync(path.join(gitDirectory, 'packed-refs'), 'utf8')
  const packed = packedRefs
    .split(/\r?\n/u)
    .find((line) => line.endsWith(` ${match[1]}`))
    ?.split(' ')[0]
  assert(/^[0-9a-f]{40}$/u.test(packed ?? ''), '无法读取当前 Git HEAD ref')
  return packed
}

function ajvErrors(validate) {
  return (validate.errors ?? [])
    .map((error) => `${error.instancePath || '/'} ${error.message}`)
    .join('; ')
}

const startedAt = Number(process.argv[2])
assert(Number.isFinite(startedAt) && startedAt > 0, '需要本次后端门禁的毫秒时间戳')
validateGenerated(startedAt)
