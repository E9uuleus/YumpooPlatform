# Agent Note: 桌面安装包与发布渠道

Status: implemented

## Problem

桌面壳原来由自写脚本打成便携目录：复制 Electron 运行时，改 exe 名，用 resedit 换图标，再打 asar。这样的产物有几个问题：

- 没有安装器，不注册开始菜单快捷方式，exe 版本资源仍显示 Electron / GitHub, Inc.；
- 打包版必须由使用者设置 `YUMPOO_WEB_URL` 才能启动；
- 缺配置、页面加载失败、证书错误时一律静默退出，使用者看不到任何原因；
- 没有更新清单可以挂接，而壳加载的是远程内容，Chromium 安全补丁必须能持续送达。

本 Note 记录桌面发布渠道的长期决策，随各期交付逐步补充。本期（DESK-1）交付安装包与默认配置；应用内更新、HTTPS 分发和发布流水线在后续各期补入。

## Decision

**打包工具**：[electron-builder](../../../../desktop/desktop-shell/electron-builder.config.cjs) 生成 NSIS 安装包，取代 `package-m0-15.cjs`。

- `appId` 为 `com.yumpoo.desktop`，同时作为 AppUserModelID 和 NSIS 安装身份，以后不得修改，否则已有安装会成为孤儿；
- `productName` 保持 `Yumpoo Desktop`，所以 userData 仍是 `%APPDATA%\Yumpoo Desktop`，便携版的加密会话和偏好可以直接沿用；exe 名保持 `YumpooDesktop.exe`；
- `electronDist` 指向锁文件安装的 Electron 运行时，不再下载第二份，打进包里的正是测试过的那份二进制；`afterPack` 删除不会被加载的 `default_app.asar`；
- 安装器选按用户 oneClick：不需要管理员权限，以后更新也不弹 UAC，因此不打包 `elevate.exe`；
- `package.json` 的 `author` 写入 CompanyName，exe 版本资源不再出现 Electron 默认值；
- 图标由 electron-builder 写入。resedit 只保留为[只读校验](../../../../tools/verification/desktop-executable.mjs)：比对图标、读取版本资源。

**Electron fuses**：

- 关闭：`RunAsNode`、`EnableNodeOptionsEnvironmentVariable`、`EnableNodeCliInspectArguments`、`GrantFileProtocolExtraPrivileges`；
- 开启：`EnableEmbeddedAsarIntegrityValidation`、`OnlyLoadAppFromAsar`，篡改过的 asar 无法启动；
- 开启 `EnableCookieEncryption`。会话 cookie 带过期时间，会落盘到 Chromium 的 cookie 库；开启后与 safeStorage 加密的凭据存储处于同一保护级别。

**分发配置**：

- 跟踪文件 [`distribution/production.json`](../../../../desktop/desktop-shell/distribution/production.json) 只包含 schemaVersion 和 origin；
- 构建时用运行时同一个[解析器](../../../../desktop/desktop-shell/src/main/distribution.ts)校验后写入 `dist/main/distribution.json`，打进 asar；
- 打包版只读这份配置，完全忽略 `YUMPOO_WEB_URL`；开发版仍只接受回环 HTTP；
- `YUMPOO_DISTRIBUTION` 用来选另一份配置（例如以后的端到端测试分发），默认 `production`。

**系统集成**：

- 打包版调用 `app.setAppUserModelId('com.yumpoo.desktop')`，与安装器写到快捷方式上的 AUMID 一致。开发运行不设置，因为没有带同一 AUMID 的快捷方式；
- electron-builder 在 Windows 上不注册 URL 协议，所以由[安装器钩子](../../../../desktop/desktop-shell/build/installer.nsh)写入 HKCU 的 `yumpoo:` 键；
- 运行时的协议注册失败只记录日志，不再中断启动。

**启动失败**：

