import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import {
  assertM018,
  assertDirectoryTargetWithin,
  assertSchema,
  atomicWriteJson,
  collectRegularFiles,
  fileRecords,
  gitHead,
  isSafeRelativePath,
  readJson,
  sha256File,
  sha256Pattern,
} from './m0-18-utils.mjs'
import { verifyM018Handoff } from './verify-m0-18-handoff.mjs'

const fileRoles = new Map([
  ['deferred-acceptance.json', 'DEFERRED_ACCEPTANCE'],
  ['m0-15/artifact-manifest.json', 'M015_MANIFEST'],
  ['m0-16/artifact-manifest.json', 'M016_MANIFEST'],
  ['m0-16/yumpoo-windows-m0-16.zip.sha256', 'M016_ZIP_DIGEST'],
  ['m0-17/backup-manifest.json', 'M017_BACKUP_MANIFEST'],
  ['m0-17/retention-plan.json', 'M017_RETENTION_PLAN'],
  ['m0-17/verification-report.json', 'M017_VERIFICATION_REPORT'],
  ['portable/portable-handoff.json', 'PORTABLE_HANDOFF'],
  ['verification-report.json', 'VERIFICATION_REPORT'],
])

const forbiddenJsonKeys = new Set([
  'authorization',
  'cookie',
  'cookies',
  'codeverifier',
  'connectionstring',
  'databaseurl',
  'environmentvariables',
  'fingerprint',
  'hostname',
  'ipaddress',
  'jdbcurl',
  'nonce',
  'oauthcode',
  'password',
  'passwd',
  'processenv',
  'rawenvironment',
  'receipt',
  'refreshtoken',
  'signature',
  'stderr',
  'stdout',
  'token',
  'username',
])

