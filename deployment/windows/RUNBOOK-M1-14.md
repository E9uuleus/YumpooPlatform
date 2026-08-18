# M1-14 Windows 服务器与 Electron PILOT 运行手册

本手册是当前部署入口。历史 M1-13 手册保留在 `RUNBOOK-M1-13.md`。M1-14 只生成部署包，不授权自动更新 `wecom-dev`；真实 Chrome/Electron 扫码证据必须保持 `ENV_PENDING`，由 M6-01 在目标环境清零。

## 1. 产物与边界

在仓库根目录执行：

```powershell
pnpm verify:m1-14:deployment
```

命令在 `out/m1-14` 生成：

- `yumpoo-windows-server-m1-14.zip` 与 `.sha256`；
- `yumpoo-desktop-m1-14-win32-x64.zip` 与 `.sha256`；
- `artifact-manifest.json` 与 `verification-report.json`。

服务器 ZIP 包含当前 JAR、Web dist、Nginx、配置/Secret 模板、Flyway 迁移、checklist 和本手册。桌面 ZIP 是 Windows x64 可运行 PILOT 包，不是安装器，且不包含业务 SPA。安装、升级、卸载和 SmartScreen 门禁仍属于 M4-14。

## 2. 企业微信管理后台

在自建应用保持同一 `CorpID`、`AgentID` 和 `Secret`，并同时完成：

1. “企业微信授权登录 → Web网页”配置精确授权回调域 `wecom-dev.yumpoo.com`，只配应用可信域名不算完成；
2. 应用可信域名继续包含 `wecom-dev.yumpoo.com`；
3. Web HTTPS callback 为 `https://wecom-dev.yumpoo.com/api/v1/auth/wecom/callback`；
4. Electron HTTPS callback 为 `https://wecom-dev.yumpoo.com/api/v1/electron/auth/wecom/callback`。

扫码授权地址必须是 `https://open.work.weixin.qq.com/wwopen/sso/qrConnect`，参数只允许 `appid`、`agentid`、`redirect_uri`、`state`。不得恢复移动端 `connect/oauth2/authorize`、`scope`、`response_type` 或 `#wechat_redirect`。

## 3. 服务器配置与启动

将服务器 ZIP 解压到版本目录，例如 `C:\Program Files\Yumpoo\releases\m1-14`。按历史 M1-13 手册的 PostgreSQL、Nginx、目录 ACL 和 Java 21 步骤准备目标机，并把普通配置放到 `C:\ProgramData\Yumpoo\config`，Secret 配置放到仅服务账号可读的 `C:\ProgramData\Yumpoo\secrets`。

`application-prod.yml` 必须设置：

```yaml
yumpoo:
  wecom:
    oauth:
      enabled: true
      corp-id: ww-replace-with-corp-id
      agent-id: replace-with-agent-id
      callback-uri: https://wecom-dev.yumpoo.com/api/v1/auth/wecom/callback
      electron-callback-uri: https://wecom-dev.yumpoo.com/api/v1/electron/auth/wecom/callback
```

`app-secret` 只写入受 ACL 保护的 Secret 文件或等价外部 Secret 注入。启动前执行 `nginx.exe -t`，确认公网只开放 443，18173、8100、5432 均仅监听 `127.0.0.1`。启动 JAR 后依次验证 liveness、readiness 和 Nginx HTTPS；不得绕过证书错误。

## 4. Chrome 扫码验收

1. 用 PC Chrome 打开 `https://wecom-dev.yumpoo.com/login`；页面不得自动跳转或自动弹窗。
2. 点击“使用企业微信扫码登录”，浏览器才进入官方 `qrConnect` 页面。
3. 用企业微信扫码并确认，回调后应建立 Web Cookie/CSRF 会话并返回原访问页。
4. 验证取消、错误企业、非企业成员、禁用/离职成员、state 重放，均只回到登录页显示脱敏状态。
5. 检查响应与日志：不得出现 Secret、Cookie 值、企微 code、state、handoff 或 verifier。

在真实环境完成前，checklist 与报告保持 `ENV_PENDING`，不得用受控身份提供者结果冒充真实扫码 `PASS`。

## 5. Electron PILOT 与 `yumpoo://`

解压桌面 ZIP 到受控 PILOT 目录。首次测试前以管理员或有协议注册权限的账号注册当前可执行文件：

```powershell
$exe = 'C:\Program Files\Yumpoo Desktop Pilot\YumpooDesktop.exe'
reg.exe add 'HKCU\Software\Classes\yumpoo' /ve /d 'URL:Yumpoo Protocol' /f
reg.exe add 'HKCU\Software\Classes\yumpoo' /v 'URL Protocol' /d '' /f
reg.exe add 'HKCU\Software\Classes\yumpoo\shell\open\command' /ve /d ('"' + $exe + '" "%1"') /f
```

仅接受 `yumpoo://auth/callback`。任何其他 scheme、host/path、缺失或错误 state、外部导航、非白名单 origin、HTTP 降级和证书错误都必须失败关闭。

验收顺序：

1. 启动 `YumpooDesktop.exe`，点击 Electron 登录按钮；系统默认浏览器打开官方扫码页。
2. 扫码后 HTTPS callback 签发 60 秒一次性 handoff，再跳回 `yumpoo://auth/callback`。
3. Main 校验 state，以 PKCE verifier 兑换 `ELECTRON` 会话并重载同一 HTTPS SPA。
4. 重放、并发消费、超时、错误 verifier/state 均拒绝；Renderer/DevTools 不可取得原始凭据。
5. 退出、401、账号禁用/离职、授权版本变化、safeStorage 解密失败或 origin 变化后，本地加密材料与两枚 Cookie 必须同时清除。

Electron 使用非持久化 BrowserWindow session；磁盘会话材料只能由 Windows `safeStorage` 加密。桌面版本固定 `0.1.0`、协议版本固定 `1`，本步骤只记录和校验，不实施 M4 的版本阻断策略。

## 6. 故障诊断与会话清理

- 页面提示只能在企业微信打开：实际仍进入了移动端 OAuth 地址，检查服务端授权端点和发布版本。
- `redirect_uri` 错误：核对两个 HTTPS callback、Nginx Host/Forwarded 头和企微 Web网页授权域。
- Electron 未被唤起：检查 HKCU 协议命令中的绝对路径、引号和当前用户注册表。
- callback 后立即失效：检查系统时间、60 秒 handoff、PKCE/state、客户端版本 `0.1.0` 和协议版本 `1`。
- safeStorage 不可用或解密失败：应用必须清除材料并要求重新登录，禁止降级明文保存。

彻底清理时先退出桌面进程，再删除 `%APPDATA%\Yumpoo Desktop\auth\electron-session.bin` 和 `.tmp`；非持久化 Cookie 随应用退出消失。注销协议：

```powershell
reg.exe delete 'HKCU\Software\Classes\yumpoo' /f
```

## 7. 脱敏、回退与证据

不得把 `CorpID` 以外的真实凭据、Secret、Cookie、企微 code、state、handoff、PKCE verifier、session/CSRF credential 写入日志、审计正文、截图文件名、验证报告或 PR。只记录 requestId、脱敏错误类别、时间和产物 SHA-256。

回退服务器时停止当前 Java 进程，将 Nginx release root 指回最近已验收版本，使用对应 JAR/Web/配置并重新执行 Flyway 兼容检查；V15 为向前迁移，不执行破坏性回滚 SQL。回退桌面时关闭 PILOT 包、清理本地材料并注销 `yumpoo://`；不得继续使用旧 handoff。回退后重新执行 liveness/readiness 和历史登录回归。
