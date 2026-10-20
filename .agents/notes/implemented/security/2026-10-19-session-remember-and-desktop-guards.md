# Agent Note: 7 天内免登录、桌面登录尝试与主窗口权限

Status: implemented

## Problem

会话闲置 8 小时即失效，7 天只是绝对上限，用户隔夜就要重新扫码。桌面业务分区是内存分区，网页的本机偏好每次启动都会丢失。桌面登录结果只能经 `yumpoo://` 深链回传，浏览器侧取消或关页时客户端停在等待状态，直到下次点击才惰性判断过期。主窗口会话拒绝一切权限与下载，复制按钮和附件保存在桌面端都不可用。

## Decision

**会话保持**：登录页提供「7 天内免登录」，默认勾选，本机 localStorage（`yumpoo.auth.remember.v1`）记住上次选择。

- 勾选：`login_session.remember_me = true`，闲置到期等于绝对到期（7 天），[续期](../../../../backend/src/main/java/com/yumpoo/platform/identityaccess/application/session/SessionService.java)不会把它缩短回闲置窗口；Session/CSRF Cookie 的 Max-Age 为剩余绝对时长。
- 不勾选：闲置 8 小时失效；Web 的 Session/CSRF Cookie 不带 Max-Age，关闭浏览器即失效；桌面不保存凭据。
- 选择随登录尝试传入：Web 经 `GET /auth/wecom/authorize?remember=`，与 nonce 并列写入 5 分钟的 `__Host-yumpoo-oauth-remember` Cookie，回调读取后清除；桌面经 `POST /electron/auth/attempts` 的 `remember` 存入 `desktop_auth_attempt.remember_me`，兑换时生效。参数或字段缺省一律按 `true`，兼容老壳与老链接。轮换会话沿用原会话的选择。

**桌面分区与凭据边界**：业务分区为 `persist:yumpoo-authenticated`，主进程在创建任何窗口前以 `cache: false` 建立，磁盘不留业务接口响应；网页 localStorage 跨重启保留，「诊断与修复 → 重置本机偏好」会一并清空。壳注入的登录 Cookie 不带过期时间，不写入磁盘；凭据只在勾选时以 safeStorage（DPAPI）保存到 `auth/electron-session.bin`，不勾选时不写入并删除已有文件，下次启动需要重新登录。

**桌面登录尝试**：再次开始登录会丢弃仍在等待的尝试并重开浏览器；尝试在到期时主动推送 `FAILED/ATTEMPT_EXPIRED`。可选桥接 `auth.cancel()`（`yumpoo:auth:cancel`，只接受主窗口主框架且来源精确匹配）清空等待中的尝试并推送 `IDLE`，迟到的回调按 `NO_PENDING_ATTEMPT` 拒绝。登录页只在打开浏览器与兑换时显示加载，等待回调时提供「重新打开浏览器」与「取消」（老壳没有 `cancel` 时不显示）。

**主窗口权限**：[安全门禁](../../../../desktop/desktop-shell/src/main/security-guards.ts)只为来源与 Web 应用精确匹配的页面放行 `clipboard-sanitized-write`（`navigator.clipboard.writeText`），剪贴板读取与其他权限仍全部拒绝。下载只放行同源 `/api/v1/attachments/{uuid}/content`，交给系统保存对话框（默认下载目录、服务端给出的文件名），其余下载照旧拦截。这部分替代[团队报表 Note](2026-10-08-admin-team-reporting.md) 中「全局下载拦截保持不变」，导出文件仍走该 Note 的 `yumpoo:files:save-export`。

## Alternatives considered

- 只把闲置时间调长到 7 天：不勾选的用户也会长期保持登录，共用电脑无法选择更短的会话。
- 把 Web 的选择存进 `wecom_oauth_attempt`：`OAuthAttemptStore.consume` 由 Web 登录与企微核验共用，需改返回类型和多处调用；与 nonce 同生共灭的 `__Host-` Cookie 已能绑定到本次尝试。
- 桌面继续用内存分区，另做偏好文件同步网页 localStorage：要在壳里复制网页的存储语义；持久分区加关闭缓存、Cookie 不带过期时间即可保持凭据边界。
- 让后端在浏览器取消时通知客户端：浏览器关页没有任何回调，只能由客户端自己计时与提供取消。
- 放开全部下载或用 `setSavePath` 直接落盘：网页可把任意内容写到本机；只放行附件内容并由用户选择位置。

## Consequences

- 勾选免登录的会话在 7 天内不因闲置失效，被盗用的 Cookie 有效期相应变长；Cookie 仍为 `__Host-`、`Secure`、`HttpOnly`（Session）与 `SameSite=Lax`，管理员强制下线与账号停用照旧立即生效。
- 服务端修复 CSRF 时下发的 CSRF Cookie 按会话的选择带或不带 Max-Age；勾选的桌面会话会把它写入持久分区（受 `EnableCookieEncryption` 保护），它不能单独构成会话。
- 上一版本在新 schema 上写入的会话取 `remember_me = false`，即原有 8 小时闲置；回退安全。
- 附件内容接口的流式响应在异步派发时不再重新认证，授权只发生在首次派发，见[运维诊断 Note](2026-09-26-operations-diagnostics-access.md)。

## Verification

- 桌面 8 个相关 spec 共 61 项通过；桌面类型检查通过。
- OpenAPI 重新生成客户端，代码与 BF 方案及源码镜像核对一致。
- 其余验证结果记录在本 PR。
