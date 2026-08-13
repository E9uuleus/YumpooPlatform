import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  assertM018,
  assertDirectoryTargetWithin,
  assertSchema,
  atomicWriteJson,
  collectRegularFiles,
  commitPattern,
  copyTree,
  fileRecords,
  gitHead,
  readJson,
  requireCommit,
  sha256File,
} from './m0-18-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const outputRoot = path.resolve(
  process.env.YUMPOO_M018_HANDOFF_ROOT ?? path.join(repositoryRoot, 'out', 'm0-18', 'portable-handoff'),
)
const partialRoot = `${outputRoot}.partial-${process.pid}-${Date.now()}`
const baselinePath = path.resolve(
  process.env.YUMPOO_M018_BASELINE_PATH ?? path.join(repositoryRoot, 'out', 'm0-18', 'openapi-baseline.yaml'),
)
const baselineMetadataPath = path.resolve(
  process.env.YUMPOO_M018_BASELINE_METADATA_PATH ?? path.join(repositoryRoot, 'out', 'm0-18', 'openapi-baseline.metadata.json'),
)
const jarPath = path.join(repositoryRoot, 'backend', 'target', 'yumpoo-server.jar')
const webRoot = path.join(repositoryRoot, 'frontend', 'web-app', 'dist')
const currentOpenApiPath = path.join(repositoryRoot, 'contracts', 'openapi', 'yumpoo-v1.yaml')
const schemaPath = path.join(repositoryRoot, 'evidence', 'm0-18', 'portable-handoff.schema.json')
assertDirectoryTargetWithin(path.join(repositoryRoot, 'out', 'm0-18'), outputRoot, 'portable handoff 输出目录')
assertDirectoryTargetWithin(path.join(repositoryRoot, 'out', 'm0-18'), partialRoot, 'portable handoff 临时目录')

assertM018(fs.statSync(jarPath, { throwIfNoEntry: false })?.isFile(), 'portable handoff 缺少已验证 JAR')
assertM018(fs.statSync(webRoot, { throwIfNoEntry: false })?.isDirectory(), 'portable handoff 缺少已构建 Web dist')
assertM018(fs.statSync(baselinePath, { throwIfNoEntry: false })?.isFile(), 'portable handoff 缺少 OpenAPI 基线')
const baseline = readJson(baselineMetadataPath, 'OpenAPI 基线 metadata')
assertM018(baseline.schemaVersion === 1 && baseline.milestone === 'M0-18', 'OpenAPI 基线 metadata 版本错误')
assertM018(commitPattern.test(baseline.baseCommit), 'OpenAPI 基线 metadata commit 无效')
assertM018(baseline.sha256 === sha256File(baselinePath), 'OpenAPI 基线 metadata 摘要不匹配')

const sourceCommit = gitHead(repositoryRoot)
const headCommit = requireCommit(process.env.YUMPOO_M018_HEAD_COMMIT ?? sourceCommit, 'headCommit')
const testedCommit = requireCommit(process.env.YUMPOO_M018_TESTED_COMMIT ?? sourceCommit, 'testedCommit')
assertM018(testedCommit === sourceCommit, 'portable handoff testedCommit 必须等于 checkout HEAD')
const startedAt = process.env.YUMPOO_M018_STARTED_AT
assertM018(typeof startedAt === 'string' && Number.isFinite(Date.parse(startedAt)), 'portable handoff 缺少有效 startedAt')

fs.rmSync(partialRoot, { recursive: true, force: true })
fs.mkdirSync(path.join(partialRoot, 'server'), { recursive: true })
fs.copyFileSync(jarPath, path.join(partialRoot, 'server', 'yumpoo-server.jar'))
copyTree(webRoot, path.join(partialRoot, 'web'), { rejectSourceMaps: true, label: 'Web dist' })
const files = fileRecords(partialRoot, collectRegularFiles(partialRoot), (relativePath) => {
  if (relativePath === 'server/yumpoo-server.jar') return 'SERVER_JAR'
  if (relativePath.startsWith('web/')) return 'WEB_ASSET'
  return ''
})
assertM018(files.some((file) => file.path === 'web/index.html'), 'portable handoff 缺少 web/index.html')
const manifest = {
  schemaVersion: 1,
  milestone: 'M0-18',
  startedAt,
  generatedAt: new Date().toISOString(),
  sourceCommit,
  baseCommit: baseline.baseCommit,
  headCommit,
  testedCommit,
  baselineOpenApiSha256: baseline.sha256,
  currentOpenApiSha256: sha256File(currentOpenApiPath),
  checks: {
    contracts: true,
    openApiCompatibility: true,
    buildMigrationArchitectureTests: true,
    backupRestore: true,
    sensitiveDataExcluded: true,
  },
  files,
}
assertM018(Date.parse(manifest.generatedAt) >= Date.parse(startedAt), 'portable handoff 时间顺序无效')
assertSchema(schemaPath, manifest, 'portable-handoff.json')
atomicWriteJson(path.join(partialRoot, 'portable-handoff.json'), manifest)
fs.rmSync(outputRoot, { recursive: true, force: true })
fs.renameSync(partialRoot, outputRoot)
console.log(`M0-18 portable handoff 已生成：${path.relative(repositoryRoot, outputRoot)}`)
