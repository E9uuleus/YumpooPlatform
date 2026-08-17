import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { collectRegularFiles, quotePowerShellLiteral, sha256 } from './m0-16-utils.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const out = path.join(root, 'out', 'm1-14')
const manifest = readJson(path.join(out, 'artifact-manifest.json'))
const report = readJson(path.join(out, 'verification-report.json'))

assert(manifest.schemaVersion === 1 && manifest.milestone === 'M1-14', 'artifact manifest 里程碑错误')
assert(report.milestone === 'M1-14' && report.liveEnvironment === 'ENV_PENDING', '验证报告必须保留真实环境 ENV_PENDING')
assert(manifest.sourceCommit === report.sourceCommit && /^[0-9a-f]{40}$/u.test(manifest.sourceCommit), '产物未绑定有效源码 commit')

for (const artifact of manifest.artifacts) {
  const file = path.join(out, artifact.name)
  assert(fs.existsSync(file), `缺少产物：${artifact.name}`)
  assert(fs.statSync(file).size === artifact.bytes, `产物字节数不符：${artifact.name}`)
  const digest = await sha256(file)
  assert(digest === artifact.sha256, `产物 SHA-256 不符：${artifact.name}`)
  assert(fs.readFileSync(`${file}.sha256`, 'utf8').trim() === `${digest}  ${artifact.name}`, `SHA 文件不符：${artifact.name}`)
}

const serverRoot = expand('yumpoo-windows-server-m1-14.zip', 'verified-server')
const desktopRoot = expand('yumpoo-desktop-m1-14-win32-x64.zip', 'verified-desktop')
for (const required of [
  'server/yumpoo-server.jar', 'web/index.html', 'windows/RUNBOOK.md',
  'windows/deployment-checklist-m1-14.json', 'windows/config/application-prod.yml',
  'windows/secrets/application-secrets.yml', 'windows/nginx/yumpoo-wecom.conf',
  'windows/database/initialize-database.sql',
  'windows/database/migrations/identityaccess/V15__productize_desktop_auth_attempt.sql',
]) {
  assert(fs.existsSync(path.join(serverRoot, ...required.split('/'))), `服务器包缺少：${required}`)
}
assert(fs.existsSync(path.join(desktopRoot, 'YumpooDesktop.exe')), '桌面包缺少 YumpooDesktop.exe')
assert(fs.existsSync(path.join(desktopRoot, 'resources', 'app.asar')), '桌面包缺少 app.asar')

for (const file of await collectRegularFiles(serverRoot)) {
  const relative = path.relative(serverRoot, file).replaceAll('\\', '/')
  assert(!relative.endsWith('.map'), `服务器包包含 source map：${relative}`)
}
for (const file of await collectRegularFiles(desktopRoot)) {
  const relative = path.relative(desktopRoot, file).replaceAll('\\', '/')
  assert(!/index\.html$|web-app|frontend\/web/iu.test(relative), `桌面包不得包含业务 SPA：${relative}`)
}

const reportText = JSON.stringify(report)
assert(!/(__Host-|handoffCode|codeVerifier|sessionCredential|csrfCredential|secret\s*[:=])/iu.test(reportText), '验证报告包含敏感材料')
console.log(`M1-14 服务器与桌面 PILOT 包已复核；source commit ${manifest.sourceCommit}`)

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

function assert(condition, message) {
  if (!condition) throw new Error(`M1-14 包验证失败：${message}`)
}
