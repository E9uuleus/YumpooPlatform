# Agent Note: 桌面安装包与发布渠道

Status: implemented

## Problem

桌面壳原来由自写脚本打成便携目录：复制 Electron 运行时，改 exe 名，用 resedit 换图标，再打 asar。这样的产物有几个问题：

- 没有安装器，不注册开始菜单快捷方式，exe 版本资源仍显示 Electron / GitHub, Inc.；
- 打包版必须由使用者设置 `YUMPOO_WEB_URL` 才能启动；
- 缺配置、页面加载失败、证书错误时一律静默退出，使用者看不到任何原因；
- 没有更新清单可以挂接，而壳加载的是远程内容，Chromium 安全补丁必须能持续送达。

本 Note 记录桌面发布渠道的长期决策，随各期交付逐步补充。DESK-1 交付安装包与默认配置；DESK-2 交付本地诊断与自救，要赶在第一次自动更新之前上线，这样坏版本也能自救；应用内更新、HTTPS 分发和发布流水线在后续各期补入。

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
- 开启 `EnableCookieEncryption`，作为纵深防御。业务窗口使用不带 `persist:` 前缀的内存分区，会话 cookie 不落盘，每次启动时从 safeStorage 加密的凭据存储重新注入；如果以后改用持久分区，cookie 也不会以明文写入磁盘。

**分发配置**：

- 跟踪文件 [`distribution/production.json`](../../../../desktop/desktop-shell/distribution/production.json) 只包含 schemaVersion 和 origin；
- 构建时用运行时同一个[解析器](../../../../desktop/desktop-shell/src/main/distribution.ts)校验后写入 `dist/main/distribution.json`，打进 asar；
- 打包版只读这份配置，完全忽略 `YUMPOO_WEB_URL`；开发版仍只接受回环 HTTP；
- `YUMPOO_DISTRIBUTION` 用来选另一份配置（例如以后的端到端测试分发），默认 `production`。

**系统集成**：

- 打包版调用 `app.setAppUserModelId('com.yumpoo.desktop')`，与安装器写到快捷方式上的 AUMID 一致。开发运行不设置，因为没有带同一 AUMID 的快捷方式；
- electron-builder 在 Windows 上不注册 URL 协议，所以由[安装器钩子](../../../../desktop/desktop-shell/build/installer.nsh)写入 HKCU 的 `yumpoo:` 键；
- 运行时的协议注册失败只记录日志，不再中断启动。

**启动失败与诊断窗**（DESK-2）：

- 以下情况都会打开本地[诊断窗](../../../../desktop/desktop-shell/src/main/diagnostics-window.ts)，不再静默退出：启动时主窗加载失败（证书错误单独标记）、分发配置缺失、启动异常，以及运行中主框架加载失败或页面进程崩溃；
- 诊断窗只加载安装包内 `dist/diagnostics` 下的三个文件，经特权自定义协议 `yumpoo-diagnostics://app/` 提供：页面带严格 CSP（`connect-src 'none'`），协议处理器只注册在诊断窗独立的内存分区上，只认这三个文件名。该分区拒绝全部权限和下载，阻止导航，除这个来源外的请求一律取消；
- 页面通过专用 preload 只能调用两个通道：`yumpoo:diagnostics:report` 与 `yumpoo:diagnostics:action`。主进程只接受该窗口主框架、且 URL 正是打包页面的调用；工作区边界规则让两个 preload 入口的通道互不相通；
- 诊断窗自身打不开时，回退到 DESK-1 的[启动失败对话框](../../../../desktop/desktop-shell/src/main/startup-failure.ts)；冒烟模式仍直接以非零码退出；
- 主窗没有成功加载过时，关闭诊断窗即退出应用，不留下看不见的进程。

**诊断项**：[采集逻辑](../../../../desktop/desktop-shell/src/main/diagnostics.ts)与 Electron 实现分开，可以注入替身测试。

- 网络：DNS、TLS 证书主体/签发者/剩余天数、`/healthz`、`/version.json`（服务版本取 `release.tag`，时钟偏差取响应 `Date` 头与请求中点之差）、系统代理；
- HTTP 探测走 Chromium 网络栈，`credentials: 'omit'`，不跟随重定向；TLS 详情由 Node 获取，信任 Windows 证书库加内置根证书；
- 每项 8 秒超时。报告里只保留规范化的错误码，不带响应正文或主机返回的文本；
- 本机：safeStorage、userData 可写、GPU 特性状态、各进程内存（`app.getAppMetrics`，也是以后内存治理的基线）。

