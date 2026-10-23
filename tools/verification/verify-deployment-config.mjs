import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import YAML from 'yaml'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const deployment = path.join(root, 'deployment', 'windows')
const deploymentRoot = deployment
const config = fs.readFileSync(path.join(deployment, 'config', 'application-prod.yml'), 'utf8')
const production = YAML.parse(config)
const application = YAML.parse(fs.readFileSync(path.join(root, 'backend', 'src', 'main', 'resources', 'application.yml'), 'utf8'))
const nginx = fs.readFileSync(path.join(deployment, 'nginx', 'yumpoo-wecom.conf'), 'utf8')

assert(config.includes('initial-identity:') && config.includes('enabled: false'), '生产配置未默认关闭首次引导')
assert(production.yumpoo?.maintenance?.['initial-identity']?.enabled === false, '生产首次引导必须默认关闭')
assert(production.logging?.console?.enabled === false, '生产配置必须关闭常规控制台日志')
assert(production.logging?.file?.name === 'C:/ProgramData/Yumpoo/logs/yumpoo-server.log', '生产 JSON 日志路径错误')
assert(application.logging?.structured?.format?.file === 'com.yumpoo.platform.foundation.infrastructure.logging.YumpooJsonLogFormatter', '应用 JSON 日志格式丢失')
const rolling = application.logging?.logback?.rollingpolicy
assert(rolling?.['max-file-size'] === '50MB' && rolling['max-history'] === 90 && rolling['total-size-cap'] === '10GB', '应用文件日志轮转边界改变')
for (const required of ['Initialize-YumpooHost.ps1', 'Invoke-YumpooDeploy.ps1', 'Start-YumpooNginx.ps1', 'YumpooDeployment.psm1', 'Update-YumpooEngine.ps1']) {
  assert(fs.statSync(path.join(deployment, 'cd', required)).isFile(), `发布包缺少固定部署程序：${required}`)
}
assert(application.management?.endpoints?.web?.exposure?.include === 'health,info,prometheus', 'Actuator 仅允许健康、版本和内部 Prometheus 指标；公网仍由 Nginx 拒绝内部路径')
const monitorGateway = fs.readFileSync(path.join(deployment, 'nginx/yumpoo-monitor.conf'), 'utf8')
assert(monitorGateway.includes('listen 127.0.0.1:19100;') && monitorGateway.includes('location / { return 404; }'), '监控网关必须限于回环地址和固定路径')
assert((monitorGateway.match(/location = \/metrics\/(windows|app)/gu) || []).length === 2 && (monitorGateway.match(/limit_except GET \{ deny all; \}/gu) || []).length === 2, '监控网关只能暴露两个只读指标路径')
assert(application.spring?.flyway?.['ignore-migration-patterns'] === '*:future', '快速回退要求旧 JAR 忽略更高版本的已应用迁移')
assert(application.info?.release?.tag === '${yumpoo.deployment.release-tag:unreleased}', '发布标签必须由部署引擎注入')
const deny = nginx.indexOf('location ~ ^/(actuator|_m0)(/|$)', nginx.indexOf('listen 443 ssl;'))
for (const [probe, target] of [['/healthz', '/actuator/health/readiness'], ['/version.json', '/actuator/info']]) {
  const location = nginx.indexOf(`location = ${probe} {`)
  assert(location > 0 && location < deny, `Nginx 公开探测 ${probe} 必须是先于 actuator 拒绝规则的精确匹配`)
  assert(nginx.includes(`proxy_pass http://127.0.0.1:8100${target};`), `Nginx 探测 ${probe} 目标错误`)
}
// Every SPA route must reach the SPA: a 443 location that serves or denies something else (such as the update feed at
// /desktop/) would answer it with nginx's own page, which no dev server or unit test exercises.
const publicLocations = [...nginx.slice(nginx.indexOf('listen 443 ssl;')).matchAll(/^\s*location\s+(?:(=|\^~|~\*?)\s+)?(\S+)\s*\{/gmu)]
  .map(([, modifier = '', target]) => ({ modifier, target }))
  .filter(({ modifier, target }) => modifier || target !== '/')
const webSource = path.join(root, 'frontend', 'web-app', 'src')
const routePaths = [
  ...[...fs.readFileSync(path.join(webSource, 'router', 'index.ts'), 'utf8').matchAll(/\bpath:\s*'([^']*)'/gu)].map(([, value]) => value),
  ...[...fs.readFileSync(path.join(webSource, 'components', 'desktop', 'surfaces.ts'), 'utf8').matchAll(/:\s*'(\/[^']*)'/gu)].map(([, value]) => value),
].filter(value => value && !value.includes('*')).map(value => (value.startsWith('/') ? value : `/${value}`))
assert(routePaths.includes('/timer/menu') && routePaths.includes('/login'), '未能读取 SPA 路由')
for (const route of routePaths) {
  const sample = route.replace(/:[^/]+/gu, 'x')
  const fixed = route.split('/:')[0]
  const shadow = publicLocations.find(({ modifier, target }) => modifier === '=' ? sample === target
    : modifier.startsWith('~') ? new RegExp(target, modifier === '~*' ? 'iu' : 'u').test(sample)
      : fixed.startsWith(target) || `${fixed}/`.startsWith(target))
  assert(!shadow, `SPA 路由 ${route} 被 Nginx location ${shadow?.modifier} ${shadow?.target} 遮蔽，生产会返回 Nginx 页面而不是 SPA`)
}

validateConfigurationTemplates()
validateNginx()
validateIisFallback()
validateWinSw()
validateNoBinary()
console.log('部署配置、代理路由与 WinSW 策略校验通过')

function readText(relativePath) {
  return fs.readFileSync(path.join(deploymentRoot, relativePath), 'utf8')
}

function readJson(filePath, label) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'))
  } catch {
    throw new Error(`部署配置 验证失败：${label} 不是有效 JSON`)
  }
}

