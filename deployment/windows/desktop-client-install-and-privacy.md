# Yumpoo Desktop 安装机制与隐私说明

读者：管理员、管理层和员工。适用于带安装向导的 NSIS 安装版 Yumpoo Desktop，即 `desktop-v1.0.0` 发布草稿（一键安装器）之后的版本。本文每条事实都以 [desktop/desktop-shell](../../desktop/desktop-shell) 源码和[桌面安装包与发布渠道](../../.agents/notes/implemented/process/2026-10-17-desktop-release-channel.md) Agent Note 为准；网络访问部分另有一次运行期抓包核对（见 [3.4](#34-运行期核对记录)）。安装器的「安装须知与隐私说明」页和下载页的「安装说明与隐私」折叠区是本文的精简版，三处内容需要同步修改。

## 1. 摘要

- 只为当前 Windows 用户安装，不需要管理员权限，不弹 UAC，自动更新同样静默完成。不设置开机自启，不注册服务或计划任务，不写 HKLM。
- 网页、接口和自动更新只连接公司服务器 `https://wecom-dev.yumpoo.com`。登录时用系统默认浏览器打开企业微信官方扫码页。没有第三方统计、广告或崩溃自动上传。
- 唯一的第三方请求：Chromium 内置拼写检查会从 Google 下载服务器下载一次英文词典（[3.3](#33-已知的第三方请求拼写检查词典)）。
- 勾选「7 天内免登录」时，登录凭据用 Windows DPAPI 加密保存在本机；不勾选则不保存，每次启动重新登录。登录 cookie 不写入磁盘。日志已脱敏，崩溃转储只保存在本机。
- 自动更新会核对公司签发的 Ed25519 授权，在退出时安装，用户不能关闭。
- 主要风险：安装包尚未做 Authenticode 签名；更新签名私钥需要妥善保管；客户端展示的是远程网页内容；共用电脑需要手动退出登录。

## 2. 安装机制

### 2.1 安装器页面

双击安装包后依次出现：

1. **安装须知与隐私说明**：本文的精简版，必须点「我同意」才能继续；
2. **选择安装位置**：默认 `%LOCALAPPDATA%\Programs\YumpooDesktop`，可以改到别的目录；
3. **附加选项**：
   - 先检查当前用户能否写入所选目录。不能写入时（例如 `C:\Program Files`）页面给出提示，并禁用「下一步」，用户需点「上一步」重新选择。安装器不会为此申请管理员权限。
   - 「创建桌面快捷方式」，默认勾选；
4. **安装进度**；
5. **完成**：可勾选「运行 Yumpoo Desktop」。

安装器没有「为所有用户安装」的选项。如果所选目录的路径里不含 `YumpooDesktop`，安装器会在其下建子目录 `YumpooDesktop`，例如选 `D:\Apps` 时实际安装到 `D:\Apps\YumpooDesktop`。卸载时会删除整个安装目录，所以不要把安装目录指向存放其他文件的目录。

`desktop-v1.0.0` 发布草稿的一键安装器装在 `%LOCALAPPDATA%\Programs\yumpoo-desktop`。在这些机器上覆盖安装或自动更新时，安装器从 `InstallLocation` 读到原目录，继续使用它。

### 2.2 安装后写入的内容

| 位置 | 内容 |
| --- | --- |
| 安装目录（默认 `%LOCALAPPDATA%\Programs\YumpooDesktop`） | `YumpooDesktop.exe`、Electron 运行时、`resources\app.asar` 与 `Uninstall YumpooDesktop.exe` |
| 开始菜单 `Yumpoo Desktop` | 始终创建，带 AppUserModelID `com.yumpoo.desktop`，Windows 靠它显示系统通知 |
| 开始菜单 `Yumpoo Desktop (Safe Mode)` | 始终创建，以 `--safe-mode` 启动：关闭硬件加速，不启动计时窗和提醒，先打开「诊断与修复」 |
| 桌面 `Yumpoo Desktop` | 可选，取消勾选则不保留 |
| `HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\<GUID>` | 卸载登记：名称、版本、发布者 `Yumpoo`、`UninstallString`，以及带 `/S` 的 `QuietUninstallString` |
| `HKCU\Software\<GUID>` | `InstallLocation`（安装目录）、快捷方式名等；覆盖安装和自动更新据此找到原目录 |
| `HKCU\Software\Classes\yumpoo` | `yumpoo:` 登录回调协议，命令为 `"<安装目录>\YumpooDesktop.exe" "%1"` |
| `%APPDATA%\Yumpoo Desktop` | userData，首次运行时创建，内容见[第 4 节](#4-本机数据) |
| `%LOCALAPPDATA%\yumpoo-desktop-updater` | 更新缓存：安装包副本 `installer.exe`（约 100 MB，作为差分更新的基准）和下载中的更新 |

### 2.3 更新与覆盖安装

- **自动更新**以静默方式运行新安装包，沿用 `InstallLocation` 中的目录；用户删除了的桌面快捷方式不会被重建，开始菜单快捷方式保留。
- **手动重新运行安装包**会再次显示上述页面，目录默认为现有安装位置，桌面快捷方式按本次的勾选处理。登录状态和偏好保留。
- 旧便携版用户直接安装即可，userData 位置相同，登录状态和偏好沿用。

### 2.4 管理员静默安装

```powershell
# 默认目录，创建桌面快捷方式
& '<下载目录>\YumpooDesktop-Setup-X.Y.Z-x64.exe' /S
# 指定目录：/D= 必须放在最后，路径不加引号
& '<下载目录>\YumpooDesktop-Setup-X.Y.Z-x64.exe' /S /D=D:\Apps\YumpooDesktop
```

静默安装不显示任何页面，按 `/D=` 的原样路径安装，不追加子目录，并创建桌面快捷方式。静默安装须由该员工本人的 Windows 账户执行，因为安装位置和注册表都属于当前用户。

## 3. 网络访问

### 3.1 连接清单

| 用途 | 地址 | 发起方 | 附带的信息 |
| --- | --- | --- | --- |
| 业务网页与接口 | `https://wecom-dev.yumpoo.com/` | 主窗口、计时窗、快捷面板 | 登录 cookie；请求头 `X-Client-Type: ELECTRON`、`X-Client-Version: <版本>`、`X-Client-Protocol-Version: 1`。这些请求头只发往本站 |
| 检查和下载更新 | `https://wecom-dev.yumpoo.com/desktop/stable/`（`latest.yml`、安装包、`.blockmap`、`.sig.json`） | electron-updater（独立的会话分区，不带业务 cookie）与主进程 | 请求头 `x-user-staging-id`，值为本机随机生成的 `.updaterId`（[第 4 节](#4-本机数据)） |
| 诊断 | 本站域名解析、443 端口 TLS 握手、`/healthz`、`/version.json` | 主进程，仅在打开「诊断与修复」或导出诊断包时 | 不带 cookie，不跟随重定向 |
| 拼写检查词典 | `https://redirector.gvt1.com/edgedl/chrome/dict/en-us-10-1.bdic`，重定向到 `*.gvt1.com` | Chromium 内置拼写检查 | 见 [3.3](#33-已知的第三方请求拼写检查词典) |

除此之外：

- 网页只能导航到本站。其他来源的跳转、弹窗和 `<webview>` 一律拦截；网页发起的下载全部拒绝，导出文件只能经主进程弹出的保存对话框写到用户选择的位置。
- 网络请求走 Windows 系统代理设置。诊断窗会显示解析到的代理；诊断里的 TLS 握手由 Node 直接连接，不经过代理。

### 3.2 登录

1. 客户端向本站申请一次性登录尝试（PKCE，带随机 `state`）；
2. 用**系统默认浏览器**打开服务端返回的企业微信官方扫码页（`open.work.weixin.qq.com`）。客户端自己不连接企业微信；
3. 扫码后，浏览器经 `yumpoo:` 协议把一次性授权码交回客户端。客户端核对 `state`，再向本站换取会话；
4. 勾选「7 天内免登录」时，会话凭据经 DPAPI 加密写入本机；不勾选时不写入，并删除已有的凭据文件。凭据以不带过期时间的会话 cookie 注入，不写入磁盘。

### 3.3 已知的第三方请求：拼写检查词典

业务窗口保留了 Chromium 默认的拼写检查。每个 userData 第一次启动时，Chromium 会从 Google 的下载服务器取一份英文 Hunspell 词典（约 440 KB），保存到 `%APPDATA%\Yumpoo Desktop\Dictionaries\en-US-10-1.bdic`，此后使用本地副本。卸载后 userData 被清理，重新安装会再下载一次。

这个请求只带 User-Agent（含 `YumpooDesktop/<版本>`）和 `Accept-Language`，不带 cookie、账号或用户输入的内容；Google 能看到的是出口 IP 和客户端版本。如果需要完全杜绝，可以在网络出口拦截 `redirector.gvt1.com` 与 `*.gvt1.com`：拼写检查下划线将失效，其他功能不受影响。关闭这个请求需要修改桌面壳，已作为后续事项上报。

### 3.4 运行期核对记录

2026-10-18 在 Windows 11 上用本仓库打出的 `desktop-v1.0.0` 程序（`win-unpacked`，生产分发配置），以全新 `--user-data-dir` 和 `--log-net-log` 启动约 2 分钟，覆盖登录页加载和首次更新检查。NetLog 中出现的目标主机只有：

- `wecom-dev.yumpoo.com`：网页资源、`/api/v1/auth/me`、`/desktop/stable/latest.yml`；
- `redirector.gvt1.com` 和一个 `rr*.gvt1.com` 节点：仅 `en-us-10-1.bdic`。

没有出现统计、遥测、崩溃上传或其他第三方主机。业务请求带有上述三个 `X-Client-*` 请求头，更新请求带 `x-user-staging-id`。

## 4. 本机数据

userData 位于 `%APPDATA%\Yumpoo Desktop`：

| 路径 | 内容 | 保护与生命周期 |
| --- | --- | --- |
| `auth\electron-session.bin` | 会话凭据、CSRF 凭据、过期时间、所属站点 | 仅在勾选「7 天内免登录」时写入；用 Electron safeStorage（Windows DPAPI，绑定当前 Windows 用户）加密，最大 16 KB；过期、站点不符或解密失败即删除；退出登录或卸载时删除 |
| `Partitions\yumpoo-authenticated\` | 网页的本机偏好（Local Storage，如主题、密度、免登录选择）与 Chromium 运行数据；登录 cookie | 持久分区，关闭 HTTP 缓存，不保存业务接口响应；登录 cookie 不带过期时间、不写入磁盘，每次启动从上面的加密文件重新注入；「诊断与修复 → 重置本机偏好」会清空其中的 Local Storage；卸载时删除 |
| `timer-preferences.json`、`inbox-preferences.json` | 计时器窗口和提醒的偏好 | 明文 JSON，不含业务内容；卸载时保留 |
| `startup-state.json` | 连续启动失败计数，用于自动进入安全模式 | 主窗口加载成功并运行 60 秒，或加载后正常退出时清零 |
| `logs\desktop.log`（及 `.1`、`.2`） | 主进程 JSON 行日志 | 单文件 5 MB 轮转，共 3 个文件。URL 只留协议、主机和路径；名称涉及 cookie、凭据、授权、正文、标题、`code`、`state`、`session`、`text`、`message` 的字段整项丢弃 |
| `Crashpad\` | 崩溃转储 | `uploadToServer: false`，只保存在本机，只随用户自己导出的诊断包离开本机 |
| `.updaterId` | electron-updater 生成的随机 UUID | 与账号、设备硬件无关；每次检查更新作为 `x-user-staging-id` 发往公司更新源，生成时也会在日志里记一行。卸载时删除，重新安装会生成新值 |
| `Dictionaries\`、`Cache\`、`Local Storage\`、`Network\`、`Local State`、`Preferences` 等 | Chromium 运行数据 | 卸载时删除 |

更新缓存 `%LOCALAPPDATA%\yumpoo-desktop-updater` 只存安装包副本和下载中的更新，不含个人数据。

DPAPI 只能防止其他 Windows 用户或拿走磁盘的人读取凭据，不能防止使用同一 Windows 账户的人：他们直接打开客户端就处于登录状态。

## 5. 权限与加固

- **渲染进程**：开启沙箱和上下文隔离，关闭 Node 集成，开启 webSecurity，禁止 `<webview>` 和混合内容。网页只能通过预加载桥调用固定的几组接口，主进程逐个核对调用来自预期窗口的主框架、页面来源为本站。
- **网页权限**：摄像头、麦克风、定位、剪贴板读取、网页通知等权限的检查和请求一律拒绝。系统通知由主进程发出。
- **导航**：只允许本站；新窗口一律拒绝；下载一律拒绝。
- **开发者工具**：打包版关闭。
- **证书**：证书错误一律拒绝，不提供「继续访问」。启动时遇到证书错误会打开诊断窗并标明原因。
- **Electron fuses**：关闭 `RunAsNode`、`EnableNodeOptionsEnvironmentVariable`、`EnableNodeCliInspectArguments`、`GrantFileProtocolExtraPrivileges`；开启 `EnableEmbeddedAsarIntegrityValidation` 和 `OnlyLoadAppFromAsar`，被篡改的 `app.asar` 无法启动；开启 `EnableCookieEncryption`。
- **分发配置**：打包版只认 asar 内的配置（站点、更新源、公钥），忽略 `YUMPOO_WEB_URL`，受 asar 完整性校验保护。
- **诊断窗**：只加载安装包内的本地页面，CSP 为 `connect-src 'none'`，使用独立的会话分区，并拒绝该分区内除本地页面以外的全部请求。

## 6. 自动更新

- **检查时机**：启动后 30 秒，之后每 4 小时一次（加最多 30 分钟随机抖动）；系统从睡眠唤醒、且距上次检查超过 15 分钟时再查一次。安全模式下照常更新。
- **授权校验**：下载完成后，客户端从更新源取 `.sig.json`，用内置公钥（主钥匙和离线备用钥匙）核对 Ed25519 签名，再逐项核对渠道、版本（必须高于当前版本）、文件名、大小和 SHA-512。任一项不符就删除下载的文件，永不安装。
- **安装时机**：任何正常退出（含托盘退出）都会静默安装已核对的更新；「重启以更新」走同一次退出，安装后自动重新启动。安装前会再算一次 SHA-512。
- **用户不能关闭**：客户端没有关闭或推迟更新的设置。这样 Chromium 安全补丁和修复版能持续送达；客户端只接受更高版本，坏版本只能用更高版本号的修复版前滚。
- 更新源上线前，检查会得到 404，客户端记录错误后照常重试，不影响使用。

## 7. 卸载

入口：「设置 → 应用 → 已安装的应用 → Yumpoo Desktop → 卸载」，或「控制面板 → 程序和功能」。管理员可执行卸载登记中的 `QuietUninstallString` 静默卸载。

卸载会删除：

- 整个安装目录、三个快捷方式、卸载登记和 `HKCU\Software\Classes\yumpoo`；
- 更新缓存 `%LOCALAPPDATA%\yumpoo-desktop-updater`；
- userData 中除 `timer-preferences.json` 和 `inbox-preferences.json` 以外的全部内容，包括加密的登录凭据、日志、崩溃转储和 Chromium 运行数据。

保留的两个偏好文件让重新安装后计时器和提醒设置不变。需要彻底清除时，卸载后手动删除 `%APPDATA%\Yumpoo Desktop`。

卸载程序不联网，只删除本机凭据，不会向服务器注销会话。需要立即作废会话时，先在客户端里退出登录再卸载。自动更新以「更新」模式运行旧卸载程序，不删除任何数据。

## 8. 诊断包

「诊断与修复」窗口的「导出诊断包…」由用户选择保存位置，生成一个 ZIP：

- `diagnostics.json`：版本、操作系统、网络探测结果、代理、safeStorage 与 userData 状态、图形加速状态、各进程内存；
- `logs/desktop*.log`：已脱敏的日志；
- `crashes/*.dmp`：最近 5 个崩溃转储（单个不超过 20 MB）。

导出时不读取 cookie、凭据或偏好文件。但**崩溃转储是进程内存的片段，可能含有崩溃时正在显示或处理的业务数据**。员工只应通过内部渠道发给管理员，管理员排查完后删除。

## 9. 风险与缓解

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 安装包未做 Authenticode 签名 | 首次运行出现 SmartScreen「未知发布者」；开启「智能应用控制」的电脑无法运行；员工难以分辨真假安装包 | 只从 `https://wecom-dev.yumpoo.com/download` 下载并核对 SHA-256；管理员可用 `gh attestation verify` 复核构建证明（[第 10 节](#10-管理员核验)）；自动更新另有 Ed25519 授权，不依赖代码签名。补签名是独立的后续工作 |
| 更新签名私钥泄露 | 攻击者若同时控制分发主机，可以推送被客户端接受的更新 | 私钥只在只允许 `dev` 的 `desktop-signing` Environment 中；另有离线备用钥匙，泄露时用它签过渡版本并替换公钥（RUNBOOK 第 11 节） |
| 客户端加载远程网页 | 公司站点被攻破时，网页能读取员工在网页中可见的业务数据，与浏览器访问相同 | 网页运行在沙箱中，所有网页权限被拒，只能导航到本站，只能调用固定的预加载接口，无法执行本机程序 |
| 按用户安装的目录对当前用户可写 | 以同一用户身份运行的恶意程序可以替换安装目录里的文件 | asar 完整性校验与 `OnlyLoadAppFromAsar` 阻止篡改 `app.asar`，但不能阻止替换整个程序；这与所有按用户安装的软件相同，依赖终端防护 |
| 自动更新不能关闭 | 有问题的版本会推送到所有人 | 草稿先在 2–3 台机器试装再 Publish；安全模式和诊断窗可以自救；用更高版本前滚修复，紧急时改用浏览器 |
| 系统代理或 VPN | 网页加载、登录和更新变慢或失败 | 诊断窗显示 DNS、TLS、代理和服务健康状况；导出诊断包交管理员排查（RUNBOOK 第 10 节） |
| 共用电脑 | 同一 Windows 账户下的其他人直接处于登录状态 | 登录时取消勾选「7 天内免登录」，关闭客户端后不保留登录；离开前在客户端退出登录；需要时卸载以删除本机凭据 |
| 拼写检查词典请求 Google | 向 Google 暴露出口 IP 与客户端版本 | 出口拦截 `*.gvt1.com` 不影响其他功能；关闭该请求需要改壳，已上报 |

## 10. 管理员核验

**核对安装包**：

1. 打开 `https://wecom-dev.yumpoo.com/download`，记下文件名与 SHA-256；
2. 计算下载文件的摘要并比对：

   ```powershell
   (Get-FileHash -Algorithm SHA256 '<下载目录>\YumpooDesktop-Setup-X.Y.Z-x64.exe').Hash.ToLower()
   ```

3. 用 GitHub CLI 复核构建证明，确认安装包由 `main` 推送 CI 构建：

   ```powershell
   gh attestation verify '<下载目录>\YumpooDesktop-Setup-X.Y.Z-x64.exe' -R E9uuleus/YumpooPlatform `
     --signer-workflow E9uuleus/YumpooPlatform/.github/workflows/m0-18-ci.yml --source-ref refs/heads/main
   ```

**核对已安装的客户端**：

- 「设置 → 应用」中的版本与发布者应为 `X.Y.Z` / `Yumpoo`；
- 「诊断与修复」窗口显示壳、Electron 和 Chromium 版本以及更新状态；
- 离线自检：`& '<安装目录>\YumpooDesktop.exe' --self-check=<绝对路径>.json`，退出码 0 表示安装完整，报告写入指定文件。

发布与试装流程见 [RUNBOOK 第 11 节](RUNBOOK.md#11-桌面发布渠道)，故障排查见 [RUNBOOK 第 10 节](RUNBOOK.md#10-桌面客户端故障排查)。
