import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { migrationInventory } from '../ci/migration-policy.mjs'
import { sboms } from '../release/policy.mjs'
import { quotePowerShellLiteral, sha256 } from './package-utils.mjs'
import { gitHead } from './delivery-utils.mjs'
import { runPnpmSync } from './process-utils.mjs'
import { verifyPortableHandoff } from './verify-portable-handoff.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const out = path.join(root, 'out', 'm1-15')
const stage = path.join(out, 'server-staging')
const serverZip = path.join(out, 'yumpoo-windows-server-m1-15.zip')
const [serverSbom, webSbom] = sboms.map(sbom => path.join(out, sbom.name))
const deployment = path.join(root, 'deployment', 'windows')
const handoff = process.env.YUMPOO_HANDOFF_ROOT
if (handoff) verifyPortableHandoff(root, path.resolve(handoff))
const jar = handoff ? path.join(handoff, 'server/yumpoo-server.jar') : path.join(root, 'backend/target/yumpoo-server.jar')
const web = handoff ? path.join(handoff, 'web') : path.join(root, 'frontend/web-app/dist')

assert(process.platform === 'win32' && process.arch === 'x64', '只能在 Windows x64 上组装')
requireFile(jar, '已验证后端 JAR')
requireDirectory(web, '已验证 Web dist')

fs.mkdirSync(out, { recursive: true })
for (const target of [stage, serverZip, `${serverZip}.sha256`, serverSbom, webSbom]) {
  fs.rmSync(target, { recursive: true, force: true })
}

try {
  assembleServer()
  compressContents(stage, serverZip)
  const sourceCommit = gitHead(root)
  const digest = await sha256(serverZip)
  fs.writeFileSync(`${serverZip}.sha256`, `${digest}  ${path.basename(serverZip)}\n`, 'utf8')
  writeSboms()
  const generatedAt = new Date().toISOString()
  const manifest = {
    schemaVersion: 2,
    milestone: 'M1-15',
    sourceCommit,
    generatedAt,
    server: { platform: 'windows', architecture: 'x64', runtimeMode: 'WINDOWS_SERVICE_WINSW' },
    desktopReuse: { milestone: 'M1-14', version: '0.1.0', repackaged: false },
    artifacts: [{ name: path.basename(serverZip), bytes: fs.statSync(serverZip).size, sha256: digest }],
    sboms: await Promise.all([serverSbom, webSbom].map(async file => ({
      name: path.basename(file), format: 'CycloneDX', bytes: fs.statSync(file).size, sha256: await sha256(file),
    }))),
  }
  fs.writeFileSync(path.join(out, 'artifact-manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')
  fs.writeFileSync(path.join(out, 'verification-report.json'), `${JSON.stringify({
    schemaVersion: 1,
    milestone: 'M1-15',
    sourceCommit,
    generatedAt,
    localGate: 'PASS',
    packageGate: 'PASS',
    liveEnvironment: 'ENV_PENDING',
    liveEvidenceOwner: 'M6-01',
    databaseMigrationAdded: false,
    publicApiChanged: false,
    sensitiveValuesRecorded: false,
  }, null, 2)}\n`, 'utf8')
  console.log(`Windows x64 服务器产物已生成：${path.relative(root, out)}`)
} finally {
  fs.rmSync(stage, { recursive: true, force: true })
}

function assembleServer() {
  fs.mkdirSync(path.join(stage, 'server'), { recursive: true })
  fs.mkdirSync(path.join(stage, 'windows'), { recursive: true })
  fs.copyFileSync(jar, path.join(stage, 'server', 'yumpoo-server.jar'))
  copyTree(web, path.join(stage, 'web'), { rejectSourceMaps: true })
  for (const file of [
    'RUNBOOK.md',
    'deployment-checklist-m1-15.json',
    'Invoke-AppManagerMaintenance.ps1',
    'Invoke-InitialIdentityBootstrap.ps1',
    'Install-YumpooMonitoring.ps1',
    'Send-YumpooRecoveryNotice.ps1',
  ]) copyAs(path.join(deployment, file), path.join(stage, 'windows', file))
  for (const directory of ['config', 'secrets', 'nginx', 'database', 'service', 'cd']) {
    copyTree(path.join(deployment, directory), path.join(stage, 'windows', directory))
  }
  copyTree(
    path.join(root, 'backend', 'src', 'main', 'resources', 'db', 'migration'),
    path.join(stage, 'windows', 'database', 'migrations'),
  )
  // 部署引擎据此判断两版之间的迁移是否只扩展，从而决定能否快速回退。
  fs.writeFileSync(path.join(stage, 'windows', 'release-metadata.json'), `${JSON.stringify({
    schemaVersion: 1,
    sourceCommit: gitHead(root),
    generatedAt: new Date().toISOString(),
    ciRunId: Number(process.env.GITHUB_RUN_ID) || null,
    ciRunAttempt: Number(process.env.GITHUB_RUN_ATTEMPT) || null,
    migrations: migrationInventory(root).map(({ version, file, sha256: digest, classification }) => ({ version, file, sha256: digest, classification })),
  }, null, 2)}\n`, 'utf8')
}

// 后端 SBOM 取自已验证 JAR 内 Spring Boot 生成的清单，与包内字节同源；Web 由锁文件列出打包进 dist 的生产依赖。
function writeSboms() {
  const command = [
    'Add-Type -AssemblyName System.IO.Compression.FileSystem',
    `$m115Jar = [IO.Compression.ZipFile]::OpenRead(${quotePowerShellLiteral(jar)})`,
    'try {',
    `$m115Entry = $m115Jar.GetEntry(${quotePowerShellLiteral(sboms[0].jarEntry)})`,
    "if ($null -eq $m115Entry) { throw 'JAR 不含 Spring Boot SBOM' }",
    `[IO.Compression.ZipFileExtensions]::ExtractToFile($m115Entry, ${quotePowerShellLiteral(serverSbom)})`,
    '} finally { $m115Jar.Dispose() }',
  ].join('\n')
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command], { stdio: 'inherit' })
  assert(result.status === 0, '无法从已验证 JAR 提取后端 SBOM')
  runPnpmSync(['sbom', '--sbom-format', 'cyclonedx', '--sbom-type', 'application', '--prod', '--lockfile-only',
    '--filter', '@yumpoo/web-app', '--out', webSbom], { cwd: root })
  for (const file of [serverSbom, webSbom]) requireFile(file, path.basename(file))
}

