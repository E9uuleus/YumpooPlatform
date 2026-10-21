# Agent Note: 桌面安装包与发布渠道

Status: implemented

## Problem

桌面壳原来由自写脚本打成便携目录：复制 Electron 运行时，改 exe 名，用 resedit 换图标，再打 asar。这样的产物有几个问题：

- 没有安装器，不注册开始菜单快捷方式，exe 版本资源仍显示 Electron / GitHub, Inc.；
- 打包版必须由使用者设置 `YUMPOO_WEB_URL` 才能启动；
- 缺配置、页面加载失败、证书错误时一律静默退出，使用者看不到任何原因；
- 没有更新清单可以挂接，而壳加载的是远程内容，Chromium 安全补丁必须能持续送达。

本 Note 记录桌面发布渠道的长期决策，随各期交付逐步补充。DESK-1 交付安装包与默认配置；DESK-2 交付本地诊断与自救，要赶在第一次自动更新之前上线，这样坏版本也能自救；DESK-3 交付应用内更新与 Ed25519 更新授权；DESK-4 交付独立发布渠道、HTTPS 更新源和下载页。

## Decision

**打包工具**：[electron-builder](../../../../desktop/desktop-shell/electron-builder.config.cjs) 生成 NSIS 安装包，取代 `package-m0-15.cjs`。

- `appId` 为 `com.yumpoo.desktop`，同时作为 AppUserModelID 和 NSIS 安装身份，以后不得修改，否则已有安装会成为孤儿；
- `productName` 保持 `Yumpoo Desktop`，所以 userData 仍是 `%APPDATA%\Yumpoo Desktop`，便携版的加密会话和偏好可以直接沿用；exe 名保持 `YumpooDesktop.exe`；
- `electronDist` 指向锁文件安装的 Electron 运行时，不再下载第二份，打进包里的正是测试过的那份二进制；`afterPack` 删除不会被加载的 `default_app.asar`；
- 安装器是强制按用户的辅助安装器：`oneClick: false`、`perMachine: false`，[安装器钩子](../../../../desktop/desktop-shell/build/installer.nsh)的 `customInstallMode` 隐藏「为所有用户」页。不需要管理员权限，以后更新也不弹 UAC，因此不打包 `elevate.exe`。DESK-1 至 DESK-4 用的是 oneClick；首个发布草稿试装时发现运行 exe 即直接安装，员工看不到安装位置、须知和隐私说明，不适合作为公司内部软件分发，所以在首次 Publish 前改掉；
- 页面依次为：必须点「我同意」的安装须知与隐私说明（`build/install-notice_zh_CN.txt`，是[安装机制与隐私说明](../../../../deployment/windows/desktop-client-install-and-privacy.md)的精简版）、安装目录、附加选项、完成。附加选项页先检查当前用户能否写入所选目录，不能写入时（如 Program Files）禁用「下一步」，让用户返回目录页，而不是申请提权；
- 辅助安装器的默认目录由 electron-builder 取自 `executableName`，为 `%LOCALAPPDATA%\Programs\YumpooDesktop`；oneClick 时取包名，是 `yumpoo-desktop`。所选路径不含 `YumpooDesktop` 时自动补上这一级子目录。不强行沿用旧名：electron-builder 的安装进度页会给任何不含 `YumpooDesktop` 的路径追加该子目录，改名需要替换模板。`desktop-v1.0.0` 草稿的试装机器经 `InstallLocation` 留在原目录；更新缓存目录仍由包名决定，为 `yumpoo-desktop-updater`；
- 附加选项页的「创建桌面快捷方式」默认勾选。取消时由 `customInstall` 删除 electron-builder 刚建的快捷方式；以后的更新带 `--updated`，electron-builder 不会重建用户删掉的快捷方式。静默安装不经过页面，照常创建。开始菜单快捷方式承载 AUMID，始终创建；
- 安装器只提供中文（`installerLanguages: ['zh_CN']`）。钩子和须知文件保存为带 BOM 的 UTF-8，否则 makensis 会按构建机的 ANSI 代码页解码中文；
- `package.json` 的 `author` 写入 CompanyName，exe 版本资源不再出现 Electron 默认值；
- 图标由 electron-builder 写入。resedit 只保留为[只读校验](../../../../tools/verification/desktop-executable.mjs)：比对图标、读取版本资源。

**Electron fuses**：

