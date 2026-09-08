# CI 与 PR

从最新 `dev` 建独立分支，提交后向 `dev` 开 PR；不直接推送或合并 `dev/main`。现有未提交改动需要单独保留。

```powershell
git fetch origin dev
pnpm install --frozen-lockfile
pnpm ci
```

日常快速反馈可先运行 `pnpm ci:static`；涉及后端时运行 `pnpm ci:backend`。`pnpm ci` 按相同顺序执行全部本机可运行阶段。需要 Node/pnpm 的仓库固定版本、Java 21 和 Linux-container Docker；Windows 交付阶段还需要 Windows x64、PowerShell 和 Electron。Linux 本地不会运行 Windows 阶段，PR 的 Windows Delivery 始终必需。

旧 `verify:m*` 命令保留切片复现用途。普通 PR 使用统一入口，不需要串联里程碑命令、手工修改验收报告或刷新历史哈希。

## 当前执行链

| 入口 | 保障 |
| --- | --- |
| `pnpm ci:contracts` | PR 基线、历史迁移与冻结清单、OpenAPI 严格兼容、例外结构、lint/样例、生成客户端漂移、全部既有事件版本与共享载荷兼容、事件库存 |
| `pnpm ci:static` | 上述契约检查各一次，加文档/归档完整性、校验器故障测试、前端/桌面 lint、架构、类型、构建与全部现有测试、历史证据结构和当前资产 |
| `pnpm ci:backend` | 一次 `clean verify`：单元/集成、安全、架构、PostgreSQL/Flyway、备份恢复；随后单独执行 96 MiB JVM 的附件流式探针 |
| `pnpm ci:portable` | 校验当前源码的 static/backend 成功记录与新鲜备份恢复报告；运行 packaged JAR + SPA HTTP；生成和验证 JAR/Web handoff |
| `pnpm ci:windows` | 复核 handoff，真实 Electron smoke、Windows 桌面/ASAR、ZIP、PowerShell 行为及服务器包验证；服务器包逐文件对照同一 handoff |

[plan.mjs](../tools/ci/plan.mjs) 是统一入口的执行清单。M2-23/M2-24 的 `:assets` 只检查切片资产，完整契约检查归 `ci:contracts`；类别资产只执行一次，旧切片记录另行校验结构。类型检查由各包实际构建或专用类型测试承担，不再重复运行同一整套 Node 入口。Windows smoke 使用 handoff 的 Web 字节；服务器不重复编译。

`out/ci/<stage>.json` 记录步骤、起止时间、状态、解析后的基线提交及源码指纹。中途失败立即非零退出；重跑会覆盖本阶段结果，不缓存跳过步骤。后续阶段拒绝旧源码、旧基线、不完整或失败报告。输出、构建目录和测试夹具不写入 Git。子进程清除继承的 `SPRING_*`、`YUMPOO_*` 和 JVM/Maven/Node 注入选项，再显式设置本次验证需要的值；不连接开发或生产数据库，也不继承受控身份 fixture。

## 四种事实来源

- **冻结历史**：归档 Agent Note 的 manifest、已在目标分支存在的 SQL 迁移、M2-23 冻结清单与既有 OpenAPI 精确例外不可改写。换行按仓库 LF 约定比较；迁移追加更高且唯一的版本，不能删除、移动或回填旧版本。
- **当前规范**：`contracts/openapi/yumpoo-v1.yaml`、事件目录与 Schema、生产代码和行为测试。历史验收的 Flyway 版本、旧 UI 尺寸、源码片段及 Note 措辞不定义当前行为。
- **兼容性基线**：PR 取 `pull_request.base.sha`，`dev` push 取 `before`，本地默认取 `origin/dev`，手动 Actions 使用 `base_ref`。开始时解析为完整 commit，缺失、全零或无效引用失败；不复制永久规范基线。用 `YUMPOO_CI_BASE_REF` 可以显式指定本地比较基线。
- **例外**：已有条目只授权其原始 `oldSha256/newSha256` 对；原因、唯一 ID、活动决策链接必须完整。后续兼容演进不需要把当前规范连到旧例外。额外不兼容变更仍失败；真正破坏变更须先作明确产品/迁移决定，不能为过 CI 自动批准或刷新哈希。请求枚举缩减和响应枚举扩展恢复严格检测。

