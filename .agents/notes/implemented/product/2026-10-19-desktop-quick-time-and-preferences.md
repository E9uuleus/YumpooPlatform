# Agent Note: 桌面快捷工时、项目侧边栏与偏好设置

Status: implemented

## Problem

填一次工时要打开主窗口进入「我的一天」；托盘左键只恢复主窗口，贴边计时侧边栏不能直接填报。桌面端没有集中设置页，置顶与显示方式散在计时窗中。更新不能延后；开机自启被否决后，常驻托盘的用户每次开机都要手动启动。

## Decision

托盘左键与全局快捷键（默认不设置，由本人在设置页录制）切换初始内容区为 340×476 的[快捷工时小窗口](../../../../desktop/desktop-shell/src/main/quick-panel.ts)，右键保留计时快捷面板，双击不再单独处理。未固定时沿用 `TimerQuickMenu` 的预热、失焦收起与 250ms 内再次触发即关闭；打开播放 220ms 动画，关闭先通知渲染端，180ms 后隐藏。未登录或尚未加载完成时恢复主窗口。小窗口隐藏时不轮询计时状态，显示时刷新；侧边栏仍持续轮询，以显示其他设备上开始的计时。

小窗口可以固定，拖动标题栏或从右下角缩放也会自动固定。内容区最大 560×860，缩放受工作区边界约束；固定状态、位置和大小保存在 `timer-preferences.json` 的 `quickPinned`、`quickBounds`，不暴露到公共计时偏好。固定后失焦不收起，跟随全局窗口置顶与高于任务栏层级，托盘点击只把可见小窗提到前面；从侧边栏选择不同项目才刷新内容，固定的小窗不阻止侧边栏收起。重新打开或重启后恢复位置与大小，取消固定后恢复从托盘弹出并保留大小。「打开主界面」按钮改为固定入口，通过页脚「我的一天」或齿轮设置进入主窗口。新增桥成员可选，旧壳隐藏固定与缩放入口。

快捷工时的项目 chips 来自服务端的项目计数，从侧边栏打开某个项目时由服务端按该项目筛选。小窗口按项目分组列出 PERSONAL 候选，使用[共享的隐藏已完成偏好](2026-10-19-work-item-hide-completed.md)，搜索始终包含已完成项并淡化。点行展开填报器，选择今天、昨天，或用逐日步进器选择更早的日期；不允许步进到未来，避免日历弹层被小窗截断。时长为 15 分钟、30 分钟、1 小时、2 小时或至少 15 分钟的自定义时长。列表顶部一行是[非项目固定分类](../architecture/2026-10-22-non-project-time-categories.md)（出差、学习、商务、会议），点分类打开与工作项行相同的记录区（`QuickComposer`），选中项目 chip 时不显示；被截断的工作项标题悬停显示完整标题。复用 `placeSequential`，从上班时间起排入所选日期的空档（当天只到现在），用 `GAPS` 提交；落点提示给出分段与不足时长。也可直接开始计时。提示按服务端实际补录的时长（计划时长减去跳过时长）；所选时段已全部有记录时，只保留「没有新增」的警告，不提示成功。保存成功后按钮变绿、行闪绿、今日合计递增，底部提示并广播刷新。不提供备注，因为补录接口没有备注字段。

默认仍自动排入空档，按需改时段：落点提示右侧的「改时段」把同一行换成两个起止输入（沿用「我的一天」的 `TimeField`，接受 9、930、9:30，↑↓ 以 15 分钟步进），「↺自动」回到自动排程，字段内回车即记录。起点取自动计划的第一段开始，没有空档时以可记录的最晚时刻为终点。改时段期间时长 chip 与区间联动：点 chip 移动终点，改起止反算时长，只有长度与某个 chip 相同时才点亮。校验与「我的一天」补录相同（结束晚于开始、至少 15 分钟、不晚于现在、不早于当天）。仍以 `GAPS` 提交，与已有记录重叠的部分由服务端跳过，界面事先提示；整段已有记录时禁用记录按钮。展开另一行或重新打开小窗回到自动；改时段期间换日期保留钟点。

