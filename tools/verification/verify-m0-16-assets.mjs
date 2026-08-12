import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import Ajv from 'ajv'
import addFormats from 'ajv-formats'
import YAML from 'yaml'
import { assertM016 } from './m0-16-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const deploymentRoot = path.join(repositoryRoot, 'deployment', 'windows')
const evidenceRoot = path.join(repositoryRoot, 'evidence', 'm0-16')

const expectedEvidenceChecks = [
  'cleanServer',
  'trustedHttps',
  'only443Public',
  'portsIsolated',
  'dedicatedLowPrivilegeAccount',
  'interactiveLogonDenied',
  'aclVerified',
  'persistentDataPreservedOnUpgrade',
  'wholeMachineRestartRecovered',
  'healthSemanticsVerified',
  'sensitiveDataRedacted',
  'signedReceiptVerified',
]

validateEvidence()
validateConfigurationTemplates()
validateIis()
validateWinSw()
validateChecklistAndRunbook()
validateNoBinary()
console.log('M0-16 部署资产、供应链锁定信息和 NOT_RUN 证据有效')

function readText(relativePath) {
  return fs.readFileSync(path.join(deploymentRoot, relativePath), 'utf8')
}

function readJson(filePath, label) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'))
  } catch {
    throw new Error(`M0-16 验证失败：${label} 不是有效 JSON`)
  }
}

function validateEvidence() {
  const schema = readJson(path.join(evidenceRoot, 'live-verification.schema.json'), '证据 Schema')
  const example = readJson(path.join(evidenceRoot, 'live-verification.example.json'), '示例证据')
  const current = readJson(path.join(evidenceRoot, 'live-verification.json'), '当前证据')
  const ajv = new Ajv({ allErrors: true, strict: false })
  addFormats(ajv)
  const validate = ajv.compile(schema)
  assertM016(validate(example), '示例证据不符合 Schema')
  assertM016(validate(current), '当前证据不符合 Schema')
  for (const evidence of [example, current]) {
    assertM016(evidence.status === 'NOT_RUN', 'M0-16 不得生成或提交 PASS 证据')
    assertM016(
      Object.keys(evidence.checks).sort().join() === [...expectedEvidenceChecks].sort().join(),
      '证据检查项白名单不匹配',
    )
  }
}

function validateConfigurationTemplates() {
  const ordinaryRaw = readText('config/application-prod.yml')
  const secretsRaw = readText('secrets/application-secrets.yml')
  const ordinary = YAML.parse(ordinaryRaw)
  const secrets = YAML.parse(secretsRaw)
  assertM016(ordinary.server?.address === '127.0.0.1', '生产模板必须只绑定 127.0.0.1')
  assertM016(Number.isInteger(ordinary.server?.port), '生产模板必须固定整数端口')
  assertM016(!ordinary.spring?.datasource?.password && !ordinary.spring?.flyway?.password, '普通配置不得包含密码')
  assertM016(
    secrets.spring?.datasource?.password?.startsWith('change-me-') &&
      secrets.spring?.flyway?.password?.startsWith('change-me-'),
    'Secret 示例必须保持显式占位值',
  )
  const deployment = ordinary.yumpoo?.deployment ?? {}
  const required = ['public-base-url', 'release-root', 'config-root', 'secrets-root', 'attachment-root', 'upload-temp-root', 'log-root']
  assertM016(required.every((name) => typeof deployment[name] === 'string'), '普通配置缺少 yumpoo.deployment 项')
  assertM016(!/password\s*:/iu.test(ordinaryRaw), '普通配置疑似包含密码项')
}

function validateIis() {
  const xml = readText('iis/web.config')
  parseXml(path.join(deploymentRoot, 'iis', 'web.config'), 'IIS web.config')
  const requiredFragments = [
    'maxAllowedContentLength="115343360"',
    '^(actuator|_m0)(/.*)?$',
    'HTTP_X_REQUEST_ID',
    'value=""',
    'HTTP_X_FORWARDED_PROTO',
    'value="https"',
    'http://127.0.0.1:8080/',
    '^/(api|actuator|_m0)(/|$)',
    'Content-Security-Policy',
    'Strict-Transport-Security',
    'X-Frame-Options',
    'X-Content-Type-Options',
    'Referrer-Policy',
    'Permissions-Policy',
  ]
  assertM016(requiredFragments.every((fragment) => xml.includes(fragment)), 'IIS 安全或路由规则不完整')
  assertM016(xml.indexOf('Deny internal endpoints') < xml.indexOf('Proxy API to loopback'), '内部端点拒绝规则必须先于 API 代理')
  assertM016(xml.indexOf('Proxy API to loopback') < xml.indexOf('SPA fallback'), 'API 代理必须先于 SPA fallback')
}