export function assertNoSensitiveMaterial(root, files = collectRegularFiles(root), forbiddenAbsoluteRoot) {
  for (const file of files) {
    const relativePath = path.relative(root, file).replaceAll('\\', '/')
    assertM018(isSafeRelativePath(relativePath), `证据包路径不安全：${relativePath}`)
    const bytes = fs.readFileSync(file)
    assertM018(!bytes.includes(0), `证据包只允许文本安全元数据：${relativePath}`)
    const text = bytes.toString('utf8')
    assertM018(!/-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----/u.test(text), `证据包疑似包含私钥：${relativePath}`)
    assertM018(!/[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}/u.test(text), `证据包疑似包含 JWT：${relativePath}`)
    assertM018(!/https?:\/\/[^/\s:@]+:[^@\s]+@/iu.test(text), `证据包 URI 包含 userinfo：${relativePath}`)
    assertM018(!/(?:^|["'\s])(?:[A-Za-z]:[\\/]|\\\\[^\\\s]+\\)/mu.test(text), `证据包包含 Windows 绝对路径：${relativePath}`)
    assertM018(!/(?:^|["'\s])\/(?:home|Users|workspace|workspaces|tmp|__w|var\/lib\/jenkins)\//mu.test(text), `证据包包含主机绝对路径：${relativePath}`)
    if (forbiddenAbsoluteRoot) {
      assertM018(!text.includes(path.resolve(forbiddenAbsoluteRoot)), `证据包泄露仓库绝对路径：${relativePath}`)
    }
    if (relativePath.endsWith('.json')) {
      walkJson(readJson(file, relativePath), relativePath)
    }
  }
}

function walkJson(value, relativePath) {
  if (Array.isArray(value)) {
    for (const item of value) walkJson(item, relativePath)
    return
  }
  if (!value || typeof value !== 'object') return
  for (const [key, child] of Object.entries(value)) {
    const normalized = key.replaceAll(/[-_]/gu, '').toLocaleLowerCase('en-US')
    assertM018(
      !forbiddenJsonKeys.has(normalized) && !normalized.endsWith('fingerprint'),
      `证据包包含敏感字段 ${key}：${relativePath}`,
    )
    walkJson(child, relativePath)
  }
}

export function verifyEvidencePack(repositoryRoot, outputRoot, expectedCommit = gitHead(repositoryRoot)) {
  const manifestPath = path.join(outputRoot, 'manifest.json')
  const reportPath = path.join(outputRoot, 'verification-report.json')
  const manifest = readJson(manifestPath, 'M0-18 evidence manifest')
  const report = readJson(reportPath, 'M0-18 verification report')
  assertSchema(path.join(repositoryRoot, 'evidence', 'm0-18', 'evidence-manifest.schema.json'), manifest, 'manifest.json')
  assertSchema(path.join(repositoryRoot, 'evidence', 'm0-18', 'verification-report.schema.json'), report, 'verification-report.json')
  assertM018(manifest.sourceCommit === expectedCommit, 'evidence manifest 未绑定当前 checkout')
  assertM018(report.sourceCommit === expectedCommit && report.testedCommit === expectedCommit, 'verification report 未绑定当前 checkout')
  assertM018(Date.parse(report.completedAt) >= Date.parse(report.startedAt), 'verification report 时间顺序无效')
  assertM018(Date.parse(report.completedAt) <= Date.now() + 120_000, 'verification report 完成时间位于未来')

  const actualFiles = collectRegularFiles(outputRoot).filter((file) => path.resolve(file) !== path.resolve(manifestPath))
  const records = fileRecords(outputRoot, actualFiles, (relativePath) => fileRoles.get(relativePath) ?? '')
  assertM018(JSON.stringify(records) === JSON.stringify(manifest.files), 'evidence manifest 与实际文件、角色、大小或 SHA-256 不一致')
  assertM018(records.length === fileRoles.size, '证据包文件数量不符合白名单')
  assertNoSensitiveMaterial(outputRoot, collectRegularFiles(outputRoot), repositoryRoot)
  return { manifest, report }
}

export function createEvidencePack(repositoryRoot, options = {}) {
  const handoffRoot = path.resolve(
    options.handoffRoot ??
      process.env.YUMPOO_M018_HANDOFF_ROOT ??
      path.join(repositoryRoot, 'out', 'm0-18', 'portable-handoff'),
  )
  const m015OutputRoot = path.resolve(
    options.m015OutputRoot ??
      process.env.YUMPOO_M015_OUTPUT_ROOT ??
      path.join(repositoryRoot, 'desktop', 'desktop-shell', 'out', 'm0-18-windows', 'm0-15'),
  )
  const outputRoot = path.resolve(
    options.outputRoot ??
      process.env.YUMPOO_M018_EVIDENCE_PACK_ROOT ??
      path.join(repositoryRoot, 'out', 'm0-18', 'evidence-pack'),
  )
  const partialRoot = `${outputRoot}.partial-${process.pid}-${Date.now()}`
  assertDirectoryTargetWithin(path.join(repositoryRoot, 'out', 'm0-18'), outputRoot, 'M0-18 证据包输出目录')
  assertDirectoryTargetWithin(path.join(repositoryRoot, 'out', 'm0-18'), partialRoot, 'M0-18 证据包临时目录')
  const sourceCommit = gitHead(repositoryRoot)
  const handoff = verifyM018Handoff(repositoryRoot, handoffRoot, { expectedCommit: sourceCommit })
  const evidenceRoot = path.join(repositoryRoot, 'evidence')
  const outputM016Root = path.join(repositoryRoot, 'out', 'm0-16')
  const outputM017Root = path.join(repositoryRoot, 'out', 'm0-17')
  const sources = [
    ['deferred-acceptance.json', path.join(evidenceRoot, 'm0-18', 'deferred-acceptance.json')],
    ['m0-15/artifact-manifest.json', path.join(m015OutputRoot, 'm0-15-artifact-manifest.json')],
    ['m0-16/artifact-manifest.json', path.join(outputM016Root, 'verified', 'artifact-manifest.json')],
    ['m0-16/yumpoo-windows-m0-16.zip.sha256', path.join(outputM016Root, 'yumpoo-windows-m0-16.zip.sha256')],
    ['m0-17/backup-manifest.json', path.join(outputM017Root, 'backup-set', 'manifest.json')],
    ['m0-17/retention-plan.json', path.join(outputM017Root, 'retention-plan.json')],
    ['m0-17/verification-report.json', path.join(outputM017Root, 'verification-report.json')],
    ['portable/portable-handoff.json', path.join(handoffRoot, 'portable-handoff.json')],
  ]

  fs.rmSync(partialRoot, { recursive: true, force: true })
  try {
    for (const [destination, source] of sources) copySafeMetadata(source, path.join(partialRoot, ...destination.split('/')))
    validateSourceMetadata(repositoryRoot, partialRoot, handoff, sourceCommit, outputM016Root)
    const report = createVerificationReport(repositoryRoot, partialRoot, handoff, sourceCommit)
    assertSchema(
      path.join(repositoryRoot, 'evidence', 'm0-18', 'verification-report.schema.json'),
      report,
      'verification-report.json',
    )
    atomicWriteJson(path.join(partialRoot, 'verification-report.json'), report)

    const packFiles = collectRegularFiles(partialRoot)
    const records = fileRecords(partialRoot, packFiles, (relativePath) => fileRoles.get(relativePath) ?? '')
    assertM018(records.length === fileRoles.size, '证据包白名单文件不完整')
    assertNoSensitiveMaterial(partialRoot, packFiles, repositoryRoot)
    const manifest = {
      schemaVersion: 1,
      milestone: 'M0-18',
      generatedAt: new Date().toISOString(),
      sourceCommit,
      files: records,
    }
    assertSchema(path.join(repositoryRoot, 'evidence', 'm0-18', 'evidence-manifest.schema.json'), manifest, 'manifest.json')
    atomicWriteJson(path.join(partialRoot, 'manifest.json'), manifest)
    assertNoSensitiveMaterial(partialRoot, collectRegularFiles(partialRoot), repositoryRoot)

    fs.rmSync(outputRoot, { recursive: true, force: true })
    fs.mkdirSync(path.dirname(outputRoot), { recursive: true })
    fs.renameSync(partialRoot, outputRoot)
    verifyEvidencePack(repositoryRoot, outputRoot, sourceCommit)
    return { outputRoot, manifest, report }
  } catch (error) {
    fs.rmSync(partialRoot, { recursive: true, force: true })
    throw error
  }
}

function copySafeMetadata(source, destination) {
  const metadata = fs.lstatSync(source, { throwIfNoEntry: false })
  assertM018(metadata?.isFile() && !metadata.isSymbolicLink(), `证据元数据缺失或不是普通文件：${path.basename(source)}`)
  fs.mkdirSync(path.dirname(destination), { recursive: true })
  fs.copyFileSync(source, destination)
}

function validateSourceMetadata(repositoryRoot, partialRoot, handoff, sourceCommit, outputM016Root) {
  const deferred = readJson(path.join(partialRoot, 'deferred-acceptance.json'))
  assertSchema(path.join(repositoryRoot, 'evidence', 'm0-18', 'deferred-acceptance.schema.json'), deferred, 'deferred-acceptance.json')

  const m016 = readJson(path.join(partialRoot, 'm0-16', 'artifact-manifest.json'))
  const m015 = readJson(path.join(partialRoot, 'm0-15', 'artifact-manifest.json'))
  assertM018(m015.sourceCommit === sourceCommit, 'M0-15 manifest 未绑定当前 checkout')
  assertM018(m016.sourceCommit === sourceCommit, 'M0-16 manifest 未绑定当前 checkout')
  const m017Backup = readJson(path.join(partialRoot, 'm0-17', 'backup-manifest.json'))
  const m017Retention = readJson(path.join(partialRoot, 'm0-17', 'retention-plan.json'))
  const m017Report = readJson(path.join(partialRoot, 'm0-17', 'verification-report.json'))
  assertSchema(path.join(repositoryRoot, 'evidence', 'm0-17', 'backup-manifest.schema.json'), m017Backup, 'M0-17 backup manifest')
  assertSchema(path.join(repositoryRoot, 'evidence', 'm0-17', 'retention-plan.schema.json'), m017Retention, 'M0-17 retention plan')
  assertSchema(path.join(repositoryRoot, 'evidence', 'm0-17', 'verification-report.schema.json'), m017Report, 'M0-17 verification report')
  assertM018(m017Backup.sourceCommit === sourceCommit && m017Report.sourceCommit === sourceCommit, 'M0-17 元数据未绑定当前 checkout')
  assertM018(handoff.sourceCommit === sourceCommit && handoff.testedCommit === sourceCommit, 'portable handoff 未绑定当前 checkout')

  const zipDigestText = fs.readFileSync(path.join(partialRoot, 'm0-16', 'yumpoo-windows-m0-16.zip.sha256'), 'utf8').trim()
  const match = zipDigestText.match(/^([0-9a-f]{64})  yumpoo-windows-m0-16\.zip$/u)
  assertM018(match, 'M0-16 ZIP 摘要格式无效')
  assertM018(match[1] === sha256File(path.join(outputM016Root, 'yumpoo-windows-m0-16.zip')), 'M0-16 ZIP 摘要与 ZIP 不一致')
}

function createVerificationReport(repositoryRoot, partialRoot, handoff, sourceCommit) {
  assertM018(process.platform === 'win32' && process.arch === 'x64', 'verification report 必须在 Windows x64 生成')
  const versions = toolVersions(repositoryRoot)
  const zipDigest = fs
    .readFileSync(path.join(partialRoot, 'm0-16', 'yumpoo-windows-m0-16.zip.sha256'), 'utf8')
    .trim()
    .slice(0, 64)
  assertM018(sha256Pattern.test(zipDigest), 'M0-16 ZIP 摘要无效')
  return {
    schemaVersion: 1,
    milestone: 'M0-18',
    status: 'PASS',
    startedAt: handoff.startedAt,
    completedAt: new Date().toISOString(),
    sourceCommit,
    baseCommit: handoff.baseCommit,
    headCommit: handoff.headCommit,
    testedCommit: handoff.testedCommit,
    reproductionCommand: 'pnpm verify:m0-18',
    environment: {
      platform: 'win32',
      architecture: 'x64',
      javaMajor: versions.javaMajor,
      nodeVersion: versions.nodeVersion,
      pnpmVersion: versions.pnpmVersion,
      mavenVersion: versions.mavenVersion,
      postgresImage: 'postgres:17.10-alpine',
      companyTimeZone: 'Asia/Shanghai',
    },
    gates: {
      contracts: 'PASS',
      openApiCompatibility: 'PASS',
      buildMigrationArchitectureTests: 'PASS',
      desktopSmoke: 'PASS',
      serverSmoke: 'PASS',
      windowsPackaging: 'PASS',
      backupRestore: 'PASS',
    },
    checks: {
      contractsValidated: true,
      liveNotRunExactSet: true,
      m017FollowUpsComplete: true,
      buildPassed: true,
      migrationPassed: true,
      architecturePassed: true,
      openApiPassed: true,
      openApiCompatibilityPassed: true,
      automatedTestsPassed: true,
      desktopPackagePassed: true,
      desktopSmokePassed: true,
      backupRestorePassed: true,
      sensitiveDataExcluded: true,
    },
    digests: {
      baselineOpenApi: handoff.baselineOpenApiSha256,
      currentOpenApi: handoff.currentOpenApiSha256,
      portableHandoff: sha256File(path.join(partialRoot, 'portable', 'portable-handoff.json')),
      m015Manifest: sha256File(path.join(partialRoot, 'm0-15', 'artifact-manifest.json')),
      m016Manifest: sha256File(path.join(partialRoot, 'm0-16', 'artifact-manifest.json')),
      m016Zip: zipDigest,
      m017BackupManifest: sha256File(path.join(partialRoot, 'm0-17', 'backup-manifest.json')),
      m017RetentionPlan: sha256File(path.join(partialRoot, 'm0-17', 'retention-plan.json')),
      m017VerificationReport: sha256File(path.join(partialRoot, 'm0-17', 'verification-report.json')),
      deferredAcceptance: sha256File(path.join(partialRoot, 'deferred-acceptance.json')),
    },
    limitations: [
      'DEVELOPMENT_GATE_ONLY',
      'LIVE_EVIDENCE_NOT_IMPLIED',
      'PRODUCTION_BACKUP_AND_RPO_RTO_DEFERRED',
      'SYNTHETIC_BACKUP_DATA_ONLY',
    ],
    ci: {
      runId: digitsOrNull(process.env.GITHUB_RUN_ID),
      runAttempt: digitsOrNull(process.env.GITHUB_RUN_ATTEMPT),
      handoffArtifactName: process.env.YUMPOO_M018_HANDOFF_ARTIFACT_NAME || null,
      handoffArtifactDigest: artifactDigestOrNull(process.env.YUMPOO_M018_HANDOFF_ARTIFACT_DIGEST),
    },
  }
}

function toolVersions(repositoryRoot) {
  const nodeVersion = process.version.replace(/^v/u, '')
  assertM018(typeof process.env.npm_execpath === 'string' && process.env.npm_execpath.length > 0, 'pnpm 执行入口不可用')
  const pnpmVersion = run(process.execPath, [process.env.npm_execpath, '--version'], repositoryRoot).trim()
  const javaOutput = run('java', ['-version'], repositoryRoot)
  const mavenWrapper = path.join(repositoryRoot, 'backend', 'mvnw.cmd').replaceAll("'", "''")
  const mavenOutput = run('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', `& '${mavenWrapper}' -v`], repositoryRoot)
  const javaMatch = javaOutput.match(/version\s+"(\d+)(?:\.|")/u)
  const mavenMatch = mavenOutput.match(/Apache Maven\s+(\d+\.\d+\.\d+)/u)
  assertM018(nodeVersion === '24.14.0', `Node 版本必须为 24.14.0，实际为 ${nodeVersion}`)
  assertM018(pnpmVersion === '11.16.0', `pnpm 版本必须为 11.16.0，实际为 ${pnpmVersion}`)
  assertM018(javaMatch?.[1] === '21', 'Java 版本必须为 21')
  assertM018(mavenMatch?.[1] === '3.9.9', 'Maven Wrapper 版本必须为 3.9.9')
  return { nodeVersion, pnpmVersion, javaMajor: 21, mavenVersion: '3.9.9' }
}

function run(command, args, cwd) {
  assertM018(typeof command === 'string' && command.length > 0, '工具命令不可用')
  const result = spawnSync(command, args, { cwd, encoding: 'utf8', windowsHide: true })
  assertM018(result.status === 0, `${path.basename(command)} 版本检查失败`)
  return `${result.stdout ?? ''}\n${result.stderr ?? ''}`
}

function digitsOrNull(value) {
  if (!value) return null
  assertM018(/^[0-9]+$/u.test(value), 'CI 数字标识格式无效')
  return value
}

function artifactDigestOrNull(value) {
  if (!value) return null
  const normalized = value.startsWith('sha256:') ? value : `sha256:${value}`
  assertM018(/^sha256:[0-9a-f]{64}$/u.test(normalized), 'handoff artifact digest 格式无效')
  return normalized
}

const invokedDirectly = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (invokedDirectly) {
  const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
  const result = createEvidencePack(repositoryRoot)
  console.log(`M0-18 开发证据包已生成并复核：${path.relative(repositoryRoot, result.outputRoot)}`)
}