候选接口一次关联项目状态字典，返回可选的 `statusCode`、`statusName`、`statusColorToken`；状态分类仅用于业务判断。状态胶囊首次显示就使用项目实际名称和颜色，不额外逐行请求详情，也不按分类缓存名称。旧响应缺字段时显示中性「查看状态」，读取详情后补全。点击（不展开行）弹出项目自己的状态列表：每次打开经 [`useConnectedItem`](../../../../frontend/web-app/src/components/projects/connect/useConnectedItem.ts) 读取最新详情，再用表格同一条迁移命令（If-Match、幂等键、需要说明时的说明框）提交；读取后补齐或更新项目自己的状态名与颜色，没有编辑权限时列表只读。搜索框右端的「文档加号」与搜索无结果时的「新建「…」」在列表顶部打开一行新建表单：选择本人负责或加入的活动项目（默认依次为当前项目 chip、侧边栏项目、上次新建用过的项目、第一个），回车创建在该项目第一个启用的类别下，处理人为本人；创建后展开记录区，可以接着填工时。

小窗改状态或新建后，用同源 `BroadcastChannel('yumpoo-work-items')` 通知其他窗口：项目表格在项目匹配时防抖 300ms 重载当前视图，首页重新读取「我的工作项」。主窗口自己的编辑不广播。

[项目侧边栏](../../../../desktop/desktop-shell/src/main/rail-window.ts)是贴边的竖条，中号宽 48px，提供小、中、大三档，最多六个项目。本机记录到本人最近的活动项目后按其播种，之前不写入，随后以显式列表为准，可拖动排序，恢复默认时重新读取最近项目。点项目在侧边栏旁打开锁定该项目的小窗口，再点同项关闭。自动隐藏留出 10px 可命中的边，推边后 300ms 滑出，离开 450ms 后收回。收起时侧边栏连同阴影整条移出屏幕，可命中条内只显示一枚 4×40 的把手，计时中改用强调色并缓慢呼吸；磁贴用项目色淡底配同色字母，当前项目以贴屏幕边一侧的短竖标标出。小窗口从该项目打开期间不收起，也可始终显示。拖动栏身可移动或换边。

原计时显示方式 `display:'dock'` 改为项目侧边栏顶部的计时槽，`dockSide`、`dockY` 是侧边栏的边与纵向位置。计时槽沿用 `orbDuration`，运行时点击全部暂停，暂停时可恢复上一组，没有可继续的工作时禁用并提示从项目选择。悬浮球保留；侧边栏不受计时器开关影响，隐藏计时面时只隐藏顶部计时槽。

设置页和侧边栏拖动保存前，项目列表逐项复制为纯 `{id,name}` 对象；Vue 响应式数组展开后仍含 Proxy，无法通过 context bridge 的结构化克隆。列表与默认值的标识、名称和顺序相同时，「恢复默认」禁用并提示「已是默认项目」。

`/settings` 是 AppShell 子路由，从头像菜单、小窗口齿轮和托盘快捷面板进入。三张可折叠卡片分别提供：

- 窗口：显示层级复用 `pinned`；保持在任务栏上方使用 `screen-saver` 层级，指针进入侧边栏或计时窗失焦时重新抬升；全局快捷键；侧边栏开关、显示方式、边缘、大小、顶部计时与项目列表。
- 常规：登录时启动、自动下载更新、已安装版本、更新状态和更新内容。
- 外观：主题、密度，以及小窗口和侧边栏 70–100% 的不透明度；不透明度存业务分区 localStorage，使用 `storage` 事件跨窗口同步。

快捷键默认不设置：Windows 上 Ctrl+Alt 等同 AltGr，默认注册会在所有应用里截走该组合。安全模式不启用设置桥与全局快捷键，设置页只显示外观。快捷键可按物理键录制、清除；组合必须包含 Ctrl、Alt 或 Win，系统拒绝注册时恢复原快捷键并提示。应用退出时注销快捷键。登录时启动默认关闭，开启后以 `--hidden` 启动，主窗口加载但不显示，留下托盘和侧边栏；只在安装版写入系统登录项，卸载删除对应 Run 值。安装器仍不提供自启选项。