- 关闭：`RunAsNode`、`EnableNodeOptionsEnvironmentVariable`、`EnableNodeCliInspectArguments`、`GrantFileProtocolExtraPrivileges`；
- 开启：`EnableEmbeddedAsarIntegrityValidation`、`OnlyLoadAppFromAsar`，篡改过的 asar 无法启动；
- 开启 `EnableCookieEncryption`，作为纵深防御。业务窗口使用持久分区 `persist:yumpoo-authenticated`，主进程以 `cache: false` 建立，不在磁盘留业务响应；登录 cookie 不带过期时间，不写入磁盘，每次启动从 safeStorage 加密的凭据存储重新注入，不勾选「7 天内免登录」时不保存凭据，见[会话保持 Note](../security/2026-10-19-session-remember-and-desktop-guards.md)。

**分发配置**：

- 跟踪文件 [`distribution/production.json`](../../../../desktop/desktop-shell/distribution/production.json) 包含 schemaVersion、origin，以及可选的 `update`（更新源 `feed`、渠道 `channel`、1–4 个 Ed25519 公钥）；
- 构建时用运行时同一个[解析器](../../../../desktop/desktop-shell/src/main/distribution.ts)校验后写入 `dist/main/distribution.json`，打进 asar；打包校验要求包内配置与跟踪文件逐字节一致；
- 打包版只读这份配置，完全忽略 `YUMPOO_WEB_URL`；开发版仍只接受回环 HTTP；
- 更新源必须是以 `/` 结尾的 HTTPS 地址，只有回环地址可以用 HTTP（端到端测试）；公钥的 `keyId` 必须是 `ed25519-` 加公钥 SHA-256 的前 16 位十六进制，配置与签名两端不会对同一把钥匙有不同叫法；
- `YUMPOO_DISTRIBUTION` 选择 `distribution/` 下的另一份跟踪配置，默认 `production`；`YUMPOO_DISTRIBUTION_FILE` 只供端到端测试指向临时生成的配置，`YUMPOO_DESKTOP_TEST_VERSION` 也只能与它一起使用。

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

**启动遮罩与首屏**（1.0 试装修正）：

- 主窗口创建后立即显示（冒烟和自检除外），不再等 `ready-to-show`。此前托盘在主窗加载前就已出现，点击托盘或再次启动会显示一个尚未绘制的空白窗口，远程页面要到首次绘制（慢网络下 10 秒以上）才会自动弹出；
- 窗口上叠一层 `WebContentsView` [启动遮罩](../../../../desktop/desktop-shell/src/main/launch-overlay.ts)，显示安装包内的 `launch.html`：品牌图标、进度条、「正在连接 YumpooPlatform…」，8 秒后用纯 CSS 显示「连接较慢」与诊断入口提示。页面没有 preload 和脚本，CSP 只允许同源样式和图片；
- 遮罩与诊断窗共用同一个本地页面分区和 `yumpoo-diagnostics://app/` 协议（会话准备只做一次），资源白名单增加 `launch.html`、`launch.css` 和品牌导出的 `mark.png`；诊断 IPC 仍只认诊断页 URL，遮罩无法调用；
- 主框架首次 `did-finish-load` 时撤下遮罩并关闭它的 webContents；启动失败时随隐藏主窗一起撤下，交给诊断窗；
- Web 的 `index.html` 有一个静态[启动页](../../../../frontend/web-app/public/boot-splash.css)，外观与遮罩一致，从首个 HTML 字节一直显示到首个路由完成（首屏要等会话检查），浏览器用户也不再看到空白。透明的计时窗共用这个页面，由 `appearance-bootstrap.js` 在绘制前隐藏启动页；
- `main-loaded` 日志带 `elapsedMs`，慢启动可直接从日志判断。

**诊断项**：[采集逻辑](../../../../desktop/desktop-shell/src/main/diagnostics.ts)与 Electron 实现分开，可以注入替身测试。

- 网络：DNS、TLS 证书主体/签发者/剩余天数、`/healthz`、`/version.json`（服务版本取 `release.tag`，时钟偏差取响应 `Date` 头与请求中点之差）、系统代理；
- HTTP 探测走 Chromium 网络栈，`credentials: 'omit'`，不跟随重定向；TLS 详情由 Node 获取，按常规校验 Windows 证书库加内置根证书；证书不受信任或已过期时握手失败，只记录错误码（如 `CERT_HAS_EXPIRED`），不为读取证书而关闭校验；
- 每项 8 秒超时。报告里只保留规范化的错误码，不带响应正文或主机返回的文本；
- 本机：safeStorage、userData 可写、GPU 特性状态、各进程内存（`app.getAppMetrics`，也是以后内存治理的基线）。