**修复动作**：[修复动作](../../../../desktop/desktop-shell/src/main/diagnostics-actions.ts)包括：

- 重试（保持当前模式重启）、以正常模式重启（清零失败计数）；
- 用浏览器打开 Web 版、打开日志文件夹、重新注册 `yumpoo:` 协议；
- 退出登录并清除本机凭据、重置本机偏好，这两项先弹窗确认；
- 导出诊断包：用户选择保存位置，内容为报告、轮转日志和最近 5 个崩溃转储，用不依赖第三方库的仅存储 ZIP 写出。

重启时不重放 `yumpoo:` 回调、`--self-check` 和 `--diagnostics` 参数。

**日志与崩溃转储**：

- 主进程把 JSON 行日志写入 `userData\logs\desktop.log`，5 MB 轮转，共保留 3 个文件。写日志失败不会影响运行；
- [脱敏](../../../../desktop/desktop-shell/src/main/logger.ts)：URL 只保留协议、主机和路径；名称涉及 cookie、凭据、授权、正文、标题的字段，以及 `code`、`state`、`session`、`text`、`message` 字段，一律丢弃；
- `crashReporter.start({ uploadToServer: false })`：崩溃转储只留在本机，只随用户导出的诊断包离开本机。

**安全模式与崩溃循环**：

- 开始菜单的「Yumpoo Desktop (Safe Mode)」快捷方式以 `--safe-mode` 启动。安全模式下关闭硬件加速，不启动计时窗和收件箱提醒，并先打开诊断窗；
- [启动计数](../../../../desktop/desktop-shell/src/main/startup-state.ts)记录在 `startup-state.json`。每次启动计数加一；主窗加载成功并存活 60 秒，或加载成功后正常退出，计数清零。累计达到 3 次时，下一次启动自动进入安全模式；
- 已在运行时，带 `--safe-mode` 或 `--diagnostics` 的第二个实例只会让正在运行的实例打开诊断窗；
- 托盘原生菜单增加「诊断与修复…」。Web 快捷面板加载不出来时右键弹出的就是这个菜单，正好覆盖最需要诊断的情形。

**卸载边界**：

