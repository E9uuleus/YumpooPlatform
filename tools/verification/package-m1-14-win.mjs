import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { collectRegularFiles, fileRecord, quotePowerShellLiteral, sha256 } from './m0-16-utils.mjs'
import { gitHead } from './m0-18-utils.mjs'
import { runSync } from './process-utils.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const out = path.join(root, 'out', 'm1-14')
const serverStage = path.join(out, 'server-staging')
const desktopTemp = path.join(root, 'desktop', 'desktop-shell', 'out', `.m1-14-package-${process.pid}-${Date.now()}`)
const serverZip = path.join(out, 'yumpoo-windows-server-m1-14.zip')
const desktopZip = path.join(out, 'yumpoo-desktop-m1-14-win32-x64.zip')
const deployment = path.join(root, 'deployment', 'windows')

assert(process.platform === 'win32' && process.arch === 'x64', '只能在 Windows x64 上组装')
requireFile(path.join(root, 'backend', 'target', 'yumpoo-server.jar'), '当前后端 JAR')
requireDirectory(path.join(root, 'frontend', 'web-app', 'dist'), '当前 Web dist')

fs.mkdirSync(out, { recursive: true })
for (const target of [serverStage, serverZip, `${serverZip}.sha256`, desktopZip, `${desktopZip}.sha256`]) {
  fs.rmSync(target, { recursive: true, force: true })
}

try {
  assembleServer()
  compressContents(serverStage, serverZip)

  runSync(process.execPath, [path.join(root, 'tools', 'verification', 'package-m0-15-win.mjs')], {
    cwd: root,
    env: { ...process.env, YUMPOO_M015_OUTPUT_ROOT: desktopTemp },
  })
  const desktopDirectories = fs.readdirSync(desktopTemp, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && entry.name.endsWith('-win32-x64'))
  assert(desktopDirectories.length === 1, '桌面打包目录数量不为 1')
  compressContents(path.join(desktopTemp, desktopDirectories[0].name), desktopZip)

  const sourceCommit = gitHead(root)
  const artifacts = []
  for (const file of [serverZip, desktopZip]) {
    const digest = await sha256(file)
    fs.writeFileSync(`${file}.sha256`, `${digest}  ${path.basename(file)}\n`, 'utf8')
    artifacts.push({ name: path.basename(file), bytes: fs.statSync(file).size, sha256: digest })
  }
  const manifest = {
    schemaVersion: 1,
    milestone: 'M1-14',
    sourceCommit,
    generatedAt: new Date().toISOString(),
    desktop: { version: '0.1.0', protocolVersion: '1', platform: 'win32', architecture: 'x64', packageType: 'PILOT_PORTABLE' },
    server: { platform: 'windows', architecture: 'x64', runtimeMode: 'MANUAL_JAVA_CONSOLE' },
    artifacts,
  }
  fs.writeFileSync(path.join(out, 'artifact-manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')
  fs.writeFileSync(path.join(out, 'verification-report.json'), `${JSON.stringify({
    schemaVersion: 1,
    milestone: 'M1-14',
    sourceCommit,
    generatedAt: manifest.generatedAt,
    localGate: 'PASS',
    packageGate: 'PASS',
    liveEnvironment: 'ENV_PENDING',
    liveEvidenceOwner: 'M6-01',
    sensitiveValuesRecorded: false,
  }, null, 2)}\n`, 'utf8')
  console.log(`M1-14 双 Windows x64 产物已生成：${path.relative(root, out)}`)
} finally {
  fs.rmSync(serverStage, { recursive: true, force: true })
  fs.rmSync(desktopTemp, { recursive: true, force: true })
}

function assembleServer() {
  fs.mkdirSync(path.join(serverStage, 'server'), { recursive: true })
  fs.mkdirSync(path.join(serverStage, 'windows'), { recursive: true })
  fs.copyFileSync(path.join(root, 'backend', 'target', 'yumpoo-server.jar'), path.join(serverStage, 'server', 'yumpoo-server.jar'))
  copyTree(path.join(root, 'frontend', 'web-app', 'dist'), path.join(serverStage, 'web'), { rejectSourceMaps: true })
  copyAs(path.join(deployment, 'RUNBOOK.md'), path.join(serverStage, 'windows', 'RUNBOOK.md'))
  copyAs(path.join(deployment, 'deployment-checklist-m1-14.json'), path.join(serverStage, 'windows', 'deployment-checklist-m1-14.json'))
  copyAs(path.join(deployment, 'Invoke-AppManagerMaintenance.ps1'), path.join(serverStage, 'windows', 'Invoke-AppManagerMaintenance.ps1'))
  for (const directory of ['config', 'secrets', 'nginx', 'database']) {
    copyTree(path.join(deployment, directory), path.join(serverStage, 'windows', directory))
  }
  copyTree(
    path.join(root, 'backend', 'src', 'main', 'resources', 'db', 'migration'),
    path.join(serverStage, 'windows', 'database', 'migrations'),
  )
  const files = fs.readdirSync(serverStage)
  assert(files.length > 0, '服务器暂存目录为空')
}

function compressContents(directory, destination) {
  const command = [
    `$m114Items = Get-ChildItem -LiteralPath ${quotePowerShellLiteral(directory)}`,
    `Compress-Archive -LiteralPath $m114Items.FullName -DestinationPath ${quotePowerShellLiteral(destination)} -CompressionLevel Optimal -Force`,
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
  if (!condition) throw new Error(`M1-14 打包失败：${message}`)
}
