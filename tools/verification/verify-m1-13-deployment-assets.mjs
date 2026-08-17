import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import YAML from 'yaml'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const deploymentRoot = path.join(repositoryRoot, 'deployment', 'windows')

const ordinaryRaw = read('config/application-prod.yml')
const secretsRaw = read('secrets/application-secrets.yml')
const ordinary = YAML.parse(ordinaryRaw)
const secrets = YAML.parse(secretsRaw)
const checklist = JSON.parse(read('deployment-checklist-m1-13.json'))
const nginx = read('nginx/yumpoo-wecom.conf')
const database = read('database/initialize-database.sql')
const runbook = read('RUNBOOK.md')

assert(ordinary.server?.address === '127.0.0.1' && ordinary.server?.port === 8100, '后端必须固定监听 127.0.0.1:8100')
const deployment = ordinary.yumpoo?.deployment ?? {}
for (const name of ['release-root', 'config-root', 'secrets-root', 'attachment-root', 'upload-temp-root', 'log-root']) {
  assert(typeof deployment[name] === 'string' && /^C:\//u.test(deployment[name]), `${name} 必须位于 C 盘`)
}
assert(!ordinaryRaw.includes('D:/') && !ordinaryRaw.includes('D:\\'), '普通配置不得引用 D 盘')
assert(!/password\s*:/iu.test(ordinaryRaw), '普通配置不得包含密码')
assert(secrets.spring?.datasource?.password?.startsWith('change-me-'), '应用数据库密码必须保持占位值')
assert(secrets.spring?.flyway?.password?.startsWith('change-me-'), '迁移数据库密码必须保持占位值')
assert(secrets.yumpoo?.wecom?.oauth?.['app-secret']?.startsWith('change-me-'), 'OAuth Secret 必须保持占位值')

assert(checklist.milestone === 'M1-13' && checklist.mode === 'TARGET_SERVER_VALIDATION', '部署清单必须属于 M1-13 目标机验证')
assert(checklist.target?.storageVolume === 'C:', '部署清单必须固定为 C 盘')
assert(checklist.runtime?.mode === 'MANUAL_JAVA_CONSOLE', '本次运行方式必须为手工 Java 控制台')
assert(checklist.runtime?.windowsServiceWrapper === false && checklist.runtime?.automaticRestart === false, '本次不得声明 Windows 服务或自动恢复')
assert(checklist.target?.frontendPort === 18173 && checklist.target?.backendPort === 8100, '前后端端口必须为 18173/8100')

for (const fragment of [
  'listen 127.0.0.1:18173 default_server;',
  'server_name wecom-dev.yumpoo.com;',
  'proxy_pass http://127.0.0.1:8100;',
  'proxy_pass http://127.0.0.1:18173;',
  'location = /api',
  'location ~ ^/(actuator|_m0)(/|$)',
]) {
  assert(nginx.includes(fragment), `Nginx 配置缺少：${fragment}`)
}

for (const fragment of [
  '\\set ON_ERROR_STOP on',
  'CREATE ROLE yumpoo_migrator LOGIN NOSUPERUSER',
  'CREATE ROLE yumpoo_app LOGIN NOSUPERUSER',
  "CREATE DATABASE yumpoo OWNER yumpoo_migrator ENCODING ''UTF8'' TEMPLATE template0",
  'CREATE SCHEMA IF NOT EXISTS yumpoo AUTHORIZATION yumpoo_migrator;',
  'GRANT USAGE ON SCHEMA yumpoo TO yumpoo_app;',
  'ALTER DEFAULT PRIVILEGES FOR ROLE yumpoo_migrator IN SCHEMA yumpoo',
]) {
  assert(database.includes(fragment), `数据库初始化脚本缺少：${fragment}`)
}
assert(!/PASSWORD\s+['"]/iu.test(database), '数据库初始化脚本不得保存密码')

for (const fragment of [
  'out\\m1-13\\yumpoo-windows-m1-13.zip',
  'C:\\ProgramData\\Yumpoo\\data\\attachments',
  'MANUAL_JAVA_CONSOLE',
  'initialize-database.sql',
  '首个身份限制',
  'BLOCKED',
]) {
  assert(runbook.includes(fragment), `M1-13 手册缺少：${fragment}`)
}
assert(!runbook.includes('D:\\Yumpoo'), 'M1-13 手册不得引用 D 盘')
assert(!/WinSW/iu.test(runbook), 'M1-13 手册不得要求 WinSW')

console.log('M1-13 C 盘、手工 Java、Nginx 与 PostgreSQL 部署资产有效')

function read(relativePath) {
  return fs.readFileSync(path.join(deploymentRoot, relativePath), 'utf8')
}

function assert(condition, message) {
  if (!condition) throw new Error(`M1-13 部署资产验证失败：${message}`)
}
