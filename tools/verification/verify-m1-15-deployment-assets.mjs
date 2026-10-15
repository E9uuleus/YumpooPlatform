import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import YAML from 'yaml'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const deployment = path.join(root, 'deployment', 'windows')
const runbook = fs.readFileSync(path.join(deployment, 'RUNBOOK.md'), 'utf8')
const historicalM113 = fs.readFileSync(path.join(deployment, 'RUNBOOK-M1-13.md'), 'utf8')
const historicalM114 = fs.readFileSync(path.join(deployment, 'RUNBOOK-M1-14.md'), 'utf8')
const checklist = JSON.parse(fs.readFileSync(path.join(deployment, 'deployment-checklist-m1-15.json'), 'utf8'))
const config = fs.readFileSync(path.join(deployment, 'config', 'application-prod.yml'), 'utf8')
const production = YAML.parse(config)
const application = YAML.parse(fs.readFileSync(path.join(root, 'backend', 'src', 'main', 'resources', 'application.yml'), 'utf8'))
const script = fs.readFileSync(path.join(deployment, 'Invoke-InitialIdentityBootstrap.ps1'), 'utf8')

assert(historicalM113.startsWith('# M1-13 '), 'M1-13 RUNBOOK 历史快照缺失')
assert(historicalM114.startsWith('# M1-14 '), 'M1-14 RUNBOOK 历史快照缺失')
assert(historicalM114.includes('verify:m1-14:deployment'), 'M1-14 RUNBOOK 历史命令缺失')
assert(runbook.startsWith('# Windows Release '), '当前 RUNBOOK 未说明 Windows Release 发布入口')
assert(checklist.milestone === 'M1-15' && checklist.mode === 'PACKAGE_ONLY', 'M1-15 checklist 模式错误')
assert(checklist.deploymentAuthorized === false, 'M1-15 不得自动授权部署')
assert(checklist.bootstrap.databaseMigrationAdded === false && checklist.bootstrap.publicApiChanged === false, 'M1-15 边界错误')
assert(checklist.liveChecks.every((item) => item.status === 'ENV_PENDING' && item.owner === 'M6-01'), '真实环境证据必须保持 ENV_PENDING')

for (const fragment of [
  'Invoke-InitialIdentityBootstrap.ps1',
  'initial-identity-bootstrap.json',
  'APP_MANAGER',
  'COMPANY_ADMIN',
  'Get-NetTCPConnection',
  '数据库备份',
  '自动删除',
  'ENV_PENDING',
  'M1-14 `0.1.0`',
  '不新增 Flyway',
  '回退',
]) assert(runbook.includes(fragment), `RUNBOOK 缺少：${fragment}`)

assert(config.includes('initial-identity:') && config.includes('enabled: false'), '生产配置未默认关闭首次引导')
assert(production.yumpoo?.maintenance?.['initial-identity']?.enabled === false, '生产首次引导必须默认关闭')
assert(production.logging?.console?.enabled === false, '生产配置必须关闭常规控制台日志')
assert(production.logging?.file?.name === 'C:/ProgramData/Yumpoo/logs/yumpoo-server.log', '生产 JSON 日志路径错误')
assert(application.logging?.structured?.format?.file === 'com.yumpoo.platform.foundation.infrastructure.logging.YumpooJsonLogFormatter', '应用 JSON 日志格式丢失')
const rolling = application.logging?.logback?.rollingpolicy
assert(rolling?.['max-file-size'] === '50MB' && rolling['max-history'] === 90 && rolling['total-size-cap'] === '10GB', '应用文件日志轮转边界改变')
for (const required of ['Initialize-YumpooHost.ps1', 'Invoke-YumpooDeploy.ps1', 'Start-YumpooNginx.ps1', 'YumpooDeployment.psm1']) {
  assert(fs.statSync(path.join(deployment, 'cd', required)).isFile(), `发布包缺少固定部署程序：${required}`)
}
assert(script.includes('YUMPOO_INITIAL_IDENTITY_BOOTSTRAP_ENABLED'), '维护脚本未显式启用 Runner')
assert(script.includes('Remove-Item -LiteralPath $resolvedInput -Force'), '维护脚本未在成功后删除输入')
console.log('Windows Release 资产、生产文件日志与 M1-15 首次引导历史边界有效')

function assert(condition, message) {
  if (!condition) throw new Error(`M1-15 部署资产验证失败：${message}`)
}