自动下载默认开启且可关闭；检查节奏不变，关闭时发现更新进入 `available`，通过设置页手动下载，重新开启则立即下载等待中的更新。公司签名授权校验与退出时安装保持不变，见[桌面安装包与发布渠道](../process/2026-10-17-desktop-release-channel.md)。

[桌面偏好](../../../../desktop/desktop-shell/src/main/desktop-preferences.ts)保存到 `userData/desktop-preferences.json`，格式为 `{version:1,railEnabled,railMode,railSize,railProjects:{owner,items}|null,aboveTaskbar,shortcut,autoDownload}`；沿用 4KB 上限、逐字段清洗、临时文件加 rename 串行写入。`owner` 是 `companyId:userId`；登录项以系统状态为准，不写入文件。新文件加入诊断「重置偏好」、卸载保留清单与隐私说明。

小窗口与侧边栏页面是 `/timer/quick` 与 `/timer/rail`，与 `/timer`、`/timer/menu` 同属壳专用页面，路径集中在 [surfaces.ts](../../../../frontend/web-app/src/components/desktop/surfaces.ts)。`/desktop/` 只属于[更新源](../process/2026-10-17-desktop-release-channel.md)：生产 Nginx 以 `location ^~ /desktop/` 把它交给静态目录，1.1.0 曾把页面放在 `/desktop/quick`、`/desktop/rail`，生产只得到 Nginx 404；部署资产检查现在拒绝任何被非 SPA location 遮蔽的路由。四个壳页面经 [loadSurface](../../../../desktop/desktop-shell/src/main/surface-load.ts) 加载，服务端返回 4xx/5xx 时按加载失败处理：销毁窗口，小窗口回落主窗口，不把错误页当内容显示。

IPC 按窗口主框架与来源校验：`yumpoo:preferences:*` 只接受主窗口，`yumpoo:quick:*` 只接受小窗口，`yumpoo:rail:*` 只接受侧边栏；`yumpoo:timer:refresh` 额外接受小窗口与侧边栏。所有窗口使用同一持久业务分区。新桥成员全部可选；Web 先于壳部署时，浏览器与旧壳的设置页只显示外观，旧壳快捷面板没有「偏好设置…」，`TimerSettings` 保留旧侧边栏说明，`TimerDock` 与 dock 几何继续为旧壳提供支持。

本记录更新[桌面计时界面](2026-09-24-desktop-timer-surfaces.md)和[计时窗与托盘](2026-09-08-timer-work-picker.md)中的侧边栏与托盘语义，取代发布渠道记录中对应用内开机自启的否决。

固定的小窗口作为普通独立窗口显示在任务栏的 YumpooPlatform 分组中，便于从任务栏找回；取消固定时移出任务栏，继续作为失焦收起的托盘弹窗。窗口不再使用 `toolbar` 类型，任务栏可见性随 `quickPinned` 切换并在重启后恢复。

## Alternatives considered

- 保留计时侧边栏再增加项目侧边栏：屏幕边缘会出现两条栏，用户选择合并。
- 只用起止时刻填报：多一步操作，按时长排入空档与「我的一天」一致；改为默认自动排程、需要时才在同一行改时段，不增加常驻输入。
- 工作项变更经桌面壳 IPC 转发到主窗口：需要桌面发版和新的 preload 契约；各窗口同源且共用业务分区，`BroadcastChannel` 不改壳即可送达，浏览器标签页之间同样有效。
- 新建时套用本人在项目表格里保存的新建默认值：那是表格行内新建的个人偏好，小窗只需要一条可以记工时的工作项。
- 固定在桌面：Electron 没有置底 API，提供「普通窗口」作为显示层级选项。
- 全屏自动隐藏：无法可靠检测其他应用全屏。
- 界面语言：仓库没有 i18n，不在本次增加。
- 独立 preload 或 Vite 入口：需要修改打包白名单，共用 index preload 并按窗口校验已能限定边界。
- 另存侧边栏位置：与 `dockSide`、`dockY` 重复，原计时设置中的停靠位置也会失效。
- 自动下载保持不可关闭：用户需要选择下载时机，仍保留自动检查、默认下载及原校验机制。
- 默认启用 `Ctrl+Alt+Y`：未经本人同意即在全系统截获组合键，与 AltGr 输入冲突。
- 保留 `/desktop/quick` 并把 Nginx 更新源收窄到 `/desktop/stable/`：可让 1.1.0 立即恢复，但两个所有者仍共用一个命名空间，渠道名不能与页面同名，且要管理员手工改主机配置，部署引擎不托管该文件。