**修复动作**：[修复动作](../../../../desktop/desktop-shell/src/main/diagnostics-actions.ts)包括：

- 重试（保持当前模式重启）、以正常模式重启（清零失败计数）；
- 用浏览器打开 Web 版、打开日志文件夹、重新注册 `yumpoo:` 协议；
- 退出登录并清除本机凭据、重置本机偏好（重置同时清空业务分区的 localStorage），这两项先弹窗确认；
- 导出诊断包：用户选择保存位置，内容为报告、轮转日志和最近 5 个崩溃转储，用不依赖第三方库的仅存储 ZIP 写出。

重启时不重放 `yumpoo:` 回调、`--self-check` 和 `--diagnostics` 参数。

**日志与崩溃转储**：

- 主进程把 JSON 行日志写入 `userData\logs\desktop.log`，5 MB 轮转，共保留 3 个文件。写日志失败不会影响运行；
- [脱敏](../../../../desktop/desktop-shell/src/main/logger.ts)：URL 只保留协议、主机和路径；名称涉及 cookie、凭据、授权、正文、标题的字段，以及 `code`、`state`、`session`、`text`、`message` 字段，一律丢弃；
- `crashReporter.start({ uploadToServer: false })`：崩溃转储只留在本机，只随用户导出的诊断包离开本机。

**安全模式与崩溃循环**：

- 开始菜单的「Yumpoo Desktop (Safe Mode)」快捷方式以 `--safe-mode` 启动。安全模式下关闭硬件加速，不启动计时窗和收件箱提醒，并先打开诊断窗；
- [启动计数](../../../../desktop/desktop-shell/src/main/startup-state.ts)记录在 `startup-state.json`。每次启动计数加一；主窗加载成功并存活 60 秒，或加载成功后正常退出，计数清零。渲染进程崩溃会取消这次的健康计时，之后计时和正常退出都不再清零，所以「加载后崩溃、停在诊断页」的启动照样计入；已经健康的启动再崩溃，计数从 1 重新开始。累计达到 3 次时，下一次启动自动进入安全模式；
- 已在运行时，带 `--safe-mode` 或 `--diagnostics` 的第二个实例只会让正在运行的实例打开诊断窗；
- 托盘原生菜单增加「诊断与修复…」。Web 快捷面板加载不出来时右键弹出的就是这个菜单，正好覆盖最需要诊断的情形。

**主进程打包**（DESK-3）：

- 主进程由 [esbuild](../../../../desktop/desktop-shell/scripts/build.cjs) 打成单个 `dist/main/index.js`：electron-updater 及其依赖树被内联，asar 中没有 `node_modules`，白名单仍然精确；
- 因此 electron-updater 是构建期输入，列在 devDependencies，electron-builder 不会再收集依赖；
- 内联包的许可证在构建时汇总为 `dist/main/THIRD_PARTY_LICENSES.txt`。没有许可证文件、也不是常见宽松许可证的包会让构建失败；
- tsc 只做类型检查。

**更新授权签名**（DESK-3）：

- 发布流水线对每个安装包签一份声明：`{schema, channel, version, file, sha512, size, tag, commit}`。规范化方式是固定字段顺序、不含空白的 JSON；
- 签名产物 `<installer>.sig.json = {keyId, statement, signature}` 与安装包一起放在更新源。签名工具 [desktop-update-signature.mjs](../../../../tools/release/desktop-update-signature.mjs) 只用 Node 内置模块（发布工作流不安装依赖），提供 `keygen`（私钥写入文件，只打印公钥）和 `sign`；
- [桌面端校验](../../../../desktop/desktop-shell/src/main/update-signature.ts)依次检查：keyId 属于内置公钥、签名有效、渠道一致、版本与下载一致且严格高于当前版本、文件名一致、大小一致、SHA-512 一致。任何一项不符都拒绝；
- 两端共用一份金样（`desktop/desktop-shell/test/fixtures/signed-update.json`），由工具用确定性测试钥匙签出。Ed25519 签名是确定的，工具测试会重签并要求逐字节一致，避免两端的规范化悄悄分叉。

**应用内更新**（DESK-3）：

