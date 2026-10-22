---
title: 桌面客户端架构
description: 壳加载在线 SPA、壳专用页面、安全加固、preload 桥与类型契约、PKCE 登录和签名更新管线。
order: 80
audience: [developer]
---

读完你会知道：桌面客户端怎样复用 Web 应用、壳专用页面放在哪里、怎样加固，以及登录和更新的流程。

## 壳加载在线 SPA

桌面客户端（Electron）不内置业务页面，主窗口加载的就是公司部署地址上的 Web 应用，所以没有离线业务能力，也没有第二套前端。壳只负责窗口、托盘、通知、本机凭据和更新。

## 壳专用页面

计时浮窗、计时菜单、快捷工时小窗和项目侧边栏也是 Web 应用里的页面，集中在 `/timer` 前缀下：`/timer`、`/timer/menu`、`/timer/quick`、`/timer/rail`，定义在 [surfaces.ts](../../../../../../frontend/web-app/src/components/desktop/surfaces.ts)。`/desktop/` 前缀留给桌面更新源，任何页面都不能使用。

壳通过 [surface-load](../../../../../../desktop/desktop-shell/src/main/surface-load.ts) 加载这些页面：服务端返回 4xx/5xx 时按加载失败处理，而不是显示错误页。

## 安全加固

- `contextIsolation`、`sandbox` 开启，Node 集成关闭，禁止 `<webview>` 与混合内容。
- [导航与下载守卫](../../../../../../desktop/desktop-shell/src/main/security-guards.ts)：只允许导航到本站，新窗口一律拒绝，网页发起的下载一律拒绝（导出经主进程的保存对话框）。
- 网页权限（摄像头、麦克风、通知等）一律拒绝，剪贴板只允许写入。
- Electron fuses 关闭 RunAsNode、NODE_OPTIONS 与调试参数，开启 asar 完整性校验与只从 asar 加载。

## preload 桥与类型契约

网页只能通过 preload 暴露的 `window.yumpooDesktop` 访问壳能力，按命名空间分组（计时、快捷小窗、侧边栏、偏好、更新、收件箱等）。接口类型定义在 [packages/preload-contract](../../../../../../packages/preload-contract/src/index.ts)，Web 只能以类型方式依赖它。新增能力一律做成**可选桥**：Web 检查方法是否存在再调用，这样新版 Web 可以兼容还没更新的旧壳。

## 登录

桌面登录走 PKCE：壳申请一次性登录尝试，在系统浏览器打开企业微信扫码页，扫码后经 `yumpoo:` 协议拿回授权码并换取会话。勾选免登录时，会话凭据用 Electron safeStorage（Windows DPAPI）加密保存在本机。

## 更新管线

- 使用 electron-updater 从公司站点的更新源检查与下载。
- 下载完成后，壳用内置公钥校验 Ed25519 签名的更新授权，逐项核对渠道、版本（必须更高）、文件名、大小与 SHA-512；任何一项不符都删除文件，**永不降级**。
- 正常退出时静默安装已校验的更新。
