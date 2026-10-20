# Agent Note: 桌面快捷工时、项目侧边栏与偏好设置

Status: implemented

## Problem

填一次工时要打开主窗口进入「我的一天」；托盘左键只恢复主窗口，贴边计时侧边栏不能直接填报。桌面端没有集中设置页，置顶与显示方式散在计时窗中。更新不能延后；开机自启被否决后，常驻托盘的用户每次开机都要手动启动。

## Decision

托盘左键与全局快捷键（默认 `Ctrl+Alt+Y`）切换内容区为 340×476 的[快捷工时小窗口](../../../../desktop/desktop-shell/src/main/quick-panel.ts)，右键保留计时快捷面板，双击不再单独处理。小窗口沿用 `TimerQuickMenu` 的预热、失焦收起与 250ms 内再次触发即关闭；打开播放 220ms 动画，关闭先通知渲染端，180ms 后隐藏。未登录或尚未加载完成时恢复主窗口。

小窗口按项目分组列出 PERSONAL 候选，使用[共享的隐藏已完成偏好](2026-10-19-work-item-hide-completed.md)，搜索始终包含已完成项并淡化。点行展开填报器，选择今天、昨天或指定日期，以及 15 分钟、30 分钟、1 小时、2 小时或至少 15 分钟的自定义时长。复用 `placeSequential`，从上班时间起排入所选日期的空档（当天只到现在），用 `GAPS` 提交；落点提示给出分段与不足时长。也可直接开始计时。保存成功后按钮变绿、行闪绿、今日合计递增，底部提示并广播刷新。不提供备注，因为补录接口没有备注字段；状态胶囊显示候选已有的状态分类。

[项目侧边栏](../../../../desktop/desktop-shell/src/main/rail-window.ts)是贴边的竖条，中号宽 48px，提供小、中、大三档，最多六个项目。首次按本人最近的活动项目播种，随后以显式列表为准，可拖动排序，恢复默认时重新读取最近项目。点项目在侧边栏旁打开锁定该项目的小窗口，再点同项关闭。自动隐藏留出 10px 可命中的边，推边后 300ms 滑出，离开 450ms 后收回；小窗口从该项目打开期间不收起，也可始终显示。拖动栏身可移动或换边。

原计时显示方式 `display:'dock'` 改为项目侧边栏顶部的计时槽，`dockSide`、`dockY` 是侧边栏的边与纵向位置。计时槽沿用 `orbDuration`，运行时点击全部暂停，暂停时可恢复上一组，没有可继续的工作时禁用并提示从项目选择。悬浮球保留；侧边栏不受计时器开关影响，隐藏计时面时只隐藏顶部计时槽。

`/settings` 是 AppShell 子路由，从头像菜单、小窗口齿轮和托盘快捷面板进入。三张可折叠卡片分别提供：

- 窗口：显示层级复用 `pinned`；保持在任务栏上方使用 `screen-saver` 层级，指针进入侧边栏或计时窗失焦时重新抬升；全局快捷键；侧边栏开关、显示方式、边缘、大小、顶部计时与项目列表。
- 常规：登录时启动、自动下载更新、已安装版本、更新状态和更新内容。
- 外观：主题、密度，以及小窗口和侧边栏 70–100% 的不透明度；不透明度存业务分区 localStorage，使用 `storage` 事件跨窗口同步。

快捷键可按物理键录制、清除；组合必须包含 Ctrl、Alt 或 Win，系统拒绝注册时恢复原快捷键并提示。应用退出时注销快捷键。登录时启动默认关闭，开启后以 `--hidden` 启动，主窗口加载但不显示，留下托盘和侧边栏；只在安装版写入系统登录项，卸载删除对应 Run 值。安装器仍不提供自启选项。

自动下载默认开启且可关闭；检查节奏不变，关闭时发现更新进入 `available`，通过设置页手动下载，重新开启则立即下载等待中的更新。公司签名授权校验与退出时安装保持不变，见[桌面安装包与发布渠道](../process/2026-10-17-desktop-release-channel.md)。