- [UpdateController](../../../../desktop/desktop-shell/src/main/update-controller.ts) 驱动 electron-updater 的 NSIS 更新：`autoDownload` 取偏好「自动下载更新」（默认 true；关闭时进入 `available`，经 `yumpoo:update:download` 手动下载）、`autoInstallOnAppQuit=false`、`allowDowngrade=false`、关闭 web installer；
- 检查时机：启动后 30 秒首次检查，之后每 4 小时一次，加最多 30 分钟抖动；系统唤醒时，若距上次检查超过 15 分钟，再检查一次；
- `update-downloaded` 之后从更新源取 `.sig.json`（30 秒超时，超时即取消请求，按取不到授权处理并回到可重试状态，避免一直卡在 `verifying` 拦住之后的检查），按上面的规则核验。通过才进入 `ready`；不通过就删除已下载的文件并记录原因码，永不安装。同时删除 electron-updater 写入缓存根目录的 `current.blockmap`：它描述的是刚下载、却没有安装的版本，留着会让之后的每次差分下载都校验失败；
- electron-updater 的代码签名钩子在没有 `publisherName` 时会被跳过（已读源码确认），所以本期不依赖它；
- 安装时机：任何正常退出（含托盘退出）都会静默安装已就绪的更新；「重启以更新」走同一次退出，包括计时确认握手，安装后自动重新启动；这个「重启」意图只属于这一次退出，用户在确认握手中取消即清除，之后的普通退出只安装不重启。诊断窗的「重试」「以正常模式重启」和启动失败对话框的「重试」走 `app.exit`，不会触发 `will-quit`，所以在那里直接安装已就绪的更新并交给安装器启动新版本（原启动参数如安全模式不保留）；没有就绪更新时照常带参数重启。安装前同步重算一次 SHA-512，校验之后被替换的文件不会被执行；
- 只有打包版、且分发配置里有 `update` 时才检查更新。安全模式照常更新，让坏版本也能被新版本替换；
- 带 `update` 的构建声明 generic `publish`，electron-builder 由此生成 `latest.yml` 与 `resources/app-update.yml`（更新缓存目录、差分下载基准）；打包始终带 `--publish never`，不会上传任何东西。

**更新入口与版本头**（DESK-3）：

- `DesktopBridge.update` 提供 `getState / check / restart / onState`，只接受主窗口主框架的调用。Web 只在桥接存在且状态为 `ready` 时显示「重启以更新」横幅；
- 托盘提示和托盘原生菜单在更新就绪时显示版本与「重启以更新到 X」；诊断窗显示更新状态；
- 托盘右键菜单显示版本与更新状态，可以检查、下载和重启更新；检查与下载时保持菜单打开，旧壳缺少更新状态时隐藏该入口。
- 发往本 origin 的请求由 `onBeforeSendHeaders` 加上 `X-Client-Type: ELECTRON`、`X-Client-Version`、`X-Client-Protocol-Version: 1`，为服务端最低版本闸门做准备。其他来源的请求不会带这些头。

**卸载边界**：

