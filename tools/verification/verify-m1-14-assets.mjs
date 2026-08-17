import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8')

const gateway = read('backend/src/main/java/com/yumpoo/platform/identityaccess/infrastructure/wecom/RestClientWeComIdentityGateway.java')
const webProvider = read('backend/src/main/java/com/yumpoo/platform/identityaccess/infrastructure/wecom/RestClientWebIdentityProvider.java')
const electronService = read('backend/src/main/java/com/yumpoo/platform/identityaccess/application/desktopauth/ProductDesktopAuthenticationService.java')
const electronController = read('backend/src/main/java/com/yumpoo/platform/identityaccess/api/DesktopAuthenticationController.java')
const migration = read('backend/src/main/resources/db/migration/identityaccess/V15__productize_desktop_auth_attempt.sql')
const desktopMain = read('desktop/desktop-shell/src/main/index.ts')
const windowPolicy = read('desktop/desktop-shell/src/main/window-policy.ts')
const credentialStore = read('desktop/desktop-shell/src/main/credential-store.ts')
const openapi = read('contracts/openapi/yumpoo-v1.yaml')
const login = read('frontend/web-app/src/views/LoginView.vue')

for (const fragment of ['open.work.weixin.qq.com', '/wwopen/sso/qrConnect', 'appid', 'agentid', 'redirect_uri', 'state']) {
  assert(gateway.includes(fragment), `Web 扫码授权缺少契约：${fragment}`)
}
for (const forbidden of ['response_type', 'snsapi_base', 'wechat_redirect']) {
  const qrMethod = gateway.slice(gateway.indexOf('URI buildQrAuthorizationUri'), gateway.indexOf('private Map<String, String> authorizationParameters'))
  assert(!qrMethod.includes(forbidden), `Web 扫码授权仍携带移动端参数：${forbidden}`)
}
assert(webProvider.includes('buildQrAuthorizationUri'), '正式 Web/Electron provider 必须调用 qrConnect 构造器')
for (const fragment of ['claimProductAuthorization', 'issueProductHandoff', 'consumeProduct', '@Transactional', 'issueElectronSession']) {
  assert(electronService.includes(fragment), `Electron 事务闭环缺少：${fragment}`)
}
for (const route of ['/electron/auth/attempts', '/electron/auth/wecom/callback', '/electron/auth/exchange']) {
  assert(openapi.includes(route), `OpenAPI 缺少：${route}`)
}
for (const fragment of ['NO_STORE', 'yumpoo://auth/callback', 'X-Client-Protocol-Version']) {
  assert(electronController.includes(fragment), `Electron API 防护缺少：${fragment}`)
}
for (const fragment of ['authenticated_user_id', 'client_version', 'client_protocol_version', 'authorization_claimed_at']) {
  assert(migration.includes(fragment), `V15 迁移缺少：${fragment}`)
}
for (const fragment of ['safeStorage', 'clearSessionCookies']) {
  assert(desktopMain.includes(fragment), `Electron Main 缺少：${fragment}`)
}
assert(windowPolicy.includes("partition: 'yumpoo-authenticated'"), 'Electron Window 必须使用非持久化会话分区')
for (const fragment of ['encryptString', 'decryptString', '__Host-yumpoo-session', '__Host-yumpoo-csrf']) {
  assert(credentialStore.includes(fragment), `Electron 凭据存储缺少：${fragment}`)
}
assert(login.includes('beginAuthentication') && login.includes('yumpooDesktop?.auth.start') && login.includes('@click="login"'), '登录页必须由用户点击触发 Web/Electron 授权')

const trackedText = [gateway, webProvider, electronService, electronController, migration, desktopMain, windowPolicy, credentialStore, openapi, login].join('\n')
assert(!/wwb496fdc488200f8f|BEGIN (RSA |EC )?PRIVATE KEY|wecom-secret\s*[:=]\s*[^${]/iu.test(trackedText), 'M1-14 源码包含真实标识或 Secret')

console.log('M1-14 扫码、PKCE、事务、safeStorage 与 OpenAPI 静态契约有效')

function assert(condition, message) {
  if (!condition) throw new Error(`M1-14 资产验证失败：${message}`)
}