[桌面偏好](../../../../desktop/desktop-shell/src/main/desktop-preferences.ts)保存到 `userData/desktop-preferences.json`，格式为 `{version:1,railEnabled,railMode,railSize,railProjects:{owner,items}|null,aboveTaskbar,shortcut,autoDownload}`；沿用 4KB 上限、逐字段清洗、临时文件加 rename 串行写入。`owner` 是 `companyId:userId`；登录项以系统状态为准，不写入文件。新文件加入诊断「重置偏好」、卸载保留清单与隐私说明。

IPC 按窗口主框架与来源校验：`yumpoo:preferences:*` 只接受主窗口，`yumpoo:quick:*` 只接受小窗口，`yumpoo:rail:*` 只接受侧边栏；`yumpoo:timer:refresh` 额外接受小窗口与侧边栏。所有窗口使用同一持久业务分区。新桥成员全部可选；Web 先于壳部署时，浏览器与旧壳的设置页只显示外观，旧壳快捷面板没有「偏好设置…」，`TimerSettings` 保留旧侧边栏说明，`TimerDock` 与 dock 几何继续为旧壳提供支持。

本记录更新[桌面计时界面](2026-09-24-desktop-timer-surfaces.md)和[计时窗与托盘](2026-09-08-timer-work-picker.md)中的侧边栏与托盘语义，取代发布渠道记录中对应用内开机自启的否决。

## Alternatives considered

- 保留计时侧边栏再增加项目侧边栏：屏幕边缘会出现两条栏，用户选择合并。
- 填报时输入起止时刻：多一步操作；按时长排入空档与「我的一天」一致，落点仍明确展示。
- 固定在桌面：Electron 没有置底 API，提供「普通窗口」作为显示层级选项。
- 全屏自动隐藏：无法可靠检测其他应用全屏。
- 界面语言：仓库没有 i18n，不在本次增加。
- 独立 preload 或 Vite 入口：需要修改打包白名单，共用 index preload 并按窗口校验已能限定边界。
- 另存侧边栏位置：与 `dockSide`、`dockY` 重复，原计时设置中的停靠位置也会失效。
- 自动下载保持不可关闭：用户需要选择下载时机，仍保留自动检查、默认下载及原校验机制。

## Consequences

增加两个常驻渲染进程（小窗口预热与项目侧边栏，各约 50–80MB），各自每五秒轮询计时状态。保持在任务栏上方为实验性，任务栏被点击后可能短暂覆盖侧边栏，直到指针进入。快捷键可能被其他应用占用，设置页显示未生效；关闭自动下载的机器可能长期停留在旧版本。

「我的一天」、齿轮与侧边栏加号通过 `loadURL` 打开主窗口目标页，会重载主窗口。侧边栏保存项目名，项目改名后需要恢复默认或重新添加。减少动态效果时关闭 CSS 动画与过渡，主进程关闭窗口的 180ms 延后保持不变。

## Verification

- `pnpm --filter @yumpoo/desktop-shell exec vitest run test/timer-window.spec.ts test/timer-menu.spec.ts test/timer-geometry.spec.ts test/update-controller.spec.ts test/update-ipc.spec.ts test/desktop-preferences.spec.ts`：六个 spec 共 57 项通过。
- `pnpm --filter @yumpoo/web-app exec vitest run src/components/desktop/quickTime.spec.ts src/components/timer src/components/inbox/InboxHost.spec.ts src/views/download/DesktopDownloadView.spec.ts`：11 个 spec 共 77 项通过。
- preload-contract 构建、Web 与桌面 `typecheck`、`pnpm lint`（0 errors）、`pnpm run test:architecture`（15 项）、`pnpm run doc-sync`（88 份活动 Note 与 4 份冻结记录）、`pnpm run test:agent-notes -- --maxWorkers=1 --testTimeout=30000`（20 项）通过。
- 本地独立数据库与浏览器走查：快捷页按提示排入一小时，今日合计从 30 分钟变为一小时 30 分钟；无搜索时隐藏已完成项并显示数量，搜索时保留、淡化并划线。
- 真实桌面托盘、系统快捷键、自动隐藏、安装版登录项及旧壳人工走查尚未完成，限制与其余验收证据记录在本 PR。
