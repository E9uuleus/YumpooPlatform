import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { collectRegularFiles, fileRecord, quotePowerShellLiteral, sha256, assertM016 } from './m0-16-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const outputRoot = path.join(repositoryRoot, 'out', 'm0-16')
const stagingRoot = path.join(outputRoot, 'staging')
const zipPath = path.join(outputRoot, 'yumpoo-windows-m0-16.zip')
const zipHashPath = `${zipPath}.sha256`
const portablePayloadRoot = process.env.YUMPOO_M016_PAYLOAD_ROOT
  ? path.resolve(process.env.YUMPOO_M016_PAYLOAD_ROOT)
  : undefined
const jarPath = portablePayloadRoot
  ? path.join(portablePayloadRoot, 'server', 'yumpoo-server.jar')
  : path.join(repositoryRoot, 'backend', 'target', 'yumpoo-server.jar')
const webRoot = portablePayloadRoot
  ? path.join(portablePayloadRoot, 'web')
  : path.join(repositoryRoot, 'frontend', 'web-app', 'dist')
const deploymentRoot = path.join(repositoryRoot, 'deployment', 'windows')

assertM016(process.platform === 'win32' && process.arch === 'x64', 'Windows 发布包只能在 Windows x64 上组装')
requireFile(jarPath, 'packaged JAR')
requireDirectory(webRoot, 'Vite dist')
requireDirectory(deploymentRoot, 'Windows 部署资产')

fs.rmSync(outputRoot, { recursive: true, force: true })
fs.mkdirSync(path.join(stagingRoot, 'server'), { recursive: true })
fs.copyFileSync(jarPath, path.join(stagingRoot, 'server', 'yumpoo-server.jar'))
copyTree(webRoot, path.join(stagingRoot, 'web'), { rejectSourceMaps: true })
copyTree(deploymentRoot, path.join(stagingRoot, 'windows'))

const payloadFiles = await collectRegularFiles(stagingRoot)
const files = await Promise.all(payloadFiles.map((file) => fileRecord(stagingRoot, file)))
files.sort((left, right) => left.path.localeCompare(right.path, 'en'))
const sourceCommit = gitHead()
const manifest = {
  schemaVersion: 1,
  milestone: 'M0-16',
  generatedAt: new Date().toISOString(),
  target: { os: 'WINDOWS', architecture: 'x64' },
  sourceCommit,
  files,
}
fs.writeFileSync(path.join(stagingRoot, 'artifact-manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')

const command = [
  `$m016Items = Get-ChildItem -LiteralPath ${quotePowerShellLiteral(stagingRoot)}`,
  `Compress-Archive -LiteralPath $m016Items.FullName -DestinationPath ${quotePowerShellLiteral(zipPath)} -CompressionLevel Optimal -Force`,
].join('; ')
const compressed = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command], { stdio: 'inherit' })
assertM016(compressed.status === 0, 'PowerShell Compress-Archive 执行失败')
const zipHash = await sha256(zipPath)
fs.writeFileSync(zipHashPath, `${zipHash}  ${path.basename(zipPath)}\n`, 'utf8')
console.log(`M0-16 Windows 开发部署包已生成：${path.relative(repositoryRoot, zipPath)}`)

function copyTree(source, destination, options = {}) {
  fs.mkdirSync(destination, { recursive: true })
  const entries = fs.readdirSync(source, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name, 'en'))
  for (const entry of entries) {
    const from = path.join(source, entry.name)
    const to = path.join(destination, entry.name)
    const metadata = fs.lstatSync(from)
    assertM016(!metadata.isSymbolicLink(), `复制源不得包含符号链接：${path.relative(repositoryRoot, from)}`)
    if (entry.isDirectory()) {
      copyTree(from, to, options)
    } else if (entry.isFile()) {
      assertM016(!(options.rejectSourceMaps && entry.name.endsWith('.map')), 'Vite dist 不得包含 source map')
      fs.copyFileSync(from, to)
    }
  }
}

function requireFile(file, label) {
  assertM016(fs.existsSync(file) && fs.statSync(file).isFile(), `缺少 ${label}`)
}

function requireDirectory(directory, label) {
  assertM016(fs.existsSync(directory) && fs.statSync(directory).isDirectory(), `缺少 ${label}`)
}

function gitHead() {
  const gitMarker = path.join(repositoryRoot, '.git')
  const gitDirectory = fs.statSync(gitMarker).isDirectory()
    ? gitMarker
    : path.resolve(repositoryRoot, fs.readFileSync(gitMarker, 'utf8').trim().replace(/^gitdir:\s*/u, ''))
  const head = fs.readFileSync(path.join(gitDirectory, 'HEAD'), 'utf8').trim()
  if (/^[0-9a-f]{40}$/u.test(head)) return head
  const match = head.match(/^ref:\s+(.+)$/u)
  assertM016(match, '无法解析 Git HEAD')
  const looseRef = path.join(gitDirectory, ...match[1].split('/'))
  if (fs.existsSync(looseRef)) {
    const value = fs.readFileSync(looseRef, 'utf8').trim()
    assertM016(/^[0-9a-f]{40}$/u.test(value), 'Git HEAD ref 无效')
    return value
  }
  const packedRefs = fs.readFileSync(path.join(gitDirectory, 'packed-refs'), 'utf8')
  const packed = packedRefs.split(/\r?\n/u).find((line) => line.endsWith(` ${match[1]}`))?.split(' ')[0]
  assertM016(/^[0-9a-f]{40}$/u.test(packed ?? ''), '无法读取源码 commit')
  return packed
}
