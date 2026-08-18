import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { collectRegularFiles, quotePowerShellLiteral, sha256 } from './m0-16-utils.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const out = path.join(root, 'out', 'm1-15')
const manifest = readJson(path.join(out, 'artifact-manifest.json'))
const report = readJson(path.join(out, 'verification-report.json'))

assert(manifest.schemaVersion === 1 && manifest.milestone === 'M1-15', 'artifact manifest 里程碑错误')
assert(report.milestone === 'M1-15' && report.liveEnvironment === 'ENV_PENDING', '验证报告必须保留 ENV_PENDING')
assert(report.databaseMigrationAdded === false && report.publicApiChanged === false, 'M1-15 边界报告错误')
assert(manifest.desktopReuse?.milestone === 'M1-14' && manifest.desktopReuse.repackaged === false, '桌面复用声明错误')
assert(manifest.sourceCommit === report.sourceCommit && /^[0-9a-f]{40}$/u.test(manifest.sourceCommit), '产物未绑定有效源码 commit')
assert(manifest.artifacts.length === 1, 'M1-15 只能包含一个服务器产物')

const artifact = manifest.artifacts[0]
const file = path.join(out, artifact.name)
assert(artifact.name === 'yumpoo-windows-server-m1-15.zip', '服务器产物名称错误')
assert(fs.existsSync(file) && fs.statSync(file).size === artifact.bytes, '服务器产物字节数不符')
const digest = await sha256(file)
assert(digest === artifact.sha256, '服务器产物 SHA-256 不符')
assert(fs.readFileSync(`${file}.sha256`, 'utf8').trim() === `${digest}  ${artifact.name}`, 'SHA 文件不符')

const serverRoot = expand(artifact.name, 'verified-server')
for (const required of [
  'server/yumpoo-server.jar',
  'web/index.html',
  'windows/RUNBOOK.md',
  'windows/deployment-checklist-m1-15.json',
  'windows/Invoke-AppManagerMaintenance.ps1',
  'windows/Invoke-InitialIdentityBootstrap.ps1',
  'windows/config/application-prod.yml',
  'windows/secrets/application-secrets.yml',
  'windows/secrets/initial-identity-bootstrap.example.json',
  'windows/database/migrations/identityaccess/V15__productize_desktop_auth_attempt.sql',
]) assert(fs.existsSync(path.join(serverRoot, ...required.split('/'))), `服务器包缺少：${required}`)

for (const packagedFile of await collectRegularFiles(serverRoot)) {
  const relative = path.relative(serverRoot, packagedFile).replaceAll('\\', '/')
  assert(!relative.endsWith('.map'), `服务器包包含 source map：${relative}`)
  assert(!/yumpoo-desktop|YumpooDesktop\.exe/iu.test(relative), `服务器包混入桌面产物：${relative}`)
  assert(relative !== 'windows/secrets/initial-identity-bootstrap.json', '服务器包混入真实首次引导输入')
  if (isTextAsset(relative)) {
    const content = fs.readFileSync(packagedFile, 'utf8')
    assert(!/wwb496fdc488200f8f|m115-private-|sensitive-(?:app-manager|company-admin)-id|BEGIN (?:RSA |EC )?PRIVATE KEY/iu.test(content),
      `服务器包文本资产包含敏感标识或凭据：${relative}`)
    assert(!/__Host-yumpoo-(?:session|csrf)=[^;\s]+/iu.test(content), `服务器包文本资产包含 Cookie 值：${relative}`)
  }
}

const reportText = JSON.stringify(report)
assert(!/(__Host-|handoffCode|codeVerifier|sessionCredential|csrfCredential|wecomUserId)/iu.test(reportText), '验证报告包含敏感材料')
console.log(`M1-15 Windows 服务器包已复核；source commit ${manifest.sourceCommit}`)

function expand(name, directoryName) {
  const destination = path.join(out, directoryName)
  fs.rmSync(destination, { recursive: true, force: true })
  const command = `Expand-Archive -LiteralPath ${quotePowerShellLiteral(path.join(out, name))} -DestinationPath ${quotePowerShellLiteral(destination)} -Force`
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command], { stdio: 'inherit' })
  assert(result.status === 0, `无法解包 ${name}`)
  return destination
}

function readJson(file) {
  assert(fs.existsSync(file), `缺少 ${path.basename(file)}`)
  return JSON.parse(fs.readFileSync(file, 'utf8'))
}

function isTextAsset(relative) {
  return /\.(?:md|json|ya?ml|ps1|conf|sql|html|js|css|txt)$/iu.test(relative)
}

function assert(condition, message) {
  if (!condition) throw new Error(`M1-15 包验证失败：${message}`)
}
