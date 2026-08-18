import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { quotePowerShellLiteral, sha256 } from './m0-16-utils.mjs'
import { gitHead } from './m0-18-utils.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const out = path.join(root, 'out', 'm1-15')
const stage = path.join(out, 'server-staging')
const serverZip = path.join(out, 'yumpoo-windows-server-m1-15.zip')
const deployment = path.join(root, 'deployment', 'windows')

assert(process.platform === 'win32' && process.arch === 'x64', '只能在 Windows x64 上组装')
requireFile(path.join(root, 'backend', 'target', 'yumpoo-server.jar'), '当前后端 JAR')
requireDirectory(path.join(root, 'frontend', 'web-app', 'dist'), '当前 Web dist')

fs.mkdirSync(out, { recursive: true })
for (const target of [stage, serverZip, `${serverZip}.sha256`]) {
  fs.rmSync(target, { recursive: true, force: true })
}

try {
  assembleServer()
  compressContents(stage, serverZip)
  const sourceCommit = gitHead(root)
  const digest = await sha256(serverZip)
  fs.writeFileSync(`${serverZip}.sha256`, `${digest}  ${path.basename(serverZip)}\n`, 'utf8')
  const generatedAt = new Date().toISOString()
  const manifest = {
    schemaVersion: 1,
    milestone: 'M1-15',
    sourceCommit,
    generatedAt,
    server: { platform: 'windows', architecture: 'x64', runtimeMode: 'MANUAL_JAVA_CONSOLE' },
    desktopReuse: { milestone: 'M1-14', version: '0.1.0', repackaged: false },
    artifacts: [{ name: path.basename(serverZip), bytes: fs.statSync(serverZip).size, sha256: digest }],
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
  console.log(`M1-15 Windows x64 服务器产物已生成：${path.relative(root, out)}`)
} finally {
  fs.rmSync(stage, { recursive: true, force: true })
}

function assembleServer() {
  fs.mkdirSync(path.join(stage, 'server'), { recursive: true })
  fs.mkdirSync(path.join(stage, 'windows'), { recursive: true })
  fs.copyFileSync(path.join(root, 'backend', 'target', 'yumpoo-server.jar'), path.join(stage, 'server', 'yumpoo-server.jar'))
  copyTree(path.join(root, 'frontend', 'web-app', 'dist'), path.join(stage, 'web'), { rejectSourceMaps: true })
  for (const file of [
    'RUNBOOK.md',
    'deployment-checklist-m1-15.json',
    'Invoke-AppManagerMaintenance.ps1',
    'Invoke-InitialIdentityBootstrap.ps1',
  ]) copyAs(path.join(deployment, file), path.join(stage, 'windows', file))
  for (const directory of ['config', 'secrets', 'nginx', 'database']) {
    copyTree(path.join(deployment, directory), path.join(stage, 'windows', directory))
  }
  copyTree(
    path.join(root, 'backend', 'src', 'main', 'resources', 'db', 'migration'),
    path.join(stage, 'windows', 'database', 'migrations'),
  )
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
  if (!condition) throw new Error(`M1-15 打包失败：${message}`)
}