- 更新（NSIS 以 `--updated` 运行旧卸载器）保留全部数据；
- 真正卸载时删除 `yumpoo:` 协议键，并删除 userData 中除 `timer-preferences.json` 和 `inbox-preferences.json` 以外的全部内容：`auth\` 中的加密凭据存储（唯一落盘的会话）以及 Chromium 的缓存、本地存储等运行数据。

**安装自检与门禁**：

- 打包版支持 `--self-check=<绝对路径>`：不联网，检查是否打包、分发配置、协议注册，并在隐藏窗口中离线渲染诊断页（等到页面状态变为 `ready`）；报告 safeStorage 可用性，把 JSON 报告写到指定文件后退出。报告写文件而不走 stdout，因为 GUI 子系统 exe 的标准输出不可靠；
- [打包校验](../../../../tools/verification/package-m0-15-win.mjs)在 `win-unpacked` 上检查图标、版本资源、fuse 状态、`resources` 白名单和 asar 白名单，生成原有 schema 的 M0-15 manifest，另写一份 `desktop-installer.json`，记录安装包名称、大小和 SHA-256；
- [安装冒烟](../../../../tools/verification/verify-desktop-installer.mjs)在 Windows 门禁中依次执行：静默安装、核对快捷方式（含安全模式快捷方式的目标和参数）和协议键、自检（含诊断页离线渲染）、预置会话与偏好数据、同版本覆盖安装、静默卸载，并断言上面的卸载边界。它会改动当前用户的配置，所以只在 `CI=true` 或 `YUMPOO_DESKTOP_INSTALLER_SMOKE=1` 时运行，并先把已有的 userData 和协议键移到临时备份目录，结束后恢复。备份与运行处于同一个 try 内，中途失败也会恢复已移开的部分；任何一项恢复失败时保留备份目录并让冒烟失败，报错中给出目录位置。NSIS 卸载程序会从 %TEMP% 重新启动自身，所以通过 Windows PowerShell 的 `Start-Process -Wait` 等待完整进程树退出，并校验启动进程退出码；收尾卸载失败或超时时一律不恢复（仍在运行的卸载程序可能删掉刚恢复的数据），保留备份并报出路径，由人确认卸载结束后手动恢复。Windows 门禁覆盖真实分离子孙进程、参数传递及超时行为。

**版本**：桌面版本线由 `desktop/desktop-shell/package.json` 的 `version` 决定，不受仓库版本占位规则约束。安装包文件名为 `YumpooDesktop-Setup-<version>-x64.exe`。

## Alternatives considered

- **继续使用自写打包脚本，再自己补安装器**：要重写快捷方式、卸载登记、覆盖安装和更新安装器握手，这些正是 electron-builder 与 electron-updater 已经配套的部分。
- **按机器安装**：每次安装和更新都要管理员权限并弹 UAC，30 人规模的自动更新会被打断。
- **MSIX / Squirrel.Windows**：MSIX 必须签名才能安装，当前没有 Authenticode 证书；Squirrel 已不再积极维护，electron-updater 也不支持它。
- **用环境变量或首次启动向导配置 origin**：每台机器都要手工配置，且可以被改成任意站点；把配置打进 asar 后受 asar 完整性校验保护。
- **只删除 `auth\` 目录**：已经足以退出登录，但会留下缓存、本地存储和崩溃转储等数据；卸载应当清理到只剩偏好文件。
- **卸载时删除整个 userData**：会丢掉计时器和提醒偏好；重新安装后这些偏好应当保留。
- **任何时候都设置 AUMID**：开发运行没有带同一 AUMID 的快捷方式，Windows 可能不显示 balloon 和 toast。
- **根据 PID 快照追踪卸载进程树**：根 PID 和已观察的子 PID 都可能被无关进程复用；创建时间也无法补回两次快照之间已退出的中间进程。采用 Windows PowerShell [Start-Process -Wait](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.management/start-process?view=powershell-5.1#-wait) 的完整进程树等待，避免自行推断进程身份与亲缘关系。
- **用 `file:` 加载诊断页**：`GrantFileProtocolExtraPrivileges` 关闭后，`file:` 无法读取 app.asar 内的页面（实测 `ERR_FILE_NOT_FOUND`）。为此重新打开这个 fuse 会削弱加固；自定义协议正是 Electron 推荐的替代做法，还能给页面一个确定的来源。
- **把诊断页放在远程 Web 应用里**：最需要诊断的正是 Web 加载不出来的时候；本地页面断网也能使用，也不会把诊断通道暴露给远程内容。
- **诊断入口加进 Web 快捷面板**：需要同时改契约、Web 与壳的能力协商；托盘原生菜单、安全模式快捷方式和自动弹出已经覆盖故障场景，所以暂缓。
- **崩溃报告自动上传**：需要新的接收端和隐私评估；30 人规模下由用户导出诊断包发给管理员即可。
- **引入 ZIP 库导出诊断包**：会让主进程多出第一个运行时依赖；仅存储格式的 ZIP 写出只需几十行代码。

## Consequences

- 安装包仍未签名，首次安装会出现 SmartScreen「未知发布者」提示；开启了 Smart App Control 的机器无法运行。补 Authenticode 签名是独立的后续工作。
- 本期还没有发布渠道和应用内更新：安装包只在 CI 中构建和冒烟，不上传为发布附件。现有便携版用户仍按 RUNBOOK 手工分发，直到桌面发布渠道上线。
- electron-builder 构建时会从 GitHub 下载 NSIS 工具链，校验和由 electron-builder 内置；CI runner 直接下载，本地可以配置镜像。
- `pnpm-workspace.yaml` 用 override 去掉了 electron-builder 内 `@electron/get@3` 的可选依赖 `global-agent`。原因是它经 `roarr` 引入 `sprintf-js`（GHSA-hp3w-g68c-fv3c，没有修复版本）。`@electron/get` 只在设置 `ELECTRON_GET_USE_PROXY` 时才加载它，加载失败会降级为不走代理；打包使用 `electronDist`，本来就不经 `@electron/get` 下载。上游换掉这条依赖后应删除这个 override。
- 开启 cookie 加密是单向的：以后关闭这个 fuse，已有的持久 cookie 库会失效。当前业务会话不依赖持久 cookie，因此不受影响。
- 安装冒烟让 Windows 门禁多出一次安装和卸载，时间要计入 `Windows Delivery` 的超时预算。
- 诊断页只在自检中离线渲染过；安全模式和崩溃循环的真实启动、断网和证书错误下诊断窗的弹出，靠单元测试与实机核验覆盖，Windows 门禁不模拟这些场景。
- 卸载会删除日志、崩溃转储和启动计数，只保留两个偏好文件，与 DESK-1 的卸载边界一致。