- 启动时主窗加载失败（包括证书错误导致的失败）或分发配置缺失时，不再静默退出，而是弹出[对话框](../../../../desktop/desktop-shell/src/main/startup-failure.ts)，提供「用浏览器打开 Web 版」「重试」「退出」；
- 冒烟模式仍直接以非零码退出。

**卸载边界**：

- 更新（NSIS 以 `--updated` 运行旧卸载器）保留全部数据；
- 真正卸载时删除 `yumpoo:` 协议键，并删除 userData 中除 `timer-preferences.json` 和 `inbox-preferences.json` 以外的全部内容。只删 `auth\` 不够，因为 Chromium cookie 库同样保存着会话。

**安装自检与门禁**：

- 打包版支持 `--self-check=<绝对路径>`：不建窗口、不联网，检查是否打包、分发配置、协议注册，并报告 safeStorage 可用性，把 JSON 报告写到指定文件后退出。报告写文件而不走 stdout，因为 GUI 子系统 exe 的标准输出不可靠；
- [打包校验](../../../../tools/verification/package-m0-15-win.mjs)在 `win-unpacked` 上检查图标、版本资源、fuse 状态、`resources` 白名单和 asar 白名单，生成原有 schema 的 M0-15 manifest，另写一份 `desktop-installer.json`，记录安装包名称、大小和 SHA-256；
- [安装冒烟](../../../../tools/verification/verify-desktop-installer.mjs)在 Windows 门禁中依次执行：静默安装、核对快捷方式和协议键、自检、预置会话与偏好数据、同版本覆盖安装、静默卸载，并断言上面的卸载边界。它会改动当前用户的配置，所以只在 `CI=true` 或 `YUMPOO_DESKTOP_INSTALLER_SMOKE=1` 时运行，并先把已有的 userData 和协议键移开，结束后恢复。

**版本**：桌面版本线由 `desktop/desktop-shell/package.json` 的 `version` 决定，不受仓库版本占位规则约束。安装包文件名为 `YumpooDesktop-Setup-<version>-x64.exe`。

## Alternatives considered

- **继续使用自写打包脚本，再自己补安装器**：要重写快捷方式、卸载登记、覆盖安装和更新安装器握手，这些正是 electron-builder 与 electron-updater 已经配套的部分。
- **按机器安装**：每次安装和更新都要管理员权限并弹 UAC，30 人规模的自动更新会被打断。
- **MSIX / Squirrel.Windows**：MSIX 必须签名才能安装，当前没有 Authenticode 证书；Squirrel 已不再积极维护，electron-updater 也不支持它。
- **用环境变量或首次启动向导配置 origin**：每台机器都要手工配置，且可以被改成任意站点；把配置打进 asar 后受 asar 完整性校验保护。
- **只删除 `auth\` 目录**：Chromium cookie 库里仍留着有效会话，卸载达不到「退出登录」的效果。
- **卸载时删除整个 userData**：会丢掉计时器和提醒偏好；重新安装后这些偏好应当保留。
- **任何时候都设置 AUMID**：开发运行没有带同一 AUMID 的快捷方式，Windows 可能不显示 balloon 和 toast。

## Consequences

- 安装包仍未签名，首次安装会出现 SmartScreen「未知发布者」提示；开启了 Smart App Control 的机器无法运行。补 Authenticode 签名是独立的后续工作。
- 本期还没有发布渠道和应用内更新：安装包只在 CI 中构建和冒烟，不上传为发布附件。现有便携版用户仍按 RUNBOOK 手工分发，直到桌面发布渠道上线。
- electron-builder 构建时会从 GitHub 下载 NSIS 工具链，校验和由 electron-builder 内置；CI runner 直接下载，本地可以配置镜像。
- 开启 cookie 加密是单向的：以后关闭这个 fuse 会让已有 cookie 库失效，用户需要重新登录。
- 安装冒烟让 Windows 门禁多出一次安装和卸载，时间要计入 `Windows Delivery` 的超时预算。
