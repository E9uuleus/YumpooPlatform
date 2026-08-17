import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { collectRegularFiles, fileRecord, quotePowerShellLiteral, sha256 } from './m0-16-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const outputRoot = path.join(repositoryRoot, 'out', 'm1-13')
const zipPath = path.join(outputRoot, 'yumpoo-windows-m1-13.zip')
const zipHashPath = `${zipPath}.sha256`
const extractedRoot = path.join(outputRoot, 'deployment-verified')

assert(fs.existsSync(zipPath), '发布 ZIP 不存在')
fs.rmSync(extractedRoot, { recursive: true, force: true })
const command = `Expand-Archive -LiteralPath ${quotePowerShellLiteral(zipPath)} -DestinationPath ${quotePowerShellLiteral(extractedRoot)} -Force`
const expanded = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command], { stdio: 'inherit' })
assert(expanded.status === 0, '发布 ZIP 无法解包')

const manifestPath = path.join(extractedRoot, 'artifact-manifest.json')
assert(fs.existsSync(manifestPath), 'ZIP 缺少 artifact-manifest.json')
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
assert(manifest.schemaVersion === 1 && manifest.milestone === 'M1-13', 'manifest 版本或里程碑错误')
assert(manifest.target?.os === 'WINDOWS' && manifest.target?.architecture === 'x64', 'manifest 平台错误')
assert(manifest.target?.storageVolume === 'C:' && manifest.target?.runtimeMode === 'MANUAL_JAVA_CONSOLE', 'manifest 运行方式错误')
assert(/^[0-9a-f]{40}$/u.test(manifest.sourceCommit), 'manifest 源码 commit 错误')

const actualFiles = (await collectRegularFiles(extractedRoot)).filter((file) => file !== manifestPath)
const actualRecords = await Promise.all(actualFiles.map((file) => fileRecord(extractedRoot, file)))
actualRecords.sort((left, right) => left.path.localeCompare(right.path, 'en'))
assert(JSON.stringify(actualRecords) === JSON.stringify(manifest.files), 'manifest 与 ZIP 文件、字节数或 SHA-256 不一致')

const required = [
  'server/yumpoo-server.jar',
  'web/index.html',
  'windows/RUNBOOK.md',
  'windows/deployment-checklist-m1-13.json',
  'windows/config/application-prod.yml',
  'windows/secrets/application-secrets.yml',
  'windows/nginx/yumpoo-wecom.conf',
  'windows/database/initialize-database.sql',
  'windows/Invoke-AppManagerMaintenance.ps1',
]
for (const item of required) assert(manifest.files.some((file) => file.path === item), `ZIP 缺少：${item}`)

for (const item of manifest.files) {
  assert(!path.isAbsolute(item.path) && !item.path.includes('..') && !item.path.includes('\\'), `非法包内路径：${item.path}`)
  assert(!/\.(map|exe|dll|msi)$/iu.test(item.path), `ZIP 包含禁止文件：${item.path}`)
  assert(!item.path.startsWith('windows/service/') && !item.path.startsWith('windows/iis/'), `ZIP 包含本次不需要的服务/IIS 资产：${item.path}`)
}

const allTextFiles = actualFiles.filter((file) => !/\.(jar|png|jpg|jpeg|gif|ico|woff2?)$/iu.test(file))
for (const file of allTextFiles) {
  const text = fs.readFileSync(file, 'utf8')
  assert(!text.includes(repositoryRoot), `ZIP 泄露源码绝对路径：${path.relative(extractedRoot, file)}`)
  assert(!/WinSW/iu.test(text), `ZIP 文本包含本次不需要的 WinSW 指令：${path.relative(extractedRoot, file)}`)
}

const actualZipHash = await sha256(zipPath)
const hashText = fs.readFileSync(zipHashPath, 'utf8').trim()
assert(hashText === `${actualZipHash}  ${path.basename(zipPath)}`, 'ZIP SHA-256 文件不匹配')
console.log(`M1-13 部署 ZIP 已复核：${actualZipHash}`)

function assert(condition, message) {
  if (!condition) throw new Error(`M1-13 部署包验证失败：${message}`)
}