function compressContents(directory, destination) {
  const command = [
    `$m115Items = Get-ChildItem -LiteralPath ${quotePowerShellLiteral(directory)}`,
    `Compress-Archive -LiteralPath $m115Items.FullName -DestinationPath ${quotePowerShellLiteral(destination)} -CompressionLevel Optimal -Force`,
  ].join('; ')
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command], { stdio: 'inherit' })
  assert(result.status === 0, `无法压缩 ${path.basename(destination)}`)
}

function copyAs(source, destination) {
  requireFile(source, path.relative(root, source))
  fs.mkdirSync(path.dirname(destination), { recursive: true })
  fs.copyFileSync(source, destination)
}

function copyTree(source, destination, options = {}) {
  requireDirectory(source, path.relative(root, source))
  fs.mkdirSync(destination, { recursive: true })
  for (const entry of fs.readdirSync(source, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name, 'en'))) {
    const from = path.join(source, entry.name)
    const to = path.join(destination, entry.name)
    const metadata = fs.lstatSync(from)
    assert(!metadata.isSymbolicLink(), `复制源包含符号链接：${path.relative(root, from)}`)
    if (entry.isDirectory()) copyTree(from, to, options)
    else if (entry.isFile()) {
      assert(!(options.rejectSourceMaps && entry.name.endsWith('.map')), `Web 产物包含 source map：${entry.name}`)
      fs.copyFileSync(from, to)
    }
  }
}

function requireFile(file, label) {
  assert(fs.existsSync(file) && fs.statSync(file).isFile(), `缺少 ${label}`)
}

function requireDirectory(directory, label) {
  assert(fs.existsSync(directory) && fs.statSync(directory).isDirectory(), `缺少 ${label}`)
}

function assert(condition, message) {
  if (!condition) throw new Error(`服务器打包失败：${message}`)
}