## Consequences

增加两个常驻渲染进程（小窗口预热与项目侧边栏，各约 50–80MB），侧边栏每五秒轮询计时状态，小窗口仅在显示时轮询。保持在任务栏上方为实验性，任务栏被点击后可能短暂覆盖侧边栏，直到指针进入。快捷键可能被其他应用占用，设置页显示未生效；关闭自动下载的机器可能长期停留在旧版本。

1.1.0 壳仍请求 `/desktop/quick`、`/desktop/rail`，在自动更新到 1.1.1 之前小窗口与侧边栏继续显示 404；服务端须先于桌面 1.1.1 发布。

小窗新建的工作项不套用个人表格默认值：优先级、日期等为空，状态为项目初始状态，类别固定为第一个启用的类别，需要时到表格中调整。跨窗口同步只覆盖小窗的改状态与新建；其他来源的变更仍靠各页面自己的刷新时机，旧壳无需更新即可收到广播。

「我的一天」、齿轮与侧边栏加号通过 `loadURL` 打开主窗口目标页，会重载主窗口。侧边栏保存项目名，项目改名后需要恢复默认或重新添加。减少动态效果时关闭 CSS 动画与过渡，主进程关闭窗口的 180ms 延后保持不变。

## Verification

- `pnpm --filter @yumpoo/desktop-shell exec vitest run test/timer-window.spec.ts test/timer-menu.spec.ts test/timer-geometry.spec.ts test/update-controller.spec.ts test/update-ipc.spec.ts test/desktop-preferences.spec.ts`：六个 spec 共 57 项通过。
- `pnpm --filter @yumpoo/web-app exec vitest run src/components/desktop/quickTime.spec.ts src/components/timer src/components/inbox/InboxHost.spec.ts src/views/download/DesktopDownloadView.spec.ts`：11 个 spec 共 77 项通过。
- preload-contract 构建、Web 与桌面 `typecheck`、`pnpm lint`（0 errors）、`pnpm run test:architecture`（15 项）、`pnpm run doc-sync`（88 份活动 Note 与 4 份冻结记录）、`pnpm run test:agent-notes -- --maxWorkers=1 --testTimeout=30000`（20 项）通过。
- 本地独立数据库与浏览器走查：快捷页按提示排入一小时，今日合计从 30 分钟变为一小时 30 分钟；无搜索时隐藏已完成项并显示数量，搜索时保留、淡化并划线。
- 迁移到 `/timer/quick`、`/timer/rail`：`test/surface-load.spec.ts` 覆盖 200、404 与网络失败；`node tools/verification/verify-deployment-config.mjs` 在路由仍为 `/desktop/quick` 时失败、迁移后通过。
- 真实桌面托盘、系统快捷键、自动隐藏、安装版登录项及旧壳人工走查尚未完成，限制与其余验收证据记录在本 PR。
- 改时段、改状态、新建与跨窗口同步：`quickTime.spec.ts`、`workItemChanges.spec.ts`、`views/desktop/QuickTimeView.spec.ts`；本地浏览器以 364px 宽打开 `/timer/quick` 走查改时段校验与重叠提示、改状态（另一标签页的项目表格与首页约 1 秒内更新）、新建（列表展开、处理人为本人）和说明框不溢出。