迁移测试从实际资源和 Flyway pending 清单推导数量、版本与执行集合，继续逐项核验表、索引、约束、已有数据、checksum 和重跑幂等性。事件只允许同版本增加可选字段（含嵌套对象）或修改 Schema 注释；既有约束、必需字段、引用、事件名与聚合语义保持兼容，旧合法样例必须可读。新版本不能使旧版本漏检。

## Actions 与最终门禁

工作流在 PR、`dev` 合并后和显式手动运行时执行。合并后的执行保留，用于验证实际集成提交；分支 push 不另起重复工作流。新一轮运行取消同一 PR/ref 的旧轮次。没有按路径跳过必需任务。

`M0 Portable Gate` 保持已有必需状态名。`Windows Delivery` 只在 portable 成功后运行。最终 `M0 Windows x64 Gate` 使用 `always()` 检查所有工作任务，逐项要求 `success` 和完成凭据；失败、取消、跳过、未知结果、缺失依赖/凭据均不能通过。工作流结构测试会拒绝新增任务未进入最终门禁、条件性跳过必需命令或 `continue-on-error`。

Actions 始终尝试上传故障诊断：`out/ci` 阶段记录和 Maven Surefire/Failsafe 报告。未产生诊断文件时只警告；交付所需的 handoff/报告缺失必须失败。handoff 名称绑定 run ID 与 attempt，Windows 校验提交、文件集合和哈希。SHA 固定的 Actions、只读 token 与不持久化 checkout 凭据继续保留。

Windows 本机不能构造的大小写碰撞与无特权符号链接夹具由必需 Linux job 执行。公司 HTTPS/企微、Defender、干净 Windows Server、真实生产迁移及灾备环境验收继续遵守现有 evidence 延期清单；CI 阶段报告仍标记 `WINDOWS_X64_CI_STAGE` 和相应 `NOT_RUN`，不代表生产部署或 `WINDOWS_X64_FULL`。

## 已核验的故障来源

| 实际记录 | 根因与替代保障 |
| --- | --- |
| [PR #64 迁移失败](https://github.com/E9uuleus/YumpooPlatform/actions/runs/34177447631)，修复 `053d417` | 新增 V52 后六处仍断言 V51/固定数量；改为实际迁移清单，保留数据语义与篡改拒绝测试 |
| [PR #64 兼容失败](https://github.com/E9uuleus/YumpooPlatform/actions/runs/34177214304)，修复 `ff5a099` | 精确例外需要同步新哈希；类别专项又强制当前规范连到旧历史基线，正常演进不断触发刷新；拆开历史授权与当前比较 |
| [PR #63 资产失败](https://github.com/E9uuleus/YumpooPlatform/actions/runs/33961259083)，修复 `4fe6b58` | 源码包含断言要求 `54px`；当前契约改用 YAML 结构，视觉/交互仍由现有前端回归负责 |
| [PR #63 数据残留](https://github.com/E9uuleus/YumpooPlatform/actions/runs/33960883440)，修复 `fb12a0c` | 类别版本外键残留污染后续测试；该已有修复保留，全量后端回归继续执行，新编排另隔离进程环境 |

## 远端规则

2026-09-08 已读取规则集 `Protect dev`（ID `20779725`）：required checks 为 `M0 Portable Gate`、`M0 Windows x64 Gate`，要求分支最新、PR 对话解决，允许 PR 路径的维护者 bypass。两个状态名保留，因此启用本变更不需要切换远端 required checks。

远端设置没有在本变更中修改。当前 `required_approving_review_count=0`，无法强制至少一位其他评审者批准；如需技术上强制该要求，维护者应在上述规则集设为 `1`，并核对角色 `5` 的 PR bypass 是否符合团队约定。不能把文档中的“须审阅”当作已启用的远端限制。