function validateConfigurationTemplates() {
  const ordinaryRaw = readText('config/application-prod.yml')
  const secretsRaw = readText('secrets/application-secrets.yml')
  const ordinary = YAML.parse(ordinaryRaw)
  const secrets = YAML.parse(secretsRaw)
  assert(ordinary.server?.address === '127.0.0.1', '生产模板必须只绑定 127.0.0.1')
  assert(ordinary.server?.port === 8100, '生产模板必须固定监听 8100')
  assert(!ordinary.spring?.datasource?.password && !ordinary.spring?.flyway?.password, '普通配置不得包含密码')
  assert(
    secrets.spring?.datasource?.password?.startsWith('change-me-') &&
      secrets.spring?.flyway?.password?.startsWith('change-me-'),
    'Secret 示例必须保持显式占位值',
  )
  assert(
    secrets.yumpoo?.session?.['current-key']?.startsWith('change-me-') &&
      secrets.yumpoo?.session?.['current-key-version'] === 'prod-v1',
    '会话 Secret 示例必须保持显式占位值和稳定 keyVersion',
  )
  assert(
    ordinary.yumpoo?.wecom?.oauth?.enabled === false &&
      ordinary.yumpoo?.wecom?.oauth?.['callback-uri'] === 'https://wecom-dev.yumpoo.com/api/v1/auth/wecom/callback' &&
      ordinary.yumpoo?.wecom?.directory?.enabled === false,
    '生产模板必须包含默认关闭且回调域名固定的企微 OAuth/通讯录配置',
  )
  assert(
    secrets.yumpoo?.wecom?.oauth?.['app-secret']?.startsWith('change-me-') &&
      secrets.yumpoo?.wecom?.directory?.['directory-secret']?.startsWith('change-me-') &&
      secrets.yumpoo?.wecom?.directory?.['profile-secret']?.startsWith('change-me-'),
    '企微 Secret 示例必须保持显式占位值',
  )
  const deployment = ordinary.yumpoo?.deployment ?? {}
  const session = ordinary.yumpoo?.session ?? {}
  assert(
    session['idle-timeout'] === '8h' &&
      session['absolute-timeout'] === '7d' &&
      session['revoked-retention'] === '24h',
    '生产模板必须固定 会话时限',
  )
  const required = ['public-base-url', 'release-root', 'config-root', 'secrets-root', 'attachment-root', 'upload-temp-root', 'log-root']
  assert(required.every((name) => typeof deployment[name] === 'string'), '普通配置缺少 yumpoo.deployment 项')
  assert(required.slice(1).every((name) => /^C:\//u.test(deployment[name])), 'Windows 部署路径必须全部位于 C 盘')
  assert(!/password\s*:/iu.test(ordinaryRaw), '普通配置疑似包含密码项')
}

function validateNginx() {
  const nginx = readText('nginx/yumpoo-wecom.conf')
  const requiredFragments = [
    'listen 127.0.0.1:18173 default_server;',
    'server_name wecom-dev.yumpoo.com;',
    'listen 443 ssl;',
    'proxy_pass http://127.0.0.1:8100;',
    'proxy_pass http://127.0.0.1:18173;',
    'location = /api',
    'location /api/',
    'location ~ ^/(actuator|_m0)(/|$)',
    'proxy_set_header X-Forwarded-For $remote_addr;',
    'proxy_set_header X-Forwarded-Proto https;',
    'proxy_set_header X-Request-Id $request_id;',
    'Content-Security-Policy',
    'Strict-Transport-Security',
    'X-Frame-Options',
    'X-Content-Type-Options',
    'Referrer-Policy',
    'Permissions-Policy',
  ]
  assert(requiredFragments.every((fragment) => nginx.includes(fragment)), 'Nginx 安全、监听或路由规则不完整')
  assert(!/^\s*listen\s+18173/mu.test(nginx), '前端 18173 必须显式绑定 127.0.0.1')
  assert(!nginx.includes('listen 8100'), 'Nginx 不得监听 Spring Boot 的 8100 端口')
  assert(nginx.indexOf('location ~ ^/(actuator|_m0)') < nginx.indexOf('location /api/'), '内部端点拒绝规则必须先于 API 代理')
}

function validateIisFallback() {
  const xml = readText('iis/web.config')
  parseXml(path.join(deploymentRoot, 'iis', 'web.config'), 'IIS 回退 web.config')
  const requiredFragments = [
    'maxAllowedContentLength="115343360"',
    '^(actuator|_m0)(/.*)?$',
    'HTTP_X_REQUEST_ID',
    'value=""',
    'HTTP_X_FORWARDED_PROTO',
    'value="https"',
    'http://127.0.0.1:8100/',
    '^/(api|actuator|_m0)(/|$)',
    'Content-Security-Policy',
    'Strict-Transport-Security',
    'X-Frame-Options',
    'X-Content-Type-Options',
    'Referrer-Policy',
    'Permissions-Policy',
  ]
  assert(requiredFragments.every((fragment) => xml.includes(fragment)), 'IIS 回退模板的安全或路由规则不完整')
  assert(xml.indexOf('Deny internal endpoints') < xml.indexOf('Proxy API to loopback'), '内部端点拒绝规则必须先于 API 代理')
  assert(xml.indexOf('Proxy API to loopback') < xml.indexOf('SPA fallback'), 'API 代理必须先于 SPA fallback')
}

function validateWinSw() {
  const lock = readJson(path.join(deploymentRoot, 'service', 'winsw-lock.json'), 'WinSW 锁定文件')
  assert(lock.version === '2.12.0' && lock.asset === 'WinSW-x64.exe' && lock.architecture === 'x64', 'WinSW 版本或架构未锁定')
  assert(lock.url === 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe', 'WinSW 必须使用官方发布 URL')
  assert(lock.bytes === 18243033, 'WinSW x64 文件大小不匹配')
  assert(lock.sha256 === '05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da', 'WinSW x64 SHA-256 不匹配')

  const xml = readText('service/yumpoo-service.xml')
  parseXml(path.join(deploymentRoot, 'service', 'yumpoo-service.xml'), 'WinSW XML')
  const requiredFragments = [
    '<delayedAutoStart>true</delayedAutoStart>',
    '<domain>NT AUTHORITY</domain>',
    '<user>LocalService</user>',
    '<depend>postgresql-x64-17</depend>',
    '-Dfile.encoding=UTF-8',
    'file:C:/ProgramData/Yumpoo/config/,file:C:/ProgramData/Yumpoo/secrets/application-secrets.yml',
    '<env name="YUMPOO_SERVER_PORT" value="8100" />',
    '<stoptimeout>60 sec</stoptimeout>',
    'action="restart" delay="10 sec"',
    'action="restart" delay="30 sec"',
    '<onfailure action="none" />',
    '<resetfailure>1 hour</resetfailure>',
    '<log mode="roll-by-size">',
    '<sizeThreshold>10240</sizeThreshold>',
    '<keepFiles>8</keepFiles>',
  ]
  assert(requiredFragments.every((fragment) => xml.includes(fragment)), 'WinSW 服务策略不完整')
  assert(/<serviceaccount>\s*<domain>NT AUTHORITY<\/domain>\s*<user>LocalService<\/user>\s*<\/serviceaccount>/u.test(xml), 'WinSW 必须使用内置 LocalService 身份')
  assert(!/(password|localsystem|interactive)/iu.test(xml), 'WinSW XML 不得保存密码、LocalSystem 或桌面交互配置')
}

function validateNoBinary() {
  const files = walkFiles(deploymentRoot)
  assert(!files.some((file) => /\.(exe|dll|msi)$/iu.test(file)), '仓库不得携带 WinSW 或其他部署二进制')
}

function walkFiles(root) {
  return fs.readdirSync(root, { withFileTypes: true }).flatMap((entry) => {
    const candidate = path.join(root, entry.name)
    return entry.isDirectory() ? walkFiles(candidate) : [candidate]
  })
}

function parseXml(filePath, label) {
  if (process.platform !== 'win32') {
    const result = spawnSync('python3', ['-c', 'import sys, xml.etree.ElementTree as ET; ET.parse(sys.argv[1])', filePath], { stdio: 'ignore' })
    assert(result.status === 0, `${label} 不是有效 XML 或 Python 3 不可用`)
    return
  }
  const command = `$m016Xml = [xml](Get-Content -LiteralPath '${filePath.replaceAll("'", "''")}' -Raw); if ($null -eq $m016Xml.DocumentElement) { exit 1 }`
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command], { stdio: 'ignore' })
  assert(result.status === 0, `${label} 不是有效 XML`)
}

function assert(condition, message) {
  if (!condition) throw new Error(`部署配置校验失败：${message}`)
}
