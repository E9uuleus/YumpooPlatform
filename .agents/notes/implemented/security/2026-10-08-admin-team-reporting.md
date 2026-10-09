# Agent Note: 团队视图管理员报表与导出文件保存通道

Status: implemented

## Problem

团队视图需要读取全公司的项目、成员计时和当前处理人，而私人仪表板的统计端口只允许统计本人所在的项目。Electron 主窗口的会话会拦截所有下载，因此浏览器生成的 PDF 和 Excel 在桌面端无法保存。

## Decision

团队视图只开放给有效角色包含 COMPANY_ADMIN 的主体（APP_MANAGER 也满足）。reporting 只调用公开端口：catalog 的 [CompanyProjectQuery](../../../../backend/src/main/java/com/yumpoo/platform/catalog/api/CompanyProjectQuery.java) 返回本公司全部项目，workitem 的 [TeamWorkQuery](../../../../backend/src/main/java/com/yumpoo/platform/workitem/api/TeamWorkQuery.java) 返回原始计时与当前处理人。两个端口都会自行拒绝非管理员。项目 ID 先与本公司项目求交集，再传给 workitem；未知 ID 静默忽略。[私人仪表板](../product/2026-09-16-personal-dashboards.md)的 MemberProjectQuery 和所有权边界不变。

统计口径：

- **工时：** 只统计未删除的原始计时，排除已删除工作项，包含已归档项目。跨天记录按与公司时区自然日的重叠时长拆分；运行中的计时统计到 asOf。
- **当前任务：** 只统计未删除、未归档、状态分类为 TODO 或 IN_PROGRESS 的工作项。不筛选项目时排除已归档项目。逾期指截止日期早于公司时区的今天。
- **成员行：** 默认展示在职启用成员和有数据的成员；没有资料的显示为“历史成员”。

所有接口都是只读的，不写审计，也不缓存。

桌面端新增固定通道 `yumpoo:files:save-export`。只有主窗口的 main frame 且 origin 精确匹配的发送方可以调用。主进程只接受 PDF 和 XLSX 两种 MIME、非空且不超过 50MB 的 `Uint8Array`。文件名会去掉路径与 Windows 非法字符，并按类型补全扩展名；目录只能由用户在原生保存对话框中选择，取消时返回 false。全局下载拦截保持不变。网页端仍通过 Blob 链接下载；桥接不存在的旧客户端会提示升级。

## Alternatives considered

- 复用 ProjectAccessSnapshotQuery 的管理员只读可见性逐个查询：会把公司范围列表拆成大量单项调用，也无法在一条 SQL 中按项目统计。
- 在 reporting 直接读取 workitem 和 catalog 的表：违反模块只依赖公开端口的边界。
- 放开 Electron 的 will-download：渲染进程可以把任意来源的下载落盘，信任边界过宽。
- 让渲染进程传入保存路径：会允许网页内容决定写入位置，因此只允许用户在原生对话框中选择。

## Consequences

管理员可以看到所有成员的工时和任务明细，这与[三级平台角色](2026-09-25-platform-role-tiers.md)中“管理员可读公司业务数据”的边界一致。大公司的季度区间可能返回数千条明细，接口限制区间最多 93 天、项目最多 500 个、成员最多 1000 个。桌面通道只在新版客户端可用，网页端不受影响。

## Verification

DashboardHttpIT 验证非管理员访问返回 403、超过 93 天返回 422，并在管理员未加入项目时仍能读取。桌面端单元测试验证 MIME 白名单、文件名清理、取消保存和伪造发送方被拒绝；workspace-boundaries 只为该通道放行两个参数。
