import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { collectRegularFiles, fileRecord, quotePowerShellLiteral, sha256 } from './m0-16-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const outputRoot = path.join(repositoryRoot, 'out', 'm1-13')
const stagingRoot = path.join(outputRoot, 'deployment-staging')
const zipPath = path.join(outputRoot, 'yumpoo-windows-m1-13.zip')
const zipHashPath = `${zipPath}.sha256`
const jarPath = path.join(repositoryRoot, 'backend', 'target', 'yumpoo-server.jar')
const webRoot = path.join(repositoryRoot, 'frontend', 'web-app', 'dist')
const deploymentRoot = path.join(repositoryRoot, 'deployment', 'windows')

assert(process.platform === 'win32' && process.arch === 'x64', 'M1-13 Windows 包只能在 Windows x64 上组装')
requireFile(jarPath, 'packaged JAR')
requireDirectory(webRoot, 'Vite dist')

fs.rmSync(stagingRoot, { recursive: true, force: true })
fs.rmSync(zipPath, { force: true })
fs.rmSync(zipHashPath, { force: true })
fs.mkdirSync(path.join(stagingRoot, 'server'), { recursive: true })
fs.mkdirSync(path.join(stagingRoot, 'windows'), { recursive: true })
fs.copyFileSync(jarPath, path.join(stagingRoot, 'server', 'yumpoo-server.jar'))
copyTree(webRoot, path.join(stagingRoot, 'web'), { rejectSourceMaps: true })

for (const relativePath of [
  'RUNBOOK.md',
  'deployment-checklist-m1-13.json',
  'Invoke-AppManagerMaintenance.ps1',
]) {
  copyFile(relativePath)
}
for (const directory of ['config', 'secrets', 'nginx', 'database']) {
  copyTree(path.join(deploymentRoot, directory), path.join(stagingRoot, 'windows', directory))
}

const payloadFiles = await collectRegularFiles(stagingRoot)
const files = await Promise.all(payloadFiles.map((file) => fileRecord(stagingRoot, file)))
files.sort((left, right) => left.path.localeCompare(right.path, 'en'))
const manifest = {
  schemaVersion: 1,
  milestone: 'M1-13',
  generatedAt: new Date().toISOString(),
  target: {
    os: 'WINDOWS',
    architecture: 'x64',
    storageVolume: 'C:',
    runtimeMode: 'MANUAL_JAVA_CONSOLE',
  },
  sourceCommit: gitHead(),
  files,
}
fs.writeFileSync(path.join(stagingRoot, 'artifact-manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')

const command = [
  `$m113Items = Get-ChildItem -LiteralPath ${quotePowerShellLiteral(stagingRoot)}`,
  `Compress-Archive -LiteralPath $m113Items.FullName -DestinationPath ${quotePowerShellLiteral(zipPath)} -CompressionLevel Optimal -Force`,
].join('; ')
const compressed = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command], { stdio: 'inherit' })
assert(compressed.status === 0, 'PowerShell Compress-Archive 执行失败')
const zipHash = await sha256(zipPath)
fs.writeFileSync(zipHashPath, `${zipHash}  ${path.basename(zipPath)}\n`, 'utf8')
console.log(`M1-13 Windows 部署包已生成：${path.relative(repositoryRoot, zipPath)}`)

function copyFile(relativePath) {
  const source = path.join(deploymentRoot, relativePath)
  requireFile(source, relativePath)
  const destination = path.join(stagingRoot, 'windows', relativePath)
  fs.mkdirSync(path.dirname(destination), { recursive: true })
  fs.copyFileSync(source, destination)
}

function copyTree(source, destination, options = {}) {
  requireDirectory(source, path.relative(repositoryRoot, source))
  fs.mkdirSync(destination, { recursive: true })
  const entries = fs.readdirSync(source, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name, 'en'))
  for (const entry of entries) {
    const from = path.join(source, entry.name)
    const to = path.join(destination, entry.name)
    const metadata = fs.lstatSync(from)
    assert(!metadata.isSymbolicLink(), `复制源不得包含符号链接：${path.relative(repositoryRoot, from)}`)
    if (entry.isDirectory()) copyTree(from, to, options)
    else if (entry.isFile()) {
      assert(!(options.rejectSourceMaps && entry.name.endsWith('.map')), 'Vite dist 不得包含 source map')
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

function gitHead() {
  const gitMarker = path.join(repositoryRoot, '.git')
  const gitDirectory = fs.statSync(gitMarker).isDirectory()
    ? gitMarker
    : path.resolve(repositoryRoot, fs.readFileSync(gitMarker, 'utf8').trim().replace(/^gitdir:\s*/u, ''))
  const head = fs.readFileSync(path.join(gitDirectory, 'HEAD'), 'utf8').trim()
  if (/^[0-9a-f]{40}$/u.test(head)) return head
  const match = head.match(/^ref:\s+(.+)$/u)
  assert(match, '无法解析 Git HEAD')
  const looseRef = path.join(gitDirectory, ...match[1].split('/'))
  if (fs.existsSync(looseRef)) return validCommit(fs.readFileSync(looseRef, 'utf8').trim())
  const packedRefs = fs.readFileSync(path.join(gitDirectory, 'packed-refs'), 'utf8')
  const packed = packedRefs.split(/\r?\n/u).find((line) => line.endsWith(` ${match[1]}`))?.split(' ')[0]
  return validCommit(packed ?? '')
}

function validCommit(value) {
  assert(/^[0-9a-f]{40}$/u.test(value), 'Git HEAD ref 无效')
  return value
}

function assert(condition, message) {
  if (!condition) throw new Error(`M1-13 打包失败：${message}`)
}