- 更新（NSIS 以 `--updated` 运行旧卸载器）保留全部数据；
- 真正卸载先删除安全模式快捷方式；遇到文件被读者占用等删除错误时，每 250 毫秒重试一次、最多 40 次。持续失败则以错误中止，保留安装文件、登记项、协议与用户数据，让用户释放占用后重试；不把残留留给重启，也不让验证脚本代为删除。安装冒烟用真实的 Windows 文件共享锁覆盖短暂占用后成功、持续占用后保留安装以及释放后重试成功。
- 真正卸载时删除 `yumpoo:` 协议键、`HKCU\Software\Microsoft\Windows\CurrentVersion\Run` 与 `StartupApproved\Run` 的 `com.yumpoo.desktop` 值，并删除 userData 中除 `timer-preferences.json`、`inbox-preferences.json` 和 `desktop-preferences.json` 以外的全部内容：`auth\` 中的加密凭据存储（唯一落盘的会话）以及 Chromium 的缓存、本地存储等运行数据；同时删除 `%LOCALAPPDATA%\yumpoo-desktop-updater`。安装器会把自身复制到这里作为差分更新的基准（约 100 MB），electron-updater 的下载缓存也放在这里。

**安装自检与门禁**：

- 打包版支持 `--self-check=<绝对路径>`：不联网，检查是否打包、分发配置、协议注册，并在隐藏窗口中离线渲染诊断页（等到页面状态变为 `ready`）；报告 safeStorage 可用性，把 JSON 报告写到指定文件后退出。报告写文件而不走 stdout，因为 GUI 子系统 exe 的标准输出不可靠；
- [打包校验](../../../../tools/verification/package-m0-15-win.mjs)在 `win-unpacked` 上检查图标、版本资源、fuse 状态、`resources` 白名单和 asar 白名单，生成原有 schema 的 M0-15 manifest，另写一份 `desktop-installer.json`，记录安装包名称、大小和 SHA-256；
- [安装冒烟](../../../../tools/verification/verify-desktop-installer.mjs)在 Windows 门禁中依次执行：静默安装、核对快捷方式（含安全模式快捷方式的目标和参数）和协议键、自检（含诊断页离线渲染）、预置会话与偏好数据、同版本覆盖安装、静默卸载，并断言上面的卸载边界；再以 `/D=` 指定的自定义目录重复安装，核对 exe 位置、`InstallLocation` 与协议键命令路径，要求不带 `/D=` 的同版本覆盖安装沿用该目录、卸载后目录被删除。每次安装都要求 `QuietUninstallString` 带 `/S`。它会改动当前用户的配置，所以只在 `CI=true` 或 `YUMPOO_DESKTOP_INSTALLER_SMOKE=1` 时运行，并先把已有的 userData、更新缓存目录和协议键移到临时备份目录（更新端到端验证同样如此），结束后恢复。备份与运行处于同一个 try 内，中途失败也会恢复已移开的部分；任何一项恢复失败时保留备份目录并让冒烟失败，报错中给出目录位置。NSIS 卸载程序会从 %TEMP% 重新启动自身，所以由 Windows PowerShell 临时编译[进程树助手](../../../../tools/verification/windows-process-tree.cs)：挂起创建根进程、加入 Job Object 后才恢复执行，直到内核报告该 Job 没有活动进程，再返回根进程退出码。Job 禁止子进程脱离，且关闭最后一个句柄会终止全部成员，因此外层超时结束助手时也会结束分离的子孙进程。收尾卸载失败或超时时仍不恢复配置，保留备份并报出路径，由人确认卸载结束后手动恢复。Windows 门禁覆盖快速原生启动器、真实分离子孙进程、参数传递、退出码及超时终止行为。

**更新端到端验证**（DESK-3）：

- [verify-desktop-update.mjs](../../../../tools/verification/verify-desktop-update.mjs) 在 Windows 门禁中为本次运行生成一次性钥匙，构建测试分发的 N 和 N+1 两个版本，并在本地 feed（支持多段 Range）上发布 N+1；
- 安装 N 后，先用未知钥匙签的授权检查一次：应拒绝且不安装；再换成受信钥匙签的授权：应完成校验、静默安装并升级到 N+1。两次下载都必须是差分下载，第二次同时证明拒绝不会留下错误的 blockmap；更新源也因此需要保留旧版本的 `.blockmap`；
- 测试版本只经 `--update-check-now=<报告路径>` 这一无窗口的单次检查模式驱动，走的是与正常启动相同的下载、核验和退出安装路径；
- 测试产物不进入发布附件，结束后用生产分发重新构建。

**版本**：桌面版本线由 `desktop/desktop-shell/package.json` 的 `version` 决定，不受仓库版本占位规则约束。安装包文件名为 `YumpooDesktop-Setup-<version>-x64.exe`。

**发布渠道**（DESK-4）：

- 桌面端有独立的标签线 `desktop-vX.Y.Z`、草稿和 Publish 决定，与服务器发布互不牵连；两者共用 `main` 推送 CI、构建证明和部署引擎，但不共用标签、附件、并发组和部署状态。操作见 RUNBOOK 第 11 节；
- 产物只构建一次：Windows Delivery 把经过安装冒烟的那份安装包，连同 blockmap、`latest.yml`、[桌面 SBOM](../../../../tools/verification/collect-desktop-release.mjs) 与 manifest 收集到 `out/desktop`，签署构建证明后上传为 `desktop-<sha>-<attempt>`。SBOM 由构建写出的内联包清单（`dist/bundled-packages.json`，不进 asar）加上 Electron 运行时生成：工作区把它们都列为 devDependencies，`pnpm sbom --prod` 会漏掉真正随安装包分发的内容；
- [Prepare Desktop Release](../../../../tools/release/prepare-desktop-release.mjs) 只在桌面版本递增时生成草稿。`0.x` 是退役的便携版线，不进入渠道；已发布、不高于最新已发布版本、或已有指向其他提交的同名草稿时都不生成。草稿永不自动删除或改指向，正在试装的草稿字节不会被替换；
- Prepare Desktop Release 只从受保护的 `dev` ref 运行，草稿和签名任务都检出 `github.workflow_sha`，不执行手动输入或 CI 事件提供的候选提交中的脚本。候选提交须为 `main` 祖先，版本与公钥只通过 GitHub API 按完整 SHA 读取为 JSON；
- 草稿任务不接触私钥，只上传核验过构建证明的 CI 文件、来源清单和 `release.json`。签名是独立任务，只有它进入 `desktop-signing` Environment，且只在草稿任务确有待签草稿时运行；签名前重新核对来源清单钉住的成功 `main` CI attempt 和本次下载的安装包构建证明，防止两个任务之间草稿安装包与来源清单一起被替换。钥匙必须属于目标提交 `production.json` 的公钥，签名后按客户端规则复核；
- 生产分发配置写入 `update`：更新源 `https://wecom-dev.yumpoo.com/desktop/stable/`、渠道 `stable`、主钥匙与离线备用钥匙的公钥；
- [Desktop Release](../../../../tools/release/desktop-release.mjs) 在发布后只读复核不可变 Release、钉住的 CI attempt、构建证明和授权签名（公钥取自被发布的提交），然后以 `desktop-publish` 请求分块上传五个更新源文件。