function validateWinSw() {
  const lock = readJson(path.join(deploymentRoot, 'service', 'winsw-lock.json'), 'WinSW 锁定文件')
  assertM016(lock.version === '2.12.0' && lock.asset === 'WinSW-x64.exe' && lock.architecture === 'x64', 'WinSW 版本或架构未锁定')
  assertM016(lock.url === 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe', 'WinSW 必须使用官方发布 URL')
  assertM016(lock.bytes === 18243033, 'WinSW x64 文件大小不匹配')
  assertM016(lock.sha256 === '05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da', 'WinSW x64 SHA-256 不匹配')

  const xml = readText('service/yumpoo-server.xml')
  parseXml(path.join(deploymentRoot, 'service', 'yumpoo-server.xml'), 'WinSW XML')
  const requiredFragments = [
    '<delayedAutoStart />',
    '<depend>postgresql-x64-17</depend>',
    '-Dfile.encoding=UTF-8',
    'file:C:/ProgramData/Yumpoo/config/,file:C:/ProgramData/Yumpoo/secrets/',
    '<stoptimeout>60 sec</stoptimeout>',
    'action="restart" delay="10 sec"',
    'action="restart" delay="30 sec"',
    '<onfailure action="none" />',
    '<resetfailure>1 hour</resetfailure>',
    '<log mode="roll-by-size">',
  ]
  assertM016(requiredFragments.every((fragment) => xml.includes(fragment)), 'WinSW 服务策略不完整')
  assertM016(!/(password|localsystem|serviceaccount|interactive)/iu.test(xml), 'WinSW XML 不得保存账号、密码或桌面交互配置')
}

function validateChecklistAndRunbook() {
  const checklist = readJson(path.join(deploymentRoot, 'deployment-checklist.json'), '部署清单')
  assertM016(checklist.mode === 'DRY_RUN_ONLY', '部署清单只能为 DRY_RUN_ONLY')
  assertM016(checklist.target?.operatingSystem === 'Windows Server 2022', '目标系统必须为 Windows Server 2022')
  assertM016(checklist.target?.publicPort === 443, '公开端口必须仅为 443')
  const expectedChecks = [
    'cleanWindowsServer2022', 'trustedHttpsCertificate', 'only443Public',
    'applicationAndDatabasePortsIsolated', 'dedicatedLowPrivilegeServiceAccount',
    'interactiveLogonDenied', 'ntfsAclApplied', 'persistentDataSurvivesUpgrade',
    'wholeMachineRestartRecovery', 'logsAndEvidenceRedacted',
  ]
  assertM016(expectedChecks.every((item) => checklist.checks.includes(item)), '目标机检查项不完整')
  const runbook = readText('RUNBOOK.md')
  for (const fragment of ['install /p', 'URL Rewrite', 'Application Request Routing', '只公开受信任证书的 443', '不得回滚或删除持久数据', 'NOT_RUN']) {
    assertM016(runbook.includes(fragment), `运行清单缺少：${fragment}`)
  }
}

function validateNoBinary() {
  const files = walkFiles(deploymentRoot)
  assertM016(!files.some((file) => /\.(exe|dll|msi)$/iu.test(file)), '仓库不得携带 WinSW 或其他部署二进制')
}

function walkFiles(root) {
  return fs.readdirSync(root, { withFileTypes: true }).flatMap((entry) => {
    const candidate = path.join(root, entry.name)
    return entry.isDirectory() ? walkFiles(candidate) : [candidate]
  })
}

function parseXml(filePath, label) {
  if (process.platform !== 'win32') return
  const command = `$m016Xml = [xml](Get-Content -LiteralPath '${filePath.replaceAll("'", "''")}' -Raw); if ($null -eq $m016Xml.DocumentElement) { exit 1 }`
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command], { stdio: 'ignore' })
  assertM016(result.status === 0, `${label} 不是有效 XML`)
}
