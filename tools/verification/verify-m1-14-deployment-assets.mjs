import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const runbook = fs.readFileSync(path.join(root, 'deployment', 'windows', 'RUNBOOK.md'), 'utf8')
const historicalRunbook = fs.readFileSync(path.join(root, 'deployment', 'windows', 'RUNBOOK-M1-13.md'), 'utf8')
const checklist = JSON.parse(fs.readFileSync(path.join(root, 'deployment', 'windows', 'deployment-checklist-m1-14.json'), 'utf8'))
const config = fs.readFileSync(path.join(root, 'deployment', 'windows', 'config', 'application-prod.yml'), 'utf8')

assert(historicalRunbook.startsWith('# M1-13 '), 'M1-13 RUNBOOK 历史快照缺失')
assert(checklist.milestone === 'M1-14' && checklist.mode === 'PACKAGE_ONLY', 'M1-14 checklist 模式错误')
assert(checklist.deploymentAuthorized === false, '本步骤不得授权自动部署')
assert(checklist.liveChecks.every((item) => item.status === 'ENV_PENDING' && item.owner === 'M6-01'), '真实环境证据必须由 M6-01 清零')
for (const fragment of [
  '企业微信授权登录 → Web网页', 'wecom-dev.yumpoo.com',
  '/api/v1/auth/wecom/callback', '/api/v1/electron/auth/wecom/callback',
  'yumpoo://auth/callback', 'safeStorage', 'ENV_PENDING',
  'Secret、Cookie', '回退', '0.1.0', '协议版本固定 `1`',
]) {
  assert(runbook.includes(fragment), `RUNBOOK 缺少：${fragment}`)
}
assert(config.includes('electron-callback-uri: https://wecom-dev.yumpoo.com/api/v1/electron/auth/wecom/callback'), '生产配置缺少 Electron callback')
console.log('M1-14 RUNBOOK、checklist、双 callback 与 ENV_PENDING 边界有效')

function assert(condition, message) {
  if (!condition) throw new Error(`M1-14 部署资产验证失败：${message}`)
}