**更新源与部署引擎**（DESK-4）：

- 引擎核对每个上传文件与同名已发布不可变 Release 附件的 digest 和大小，再独立检查标签提交、`main` 祖先关系和 CI 推送运行。引擎在 Windows PowerShell 5.1 上运行，没有 Ed25519，所以不复核签名：授权由客户端在安装前核对，引擎保证更新源上的字节就是 Release 附件；
- 写入顺序固定：先写带版本号的安装包、blockmap、签名（已有同名文件内容相同则保留，不同则拒绝），最后分别原子替换 `latest.yml` 与 `release.json`。两个替换不是事务，中断时指针可以暂时不同，但每个指针引用的版本文件都已就位；
- 版本不能低于任一已公开指针的版本，第一次发布尚无 `release.json` 时也受 `latest.yml` 约束。重试同一已核验版本会补齐缺失文件和未完成的指针，只有全部内容一致才返回 `already_current`；
- 保留最近 3 个版本的安装包与签名、20 个版本的 blockmap，旧版本客户端靠自己版本的 blockmap 做差分下载；
- 桌面请求与服务器部署共用部署锁，不进入维护、不写 `active.json` 和 `current.json`；账本条目没有 `releaseRoot`，服务器保留策略与回退看不到它。服务器处于 `running` 或 `recovery_required` 时桌面请求同样被拒绝，宁可延后桌面发布也不在待恢复的主机上写文件；
- `host.json` 的 `desktopFeed` 默认关闭，由管理员用 `Update-YumpooEngine.ps1 -EnableDesktopFeed` 启用；Nginx 用 `root` 把 `/desktop/` 映射到 `desktopFeed.root`，`latest.yml`、`release.json` 不缓存，带版本号的文件长期缓存；
- 每周发布演练要求更新源上的 `latest.yml`、`release.json` 与最新已发布桌面 Release 的附件逐字节一致。

**下载页**（DESK-4）：Web 新增匿名路由 `/download`，读取更新源的 `release.json`，展示版本、大小、SHA-256、改动摘要和 SmartScreen 首装指引；改动摘要取范围为「桌面」的 [PR 发布说明](2026-10-20-release-notes-from-pull-requests.md)，不含运维条目；路由守卫和会话跳转都不处理它。浏览器账户菜单和升级页链接到这里。桌面壳仍拒绝附件以外的下载，所以在壳内只提示改用浏览器打开。

## Alternatives considered

