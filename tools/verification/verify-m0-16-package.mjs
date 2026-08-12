import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { collectRegularFiles, fileRecord, quotePowerShellLiteral, sha256, assertM016 } from './m0-16-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const outputRoot = path.join(repositoryRoot, 'out', 'm0-16')
const zipPath = path.join(outputRoot, 'yumpoo-windows-m0-16.zip')
const zipHashPath = `${zipPath}.sha256`
const extractedRoot = path.join(outputRoot, 'verified')

assertM016(fs.existsSync(zipPath), '发布 ZIP 不存在')
fs.rmSync(extractedRoot, { recursive: true, force: true })
const command = `Expand-Archive -LiteralPath ${quotePowerShellLiteral(zipPath)} -DestinationPath ${quotePowerShellLiteral(extractedRoot)} -Force`
const expanded = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command], { stdio: 'inherit' })
assertM016(expanded.status === 0, '发布 ZIP 无法解包')

const manifestPath = path.join(extractedRoot, 'artifact-manifest.json')
assertM016(fs.existsSync(manifestPath), 'ZIP 缺少 artifact-manifest.json')
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
assertM016(manifest.schemaVersion === 1 && manifest.milestone === 'M0-16', 'manifest 版本或里程碑错误')
assertM016(/^\d{4}-\d{2}-\d{2}T.*Z$/u.test(manifest.generatedAt), 'manifest 生成时间必须是 UTC')
assertM016(manifest.target?.os === 'WINDOWS' && manifest.target?.architecture === 'x64', 'manifest 目标平台错误')
assertM016(/^[0-9a-f]{40}$/u.test(manifest.sourceCommit), 'manifest 源码 commit 错误')
assertM016(Array.isArray(manifest.files) && manifest.files.length > 0, 'manifest 文件列表为空')

const actualFiles = (await collectRegularFiles(extractedRoot)).filter((file) => file !== manifestPath)
const actualRecords = await Promise.all(actualFiles.map((file) => fileRecord(extractedRoot, file)))
actualRecords.sort((left, right) => left.path.localeCompare(right.path, 'en'))
assertM016(JSON.stringify(actualRecords) === JSON.stringify(manifest.files), 'manifest 与 ZIP 文件、字节数或 SHA-256 不一致')
const sortedPaths = manifest.files.map((item) => item.path).sort((a, b) => a.localeCompare(b, 'en'))
assertM016(JSON.stringify(sortedPaths) === JSON.stringify(manifest.files.map((item) => item.path)), 'manifest 路径未排序')

for (const item of manifest.files) {
  assertM016(!path.isAbsolute(item.path) && !item.path.includes('..') && !item.path.includes('\\'), 'manifest 不得包含绝对路径、反斜杠或上级跳转')
  assertM016(/^[0-9a-f]{64}$/u.test(item.sha256) && Number.isSafeInteger(item.bytes) && item.bytes >= 0, 'manifest 文件记录无效')
  assertM016(
    item.path === 'server/yumpoo-server.jar' || item.path.startsWith('web/') || item.path.startsWith('windows/'),
    `ZIP 包含白名单外文件：${item.path}`,
  )
  assertM016(!/\.(map|exe|dll|msi)$/iu.test(item.path), `ZIP 包含禁止文件：${item.path}`)
}
assertM016(manifest.files.some((item) => item.path === 'server/yumpoo-server.jar'), 'ZIP 缺少后端 JAR')
assertM016(manifest.files.some((item) => item.path === 'web/index.html'), 'ZIP 缺少 Vite index.html')
assertM016(manifest.files.some((item) => item.path === 'windows/iis/web.config'), 'ZIP 缺少 IIS 模板')

const actualZipHash = await sha256(zipPath)
const hashText = fs.readFileSync(zipHashPath, 'utf8').trim()
assertM016(hashText === `${actualZipHash}  ${path.basename(zipPath)}`, 'ZIP SHA-256 文件不匹配')
const allTextFiles = actualFiles.filter((file) => !/\.(jar|png|jpg|jpeg|gif|ico|woff2?)$/iu.test(file))
for (const file of allTextFiles) {
  const text = fs.readFileSync(file, 'utf8')
  assertM016(!text.includes(repositoryRoot), `ZIP 泄露源码绝对路径：${path.relative(extractedRoot, file)}`)
}
console.log(`M0-16 ZIP 白名单、manifest 和 SHA-256 已复核：${actualZipHash}`)
