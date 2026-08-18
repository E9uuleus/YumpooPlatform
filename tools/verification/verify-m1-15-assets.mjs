import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8')

const service = read('backend/src/main/java/com/yumpoo/platform/identityaccess/application/bootstrap/InitialIdentityBootstrapService.java')
const roles = read('backend/src/main/java/com/yumpoo/platform/identityaccess/application/authorization/PlatformRoleManagementService.java')
const runner = read('backend/src/main/java/com/yumpoo/platform/identityaccess/infrastructure/bootstrap/InitialIdentityBootstrapRunner.java')
const inputReader = read('backend/src/main/java/com/yumpoo/platform/identityaccess/infrastructure/bootstrap/InitialIdentityBootstrapInputReader.java')
const script = read('deployment/windows/Invoke-InitialIdentityBootstrap.ps1')
const template = read('deployment/windows/secrets/initial-identity-bootstrap.example.json')

for (const fragment of [
  'DirectorySyncTriggerType.SCHEDULED',
  'INITIAL_IDENTITY_BOOTSTRAP',
  'DirectorySyncRunStatus.SUCCEEDED',
  'counts().discovered() == 0',
  'ExternalIdentityProvider.WECOM',
  'EmploymentStatus.ACTIVE',
  'AccountStatus.ENABLED',
]) assert(service.includes(fragment), `首次身份编排缺少：${fragment}`)

for (const fragment of [
  '@Transactional',
  'ManagedPlatformRole.APP_MANAGER',
  'ManagedPlatformRole.COMPANY_ADMIN',
  'requireInitialBootstrapOpen',
  'INITIAL_IDENTITY_BOOTSTRAP_SUCCEEDED',
]) assert(roles.includes(fragment), `双角色事务缺少：${fragment}`)

for (const fragment of [
  'spring.main.web-application-type',
  'InitialIdentityBootstrapAuditService',
  'INITIAL_IDENTITY_BOOTSTRAP_WECOM_CONFIG_INVALID',
]) assert(runner.includes(fragment), `维护 Runner 缺少：${fragment}`)

for (const fragment of ['MAX_INPUT_BYTES', 'NOFOLLOW_LINKS', 'toRealPath', 'FIELDS', 'CONFIRMATION']) {
  assert(inputReader.includes(fragment), `输入保护缺少：${fragment}`)
}

for (const fragment of [
  'GetActiveTcpListeners',
  'S-1-1-0',
  'S-1-5-11',
  'S-1-5-32-545',
  '--spring.main.web-application-type=none',
  'Remove-Item -LiteralPath $resolvedInput -Force',
]) assert(script.includes(fragment), `PowerShell 引导缺少：${fragment}`)

const parsedTemplate = JSON.parse(template)
assert(parsedTemplate.schemaVersion === 1, '输入模板版本错误')
assert(parsedTemplate.confirmation === 'M1-15_INITIAL_IDENTITY_BOOTSTRAP', '输入模板确认短语错误')
assert(parsedTemplate.appManagerWeComUserId !== parsedTemplate.companyAdminWeComUserId, '输入模板角色必须分离')

const tracked = [service, roles, runner, inputReader, script, template].join('\n')
assert(!/wwb496fdc488200f8f|BEGIN (RSA |EC )?PRIVATE KEY|__Host-yumpoo-session/iu.test(tracked), 'M1-15 资产包含真实标识或凭据')
console.log('M1-15 目录同步、双首管事务、输入保护与脱敏静态契约有效')

function assert(condition, message) {
  if (!condition) throw new Error(`M1-15 资产验证失败：${message}`)
}