- **继续使用自写打包脚本，再自己补安装器**：要重写快捷方式、卸载登记、覆盖安装和更新安装器握手，这些正是 electron-builder 与 electron-updater 已经配套的部分。
- **按机器安装**：每次安装和更新都要管理员权限并弹 UAC，30 人规模的自动更新会被打断。辅助安装器默认会提供「为所有用户」选项，所以用 `customInstallMode` 把它隐藏。
- **继续用 oneClick 安装器**：零点击、更新天然静默，但员工看不到安装位置、须知与隐私说明，也不能选目录和桌面快捷方式。强制按用户的辅助安装器同样不需要管理员权限，更新同样静默，代价只是首次安装多点几次「下一步」。
- **用 MUI 目录页的 leave 回调检查写入权限**：electron-builder 自己的目录页不开放 leave 钩子，且它要到安装进度页的 pre 回调才追加 `yumpoo-desktop` 子目录；在目录页之后的附加选项页里先补上子目录再检查，测的就是实际安装位置。
- **安装器提供开机自启选项**：安装器本身仍不提供；开机自启改为应用内默认关闭的「登录时启动」，理由与取舍见[桌面快捷工时与偏好设置](../product/2026-10-19-desktop-quick-time-and-preferences.md)。
- **MSIX / Squirrel.Windows**：MSIX 必须签名才能安装，当前没有 Authenticode 证书；Squirrel 已不再积极维护，electron-updater 也不支持它。
- **用环境变量或首次启动向导配置 origin**：每台机器都要手工配置，且可以被改成任意站点；把配置打进 asar 后受 asar 完整性校验保护。
- **只删除 `auth\` 目录**：已经足以退出登录，但会留下缓存、本地存储和崩溃转储等数据；卸载应当清理到只剩偏好文件。
- **卸载时删除整个 userData**：会丢掉计时器和提醒偏好；重新安装后这些偏好应当保留。
- **任何时候都设置 AUMID**：开发运行没有带同一 AUMID 的快捷方式，Windows 可能不显示 balloon 和 toast。
- **根据 PID 快照追踪卸载进程树**：根 PID 和已观察的子 PID 都可能被无关进程复用；创建时间也无法补回两次快照之间已退出的中间进程。采用 Windows [Job Object](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects) 的内核成员计数，避免自行推断进程身份与亲缘关系。
- **直接使用 Windows PowerShell 5.1 的 `Start-Process -Wait`**：它先启动根进程，再检查 `HasExited` 并登记 Job，快速启动器可能已退出或创建了不在 Job 中的后代；PR #146 的 Windows 门禁出现等待提前结束。挂起创建、先登记再恢复关闭了这个竞态窗口；仅增加测试延时或轮询卸载目录不能证明用户配置已可安全恢复。
- **只在托盘点击时才显示加载页**：首次启动仍是「只有托盘图标」，用户容易以为没有打开而反复双击；启动即显示带遮罩的主窗口更符合桌面应用习惯。
- **先在主窗口加载本地启动页、再导航到 Web**：跨源导航没有 paint holding，提交新页面到首次绘制之间仍会闪白；主窗口的 preload 和会话也会暴露给本地页面。独立的 `WebContentsView` 叠层与主窗口内容互不影响。
- **启动页用 vBrandLoading 的分片动画**：需要内联约 150 KB 的品牌 SVG 及其纹理，Web 的 CSP 还会拦截 data: 纹理；首屏只用导出的 PNG 加轻微呼吸动效，进入应用后的加载仍由 vBrandLoading 负责。
- **用 `file:` 加载诊断页**：`GrantFileProtocolExtraPrivileges` 关闭后，`file:` 无法读取 app.asar 内的页面（实测 `ERR_FILE_NOT_FOUND`）。为此重新打开这个 fuse 会削弱加固；自定义协议正是 Electron 推荐的替代做法，还能给页面一个确定的来源。
- **把诊断页放在远程 Web 应用里**：最需要诊断的正是 Web 加载不出来的时候；本地页面断网也能使用，也不会把诊断通道暴露给远程内容。
- **诊断入口加进 Web 快捷面板**：需要同时改契约、Web 与壳的能力协商；托盘原生菜单、安全模式快捷方式和自动弹出已经覆盖故障场景，所以暂缓。
- **崩溃报告自动上传**：需要新的接收端和隐私评估；30 人规模下由用户导出诊断包发给管理员即可。
- **依赖 electron-updater 的 `verifyUpdateCodeSignature` 钩子校验更新**：没有 `publisherName` 时钩子根本不会被调用；安装包未签名时只能在 `update-downloaded` 之后自己核验。补 Authenticode 后再叠加发布者校验。
- **`autoInstallOnAppQuit=true`**：electron-updater 下载完成即挂上退出安装，不等我们的授权核验。改为自行在 `will-quit` 中安装，并且只安装已核验且摘要未变的文件。
- **让 electron-builder 收集生产依赖进 asar**：asar 白名单要随依赖树变化，且 pnpm 隔离布局需要额外配置；单文件 bundle 让白名单保持精确，代价是许可证需要自行汇总。
- **在快捷面板加入「新版本已就绪」行**：快捷面板是固定尺寸的 Web 窗口，加行需要同时调整尺寸与契约；托盘提示、托盘菜单和主窗横幅已足够，暂缓。
- **用 minisign 或 PGP**：Node 内置 Ed25519 已足够，不需要额外工具链；声明格式自定义且很小，以后迁移到 Tauri updater 时可以对照转换。
- **引入 ZIP 库导出诊断包**：会让主进程多出第一个运行时依赖；仅存储格式的 ZIP 写出只需几十行代码。
- **桌面与服务器共用发布并发组**：GitHub 每个并发组只保留一个待运行项，新的待运行项会取消旧的，两条发布线会互相吞掉排队的运行；串行由主机的部署锁和 inbox 顺序保证。
- **在草稿任务里直接签名**：草稿任务要读 Actions 产物、写 Release，每次 `main` 推送都会运行；让它进入签名 Environment，私钥就暴露给更多代码，Environment 也会记下大量无关的部署。拆出只在有待签草稿时运行的签名任务。
- **执行候选提交里的发布脚本后再检查其来源**：未审查提交可以替换检查本身。Environment 规则约束工作流的 ref，不约束 checkout 的目标；因此执行代码固定来自受保护工作流版本，候选版本只提供数据。
- **签名任务信任草稿阶段的核验结果**：草稿附件仍可修改，安装包和来源清单可以同时被替换。签名任务必须针对本次下载的字节再次验证构建证明，不能把发布后的验证当作撤销已签授权的手段。
- **客户端从 GitHub Releases 下载更新**：主机所在网络访问 GitHub 下载域名会超时，同事的网络也一样；更新源放在已有的 Nginx 主机上。
- **把更新源文件打成一个 ZIP 上传**：发布工具只用 Node 内置模块，没有 ZIP 写入；按文件分块沿用服务器包的 2 MiB 分块和逐块校验，引擎逐个文件对照 Release 附件。
- **Nginx 用 `alias` 映射 `/desktop/`**：`alias` 与嵌套正则 location 组合有已知问题；`root` 让目录结构与 URL 一一对应。
- **用 `pnpm sbom --prod` 生成桌面 SBOM**：桌面依赖全是 devDependencies（运行时被内联打包），`--prod` 得到的是空清单；不带 `--prod` 又会列入测试和构建工具。

## Consequences

- 安装包仍未签名，首次安装会出现 SmartScreen「未知发布者」提示；开启了 Smart App Control 的机器无法运行。补 Authenticode 签名是独立的后续工作。
- 发布渠道要等维护者完成远端设置（`Release tags` 规则加入 `desktop-v*`、`desktop-signing` Environment 与 secret、`wecom-dev` 的 tag 规则）、管理员升级引擎并配置 Nginx 之后，再把桌面版本递增到 `1.0.0` 才开始运作；在那之前便携版用户仍按 RUNBOOK 手工分发。
- electron-builder 构建时会从 GitHub 下载 NSIS 工具链，校验和由 electron-builder 内置；CI runner 直接下载，本地可以配置镜像。
- `pnpm-workspace.yaml` 用 override 去掉了 electron-builder 内 `@electron/get@3` 的可选依赖 `global-agent`。原因是它经 `roarr` 引入 `sprintf-js`（GHSA-hp3w-g68c-fv3c，没有修复版本）。`@electron/get` 只在设置 `ELECTRON_GET_USE_PROXY` 时才加载它，加载失败会降级为不走代理；打包使用 `electronDist`，本来就不经 `@electron/get` 下载。上游换掉这条依赖后应删除这个 override。
- 开启 cookie 加密是单向的：以后关闭这个 fuse，已有的持久 cookie 库会失效。当前业务会话不依赖持久 cookie，因此不受影响。
- 安装冒烟让 Windows 门禁多出一次安装和卸载，时间要计入 `Windows Delivery` 的超时预算。
- 诊断页只在自检中离线渲染过；安全模式和崩溃循环的真实启动、断网和证书错误下诊断窗的弹出，靠单元测试与实机核验覆盖，Windows 门禁不模拟这些场景。
- 卸载会删除日志、崩溃转储和启动计数，只保留计时器、提醒与桌面偏好文件，与 DESK-1 的卸载边界一致。
- 生产安装包从 DESK-4 起检查 `https://wecom-dev.yumpoo.com/desktop/stable/`。更新源上线前检查只会得到 404，客户端记为错误并照常重试，不影响使用。
- 桌面发布要同时等服务器的部署锁：长时间的服务器部署或待恢复状态会延后桌面发布。
- 签名私钥泄露时，攻击者在同时控制分发主机的前提下可以推送更新。缓解措施：Environment 只允许 dev ref、离线备用钥匙、轮换预案（用备用钥匙签一个过渡版本，在其中换掉公钥）。
- 回退只能前滚：客户端拒绝不高于当前的版本，坏版本要靠发布更高的修复版替换。
- 更新端到端验证让 Windows 门禁多出两次打包和一次安装升级，要计入 `Windows Delivery` 的超时预算。
